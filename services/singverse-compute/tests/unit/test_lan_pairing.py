"""Persistent ownership, interoperable RSA messages and bounded LAN-only admission."""
import asyncio
import base64
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
from pathlib import Path
from subprocess import CalledProcessError, TimeoutExpired
import tempfile
import threading
import unittest
from unittest.mock import patch

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa

from singverse_compute.api.middleware import LanPairingBoundary
from singverse_compute.application.lan_pairing import LanPairing, PROTOCOL, private_peer
from singverse_compute.core.config import Settings
from singverse_compute.core.errors import ComputeError
from singverse_compute.infrastructure.security import InternalAuth


def identity(key):
    encoded = key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    return hashlib.sha256(encoded).hexdigest(), base64.b64encode(encoded).decode()


def signature(key, node, challenge, client):
    message = (PROTOCOL + '\n' + node + '\n' + challenge + '\n' + client).encode()
    return base64.b64encode(key.sign(message, padding.PSS(mgf=padding.MGF1(hashes.SHA256()), salt_length=32), hashes.SHA256())).decode()


def http_get(app, path, token):
    """Exercise the actual ASGI route without requiring an unrelated listening port."""
    messages = []
    scope = {'type': 'http', 'asgi': {'version': '3.0'}, 'http_version': '1.1', 'method': 'GET',
             'scheme': 'http', 'path': path, 'raw_path': path.encode(), 'query_string': b'', 'root_path': '',
             'headers': [(b'host', b'127.0.0.1'), (b'authorization', ('Bearer ' + token).encode())],
             'client': ('127.0.0.1', 1234), 'server': ('127.0.0.1', 8090)}
    async def receive():
        return {'type': 'http.request', 'body': b'', 'more_body': False}
    async def send(message):
        messages.append(message)
    asyncio.run(app(scope, receive, send))
    return next(message['status'] for message in messages if message['type'] == 'http.response.start')


class LanPairingTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.first = rsa.generate_private_key(public_exponent=65537, key_size=3072)
        cls.second = rsa.generate_private_key(public_exponent=65537, key_size=3072)

    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.auth = InternalAuth(Path(self.folder.name) / 'token')
        self.auth.initialize()
        self.service = LanPairing(self.auth)
        self.node = self.service.discovery('127.0.0.1')['nodeId']

    def challenge(self, key=None, service=None, peer='127.0.0.1'):
        key, service = key or self.first, service or self.service
        client, encoded = identity(key)
        challenge = service.challenge(peer, self.node, client)['challenge']
        return client, encoded, challenge, signature(key, self.node, challenge, client)

    def pair(self, values, service=None, peer='127.0.0.1'):
        client, encoded, challenge, proof = values
        return (service or self.service).pair(peer, self.node, client, encoded, challenge, proof)

    def assertStatus(self, status, call):
        with self.assertRaises(ComputeError) as raised:
            call()
        self.assertEqual(raised.exception.status, status)

    def test_discovery_and_pair_return_no_plaintext_credentials_and_keep_bearer(self):
        discovered = self.service.discovery('::ffff:127.0.0.1')
        self.assertEqual(set(discovered), {'service', 'apiVersion', 'nodeId', 'name', 'ownerId', 'pairingEnabled'})
        self.assertEqual(discovered['ownerId'], '')
        self.assertTrue(discovered['pairingEnabled'])
        request = self.challenge()
        result = self.pair(request)
        self.assertEqual(set(result), {'nodeId', 'challenge', 'encryptedToken'})
        self.assertEqual(result['challenge'], request[2])
        self.assertNotIn(self.auth._token(), json.dumps(result))
        decrypted = self.first.decrypt(base64.b64decode(result['encryptedToken']), padding.OAEP(
            mgf=padding.MGF1(hashes.SHA256()), algorithm=hashes.SHA256(),
            label=(PROTOCOL + ':' + self.node + ':' + request[2]).encode()))
        self.assertEqual(decrypted.decode('ascii'), self.auth._token())
        self.auth.authorize('Bearer ' + decrypted.decode())
        self.assertEqual(self.service.discovery('127.0.0.1')['ownerId'], request[0])
        stored = self.service.path.read_text()
        self.assertNotIn(self.auth._token(), stored)
        self.assertNotIn('PRIVATE KEY', stored)
        if os.name != 'nt':
            self.assertEqual(self.service.path.stat().st_mode & 0o777, 0o600)

    def test_nonce_is_one_use_and_bound_to_peer_client_and_exact_signature(self):
        request = self.challenge()
        self.assertStatus(401, lambda: self.pair(request, peer='10.0.0.9'))
        self.assertStatus(401, lambda: self.pair(request))
        request = self.challenge()
        client, encoded = identity(self.second)
        self.assertStatus(401, lambda: self.pair((client, encoded, request[2], request[3])))
        request = self.challenge()
        self.assertStatus(401, lambda: self.pair((request[0], identity(self.second)[1], request[2], request[3])))
        request = self.challenge()
        wrong = base64.b64encode(self.first.sign(b'wrong message', padding.PSS(
            mgf=padding.MGF1(hashes.SHA256()), salt_length=32), hashes.SHA256())).decode()
        self.assertStatus(401, lambda: self.pair((*request[:3], wrong)))
        request = self.challenge()
        self.pair(request)
        self.assertStatus(401, lambda: self.pair(request))

    def test_challenge_expires_at_thirty_seconds_and_unknown_node_is_rejected(self):
        with patch('singverse_compute.application.lan_pairing.time.monotonic', return_value=100):
            request = self.challenge()
        with patch('singverse_compute.application.lan_pairing.time.monotonic', return_value=130):
            self.assertStatus(401, lambda: self.pair(request))
        self.assertStatus(409, lambda: self.service.challenge('127.0.0.1', '00000000-0000-0000-0000-000000000000', identity(self.first)[0]))
        self.assertEqual(self.service.discovery('127.0.0.1')['ownerId'], '')

    def test_invalid_key_format_and_failed_owner_persistence_never_publish_a_binding(self):
        request = self.challenge()
        pkcs1 = self.first.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.PKCS1)
        self.assertStatus(422, lambda: self.pair((request[0], base64.b64encode(pkcs1).decode(), request[2], request[3])))
        small = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        request = self.challenge(small)
        self.assertStatus(422, lambda: self.pair(request))
        request = self.challenge()
        with patch.object(self.service, '_write_state', side_effect=ComputeError(503, 'Fixture persistence failure')):
            self.assertStatus(503, lambda: self.pair(request))
        self.assertEqual(self.service.discovery('127.0.0.1')['ownerId'], '')
        self.assertStatus(401, lambda: self.pair(request))

    def test_persistent_owner_survives_restart_and_internal_token_rotation(self):
        self.pair(self.challenge())
        owner = identity(self.first)[0]
        self.auth.token_file.unlink()
        self.auth.initialize()
        restarted = LanPairing(self.auth)
        self.assertEqual(restarted.discovery('127.0.0.1')['nodeId'], self.node)
        self.assertEqual(restarted.discovery('127.0.0.1')['ownerId'], owner)
        result = self.pair(self.challenge(service=restarted), service=restarted)
        self.assertTrue(result['encryptedToken'])
        self.assertStatus(409, lambda: self.challenge(self.second, service=restarted))

    def test_concurrent_first_claim_across_service_instances_has_only_one_owner(self):
        other = LanPairing(self.auth)
        requests = (self.challenge(), self.challenge(self.second, service=other))
        barrier = threading.Barrier(2)
        def claim(service, values):
            barrier.wait(timeout=5)
            try:
                return self.pair(values, service=service), values[0]
            except ComputeError as error:
                return error.status, values[0]
        with ThreadPoolExecutor(max_workers=2) as executor:
            futures = [executor.submit(claim, service, values) for service, values in zip((self.service, other), requests)]
            results = [future.result(timeout=10) for future in futures]
        self.assertEqual(sum(isinstance(result, dict) for result, _ in results), 1)
        self.assertEqual([result for result, _ in results if isinstance(result, int)], [409])
        winner = next(owner for result, owner in results if isinstance(result, dict))
        self.assertEqual(LanPairing(self.auth).discovery('127.0.0.1')['ownerId'], winner)

    def test_disabled_missing_invalid_token_and_corrupt_state_never_reset_ownership(self):
        self.pair(self.challenge())
        state = self.service.path.read_bytes()
        disabled = LanPairing(self.auth, False)
        self.assertFalse(disabled.discovery('127.0.0.1')['pairingEnabled'])
        self.assertStatus(403, lambda: self.challenge(service=disabled))
        self.auth.token_file.write_text('invalid')
        self.assertFalse(self.service.discovery('127.0.0.1')['pairingEnabled'])
        self.assertStatus(403, lambda: self.challenge())
        self.assertEqual(self.service.path.read_bytes(), state)
        unconfigured = LanPairing(InternalAuth(None))
        self.assertFalse(unconfigured.discovery('127.0.0.1')['pairingEnabled'])
        self.service.path.write_text('{broken')
        invalid = LanPairing(self.auth)
        self.assertStatus(503, lambda: invalid.discovery('127.0.0.1'))
        self.assertEqual(self.service.path.read_text(), '{broken')

    def test_unwritable_or_corrupt_pairing_identity_does_not_break_authenticated_status(self):
        from singverse_compute.bootstrap import create_app
        token = self.auth._token()
        with patch('singverse_compute.application.lan_pairing.os.open', side_effect=PermissionError('Read-only fixture directory')):
            app = create_app(Settings(token_file=self.auth.token_file))
        self.assertEqual(http_get(app, '/internal/v1/status', token), 200)
        self.assertEqual(http_get(app, '/internal/v1/lan/discovery', token), 503)
        self.assertEqual(self.auth._token(), token)
        self.service.path.write_text('{unreadable prior owner')
        for enabled in (True, False):
            app = create_app(Settings(token_file=self.auth.token_file, lan_pairing_enabled=enabled))
            self.assertEqual(http_get(app, '/internal/v1/status', token), 200)
            self.assertEqual(http_get(app, '/internal/v1/lan/discovery', token), 503)
            self.assertEqual(self.service.path.read_text(), '{unreadable prior owner')

    def test_disabled_pairing_does_not_create_or_lock_identity_files(self):
        from singverse_compute.bootstrap import create_app
        self.service.path.unlink()
        lock_path = self.service.path.with_name('.lan-pairing.lock')
        lock_path.unlink()
        with patch.object(LanPairing, '_file_lock', side_effect=AssertionError('Must not write a disabled identity')) as locked:
            app = create_app(Settings(token_file=self.auth.token_file, lan_pairing_enabled=False))
            self.assertFalse(app.state.lan_pairing.discovery('127.0.0.1')['pairingEnabled'])
            locked.assert_not_called()
        self.assertEqual(http_get(app, '/internal/v1/status', self.auth._token()), 200)
        self.assertFalse(self.service.path.exists())
        self.assertFalse(lock_path.exists())

    def test_acl_failures_disable_only_lan_and_programming_errors_are_not_suppressed(self):
        from singverse_compute.bootstrap import create_app
        for failure in (PermissionError('Fixture ACL denied'), CalledProcessError(1, 'fixture-icacls'), TimeoutExpired('fixture-icacls', 5)):
            with self.subTest(failure=type(failure).__name__), patch(
                    'singverse_compute.application.lan_pairing.restrict_file', side_effect=failure):
                app = create_app(Settings(token_file=self.auth.token_file))
            self.assertEqual(http_get(app, '/internal/v1/status', self.auth._token()), 200)
            self.assertEqual(http_get(app, '/internal/v1/lan/discovery', self.auth._token()), 503)
        with patch('singverse_compute.application.lan_pairing.restrict_file', side_effect=AssertionError('Fixture code defect')):
            with self.assertRaises(AssertionError):
                LanPairing(self.auth)

    def test_challenge_registry_and_per_peer_rate_are_bounded(self):
        client = identity(self.first)[0]
        for index in range(1, 65):
            self.service.challenge('10.0.0.' + str(index), self.node, client)
        self.assertEqual(len(self.service.challenges), 64)
        self.assertStatus(429, lambda: self.service.challenge('10.0.0.65', self.node, client))
        separate = LanPairing(self.auth)
        for _ in range(16):
            separate.challenge('127.0.0.1', self.node, client)
        self.assertStatus(429, lambda: separate.challenge('127.0.0.1', self.node, client))

    def test_exact_private_allowlist_and_explicit_pairing_configuration(self):
        for address in ('127.0.0.1', '10.1.2.3', '172.16.0.1', '172.31.255.254', '192.168.1.1', '::1', 'fc00::1', 'fd12::1', '::ffff:10.1.2.3'):
            self.assertTrue(private_peer(address))
        for address in ('8.8.8.8', '0.0.0.0', '100.64.0.1', '169.254.1.1', '192.0.0.1', '192.0.2.1', '198.18.0.1',
                        '172.15.255.255', '172.32.0.1', '224.0.0.1', '::', 'fe80::1', 'ff02::1', '2606:4700:4700::1111', '::ffff:8.8.8.8', 'localhost'):
            with self.subTest(address=address):
                self.assertStatus(403, lambda: private_peer(address))
        with patch.dict(os.environ, {'COMPUTE_LAN_PAIRING_ENABLED': 'false'}):
            self.assertFalse(Settings.from_env().lan_pairing_enabled)
        with patch.dict(os.environ, {'COMPUTE_LAN_PAIRING_ENABLED': 'invalid'}):
            with self.assertRaises(ValueError):
                Settings.from_env()


class LanBoundaryTest(unittest.TestCase):
    def request(self, peer='127.0.0.1', headers=None, body=b'', method='POST'):
        messages = []
        called = []
        async def target(scope, receive, send):
            called.append(scope['state']['lan_peer'])
            messages.append(await receive())
        boundary = LanPairingBoundary(target)
        scope = {'type': 'http', 'method': method, 'path': '/internal/v1/lan/pair',
                 'headers': headers if headers is not None else [(b'host', b'127.0.0.1:8090')], 'client': (peer, 1234)}
        async def receive():
            return {'type': 'http.request', 'body': body, 'more_body': False}
        async def send(message):
            messages.append(message)
        asyncio.run(boundary(scope, receive, send))
        status = next((message['status'] for message in messages if message['type'] == 'http.response.start'), None)
        return called, status

    def test_real_peer_private_literal_host_and_absence_of_browser_proxy_headers_required(self):
        for peer, headers in (
            ('8.8.8.8', [(b'host', b'127.0.0.1')]),
            ('169.254.1.2', [(b'host', b'169.254.1.1')]),
            ('127.0.0.1', [(b'host', b'localhost:8090')]),
            ('127.0.0.1', [(b'host', b'public.example')]),
            ('127.0.0.1', [(b'host', b'8.8.8.8')]),
            ('127.0.0.1', [(b'host', b'127.0.0.1'), (b'origin', b'null')]),
            ('127.0.0.1', [(b'host', b'127.0.0.1'), (b'forwarded', b'for=10.0.0.1')]),
            ('127.0.0.1', [(b'host', b'127.0.0.1'), (b'x-forwarded-for', b'10.0.0.1')]),
            ('127.0.0.1', [(b'host', b'127.0.0.1'), (b'x-forwarded-host', b'10.0.0.1')]),
            ('127.0.0.1', [(b'host', b'127.0.0.1'), (b'host', b'10.0.0.1')]),
        ):
            with self.subTest(peer=peer, headers=headers):
                self.assertEqual(self.request(peer, headers), ([], 403))
        self.assertEqual(self.request('::ffff:10.0.0.1', [(b'host', b'[fd12::1]:8090')])[0], ['10.0.0.1'])
        self.assertEqual(self.request(body=b'a' * 8193), ([], 413))
        self.assertEqual(self.request(body=b'a' * 8192)[0], ['127.0.0.1'])

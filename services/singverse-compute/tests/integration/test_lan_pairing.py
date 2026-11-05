"""Actual random-port HTTP pairing; no browser receives the internal credential."""
import base64
import hashlib
import json
from pathlib import Path
import socket
import tempfile
import threading
import time
import unittest
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
import uvicorn

from singverse_compute.application.lan_pairing import PROTOCOL
from singverse_compute.bootstrap import create_app
from singverse_compute.core.config import Settings


class LanPairingHttpTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.folder = tempfile.TemporaryDirectory()
        cls.auth_file = Path(cls.folder.name) / 'token'
        cls.app = create_app(Settings(token_file=cls.auth_file))
        cls.socket = socket.socket()
        cls.socket.bind(('127.0.0.1', 0))
        cls.base = 'http://127.0.0.1:' + str(cls.socket.getsockname()[1])
        cls.server = uvicorn.Server(uvicorn.Config(cls.app, log_level='error', proxy_headers=False))
        cls.thread = threading.Thread(target=cls.server.run, kwargs={'sockets': [cls.socket]}, daemon=True)
        cls.thread.start()
        for _ in range(100):
            if cls.server.started:
                return
            time.sleep(0.02)
        cls.tearDownClass()
        raise RuntimeError('LAN pairing test server did not start')

    @classmethod
    def tearDownClass(cls):
        cls.server.should_exit = True
        cls.thread.join(timeout=5)
        cls.socket.close()
        cls.folder.cleanup()

    def request(self, path, body=None, headers=None):
        data = json.dumps(body).encode() if body is not None else None
        request = Request(self.base + path, data=data, headers={'Content-Type': 'application/json', **(headers or {})})
        try:
            with urlopen(request, timeout=5) as response:
                return response.status, json.load(response)
        except HTTPError as error:
            with error:
                return error.code, json.load(error)

    def test_http_discovery_signed_pair_replay_owner_and_existing_bearer(self):
        path = '/internal/v1/lan/'
        status, discovered = self.request(path + 'discovery')
        self.assertEqual(status, 200)
        self.assertEqual(set(discovered), {'service', 'apiVersion', 'nodeId', 'name', 'ownerId', 'pairingEnabled'})
        self.assertNotIn(self.auth_file.read_text(), json.dumps(discovered))
        self.assertEqual(self.request('/internal/v1/status')[0], 401)
        private = rsa.generate_private_key(public_exponent=65537, key_size=3072)
        encoded = private.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
        client = hashlib.sha256(encoded).hexdigest()
        request = {'nodeId': discovered['nodeId'], 'clientId': client}
        self.assertEqual(self.request(path + 'challenge', {**request, 'unexpected': True})[0], 422)
        status, challenge = self.request(path + 'challenge', request)
        self.assertEqual(status, 200)
        message = (PROTOCOL + '\n' + discovered['nodeId'] + '\n' + challenge['challenge'] + '\n' + client).encode()
        proof = private.sign(message, padding.PSS(mgf=padding.MGF1(hashes.SHA256()), salt_length=32), hashes.SHA256())
        pair = {**request, 'publicKey': base64.b64encode(encoded).decode(), 'challenge': challenge['challenge'],
                'signature': base64.b64encode(proof).decode()}
        status, result = self.request(path + 'pair', pair)
        self.assertEqual(status, 200)
        self.assertNotIn(self.auth_file.read_text(), json.dumps(result))
        token = private.decrypt(base64.b64decode(result['encryptedToken']), padding.OAEP(
            mgf=padding.MGF1(hashes.SHA256()), algorithm=hashes.SHA256(),
            label=(PROTOCOL + ':' + discovered['nodeId'] + ':' + challenge['challenge']).encode())).decode()
        self.assertEqual(token, self.auth_file.read_text())
        self.assertEqual(self.request('/internal/v1/status', headers={'Authorization': 'Bearer ' + token})[0], 200)
        self.assertEqual(self.request(path + 'pair', pair)[0], 401)
        self.assertEqual(self.request(path + 'challenge', {**request, 'clientId': '0' * 64})[0], 409)
        self.assertEqual(self.request(path + 'discovery')[1]['ownerId'], client)

    def test_http_boundary_rejects_browser_proxy_dns_host_and_oversized_body(self):
        for headers in ({'Origin': 'http://127.0.0.1'}, {'Forwarded': 'for=127.0.0.1'},
                        {'X-Forwarded-For': '10.0.0.1'}, {'Host': 'localhost'}, {'Host': '8.8.8.8'}):
            with self.subTest(headers=headers):
                self.assertEqual(self.request('/internal/v1/lan/discovery', headers=headers)[0], 403)
        self.assertEqual(self.request('/internal/v1/lan/pair', {'oversized': 'x' * 8192})[0], 413)

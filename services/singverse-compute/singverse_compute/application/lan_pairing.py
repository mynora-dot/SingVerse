"""Private-LAN discovery and persistent, single-owner credential recovery."""
import base64
from contextlib import contextmanager
import hashlib
import ipaddress
import json
import logging
import os
from pathlib import Path
import re
import secrets
import socket
import stat
from subprocess import SubprocessError
import threading
import time
import uuid

from cryptography.exceptions import InvalidSignature, UnsupportedAlgorithm
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa

from ..core.errors import ComputeError
from ..infrastructure.security import InternalAuth, restrict_file

PROTOCOL = 'singverse-compute-lan-v1'
PRIVATE_NETWORKS = tuple(ipaddress.ip_network(value) for value in (
    '127.0.0.0/8', '10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16',
    '::1/128', 'fc00::/7',
))


def private_peer(value: str) -> str:
    """Exact allowlist, deliberately excluding public, link-local and reserved ranges."""
    try:
        address = ipaddress.ip_address(value)
        if isinstance(address, ipaddress.IPv6Address) and address.ipv4_mapped:
            address = address.ipv4_mapped
        if any(address.version == network.version and address in network for network in PRIVATE_NETWORKS):
            return str(address)
    except ValueError:
        pass
    raise ComputeError(403, 'LAN pairing requires a private network peer')


def public_key(value: str):
    try:
        encoded = base64.b64decode(value, validate=True)
        key = serialization.load_der_public_key(encoded)
        if not isinstance(key, rsa.RSAPublicKey) or key.key_size != 3072:
            raise ValueError()
        if key.public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo) != encoded:
            raise ValueError()
        return key, encoded
    except (ValueError, TypeError, UnsupportedAlgorithm) as error:
        raise ComputeError(422, 'A DER SPKI RSA-3072 public key is required') from error


class LanPairing:
    def __init__(self, auth: InternalAuth, enabled: bool = True):
        self.auth, self.enabled = auth, enabled
        self.path = auth.token_file.with_name('lan-pairing.json') if auth.token_file is not None else None
        self.node_id = str(uuid.uuid4())
        self.lock = threading.RLock()
        self.challenges = {}
        self.attempts = {}
        self.identity_error = False
        if self.path is not None:
            try:
                if not self.enabled:
                    if self.path.exists() or self.path.is_symlink():
                        self.node_id = self._read_state()['nodeId']
                else:
                    with self.lock, self._file_lock():
                        if self.path.exists() or self.path.is_symlink():
                            state = self._read_state()
                            restrict_file(self.path)
                            self.node_id = state['nodeId']
                        else:
                            self._write_state({'version': 1, 'nodeId': self.node_id, 'ownerId': '', 'publicKey': ''})
            except (ComputeError, OSError, ValueError, SubprocessError):
                # Optional discovery must not disable an existing, valid Bearer deployment.
                self.identity_error = True
                logging.getLogger('singverse_compute.lan').warning('LAN pairing identity unavailable; check the private identity directory')

    @contextmanager
    def _file_lock(self):
        """Separate advisory lock survives atomic state replacement and releases on process exit."""
        if self.identity_error:
            raise ComputeError(503, 'LAN pairing identity storage is unavailable')
        if self.path is None:
            yield
            return
        lock_path = self.path.with_name('.lan-pairing.lock')
        flags = os.O_RDWR | os.O_CREAT | getattr(os, 'O_NOFOLLOW', 0)
        try:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            descriptor = os.open(lock_path, flags, 0o600)
            with os.fdopen(descriptor, 'r+b') as handle:
                if not stat.S_ISREG(os.fstat(handle.fileno()).st_mode) or lock_path.is_symlink():
                    raise OSError('Invalid pairing lock')
                if os.fstat(handle.fileno()).st_size == 0:
                    restrict_file(lock_path)
                    handle.write(b'0')
                    handle.flush()
                if os.name == 'nt':
                    import msvcrt
                    handle.seek(0)
                    msvcrt.locking(handle.fileno(), msvcrt.LK_LOCK, 1)
                else:
                    import fcntl
                    fcntl.flock(handle.fileno(), fcntl.LOCK_EX)
                try:
                    yield
                finally:
                    if os.name == 'nt':
                        handle.seek(0)
                        msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
                    else:
                        fcntl.flock(handle.fileno(), fcntl.LOCK_UN)
        except ComputeError:
            raise
        except (OSError, ValueError, SubprocessError) as error:
            raise ComputeError(503, 'LAN pairing identity storage is unavailable') from error

    def _read_state(self):
        try:
            if self.path.is_symlink():
                raise ValueError()
            with self.path.open(encoding='utf-8') as source:
                state = json.loads(source.read(8193))
            if (not isinstance(state, dict) or set(state) != {'version', 'nodeId', 'ownerId', 'publicKey'}
                    or type(state['version']) is not int or state['version'] != 1
                    or not isinstance(state['nodeId'], str) or str(uuid.UUID(state['nodeId'])) != state['nodeId']):
                raise ValueError()
            owner = state['ownerId']
            if not isinstance(owner, str) or not isinstance(state['publicKey'], str):
                raise ValueError()
            if owner:
                if not isinstance(owner, str) or not re.fullmatch('[a-f0-9]{64}', owner):
                    raise ValueError()
                _, encoded = public_key(state['publicKey'])
                if hashlib.sha256(encoded).hexdigest() != owner:
                    raise ValueError()
            elif state['publicKey'] != '':
                raise ValueError()
            return state
        except (OSError, UnicodeError, ValueError, TypeError, KeyError, ComputeError) as error:
            raise ComputeError(503, 'LAN pairing identity is invalid; restore the existing identity file') from error

    def _write_state(self, state):
        temporary = self.path.with_name('.lan-pairing-' + secrets.token_hex(16) + '.tmp')
        try:
            descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(descriptor, 'w', encoding='utf-8') as output:
                restrict_file(temporary)
                json.dump(state, output, separators=(',', ':'))
                output.flush()
                os.fsync(output.fileno())
            os.replace(temporary, self.path)
            if os.name != 'nt':
                descriptor = os.open(self.path.parent, os.O_RDONLY)
                try:
                    os.fsync(descriptor)
                finally:
                    os.close(descriptor)
        except (OSError, ValueError, SubprocessError) as error:
            raise ComputeError(503, 'LAN pairing identity could not be saved') from error
        finally:
            temporary.unlink(missing_ok=True)

    def _token(self):
        token = self.auth._token()
        if not 32 <= len(token) <= 256 or not all(33 <= ord(char) <= 126 for char in token):
            raise ComputeError(503, 'LAN pairing requires a valid internal credential')
        return token

    def _available(self):
        if not self.enabled or self.path is None or self.identity_error:
            return False
        try:
            self._token()
            return True
        except ComputeError:
            return False

    def _state(self):
        if self.identity_error:
            raise ComputeError(503, 'LAN pairing identity storage is unavailable')
        if self.path is None or not self.enabled and not self.path.exists():
            return {'version': 1, 'nodeId': self.node_id, 'ownerId': '', 'publicKey': ''}
        return self._read_state()

    def discovery(self, peer):
        private_peer(peer)
        with self.lock:
            state = self._state()
            return {'service': 'singverse-compute', 'apiVersion': '1', 'nodeId': state['nodeId'],
                    'name': socket.gethostname()[:128], 'ownerId': state['ownerId'], 'pairingEnabled': self._available()}

    def _guard(self, node_id, client_id, state):
        if not self._available():
            raise ComputeError(403, 'LAN pairing is disabled or unavailable')
        if node_id != state['nodeId']:
            raise ComputeError(409, 'LAN node identity has changed')
        if state['ownerId'] and state['ownerId'] != client_id:
            raise ComputeError(409, 'LAN node is already paired with another server')

    def _rate(self, peer):
        now = time.monotonic()
        self.challenges = {value: item for value, item in self.challenges.items() if item[2] > now}
        self.attempts = {value: [instant for instant in items if instant > now - 30]
                         for value, items in self.attempts.items() if items and items[-1] > now - 30}
        if len(self.attempts.get(peer, [])) >= 16 or peer not in self.attempts and len(self.attempts) >= 64:
            raise ComputeError(429, 'LAN pairing requests are temporarily limited')
        self.attempts.setdefault(peer, []).append(now)
        return now

    def challenge(self, peer, node_id, client_id):
        peer = private_peer(peer)
        with self.lock, self._file_lock():
            now = self._rate(peer)
            self._guard(node_id, client_id, self._state())
            if len(self.challenges) >= 64:
                raise ComputeError(429, 'LAN pairing challenges are temporarily limited')
            challenge = secrets.token_hex(32)
            self.challenges[challenge] = (peer, client_id, now + 30)
            return {'nodeId': node_id, 'challenge': challenge}

    def pair(self, peer, node_id, client_id, encoded_key, challenge, encoded_signature):
        peer = private_peer(peer)
        with self.lock:
            self._rate(peer)
            expected = self.challenges.pop(challenge, None)
            if expected is None or expected[:2] != (peer, client_id):
                raise ComputeError(401, 'LAN pairing challenge is invalid or expired')
            key, encoded = public_key(encoded_key)
            if hashlib.sha256(encoded).hexdigest() != client_id:
                raise ComputeError(401, 'LAN client identity does not match its public key')
            message = (PROTOCOL + '\n' + node_id + '\n' + challenge + '\n' + client_id).encode('utf-8')
            try:
                signature = base64.b64decode(encoded_signature, validate=True)
                key.verify(signature, message, padding.PSS(mgf=padding.MGF1(hashes.SHA256()), salt_length=32), hashes.SHA256())
            except (ValueError, InvalidSignature) as error:
                raise ComputeError(401, 'LAN client signature is invalid') from error
            with self._file_lock():
                state = self._state()
                self._guard(node_id, client_id, state)
                token = self._token().encode('ascii')
                encrypted = key.encrypt(token, padding.OAEP(mgf=padding.MGF1(hashes.SHA256()), algorithm=hashes.SHA256(),
                        label=(PROTOCOL + ':' + node_id + ':' + challenge).encode('utf-8')))
                if not state['ownerId']:
                    state['ownerId'], state['publicKey'] = client_id, encoded_key
                    self._write_state(state)
                return {'nodeId': node_id, 'challenge': challenge, 'encryptedToken': base64.b64encode(encrypted).decode('ascii')}

    def close(self):
        with self.lock:
            self.challenges.clear()
            self.attempts.clear()

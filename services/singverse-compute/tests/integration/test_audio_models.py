"""Random-port model API checks; no external download or runtime installation."""
import io
import json
from pathlib import Path
import socket
import tempfile
import threading
import time
import unittest
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from unittest.mock import patch
import uvicorn
from singverse_compute.bootstrap import create_app
from singverse_compute.core.config import Settings


class AudioModelHttpTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory()
        root = Path(cls.temporary.name)
        token = root/'token'; token.write_text('m'*64)
        cls.app = create_app(Settings(token_file=token, transcode_root=root/'cache', audio_model_repo=root/'htdemucs', minimum_free_bytes=0))
        cls.app.state.audio_roles.models.environment = {'fingerprint': 'test', 'devices': {}, 'versions': {}, 'system': 'Linux', 'architecture': 'aarch64', 'processor': 'fixture'}
        cls.socket = socket.socket(); cls.socket.bind(('127.0.0.1', 0))
        cls.base = f'http://127.0.0.1:{cls.socket.getsockname()[1]}/internal/v1/audio-roles/models'
        cls.server = uvicorn.Server(uvicorn.Config(cls.app, log_level='error'))
        cls.thread = threading.Thread(target=cls.server.run, kwargs={'sockets': [cls.socket]}, daemon=True); cls.thread.start()
        for _ in range(100):
            if cls.server.started: return
            time.sleep(.02)
        raise RuntimeError('Model API server failed to start')

    @classmethod
    def tearDownClass(cls):
        cls.server.should_exit = True; cls.thread.join(5); cls.socket.close(); cls.temporary.cleanup()

    def request(self, suffix='', data=None, authenticated=True):
        headers = {'Authorization': 'Bearer '+'m'*64} if authenticated else {}
        headers['Content-Type'] = 'application/octet-stream' if suffix == '/import' else 'application/json'
        return urlopen(Request(self.base+suffix, headers=headers, data=data), timeout=10)

    def test_model_operations_require_internal_auth(self):
        for suffix, data in [('', None), ('/import', b'payload'), ('/download', b'{}')]:
            with self.assertRaises(HTTPError) as caught: self.request(suffix, data, False)
            self.assertEqual(caught.exception.code, 401)
            caught.exception.close()

    def test_import_checksum_failure_does_not_enable_a_model(self):
        with self.assertRaises(HTTPError) as caught: self.request('/import', b'not the pinned weights')
        self.assertEqual(caught.exception.code, 422); caught.exception.close()
        with self.request() as response: result = json.load(response)
        self.assertFalse(result['model']['installed']); self.assertIsNone(result['selected'])
        self.assertEqual(result['operation']['state'], 'FAILED')
        self.assertFalse(self.app.state.audio_roles.repo.exists())

    def test_download_accepts_no_arbitrary_url_and_uses_only_manifest_source(self):
        manager = self.app.state.audio_roles.models
        with patch('singverse_compute.application.audio_models.urlopen', return_value=io.BytesIO(b'invalid')) as fetch:
            with self.request('/download', b'{}') as response: self.assertEqual(response.status, 202)
            manager.thread.join(5)
            self.assertEqual(fetch.call_args.args[0], 'https://dl.fbaipublicfiles.com/demucs/hybrid_transformer/955717e8-8726e21a.th')
        self.assertEqual(manager.operation['state'], 'FAILED')
        with self.assertRaises(HTTPError) as caught: self.request('/select', b'{"backend":"rknn"}')
        self.assertEqual(caught.exception.code, 422); caught.exception.close()

    def test_openvino_zip_import_is_streamed_validated_and_does_not_enable_inference(self):
        import hashlib
        import zipfile
        from singverse_compute.application.audio_catalog import MODELS, OPENVINO
        data = {'htdemucs_fwd.xml': b'controlled model graph', 'htdemucs_fwd.bin': b'controlled weights'}
        specification = {**MODELS[OPENVINO], 'files': [
            {'name': name, 'size': len(value), 'sha256': hashlib.sha256(value).hexdigest(), 'url': 'https://example.invalid/'+name}
            for name, value in data.items()]}
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, 'w') as archive:
            for name, value in data.items(): archive.writestr(name, value)
        with patch.dict(MODELS, {OPENVINO: specification}):
            with self.request('/import?model=htdemucs-openvino', buffer.getvalue()) as response:
                self.assertEqual(json.load(response)['state'], 'SUCCEEDED')
            with self.request() as response:
                status = json.load(response)
            installed = next(model for model in status['models'] if model['id'] == OPENVINO)
            self.assertTrue(installed['installed'])
            self.assertIsNone(status['selected'])
            self.assertFalse(status['model']['installed'])
        with self.assertRaises(HTTPError) as caught:
            self.request('/import?model=unknown', b'body')
        self.assertEqual(caught.exception.code, 400); caught.exception.close()

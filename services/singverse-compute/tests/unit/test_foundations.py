import json
import logging
from pathlib import Path
import tempfile
import unittest
from singverse_compute.core.config import Settings
from singverse_compute.core.errors import ComputeError
from singverse_compute.core.logging import JsonFormatter
from singverse_compute.infrastructure.security import InternalAuth
from unittest.mock import patch

class FoundationTest(unittest.TestCase):
    def test_relative_deployment_paths_rejected(self):
        with patch.dict('os.environ', {'COMPUTE_AUTH_TOKEN_FILE':'relative'}):
            with self.assertRaises(ValueError): Settings.from_env()

    def test_token_initialization_private_and_stable(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder)/'token'
            auth = InternalAuth(path); auth.initialize(); original=path.read_text(); auth.initialize()
            import os
            if os.name == 'nt':
                import subprocess
                permissions=subprocess.run(['icacls',str(path)],capture_output=True,text=True,check=True).stdout
                self.assertNotIn('(I)',permissions)
                self.assertNotIn('S-1-1-0',permissions)  # Everyone must not be granted access.
            else:
                self.assertEqual(path.stat().st_mode & 0o777, 0o600)
            self.assertEqual(path.read_text(), original)
            auth.authorize('Bearer '+original)
            with self.assertRaises(ComputeError): auth.authorize('Bearer wrong')
            with self.assertRaises(ComputeError): auth.authorize('Bearer 无效凭据')

    def test_remote_reader_failure_releases_instance_capacity(self):
        from types import SimpleNamespace
        from singverse_compute.bootstrap import create_app
        from singverse_compute.api.routers.remote_tags import Input, remote_tags
        with tempfile.TemporaryDirectory() as folder:
            app = create_app(Settings(token_file=Path(folder) / 'token'))
            other = create_app(Settings(token_file=Path(folder) / 'other-token'))
            body = Input(callbackOrigin='http://127.0.0.1', reference='11111111-1111-1111-1111-111111111111', filename='song.mp3')
            request = SimpleNamespace(app=app)
            with patch('singverse_compute.api.routers.remote_tags.extract', side_effect=[RuntimeError('fixture failure'), {'state': 'EMPTY'}]):
                with self.assertRaises(RuntimeError): remote_tags(body, request)
                self.assertEqual(remote_tags(body, request)['state'], 'EMPTY')
            app.state.remote_tag_slots.acquire()
            try:
                self.assertTrue(other.state.remote_tag_slots.acquire(blocking=False))
                other.state.remote_tag_slots.release()
            finally:
                app.state.remote_tag_slots.release()

    def test_json_log_whitelists_request_context(self):
        record=logging.makeLogRecord({'name':'singverse_compute.http','msg':'request.complete','levelname':'INFO',
                                      'request_id':'test','status':200,'authorization':'never-log','body':'never-log'})
        event=json.loads(JsonFormatter().format(record))
        self.assertEqual(event['request_id'],'test'); self.assertEqual(event['status'],200)
        self.assertNotIn('authorization',event); self.assertNotIn('body',event)

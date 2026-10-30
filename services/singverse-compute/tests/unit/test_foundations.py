import json
import logging
from pathlib import Path
import tempfile
import unittest
from singverse_compute.core.config import Settings
from singverse_compute.core.errors import ComputeError
from singverse_compute.core.logging import JsonFormatter
from singverse_compute.domain.media_tags import MediaTags
from singverse_compute.infrastructure.security import InternalAuth
from singverse_compute.infrastructure.filesystem import RootedMediaPaths
from singverse_compute.application.media_tags import MediaTagService
from unittest.mock import patch

class FoundationTest(unittest.TestCase):
    def test_relative_deployment_paths_rejected(self):
        with patch.dict('os.environ', {'COMPUTE_MEDIA_ROOTS':'relative'}):
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

    def test_worker_failure_releases_capacity_and_outside_path_never_reads(self):
        class Reader:
            available = True
            calls = 0
            def read(self, path):
                self.calls += 1
                if self.calls == 1: raise RuntimeError('fixture worker failure')
                return MediaTags(title='test')
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder)/'media.mp3'; path.write_bytes(b'fixture')
            reader=Reader(); service=MediaTagService(RootedMediaPaths((Path(folder),)),reader,1)
            with self.assertRaises(ComputeError): service.read('/not/allowed.mp3')
            self.assertEqual(reader.calls,0)
            with self.assertRaises(RuntimeError): service.read(str(path))
            self.assertEqual(service.read(str(path)).title,'test')

    def test_json_log_whitelists_request_context(self):
        record=logging.makeLogRecord({'name':'singverse_compute.http','msg':'request.complete','levelname':'INFO',
                                      'request_id':'test','status':200,'authorization':'never-log','body':'never-log'})
        event=json.loads(JsonFormatter().format(record))
        self.assertEqual(event['request_id'],'test'); self.assertEqual(event['status'],200)
        self.assertNotIn('authorization',event); self.assertNotIn('body',event)

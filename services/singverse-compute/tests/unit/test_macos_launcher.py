import importlib.util
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import plistlib
import tempfile
import threading
from types import SimpleNamespace
import unittest
from unittest.mock import patch
from urllib.error import HTTPError

spec = importlib.util.spec_from_file_location('macos_launcher', Path(__file__).resolve().parents[4]/'scripts/macos-compute.py')
launcher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(launcher)


class MacosLauncherTest(unittest.TestCase):
    def test_pairing_defaults_to_enabled_without_saved_configuration(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(launcher.os.environ, {}, clear=True):
            value = launcher.pairing_environment(Path(directory) / 'compute.plist')
        self.assertEqual(value, {'COMPUTE_LAN_PAIRING_ENABLED': 'true'})

    def test_pairing_restart_preserves_saved_disabled_setting(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(launcher.os.environ, {}, clear=True):
            previous = Path(directory) / 'compute.plist'
            previous.write_bytes(plistlib.dumps({'EnvironmentVariables': {'COMPUTE_LAN_PAIRING_ENABLED': 'false'}}))
            self.assertEqual(launcher.pairing_environment(previous), {'COMPUTE_LAN_PAIRING_ENABLED': 'false'})

    def test_explicit_shell_pairing_setting_overrides_saved_configuration(self):
        with tempfile.TemporaryDirectory() as directory:
            previous = Path(directory) / 'compute.plist'
            for saved, current in (('true', 'false'), ('false', 'TRUE')):
                with self.subTest(saved=saved, current=current), patch.dict(launcher.os.environ, {'COMPUTE_LAN_PAIRING_ENABLED': current}, clear=True):
                    previous.write_bytes(plistlib.dumps({'EnvironmentVariables': {'COMPUTE_LAN_PAIRING_ENABLED': saved}}))
                    self.assertEqual(launcher.pairing_environment(previous), {'COMPUTE_LAN_PAIRING_ENABLED': current.lower()})

    def test_pairing_defaults_for_older_plist_and_rejects_invalid_shell_or_saved_values(self):
        with tempfile.TemporaryDirectory() as directory:
            previous = Path(directory) / 'compute.plist'
            previous.write_bytes(plistlib.dumps({'EnvironmentVariables': {'COMPUTE_AUDIO_THREADS': '2'}}))
            with patch.dict(launcher.os.environ, {}, clear=True):
                self.assertEqual(launcher.pairing_environment(previous), {'COMPUTE_LAN_PAIRING_ENABLED': 'true'})
            for value in ('', 'yes', '0', 'false '):
                with self.subTest(value=value), patch.dict(launcher.os.environ, {'COMPUTE_LAN_PAIRING_ENABLED': value}, clear=True), self.assertRaisesRegex(ValueError, 'must be true or false'):
                    launcher.pairing_environment(previous)
            previous.write_bytes(plistlib.dumps({'EnvironmentVariables': {'COMPUTE_LAN_PAIRING_ENABLED': 'invalid'}}))
            with patch.dict(launcher.os.environ, {}, clear=True), self.assertRaisesRegex(ValueError, 'must be true or false'):
                launcher.pairing_environment(previous)

    def test_audio_configuration_uses_fixed_models_and_explicit_budget(self):
        value = launcher.audio_environment(Path('/models/htdemucs'), 2048)
        self.assertEqual(value['COMPUTE_AUDIO_MODEL_REPO'], '/models/htdemucs')
        self.assertEqual(value['COMPUTE_AUDIO_MEMORY_BYTES'], str(2*1024**3))
        self.assertEqual(value['PYTORCH_ENABLE_MPS_FALLBACK'], '0')
        for path, memory in ((Path('relative'), 2048), (Path('/models'), 511)):
            with self.assertRaises(ValueError):
                launcher.audio_environment(path, memory)

    def test_launchd_restart_retries_registration_removal_race(self):
        with patch.object(launcher.subprocess, 'run', side_effect=[SimpleNamespace(returncode=5), SimpleNamespace(returncode=0)]) as run, patch.object(launcher.time, 'sleep') as sleep:
            launcher.bootstrap('gui/501', Path('/runtime/node.plist'))
        self.assertEqual(run.call_count, 2)
        sleep.assert_called_once_with(.25)

    def test_other_launchd_errors_are_not_hidden(self):
        with patch.object(launcher.subprocess, 'run', return_value=SimpleNamespace(returncode=64)) as run, self.assertRaises(SystemExit):
            launcher.bootstrap('gui/501', Path('/runtime/node.plist'))
        self.assertEqual(run.call_count, 1)


class MacosReadinessTest(unittest.TestCase):
    def setUp(self):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        self.runtime = Path(folder.name)
        self.token = 'isolated-native-credential-' + 'a' * 40
        self.token_file = self.runtime / 'token'
        self.token_file.write_text(self.token)
        self.status = 200
        self.identity = {'service': 'singverse-compute', 'apiVersion': '1', 'capabilities': []}
        self.requests = []
        fixture = self

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                fixture.requests.append((self.path, self.headers.get('Authorization')))
                if self.path == '/health':
                    status, value = 200, {'status': 'UP'}
                else:
                    status = fixture.status if self.headers.get('Authorization') == 'Bearer ' + fixture.token else 401
                    value = fixture.identity
                self.send_response(status)
                if status == 302:
                    self.send_header('Location', '/redirect-target')
                self.end_headers()
                self.wfile.write(json.dumps(value).encode())

            def log_message(self, *args):
                pass

        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.worker = threading.Thread(target=self.server.serve_forever, kwargs={'poll_interval': .01}, daemon=True)
        self.worker.start()
        self.addCleanup(self.close_server)
        origin = patch.object(launcher, 'NODE_ORIGIN', 'http://127.0.0.1:' + str(self.server.server_port))
        origin.start()
        self.addCleanup(origin.stop)

    def close_server(self):
        self.server.shutdown()
        self.worker.join(5)
        self.server.server_close()

    def test_readiness_checks_authenticated_identity_without_environment_proxy(self):
        with patch.dict(launcher.os.environ, {'http_proxy': 'http://127.0.0.1:1', 'no_proxy': ''}, clear=True):
            launcher.check_node(self.token_file)
        self.assertEqual(self.requests, [('/internal/v1/status', 'Bearer ' + self.token)])

    def test_existing_process_is_rejected_when_only_health_succeeds(self):
        self.status = 401
        with patch.object(launcher, 'RUNTIME', self.runtime), \
             patch.object(launcher.platform, 'system', return_value='Darwin'), \
             patch.object(launcher.subprocess, 'run', return_value=SimpleNamespace(returncode=0, stdout=str(launcher.ROOT).encode())), \
             patch('sys.argv', ['macos-compute.py', 'start']), \
             patch.object(launcher, 'login_agent') as login, \
             self.assertRaisesRegex(SystemExit, 'authenticated status failed'):
            launcher.main()
        login.assert_not_called()

    def test_node_file_failure_is_not_reported_ready(self):
        self.status = 503
        with self.assertRaises(HTTPError) as raised:
            launcher.check_node(self.token_file)
        self.assertEqual(raised.exception.code, 503)
        raised.exception.close()

    def test_incompatible_identity_is_rejected(self):
        self.identity['service'] = 'unrelated-service'
        with self.assertRaisesRegex(ValueError, 'incompatible status'):
            launcher.check_node(self.token_file)

    def test_redirect_cannot_receive_the_credential(self):
        self.status = 302
        with self.assertRaises(HTTPError) as raised:
            launcher.check_node(self.token_file)
        self.assertEqual(raised.exception.code, 302)
        raised.exception.close()
        self.assertEqual(len(self.requests), 1)

    def test_local_unreadable_credential_does_not_issue_a_request(self):
        with patch.object(Path, 'open', side_effect=PermissionError('private filesystem detail')), \
             self.assertRaisesRegex(ValueError, '^Cannot read the native compute credential file$'):
            launcher.check_node(self.token_file)
        self.assertEqual(self.requests, [])

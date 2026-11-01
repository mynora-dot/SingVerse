import io
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from singverse_compute.application.audio_models import AudioModels, DIGEST, NAME, CONFIG
from singverse_compute.application.audio_roles import AudioRoles
from singverse_compute.core.errors import ComputeError


class AudioModelManagementTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        root = Path(self.temporary.name)
        audio = SimpleNamespace(root=root/'tasks', repo=root/'htdemucs', runtime_available=True,
                                digest=AudioRoles.digest, settings=SimpleNamespace(minimum_free_bytes=0, audio_memory_bytes=1024))
        audio.transcoding = SimpleNamespace(backends=SimpleNamespace(cpu_fallback=True))
        audio.root.mkdir()
        self.manager = AudioModels(audio)
        self.manager.environment = {'fingerprint': 'env1', 'devices': {'cpu': {'name': 'CPU'}, 'cuda': {'name': 'GPU'}}, 'versions': {}}

    def test_only_environment_and_model_qualified_backends_are_selected(self):
        manager = self.manager
        self.assertIsNone(manager.selected())
        manager.qualified['cpu'] = {'state': 'AVAILABLE', 'environment': 'env1', 'digest': DIGEST}
        self.assertEqual(manager.selected(), 'cpu')
        manager.qualified['cuda'] = {'state': 'AVAILABLE', 'environment': 'env1', 'digest': DIGEST}
        self.assertEqual(manager.selected(), 'cuda')
        manager.environment['fingerprint'] = 'driver-changed'
        self.assertIsNone(manager.selected())
        with self.assertRaises(ComputeError): manager.choose('cuda')
        entries = {item['id']: item for item in manager.entries()}
        self.assertEqual(entries['rknn']['state'], 'MODEL_UNSUPPORTED')
        self.assertEqual(entries['xpu']['state'], 'MODEL_UNSUPPORTED')

    def test_bad_import_preserves_existing_files_and_is_never_enabled(self):
        manager = self.manager
        manager.repo.mkdir(); (manager.repo / NAME).write_bytes(b'preserve')
        manager.begin('IMPORTING')
        with manager.stage() as temporary:
            with self.assertRaises(ComputeError) as caught:
                manager.install(io.BytesIO(b'invalid'), Path(temporary))
            manager.failed(caught.exception)
        self.assertEqual((manager.repo / NAME).read_bytes(), b'preserve')
        self.assertEqual(manager.operation['state'], 'FAILED')
        self.assertFalse(manager.weights_valid())

    def test_cancel_and_size_limit_cleanup_without_installing(self):
        manager = self.manager
        manager.begin('IMPORTING'); manager.stop()
        with manager.stage() as temporary:
            with self.assertRaises(ComputeError): manager.install(io.BytesIO(b'x'), Path(temporary))
        self.assertFalse(manager.repo.exists())
        manager.failed(ComputeError(409, 'cancelled'))
        manager.begin('IMPORTING')
        with manager.stage() as temporary, patch('singverse_compute.application.audio_models.MAX_BYTES', 2):
            with self.assertRaises(ComputeError) as caught: manager.install(io.BytesIO(b'oversize'), Path(temporary))
            self.assertEqual(caught.exception.status, 413)
        self.assertFalse(manager.repo.exists())

    def test_valid_import_is_atomic_and_repeated_install_keeps_model(self):
        import hashlib
        manager = self.manager
        data = b'controlled fixture'
        manager.repo.mkdir()  # A pre-created empty model directory is a supported deployment layout.
        with patch('singverse_compute.application.audio_models.DIGEST', hashlib.sha256(data).hexdigest()), patch('singverse_compute.application.audio_models.MODEL_BYTES', len(data)):
            for _ in range(2):
                manager.begin('IMPORTING')
                with manager.stage() as temporary: manager.install(io.BytesIO(data), Path(temporary))
                self.assertEqual(manager.operation['state'], 'SUCCEEDED')
                self.assertEqual((manager.repo / NAME).read_bytes(), data)
                self.assertEqual((manager.repo / 'htdemucs.yaml').read_text(), CONFIG)
        self.assertFalse(list(manager.repo.parent.glob('.singverse-model-*')))

    def test_restart_marks_interrupted_install_failed_and_preserves_model(self):
        manager = self.manager
        manager.begin('DOWNLOADING')
        stage = manager.repo.parent / '.singverse-model-old'; stage.mkdir(); (stage/'partial').write_bytes(b'x')
        restored = AudioModels(manager.audio)
        self.assertEqual(restored.operation['state'], 'FAILED')
        self.assertEqual(restored.operation['error'], 'NODE_RESTARTED')
        self.assertFalse(stage.exists())

    def test_install_requires_writable_directory_and_resources(self):
        manager = self.manager
        with patch.object(manager, 'writable', return_value=False):
            with self.assertRaises(ComputeError): manager.download()
        with patch('singverse_compute.application.audio_models.memory_available', return_value=1):
            with self.assertRaises(ComputeError): manager.check_resources('cpu')
        self.assertEqual(manager.operation['state'], 'IDLE')

    def test_unsupported_mps_operator_has_actionable_sanitized_diagnosis(self):
        from singverse_compute.application.audio_roles import AudioTask
        audio=self.manager.audio;audio.transcoding=SimpleNamespace(tasks={});audio.settings.audio_threads=2
        def failed_run(*args, **kwargs):
            kwargs['error_line']('NotImplementedError: Output channels > 65536 not supported at the MPS device. /private/test-path')
            raise ComputeError(422,'Media execution failed')
        with patch('singverse_compute.application.audio_roles.run',side_effect=failed_run):
            with self.assertRaises(ComputeError) as caught: AudioRoles._run(audio,['test'],AudioTask('test'))
        self.assertIn('所需算子',caught.exception.message)
        self.assertNotIn('/private',caught.exception.message)

    def test_hardware_cpu_guard_and_legacy_selection(self):
        manager = self.manager
        with patch.object(manager, 'entries', return_value=[{'id':'cpu','state':'AVAILABLE','detected':False}, {'id':'cuda','state':'UNVERIFIED','detected':True}]):
            self.assertEqual(manager.selected(), 'cpu')
            with self.assertRaises(ComputeError): manager.choose('cpu')
            manager.audio.transcoding.backends.cpu_fallback = False
            self.assertIsNone(manager.selected())
            manager.selection = 'cpu'
            self.assertIsNone(manager.selected())
        with patch.object(manager, 'entries', return_value=[{'id':'cpu','state':'AVAILABLE','detected':False}]):
            self.assertEqual(manager.selected(), 'cpu')


class StartupQualificationTest(unittest.TestCase):
    setUp = AudioModelManagementTest.setUp

    def prepare_startup(self):
        import threading
        manager = self.manager
        manager.audio.lock = threading.RLock()
        manager.audio.active = False
        manager.audio.transcoding.lock = threading.RLock()
        manager.audio.transcoding.realtime_reserved_until = 0
        manager.audio.transcoding.tasks = {}
        return manager

    def test_startup_qualifies_only_unverified_installed_backends_and_cpu_fallback(self):
        import threading
        manager = self.prepare_startup()
        entries = [{'id': 'cpu', 'state': 'UNVERIFIED'}, {'id': 'mps', 'state': 'UNVERIFIED', 'detected': True},
                   {'id': 'cuda', 'state': 'MODEL_MISSING'}, {'id': 'openvino', 'state': 'AVAILABLE'}]
        calls = []
        def test(backend):
            calls.append(backend)
            manager.thread = threading.Thread(target=lambda: None)
            manager.thread.start()
        with patch.object(manager, 'probe'), patch.object(manager, 'entries', return_value=entries), patch.object(manager, 'test', side_effect=test):
            manager.start(); manager.start(); manager.startup_thread.join(2)
        self.assertEqual(calls, ['mps', 'cpu'])
        self.assertEqual(manager.startup_check['state'], 'COMPLETED')

    def test_startup_skips_valid_qualifications_and_disabled_cpu_fallback(self):
        manager = self.prepare_startup()
        manager.audio.transcoding.backends.cpu_fallback = False
        entries = [{'id': 'cpu', 'state': 'UNVERIFIED'}, {'id': 'mps', 'state': 'AVAILABLE', 'detected': True}]
        with patch.object(manager, 'probe'), patch.object(manager, 'entries', return_value=entries), patch.object(manager, 'test') as trial:
            manager.start(); manager.startup_thread.join(2)
        trial.assert_not_called()
        self.assertEqual(manager.startup_check['state'], 'COMPLETED')

    def test_startup_waits_for_playback_and_can_be_cancelled_without_running_inference(self):
        import time
        manager = self.prepare_startup()
        manager.audio.transcoding.realtime_reserved_until = time.monotonic() + 60
        with patch.object(manager, 'probe'), patch.object(manager, 'entries', return_value=[{'id': 'cpu', 'state': 'UNVERIFIED'}]), patch.object(manager, 'test') as trial:
            manager.start()
            deadline = time.monotonic() + 2
            while manager.startup_check['state'] != 'WAITING' and time.monotonic() < deadline:
                time.sleep(.01)
            self.assertEqual(manager.startup_check['state'], 'WAITING')
            manager.close()
        trial.assert_not_called()
        self.assertEqual(manager.startup_check['state'], 'CANCELLED')

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
        self.assertEqual(entries['rknn']['state'], 'RUNTIME_MISSING')
        self.assertEqual(entries['xpu']['state'], 'MODEL_UNSUPPORTED')

    def test_rknn_requires_local_model_runtime_and_matching_qualification(self):
        manager = self.manager
        manager.audio.dependencies_available = True
        manager.environment['devices']['rknn'] = {'name': 'NPU'}
        manager.environment['versions']['rknn-toolkit-lite2'] = '2.3.2'
        with patch.object(manager, 'model_valid', return_value=False):
            entry = next(x for x in manager.entries() if x['id'] == 'rknn')
            self.assertEqual(entry['state'], 'MODEL_MISSING')
        with patch.object(manager, 'model_valid', return_value=True), patch.object(manager, 'inference_digest', return_value='local-digest'):
            entry = next(x for x in manager.entries() if x['id'] == 'rknn')
            self.assertEqual(entry['state'], 'UNVERIFIED')
            manager.qualified['rknn'] = {'state': 'AVAILABLE', 'environment': 'env1', 'digest': 'local-digest'}
            self.assertEqual(manager.selected(), 'rknn')
            manager.qualified['rknn']['digest'] = 'old-model'
            self.assertIsNone(manager.selected())
            manager.qualified['rknn']['digest'] = 'local-digest'
            manager.environment['versions']['rknn-toolkit-lite2'] = 'wrong-version'
            self.assertIsNone(manager.selected())

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

    def test_runtime_probe_uses_configured_audio_budget_and_keeps_its_limit(self):
        manager = self.manager
        observed_rss = 1536 * 1024**2
        runtime = {'devices': {'rocm': {'name': 'AMD GPU', 'memoryBytes': 6 * 1024**3}},
                   'versions': {'torch': '2.5.1+rocm6.2', 'torchaudio': '2.5.1+rocm6.2', 'demucs': '4.0.1'}}

        def import_runtime(command, *, memory_limit=1024**3, **kwargs):
            if observed_rss > memory_limit:
                raise ComputeError(422, 'Media process exceeded memory budget')
            return json.dumps(runtime).encode()

        for budget in (1024**3, 3 * 1024**3):
            with self.subTest(audio_budget=budget):
                manager.audio.settings.audio_memory_bytes = budget
                with patch('singverse_compute.infrastructure.transcoding.process.run', side_effect=import_runtime) as run:
                    environment = manager.probe(refresh=True)
                    self.assertEqual(run.call_args.kwargs['memory_limit'], budget)
                    self.assertEqual(run.call_args.kwargs['timeout'], 30)
                    self.assertEqual(run.call_args.kwargs['limit'], 65536)
                    if budget < observed_rss:
                        self.assertEqual(environment['error'], 'RUNTIME_MISSING_OR_INCOMPATIBLE')
                        self.assertEqual(environment['devices'], {})
                    else:
                        self.assertEqual(environment['devices']['rocm']['name'], 'AMD GPU')
                        self.assertEqual(environment['versions'], runtime['versions'])
                        self.assertNotIn('error', environment)
                    self.assertIs(manager.probe(), environment)
                    self.assertEqual(run.call_count, 1, 'A cached environment does not re-import the GPU runtime')

    def test_gpu_free_memory_query_uses_configured_audio_budget_and_keeps_its_limit(self):
        manager = self.manager
        observed_rss = 1536 * 1024**2
        manager.environment['devices']['rocm'] = {'name': 'AMD GPU', 'memoryBytes': 6 * 1024**3}

        def query_free_memory(command, *, memory_limit=1024**3, **kwargs):
            if observed_rss > memory_limit:
                raise ComputeError(422, 'Media process exceeded memory budget')
            return str(4 * 1024**3).encode()

        for budget in (1024**3, 3 * 1024**3):
            with self.subTest(audio_budget=budget):
                manager.audio.settings.audio_memory_bytes = budget
                with patch('singverse_compute.application.audio_models.memory_available', return_value=6 * 1024**3), \
                        patch('singverse_compute.infrastructure.transcoding.process.run', side_effect=query_free_memory) as run:
                    if budget < observed_rss:
                        with self.assertRaises(ComputeError) as caught:
                            manager.check_resources('rocm')
                        self.assertEqual(caught.exception.status, 422)
                        self.assertEqual(caught.exception.message, 'GPU free memory cannot be determined')
                    else:
                        manager.check_resources('rocm')
                    self.assertEqual(run.call_count, 1)
                    self.assertEqual(run.call_args.kwargs['memory_limit'], budget)
                    self.assertEqual(run.call_args.kwargs['timeout'], 30)
                    self.assertEqual(run.call_args.kwargs['limit'], 4096)

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

    def test_explicit_cpu_is_allowed_even_when_accelerator_is_detected(self):
        manager = self.manager
        with patch.object(manager, 'entries', return_value=[{'id':'cpu','state':'AVAILABLE','detected':False}, {'id':'cuda','state':'UNVERIFIED','detected':True}]):
            self.assertEqual(manager.selected(), 'cpu')
            manager.choose('cpu')
            self.assertEqual(manager.selected(), 'cpu')
            manager.audio.transcoding.backends.cpu_fallback = False
            self.assertEqual(manager.selected(), 'cpu')
            manager.selection = 'auto'
            self.assertIsNone(manager.selected())
        with patch.object(manager, 'entries', return_value=[{'id':'cpu','state':'AVAILABLE','detected':False}]):
            self.assertEqual(manager.selected(), 'cpu')


    def test_installed_rknn_manifest_miss_and_hit_do_not_wait_for_environment_probe(self):
        from concurrent.futures import ThreadPoolExecutor
        import hashlib
        import threading
        from singverse_compute.application.audio_catalog import RKNN
        from singverse_compute.infrastructure.rknn_audio import local_manifest
        manager = self.manager
        folder = manager.model_path(RKNN); folder.mkdir()
        model = folder / 'uvr.rknn'; model.write_bytes(b'controlled graph')
        (folder / 'manifest.json').write_text(json.dumps({
            'profile': 'uvr-mdxnet-9482-v1', 'target': 'rk3566',
            'sourceSha256': '9d78f8566fa8198065214ab628be1de966a500c57786695aa4b13e2b27a7727d',
            'toolkit': '2.3.2', 'size': model.stat().st_size,
            'sha256': hashlib.sha256(model.read_bytes()).hexdigest()}))
        locked, proceed = threading.Event(), threading.Event()
        def probing():
            with manager.lock:
                locked.set(); proceed.wait(3)
        holder = threading.Thread(target=probing); holder.start()
        try:
            self.assertTrue(locked.wait(1))
            with patch('singverse_compute.infrastructure.rknn_audio.local_manifest', wraps=local_manifest) as hashing, \
                    ThreadPoolExecutor(max_workers=1) as executor:
                for _ in range(2):
                    self.assertTrue(executor.submit(manager.model_valid, RKNN).result(timeout=1))
                self.assertEqual(hashing.call_count, 1, 'The second request uses the verified manifest snapshot')
        finally:
            proceed.set(); holder.join(2)

    def test_status_reuses_one_backend_snapshot_and_rknn_hash_until_files_change(self):
        import hashlib
        from singverse_compute.application.audio_catalog import RKNN
        from singverse_compute.infrastructure.rknn_audio import local_manifest
        manager = self.manager
        folder = manager.model_path(RKNN); folder.mkdir()
        model = folder / 'uvr.rknn'; model.write_bytes(b'controlled graph')
        metadata = {'profile': 'uvr-mdxnet-9482-v1', 'target': 'rk3566',
                    'sourceSha256': '9d78f8566fa8198065214ab628be1de966a500c57786695aa4b13e2b27a7727d',
                    'toolkit': '2.3.2', 'size': model.stat().st_size, 'sha256': hashlib.sha256(model.read_bytes()).hexdigest()}
        manifest = folder / 'manifest.json'; manifest.write_text(json.dumps(metadata))
        manager.audio.dependencies_available = True
        manager.environment['devices']['rknn'] = {'name': 'NPU'}
        manager.environment['versions']['rknn-toolkit-lite2'] = '2.3.2'
        manager.qualified['rknn'] = {'state': 'AVAILABLE', 'environment': 'env1', 'digest': local_manifest(folder)[1]}
        with patch('singverse_compute.infrastructure.rknn_audio.local_manifest', wraps=local_manifest) as hashing, patch.object(manager, 'entries', wraps=manager.entries) as entries:
            self.assertEqual(manager.status()['selected'], 'rknn')
            self.assertEqual(entries.call_count, 1)
            self.assertEqual(hashing.call_count, 1)
            manager.status()
            self.assertEqual(hashing.call_count, 1)
            # Same-length edits still invalidate the cache, even when mtime is restored.
            before = model.stat()
            model.write_bytes(b'corrupted graph!')
            import os
            os.utime(model, ns=(before.st_atime_ns, before.st_mtime_ns))
            self.assertFalse(manager.model_valid(RKNN))
            self.assertEqual(hashing.call_count, 2)
            model.write_bytes(b'controlled graph')
            self.assertTrue(manager.model_valid(RKNN))
            metadata['note'] = 'replacement manifest'
            manifest.write_text(json.dumps(metadata))
            self.assertNotEqual(manager.inference_digest(RKNN), manager.qualified['rknn']['digest'])
            self.assertEqual(hashing.call_count, 4)


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

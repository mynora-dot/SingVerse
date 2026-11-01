"""Deterministic node policy checks; no real model or GPU required."""
import json
from pathlib import Path
import tempfile
import threading
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

from singverse_compute.application.audio_roles import AudioRoles, AudioTask
from singverse_compute.infrastructure.hardware import accelerator_failure
from singverse_compute.infrastructure.transcoding.backends import Backends
from singverse_compute.infrastructure.transcoding.settings import HardwareSettings
from singverse_compute.core.errors import ComputeError


class ExecutionPolicyTest(unittest.TestCase):
    def backends(self):
        backend = Backends('unused')
        backend._results = [{'backend': name, 'probeAvailable': False, 'codecs': {'h264': {'samplePassed': passed}}}
                            for name, passed in [('nvenc', False), ('software', True)]]
        return backend

    def test_hardware_prioritized_and_cpu_only_automatic(self):
        backend = self.backends()
        with patch.object(backend, 'detected', return_value=['nvenc']):
            self.assertEqual(backend.select(), 'software')
            backend.cpu_fallback = False
            with self.assertRaises(ComputeError): backend.select()
            backend._results[0]['codecs']['h264']['samplePassed'] = True
            backend.preferred = 'software'  # old explicit CPU config
            self.assertEqual(backend.select(), 'nvenc')
        with patch.object(backend, 'detected', return_value=[]):
            backend._results[0]['codecs']['h264']['samplePassed'] = False
            self.assertEqual(backend.select(), 'software')

    def test_persistence_default_revision_and_manual_cpu_guard(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); backend = self.backends()
            (root/'hardware-settings.json').write_text(json.dumps({'backend': 'auto', 'revision': 2}))
            with patch.object(backend, 'detected', return_value=['nvenc']):
                settings = HardwareSettings(root, backend)
                self.assertTrue(settings.read()['cpuFallback'])
                with self.assertRaises(ComputeError): settings.save('software', 2)
                result = settings.save('auto', 2, False)
                self.assertFalse(result['cpuFallback'])
                self.assertEqual(HardwareSettings(root, backend).read()['cpuFallback'], False)
                with self.assertRaises(ComputeError): settings.save('auto', 2, True)

    def worker(self):
        worker = object.__new__(AudioRoles)
        worker.backend_runtime_available = Mock(return_value=True)
        worker.models = SimpleNamespace(entries=lambda: [{'id': 'cpu', 'state': 'AVAILABLE'}], check_resources=Mock())
        return worker

    def test_audio_device_failure_retries_once_and_records_backend(self):
        worker = self.worker(); task = AudioTask('task'); task.backend = 'cuda'; task.cpu_fallback = True
        error = ComputeError(422, 'Hardware accelerator execution failed')
        with tempfile.TemporaryDirectory() as directory, patch.object(AudioRoles, '_separate_once', side_effect=[error, ('vocal', 'backing')]) as run:
            self.assertEqual(worker._separate(Path('source'), Path(directory)/'out', task), ('vocal', 'backing'))
            self.assertEqual(task.backend, 'cpu'); self.assertEqual(run.call_count, 2)
            self.assertEqual(task.result['fallbackReason'], 'ACCELERATOR_EXECUTION_FAILED')
            run.side_effect = error
            with self.assertRaises(ComputeError): worker._separate(Path('source'), Path(directory)/'out', task)
            self.assertEqual(run.call_count, 3)

    def test_no_retry_for_disabled_fallback_missing_cpu_or_unrelated_error(self):
        for enabled, ready, message in [(False, True, 'Hardware accelerator execution failed'),
                (True, False, 'Hardware accelerator execution failed'), (True, True, 'Invalid data'),
                (True, True, 'No space left'), (True, True, 'Media process exceeded memory budget')]:
            with self.subTest(message=message, enabled=enabled, ready=ready):
                worker = self.worker(); worker.backend_runtime_available.return_value = ready
                task = AudioTask('task'); task.backend = 'cuda'; task.cpu_fallback = enabled
                with patch.object(AudioRoles, '_separate_once', side_effect=ComputeError(422, message)) as run:
                    with self.assertRaises(ComputeError): worker._separate(Path('source'), Path('out'), task)
                    self.assertEqual(run.call_count, 1)
        self.assertFalse(accelerator_failure('CUDA error: no space left'))

    def test_cancel_does_not_retry(self):
        worker = self.worker(); task = AudioTask('task'); task.backend = 'cuda'; task.cpu_fallback = True; task.cancel.set()
        with patch.object(AudioRoles, '_separate_once', side_effect=ComputeError(422, 'Hardware accelerator execution failed')) as run:
            with self.assertRaises(ComputeError): worker._separate(Path('source'), Path('out'), task)
            self.assertEqual(run.call_count, 1)

    def test_video_retry_only_before_playlist_and_when_enabled(self):
        from singverse_compute.application.transcoding import Transcoding, Execution
        from singverse_compute.domain.transcoding import Media, Stream, Target
        from singverse_compute.core.config import Settings
        from singverse_compute.infrastructure.filesystem import RootedMediaPaths
        from singverse_compute.infrastructure.security import InternalAuth
        for enabled, published, message, expected in [(True, False, 'Hardware accelerator execution failed', 2),
                (False, False, 'Hardware accelerator execution failed', 1),
                (True, True, 'Hardware accelerator execution failed', 1), (True, False, 'Invalid data', 1)]:
            with self.subTest(enabled=enabled, published=published, message=message), tempfile.TemporaryDirectory() as folder:
                root = Path(folder); media = root/'media'; media.mkdir(); source = media/'source.mkv'; source.write_bytes(b'fixture')
                cache = root/'cache'
                config = Settings((media,), root/'token', transcode_root=cache, minimum_free_bytes=0)
                probe = SimpleNamespace(read=lambda *a: Media('mkv', 60, 1000, (Stream('stream:0',0,'video','h264'),)))
                backends = SimpleNamespace(select=lambda *a, **kw: 'nvenc', detected=lambda: ['nvenc'])
                auth = InternalAuth(root/'token'); auth.initialize()
                calls = []
                def encode(*args):
                    directory, backend = args[4], args[-1]; calls.append(backend)
                    if backend == 'nvenc':
                        if published: (directory/'master.m3u8').write_text('#EXTM3U')
                        raise ComputeError(422, message)
                    return []
                service = Transcoding(config, RootedMediaPaths((media,)), auth, probe, SimpleNamespace(execute=encode), backends, None)
                task = Execution('task', 'signature', Target()); task.cpu_fallback = enabled
                (cache/'task').mkdir(); service.execute(task, str(source), None)
                self.assertEqual(len(calls), expected)
                self.assertEqual(task.state, 'SUCCEEDED' if expected == 2 else 'FAILED')
                if expected == 2:
                    self.assertEqual(task.backend, 'software'); self.assertEqual(task.fallback_reason, 'ACCELERATOR_EXECUTION_FAILED')
                service.close()

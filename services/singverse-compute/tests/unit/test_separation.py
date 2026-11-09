import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from callback_source import CallbackTestCase
import hashlib
import json
import subprocess
import tempfile
import threading
import time
import unittest
import uuid
from types import SimpleNamespace
from unittest.mock import Mock, patch

from singverse_compute.application.audio_roles import AudioTask
from singverse_compute.application.separation_engine import SeparationEngine
from singverse_compute.core.errors import ComputeError
from singverse_compute.domain.audio_separation import CLEAN, DSP, recipe, threads


class RecipeTest(unittest.TestCase):
    def test_cpu_probe_does_not_query_gpu_devices_or_drivers(self):
        from importlib.metadata import PackageNotFoundError
        from singverse_compute.infrastructure.separation_worker import probe
        class CpuCore:
            @property
            def available_devices(self):
                raise AssertionError('CPU fallback must not enumerate GPU drivers')
            def get_property(self, device, name):
                if device != 'CPU': raise AssertionError('Unexpected GPU query')
                return 'CPU' if name == 'FULL_DEVICE_NAME' else ''
        def package(name):
            if name in ('onnxruntime', 'numpy', 'soundfile', 'openvino'):
                return 'test-version'
            raise PackageNotFoundError(name)
        with patch.dict(sys.modules, {'onnxruntime': SimpleNamespace(get_available_providers=lambda: ['CPUExecutionProvider']),
                                     'openvino': SimpleNamespace(Core=CpuCore)}), \
             patch('singverse_compute.infrastructure.separation_worker.version', side_effect=package), \
             patch('singverse_compute.infrastructure.separation_worker.platform.system', return_value='Linux'), \
             patch('subprocess.run', side_effect=AssertionError('CPU probe ran a GPU command')):
            result = probe(cpu_only=True)
        self.assertTrue(result['standaloneCpu'])
        self.assertEqual(set(result['backends']), {'cpu', 'openvino-cpu'})
        self.assertNotIn('gpu', result['driver'])

    def test_cache_separates_profiles_preview_format_and_confirmed_channel(self):
        values = [recipe(), recipe(DSP), recipe(CLEAN), recipe(DSP, preview=True),
                  recipe(DSP, output_format='flac'), recipe(DSP, source_audio_id='stream:1:LEFT')]
        self.assertEqual(len({item['recipeFingerprint'] for item in values}), len(values))
        with self.assertRaises(ValueError): recipe(CLEAN, model_id='other-model')
        with self.assertRaises(ValueError): recipe(DSP, source_audio_id='../input')

    def test_threads_respect_effective_quota_board_and_override(self):
        with patch('singverse_compute.domain.audio_separation.effective_cores', return_value=16), \
             patch('singverse_compute.infrastructure.hardware.device_tree', return_value='rockchip,rk3566'):
            self.assertEqual(threads(), 2)
            self.assertEqual(threads(4), 4)
        with patch('singverse_compute.domain.audio_separation.effective_cores', return_value=1):
            self.assertEqual(threads(8), 1)


class RecoveryTest(unittest.TestCase):
    def test_explicit_cpu_and_cpu_recovery_do_not_probe_accelerator_environment(self):
        engine = SeparationEngine.__new__(SeparationEngine)
        engine.model_ready = Mock(return_value=True)
        def environment(cpu):
            if not cpu: raise AssertionError('CPU selection probed a GPU environment')
            return {'standaloneCpu': True, 'backends': ['cpu']}
        engine.environment = environment
        engine.key = lambda backend: (backend, backend+'-identity')
        engine.qualified = {'cpu-identity': {'state': 'AVAILABLE'}}
        engine.cooldown = {}
        self.assertEqual(engine.selected('cpu'), 'cpu')

    def test_unavailable_preferred_device_can_use_qualified_cpu_at_admission(self):
        engine = SeparationEngine.__new__(SeparationEngine)
        engine.audio = SimpleNamespace(transcoding=SimpleNamespace(backends=SimpleNamespace(cpu_fallback=True)))
        engine.selected = Mock(side_effect=lambda backend: 'cpu' if backend == 'cpu' else None)
        engine.check_resources = Mock()
        self.assertEqual(engine.admit(CLEAN, 'cuda'), 'cpu')
        engine.audio.transcoding.backends.cpu_fallback = False
        with self.assertRaises(ComputeError): engine.admit(CLEAN, 'cuda')

    def test_qualification_identity_is_bound_to_backend_even_in_same_environment(self):
        engine = SeparationEngine.__new__(SeparationEngine)
        engine.environment = Mock(return_value={'fingerprint': 'same-environment'})
        self.assertNotEqual(engine.key('cpu')[1], engine.key('openvino-cpu')[1])
        self.assertNotEqual(engine.key('cuda')[1], engine.key('tensorrt')[1])

    def engine(self, error):
        engine = SeparationEngine.__new__(SeparationEngine)
        engine.key = lambda backend: (backend, backend+'-identity')
        engine.cooldown = {}
        engine.audio = SimpleNamespace(inference_event=Mock())
        engine.selected = Mock(return_value='cpu')
        engine.check_resources = Mock()
        engine.worker = Mock()
        engine.worker.request.side_effect = [error, {'backend': 'cpu'}]
        return engine

    def test_device_fault_replays_same_model_once_without_dsp(self):
        for message in ('Hardware accelerator initialization failed', 'Audio worker exited', 'Process exceeded memory budget', 'Requested provider is unavailable'):
            with self.subTest(message=message), tempfile.TemporaryDirectory() as temporary:
                task = AudioTask('test'); task.backend = 'cuda'; task.cpu_fallback = True
                engine = self.engine(ComputeError(422, message))
                result = engine.infer(Path(temporary)/'input.wav', Path(temporary)/'out', task)
                self.assertEqual(result['backend'], 'cpu')
                self.assertEqual(task.backend, 'cpu')
                calls = engine.worker.request.call_args_list
                self.assertEqual(calls[0].args[1], calls[1].args[1])
                self.assertEqual(calls[1].args[0][0], 'cpu')
                self.assertIn('cuda-identity', engine.cooldown)

    def test_cancel_io_and_cpu_failure_do_not_retry(self):
        for backend, message, cancelled in [('cuda', 'Cannot read source', False), ('cpu', 'Audio worker exited', False), ('cuda', 'Audio worker exited', True)]:
            task = AudioTask('test'); task.backend = backend; task.cpu_fallback = True
            if cancelled: task.cancel.set()
            engine = self.engine(ComputeError(422, message))
            with self.assertRaises(ComputeError): engine.infer(Path('in'), Path('out'), task)
            self.assertEqual(engine.worker.request.call_count, 1)


class DspServiceTest(CallbackTestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        from singverse_compute.bootstrap import create_app
        from singverse_compute.core.config import Settings
        app = create_app(Settings(token_file=self.root/'token', transcode_root=self.root/'cache', minimum_free_bytes=0))
        self.audio = app.state.audio_roles
        self.addCleanup(self.audio.close)

    def source(self, mono=False):
        path = self.root/('mono.flac' if mono else 'stereo.flac')
        signal = '.2*sin(440*2*PI*t)' if mono else '.2*sin(440*2*PI*t)+.1*sin(880*2*PI*t)|.2*sin(440*2*PI*t)-.1*sin(880*2*PI*t)'
        subprocess.run(['ffmpeg', '-v', 'error', '-f', 'lavfi', '-i', f'aevalsrc={signal}:s=44100:d=16', str(path)], check=True)
        return path

    def submit(self, source, **options):
        grant = self.callback(source, self.audio.transcoding.auth._token())
        identifier = str(uuid.uuid4())
        with patch.object(self.audio.models, 'probe', side_effect=AssertionError('DSP imported AI runtime')):
            self.audio.submit(identifier, grant['reference'], 'SEPARATE', callback_origin=grant['callbackOrigin'],
                              separation_profile=DSP, **options)
            self.audio.thread.join(20)
            self.assertFalse(self.audio.active)
        return self.audio.view(identifier)

    def test_dsp_without_models_flac_preview_is_dual_mono_and_keeps_source(self):
        source = self.source(); digest = hashlib.sha256(source.read_bytes()).hexdigest()
        result = self.submit(source, output_format='flac', preview=True)
        self.assertEqual(result['state'], 'SUCCEEDED', result)
        data = result['result']; self.assertEqual(data['duration'], 12)
        self.assertAlmostEqual(data['sourceOffsetSeconds'], 1.4)
        path = self.audio.artifact(result['id'])
        raw = subprocess.check_output(['ffmpeg', '-v', 'error', '-i', str(path), '-f', 'f32le', '-'])
        from array import array
        samples = array('f'); samples.frombytes(raw)
        self.assertEqual(samples[::2], samples[1::2])
        self.assertGreater(max(samples), .09)
        self.assertEqual(hashlib.sha256(source.read_bytes()).hexdigest(), digest)
        self.assertEqual(data['mediaType'], 'audio/flac')

    def test_mono_and_dual_mono_are_rejected_without_published_artifact(self):
        result = self.submit(self.source(True))
        self.assertEqual(result['state'], 'FAILED')
        self.assertIn('DSP_NOT_SUITABLE', result['result']['diagnostic'])
        self.assertFalse(list(self.audio.root.glob('*.part')))
        path = self.root/'identical.flac'
        subprocess.run(['ffmpeg', '-v', 'error', '-i', str(self.root/'mono.flac'), '-ac', '2', str(path)], check=True)
        result = self.submit(path)
        self.assertEqual(result['state'], 'FAILED')
        self.assertIn('DSP_NOT_SUITABLE', result['result']['diagnostic'])
        self.assertFalse(list(self.audio.root.glob('*.m4a')))

    def test_confirmed_backing_is_copied_instead_of_subtracted(self):
        source = self.source()
        result = self.submit(source, output_format='flac', source_audio_id='stream:0')
        self.assertEqual(result['state'], 'SUCCEEDED', result)
        self.assertTrue(result['result']['sourceReused'])
        self.assertTrue(result['result']['signal']['streamCopy'])
        def pcm(path): return subprocess.check_output(['ffmpeg', '-v', 'error', '-i', str(path), '-f', 's32le', '-'])
        self.assertEqual(pcm(source), pcm(self.audio.artifact(result['id'])))


class SpectralParityTest(unittest.TestCase):
    def test_numpy_stft_and_istft_match_existing_torch_recipe(self):
        try:
            import numpy as np
            import torch
        except ImportError:
            self.skipTest('Optional Torch parity runtime not installed')
        from singverse_compute.infrastructure.uvr_audio import CHUNK, NFFT, HOP, BINS, FRAMES, spectrum, waveform
        value = np.random.default_rng(8).normal(0, .1, (2, CHUNK)).astype('float32')
        transformed = torch.stft(torch.from_numpy(value), NFFT, HOP, window=torch.hann_window(NFFT), return_complex=True)
        expected = torch.view_as_real(transformed).permute(0, 3, 1, 2).reshape(1, 4, NFFT//2+1, FRAMES)[:, :, :BINS].numpy().copy()
        expected[:, :, :3] = 0
        np.testing.assert_allclose(spectrum(value), expected, atol=4e-6, rtol=2e-5)
        full = np.zeros((2, 2, NFFT//2+1, FRAMES), dtype='float32'); full[:, :, :BINS] = expected.reshape(2, 2, BINS, FRAMES)
        reference = torch.istft(torch.from_numpy(full[:, 0]+1j*full[:, 1]), NFFT, HOP, window=torch.hann_window(NFFT), length=CHUNK).numpy()
        np.testing.assert_allclose(waveform(expected), reference, atol=2e-7, rtol=2e-5)

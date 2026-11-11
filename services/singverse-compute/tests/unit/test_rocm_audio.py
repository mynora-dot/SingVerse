"""Native runtime, trusted weight loading and explicit device/PCM contracts; CPU tests are not GPU qualification."""
from contextlib import redirect_stdout
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

from singverse_compute.core.audio_runtime import audio_runtime_profile, NATIVE_ROCM_PACKAGES
from singverse_compute.core.build_info import includes_backend
from singverse_compute.application.audio_roles import AudioRoles
from singverse_compute.infrastructure.rocm_audio import load_official_model, separate


class AudioRuntimeProfileTest(unittest.TestCase):
    def test_legacy_and_native_are_explicit_profiles(self):
        for suffix in ('', '+cpu', '+cu124', '+rocm6.2'):
            packages = {'demucs': '4.0.1', 'torch': '2.5.1' + suffix, 'torchaudio': '2.5.1' + suffix}
            with patch('singverse_compute.core.audio_runtime.version', side_effect=packages.__getitem__):
                self.assertEqual(audio_runtime_profile(), 'legacy')
        packages = {'demucs': '4.0.1', **NATIVE_ROCM_PACKAGES}
        with patch('singverse_compute.core.audio_runtime.version', side_effect=packages.__getitem__):
            self.assertEqual(audio_runtime_profile(), 'rocm-native')

    def test_mixed_or_missing_device_libraries_are_not_accepted(self):
        from importlib.metadata import PackageNotFoundError
        for name in ('torch', 'torchaudio', 'rocm-sdk-core', 'amd-torch-device-gfx1103', 'amd-torch-device-gfx110x'):
            packages = {'demucs': '4.0.1', **NATIVE_ROCM_PACKAGES, name: 'unverified'}
            with self.subTest(package=name), patch('singverse_compute.core.audio_runtime.version', side_effect=packages.__getitem__):
                self.assertIsNone(audio_runtime_profile())
        def missing(name):
            if name == 'rocm-sdk-device-gfx1103':
                raise PackageNotFoundError(name)
            return {'demucs': '4.0.1', **NATIVE_ROCM_PACKAGES}[name]
        with patch('singverse_compute.core.audio_runtime.version', side_effect=missing):
            self.assertIsNone(audio_runtime_profile())

    def test_native_image_only_adds_its_real_backend(self):
        with patch('singverse_compute.core.build_info.build_info', return_value={'variant': 'rocm-native'}):
            self.assertTrue(includes_backend('rocm'))
            self.assertTrue(includes_backend('cpu'))
            self.assertFalse(includes_backend('cuda'))
            self.assertFalse(includes_backend('openvino'))

    def test_native_tasks_use_adapter_and_preserve_explicit_backend(self):
        for backend in ('rocm', 'cpu'):
            with self.subTest(backend=backend), tempfile.TemporaryDirectory() as temporary:
                output = Path(temporary)
                folder = output / 'htdemucs' / 'input'; folder.mkdir(parents=True)
                for name in ('vocals.wav', 'no_vocals.wav'):
                    (folder / name).touch()
                worker = SimpleNamespace(repo=Path('/model'), settings=SimpleNamespace(audio_cpu_python=None), _run=Mock())
                with patch('singverse_compute.application.audio_roles.audio_runtime_profile', return_value='rocm-native'):
                    AudioRoles._separate_once(worker, Path('input.wav'), output, SimpleNamespace(backend=backend))
                command = worker._run.call_args.args[0]
                self.assertIn('singverse_compute.infrastructure.rocm_audio', command)
                self.assertEqual(command[command.index('--backend') + 1], backend)
                worker._run.assert_called_once()

    def test_cpu_uses_independent_interpreter_even_without_service_torch(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory); folder = output/'htdemucs'/'input'; folder.mkdir(parents=True)
            for name in ('vocals.wav', 'no_vocals.wav'):
                (folder/name).touch()
            worker = SimpleNamespace(repo=Path('/model'), settings=SimpleNamespace(audio_cpu_python=Path('/cpu/bin/python')),
                                     cpu_python=lambda: '/cpu/bin/python', native_worker=None, _run=Mock())
            with patch('singverse_compute.application.audio_roles.audio_runtime_profile', return_value=None):
                AudioRoles._separate_once(worker, Path('input.wav'), output, SimpleNamespace(backend='cpu'))
            self.assertEqual(worker._run.call_args.args[0][0], '/cpu/bin/python')

    def test_legacy_rocm_keeps_its_existing_cli(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary) / 'htdemucs' / 'input'; folder.mkdir(parents=True)
            for name in ('vocals.wav', 'no_vocals.wav'):
                (folder / name).touch()
            worker = SimpleNamespace(repo=Path('/model'), _run=Mock())
            with patch('singverse_compute.application.audio_roles.audio_runtime_profile', return_value='legacy'):
                AudioRoles._separate_once(worker, Path('input.wav'), Path(temporary), SimpleNamespace(backend='rocm'))
            command = worker._run.call_args.args[0]
            self.assertEqual(command[2], 'demucs')
            self.assertEqual(command[command.index('-d') + 1], 'cuda')


@unittest.skipUnless(importlib.util.find_spec('torch'), 'Optional audio runtime required')
class NativeAudioAdapterTest(unittest.TestCase):
    def test_mps_device_matches_the_concrete_tensor_device_index(self):
        import torch
        from singverse_compute.infrastructure.rocm_audio import _device
        with patch('singverse_compute.infrastructure.rocm_audio.audio_runtime_profile', return_value='legacy'), \
                patch('torch.backends.mps.is_available', return_value=True):
            device = _device('mps')
        self.assertEqual(device.type, 'mps')
        self.assertEqual(device.index, 0)
        self.assertEqual(device, torch.device('mps:0'))

    def model(self):
        import torch
        class Model(torch.nn.Module):
            samplerate = 44100
            audio_channels = 2
            segment = 7.8
            sources = ['drums', 'bass', 'other', 'vocals']
            def __init__(self):
                super().__init__()
                self.coefficients = torch.nn.Parameter(torch.tensor([.1, .2, .3, .4]), requires_grad=False)
            def forward(self, value):
                return value[:, None] * self.coefficients[None, :, None, None]
        return Model()

    def test_full_pcm_contract_normalization_alignment_and_silence(self):
        import numpy as np
        import soundfile as sf
        import torch
        for silent in (False, True):
            with self.subTest(silent=silent), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary); source = root/'input.wav'
                value = np.zeros((4096, 2), dtype='float32') if silent else np.column_stack([
                    .1 * np.sin(np.arange(4096)*.07) + .02, .1 * np.cos(np.arange(4096)*.05) + .02]).astype('float32')
                sf.write(source, value, 44100, subtype='FLOAT')
                model = self.model()
                def apply(current, mix, **options):
                    self.assertEqual(options, {'device': torch.device('cpu'), 'shifts': 0, 'split': False,
                                              'overlap': .25, 'segment': 7.8, 'num_workers': 0})
                    return current(mix.tensor)[..., mix.offset:mix.offset+mix.length]
                log = io.StringIO()
                with patch('singverse_compute.infrastructure.rocm_audio.audio_runtime_profile', return_value='rocm-native'), \
                        patch('torch.version.hip', '7.15.26333'), patch('torch.version.cuda', None), \
                        patch('singverse_compute.infrastructure.rocm_audio.load_official_model', return_value=model), \
                        patch('demucs.apply.apply_model', side_effect=apply), redirect_stdout(log):
                    separate(source, root/'out', root/'model', 'cpu')
                evidence = json.loads(log.getvalue())
                self.assertEqual((evidence['startedForwards'], evidence['completedForwards']), (1, 1))
                self.assertFalse(evidence['cpuInferenceFallback'])
                self.assertEqual(evidence['device'], 'cpu')
                self.assertEqual(evidence['bufferFrames'], int(7.8*44100))
                self.assertIn('modelLoad', evidence['timingsMs'])
                mean = value.mean(1).mean()
                for name, expected in [('vocals', .4*(value-mean)+mean), ('no_vocals', .6*(value-mean)+3*mean)]:
                    path = root/'out'/'htdemucs'/'input'/(name+'.wav')
                    actual, rate = sf.read(path, dtype='float32', always_2d=True)
                    self.assertEqual(sf.info(path).subtype, 'PCM_16')
                    self.assertEqual((rate, actual.shape), (44100, value.shape))
                    np.testing.assert_allclose(actual, expected, atol=1/32768, rtol=1e-5)

    def test_streaming_matches_full_track_overlap_and_last_chunk_context(self):
        import numpy as np
        import soundfile as sf
        import torch
        from demucs.apply import apply_model
        from singverse_compute.infrastructure.rocm_audio import NativeRuntime
        torch.set_num_threads(2)
        class ContextModel(type(self.model())):
            segment = .1
            def valid_length(self, length):
                return int(self.segment*self.samplerate)
            def forward(self, value):
                # Neighbour dependence catches incorrect padding of the last short chunk.
                value = torch.nn.functional.avg_pool1d(value, 7, stride=1, padding=3)
                return super().forward(value)
        model = ContextModel()
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary)
            for frames in (1, 2000, 6615, 9001, 30001):
                with self.subTest(frames=frames):
                    value=np.column_stack([.1*np.sin(np.arange(frames)*.07)+.02,
                                           .1*np.cos(np.arange(frames)*.05)+.02]).astype('float32')
                    source=root/'input.wav'; sf.write(source,value,44100,subtype='FLOAT')
                    mix=torch.from_numpy(value.T.copy());reference=mix.mean(0)
                    scale=reference.std() if frames>1 else torch.tensor(0.)
                    mean=reference.mean()
                    normalized=(mix-mean)/scale if scale>=1e-8 else torch.zeros_like(mix)
                    with torch.inference_mode():
                        expected=apply_model(model,normalized[None],device='cpu',shifts=0,split=True,
                                             overlap=.25,segment=model.segment,num_workers=0)[0]
                        expected=expected*scale+mean
                    with patch('singverse_compute.infrastructure.rocm_audio._device', return_value=torch.device('cpu')), \
                            patch('singverse_compute.infrastructure.rocm_audio.load_official_model', return_value=model) as load:
                        runtime=NativeRuntime(root/'model','cpu')
                        first=runtime.separate(source,root/'out',backing_only=True)
                        second=runtime.separate(source,root/'out',backing_only=True)
                    self.assertFalse(first['modelReused']);self.assertTrue(second['modelReused']);load.assert_called_once()
                    self.assertEqual(first['bufferFrames'],4410)
                    self.assertFalse((root/'out'/'htdemucs'/'input'/'vocals.wav').exists())
                    actual,rate=sf.read(root/'out'/'htdemucs'/'input'/'no_vocals.wav',dtype='float32',always_2d=True)
                    self.assertEqual((rate,len(actual)),(44100,frames))
                    np.testing.assert_allclose(actual,expected[:3].sum(0).clamp(-1,1).T.numpy(),atol=2/32768,rtol=1e-5)

    def test_missing_gpu_is_not_retried_on_cpu(self):
        with patch('singverse_compute.infrastructure.rocm_audio.audio_runtime_profile', return_value='rocm-native'), \
                patch('torch.version.hip', '7.15.26333'), patch('torch.version.cuda', None), \
                patch('torch.cuda.is_available', return_value=False), \
                patch('singverse_compute.infrastructure.rocm_audio.load_official_model') as load:
            with self.assertRaisesRegex(RuntimeError, 'CPU inference was not requested'):
                separate('input.wav', 'output', 'model', 'rocm')
            load.assert_not_called()

    def test_architecture_override_is_rejected_before_inference(self):
        with patch('singverse_compute.infrastructure.rocm_audio.audio_runtime_profile', return_value='rocm-native'), \
                patch.dict(os.environ, {'HSA_OVERRIDE_GFX_VERSION': '11.0.0'}):
            with self.assertRaisesRegex(RuntimeError, 'overrides are forbidden'):
                separate('input.wav', 'output', 'model', 'rocm')

    def test_wrong_weight_content_is_never_deserialized(self):
        from singverse_compute.application.audio_catalog import BASELINE, manifest
        entry = manifest(BASELINE)['files'][0]
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary)/entry['name']
            with path.open('wb') as stream:
                stream.truncate(entry['size'])
            with patch('torch.load') as load:
                with self.assertRaisesRegex(ValueError, 'SHA256 mismatch'):
                    load_official_model(temporary)
                load.assert_not_called()

    @unittest.skipUnless(hasattr(os, 'O_NOFOLLOW'), 'Linux no-follow model open required')
    def test_symlinked_weight_is_not_deserialized(self):
        from singverse_compute.application.audio_catalog import BASELINE, manifest
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); target = root/'other'; target.write_bytes(b'arbitrary data')
            (root/manifest(BASELINE)['files'][0]['name']).symlink_to(target)
            with patch('torch.load') as load:
                with self.assertRaises(OSError):
                    load_official_model(root)
                load.assert_not_called()

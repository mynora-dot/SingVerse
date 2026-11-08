import hashlib
import json
import logging
import tempfile
import sys
from types import SimpleNamespace
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

from singverse_compute.infrastructure.rknn_audio import Forward, local_manifest, separate
from singverse_compute.application.audio_catalog import model_for_backend, RKNN


class RknnAudioTest(unittest.TestCase):
    def test_sdk_logging_changes_do_not_break_later_dsp_imports(self):
        for result in (0, -1):
            runtime = Mock()
            runtime.load_rknn.return_value = 0
            def initialize():
                logging._nameToLevel.pop('WARNING', None)
                return result
            runtime.init_runtime.side_effect = initialize
            with patch.dict(logging._nameToLevel), \
                    patch.dict(sys.modules, {'rknnlite.api': SimpleNamespace(RKNNLite=Mock(return_value=runtime))}), \
                    patch('singverse_compute.infrastructure.rknn_audio.local_manifest', return_value=({'target': 'rk3566'}, 'digest')), \
                    patch('singverse_compute.infrastructure.hardware.device_tree', return_value='rockchip,rk3566'):
                if result == 0:
                    Forward('model').close()
                else:
                    with self.assertRaisesRegex(RuntimeError, 'initialization failed'):
                        Forward('model')
                logger = logging.getLogger('rknn-dsp-regression')
                logger.setLevel('WARNING')
                self.assertEqual(logger.level, logging.WARNING)
            runtime.release.assert_called_once()

    def test_runtime_checks_board_using_container_compatible_identity(self):
        runtime = Mock()
        runtime.load_rknn.return_value = runtime.init_runtime.return_value = 0
        factory = Mock(return_value=runtime)
        with patch.dict(sys.modules, {'rknnlite.api': SimpleNamespace(RKNNLite=factory)}), \
                patch('singverse_compute.infrastructure.rknn_audio.local_manifest', return_value=({'target': 'rk3566'}, 'digest')), \
                patch('singverse_compute.infrastructure.hardware.device_tree', return_value='rockchip,rk3566\0'):
            forward = Forward('model')
            forward.close()
        runtime.init_runtime.assert_called_once()
        runtime.release.assert_called_once()

    def test_runtime_rejects_model_for_another_board_before_loading(self):
        factory = Mock()
        with patch.dict(sys.modules, {'rknnlite.api': SimpleNamespace(RKNNLite=factory)}), \
                patch('singverse_compute.infrastructure.rknn_audio.local_manifest', return_value=({'target': 'rk3588'}, 'digest')), \
                patch('singverse_compute.infrastructure.hardware.device_tree', return_value='rockchip,rk3566\0'):
            with self.assertRaisesRegex(RuntimeError, 'target differs'):
                Forward('model')
        factory.assert_not_called()

    def test_local_model_requires_target_profile_size_and_digest(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            model = root / 'uvr.rknn'
            model.write_bytes(b'controlled graph')
            metadata = {'profile': 'uvr-mdxnet-9482-v1', 'target': 'rk3588',
                        'sourceSha256': '9d78f8566fa8198065214ab628be1de966a500c57786695aa4b13e2b27a7727d',
                        'toolkit': '2.3.2', 'size': model.stat().st_size, 'sha256': hashlib.sha256(model.read_bytes()).hexdigest()}
            path = root / 'manifest.json'
            path.write_text(json.dumps(metadata))
            first = local_manifest(root)[1]
            model.write_bytes(b'corrupted graph!')
            with self.assertRaises(ValueError):
                local_manifest(root)
            model.write_bytes(b'controlled graph')
            for key, value in [('target', 'other'), ('profile', 'unknown'), ('size', 0), ('sha256', 'wrong')]:
                path.write_text(json.dumps({**metadata, key: value}))
                with self.assertRaises(ValueError):
                    local_manifest(root)
            path.write_text(json.dumps({**metadata, 'note': 'changed'}))
            self.assertNotEqual(local_manifest(root)[1], first)
        self.assertEqual(model_for_backend('rknn'), RKNN)

    def test_runtime_released_on_pipeline_failure(self):
        with patch('singverse_compute.infrastructure.rknn_audio.Forward') as runtime, patch(
                'singverse_compute.infrastructure.uvr_audio.separate', side_effect=RuntimeError('failed')):
            with self.assertRaises(RuntimeError):
                separate('in.wav', 'out', 'model')
            runtime.return_value.close.assert_called_once()


class UvrDspTest(unittest.TestCase):
    def test_identity_spectrum_preserves_stereo_and_chunk_boundaries(self):
        import numpy as np
        import soundfile as sf
        from singverse_compute.infrastructure.uvr_audio import separate as pipeline, STRIDE, TRIM
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            length = STRIDE + 1234
            t = np.arange(length) / 44100
            samples = np.stack([.1*np.sin(2*np.pi*440*t), .05*np.sin(2*np.pi*660*t)], axis=1).astype('float32')
            sf.write(root/'input.wav', samples, 44100, subtype='FLOAT')
            calls = []
            def infer(x):
                self.assertEqual(x.shape, (1, 4, 2048, 256))
                calls.append(x.shape)
                return x
            folder = pipeline(root/'input.wav', root/'out', infer)
            vocal, rate = sf.read(folder/'vocals.wav')
            backing, _ = sf.read(folder/'no_vocals.wav')
            self.assertEqual(len(calls), 2)
            self.assertEqual(rate, 44100)
            self.assertEqual(vocal.shape, samples.shape)
            # Band truncation and the intentionally removed three low bins affect edges.
            np.testing.assert_allclose(vocal + backing, samples, atol=7e-5)
            np.testing.assert_allclose(vocal[TRIM:-TRIM], samples[TRIM:-TRIM], atol=.002)

    def test_invalid_model_output_fails(self):
        import numpy as np
        import soundfile as sf
        from singverse_compute.infrastructure.uvr_audio import separate as pipeline
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            sf.write(root/'input.wav', np.zeros((10, 2)), 44100)
            with self.assertRaises(RuntimeError):
                pipeline(root/'input.wav', root/'out', lambda x: np.full_like(x, np.nan))

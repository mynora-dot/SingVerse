"""Opt-in real Apple GPU check; synthetic input does not certify music quality."""
import hashlib
import os
from pathlib import Path
import tempfile
import unittest
import uuid

from singverse_compute.application.audio_roles import AudioTask
from singverse_compute.bootstrap import create_app
from singverse_compute.core.config import Settings


@unittest.skipUnless(os.environ.get('SINGVERSE_TEST_MPS_MODEL_REPO'), 'Explicit local MPS model required')
class MpsAudioSmokeTest(unittest.TestCase):
    def test_mps_qualification_separation_and_cpu_comparison(self):
        import numpy as np
        import soundfile as sf
        import torch
        self.assertTrue(torch.backends.mps.is_available(), 'Run on native Apple Silicon with GPU access')
        repo = Path(os.environ['SINGVERSE_TEST_MPS_MODEL_REPO'])
        self.assertTrue(repo.is_absolute())
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            media = root/'media'
            media.mkdir()
            source = media/'stereo.wav'
            frames = np.arange(44100*8, dtype=np.float32)/44100
            samples = np.stack((.1*np.sin(2*np.pi*440*frames), .1*np.sin(2*np.pi*660*frames)), axis=1)
            samples[:4410] = 0
            samples[-4410:] = 0
            sf.write(source, samples, 44100, subtype='PCM_16')
            original = hashlib.sha256(source.read_bytes()).hexdigest()
            token = root/'token'
            token.write_text('m'*64)
            app = create_app(Settings((media,), token, transcode_root=root/'cache', minimum_free_bytes=0,
                                      audio_model_repo=repo, audio_memory_bytes=2*1024**3))
            worker = app.state.audio_roles
            try:
                worker.models.test('mps')
                worker.models.thread.join(240)
                self.assertEqual(worker.models.operation['state'], 'SUCCEEDED', worker.models.operation)
                self.assertEqual(worker.models.selected(), 'mps')
                identifier = str(uuid.uuid4())
                worker.submit(identifier, str(source), None, 'SEPARATE', 'mps')
                worker.thread.join(240)
                result = worker.view(identifier)
                self.assertEqual(result['state'], 'SUCCEEDED', result)
                self.assertEqual(result['result']['backend'], 'mps')
                self.assertEqual(result['result']['pipeline'], 'mps+cpu-stft')
                self.assertEqual(result['result']['device']['name'], 'Apple GPU')
                self.assertEqual(worker.digest(worker.artifact(identifier)), result['result']['sha256'])
                # Compare uncompressed outputs against the unchanged CPU CLI, not AAC encoding noise.
                stem_sets = []
                for backend in ('cpu', 'mps'):
                    task = AudioTask(str(uuid.uuid4()))
                    task.backend = backend
                    paths = worker._separate(source, root/backend, task)
                    stems = []
                    for path in paths:
                        value, rate = sf.read(path, dtype='float32', always_2d=True)
                        self.assertEqual(rate, 44100)
                        self.assertEqual(value.shape, (8*44100, 2))
                        self.assertTrue(np.isfinite(value).all())
                        stems.append(value)
                    stem_sets.append(np.stack(stems))
                relative_error = float(np.linalg.norm(stem_sets[0]-stem_sets[1]) / max(np.linalg.norm(stem_sets[0]), 1e-8))
                self.assertLess(relative_error, .02, 'MPS output differs substantially from the CPU baseline')
                print(f'MPS/CPU stem relative error: {relative_error:.6f}', flush=True)
                self.assertEqual(hashlib.sha256(source.read_bytes()).hexdigest(), original)
                worker.release(identifier)
                self.assertFalse((worker.root/(identifier+'.m4a')).exists())
            finally:
                worker.models.close()
                worker.close()

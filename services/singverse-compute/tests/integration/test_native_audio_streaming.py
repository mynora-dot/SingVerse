"""Explicit CPU correctness comparison with actual pinned weights; not GPU qualification."""
import os
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import patch


@unittest.skipUnless(os.environ.get('SINGVERSE_TEST_AUDIO_MODEL_REPO'), 'Explicit local audio model required')
class NativeStreamingModelTest(unittest.TestCase):
    def test_real_model_matches_full_track_reference_and_reuses_model(self):
        import numpy as np
        import soundfile as sf
        import torch
        from demucs.apply import apply_model
        from singverse_compute.infrastructure.rocm_audio import load_official_model, NativeRuntime
        torch.set_num_threads(2)
        repo=Path(os.environ['SINGVERSE_TEST_AUDIO_MODEL_REPO'])
        model=load_official_model(repo)
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);source=root/'test.wav'
            # Crosses a full 7.8-second segment and exercises contextual tail padding.
            frames=int(8.4*44100)
            t=np.arange(frames,dtype='float32')/44100
            value=np.column_stack([.1*np.sin(2*np.pi*220*t)+.01,.09*np.sin(2*np.pi*440*t)+.01])
            sf.write(source,value,44100,subtype='PCM_16')
            samples,_=sf.read(source,dtype='float32',always_2d=True)
            mixture=torch.from_numpy(samples.T.copy());reference=mixture.mean(0)
            mean,scale=reference.mean(),reference.std()
            with torch.inference_mode():
                stems=apply_model(model,((mixture-mean)/scale)[None],device='cpu',shifts=0,
                                  split=True,overlap=.25,segment=float(model.segment),num_workers=0)[0]
                expected=(stems*scale+mean)[:3].sum(0).clamp(-1,1).T.numpy()
            with patch('singverse_compute.infrastructure.rocm_audio._device',return_value=torch.device('cpu')), \
                    patch('singverse_compute.infrastructure.rocm_audio.load_official_model',return_value=model) as load:
                runtime=NativeRuntime(repo,'cpu');started=time.monotonic()
                first=runtime.separate(source,root/'first',backing_only=True)
                second=runtime.separate(source,root/'second',backing_only=True)
                elapsed=time.monotonic()-started
                load.assert_called_once()
            self.assertFalse(first['modelReused']);self.assertTrue(second['modelReused'])
            actual,rate=sf.read(root/'second'/'htdemucs'/'test'/'no_vocals.wav',dtype='float32',always_2d=True)
            np.testing.assert_allclose(actual,expected,atol=2/32768,rtol=1e-5)
            self.assertEqual((rate,actual.shape),(44100,(frames,2)))
            print(f'NATIVE_STREAMING_CPU frames={frames} forwards={second["completedForwards"]} '
                  f'buffer_frames={second["bufferFrames"]} max_abs_error={np.max(np.abs(actual-expected)):.8f} two_runs_s={elapsed:.3f}')

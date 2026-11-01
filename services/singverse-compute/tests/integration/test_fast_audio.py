"""Opt-in fixed classifier smoke; synthetic inputs do not measure singing accuracy."""
import os
import hashlib
import uuid
from pathlib import Path
import subprocess
import tempfile
import threading
import time
import unittest
from singverse_compute.bootstrap import create_app
from singverse_compute.core.config import Settings


@unittest.skipUnless(os.environ.get('SINGVERSE_TEST_FAST_MODEL_REPO'), 'Explicit local fast classifier required')
class FastClassifierSmokeTest(unittest.TestCase):
    def test_fixed_weights_cached_worker_and_silence(self):
        repo = Path(os.environ['SINGVERSE_TEST_FAST_MODEL_REPO'])
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            media = root / 'media'; media.mkdir()
            token = root / 'token'; token.write_text('a' * 64)
            samples = []
            for name, signal in (('tone', 'sine=duration=6'), ('silence', 'anullsrc=r=32000:cl=mono')):
                path = root / (name + '.wav')
                subprocess.run(['ffmpeg', '-v', 'error', '-f', 'lavfi', '-i', signal, '-t', '6', '-ac', '1', '-ar', '32000', str(path)], check=True)
                samples.append(path)
            worker = create_app(Settings((media,), token, transcode_root=root / 'cache', audio_model_repo=repo,
                minimum_free_bytes=0, audio_memory_bytes=2*1024**3)).state.audio_roles
            try:
                self.assertTrue(worker.fast.available)
                start = time.monotonic()
                scores = worker.fast.predict(samples * 3, threading.Event())
                cold = time.monotonic() - start; pid = worker.fast.process.pid
                start = time.monotonic()
                repeated = worker.fast.predict(samples * 3, threading.Event())
                warm = time.monotonic() - start
                self.assertEqual(worker.fast.process.pid, pid)
                self.assertEqual(len(scores), 6)
                self.assertTrue(all(score == 0 for score in scores[1::2]))
                self.assertTrue(all(score < .2 for score in scores[::2]))
                for first, second in zip(scores, repeated):
                    self.assertAlmostEqual(first, second, places=5)
                print(f'fast classifier cold={cold:.3f}s warm={warm:.3f}s (six 6s excerpts)', flush=True)
                source = media / 'dual.mkv'
                subprocess.run(['ffmpeg', '-v', 'error', '-f', 'lavfi', '-i', 'sine=duration=12',
                    '-f', 'lavfi', '-i', 'anullsrc=r=32000:cl=mono', '-t', '12', '-map', '0:a', '-map', '1:a',
                    '-c:a', 'pcm_s16le', str(source)], check=True)
                digest = hashlib.sha256(source.read_bytes()).hexdigest()
                identifier = str(uuid.uuid4()); start = time.monotonic()
                worker.submit(identifier, str(source), None, 'FAST'); worker.thread.join(30)
                result = worker.view(identifier)
                self.assertEqual(result['state'], 'SUCCEEDED', result)
                self.assertEqual(result['result']['state'], 'UNKNOWN', result)
                self.assertEqual(result['result']['evidence']['analysisVersion'], 'fast-vocal-v2')
                self.assertEqual(len(result['result']['evidence']['windows']), 3)
                self.assertEqual(worker.fast.process.pid, pid)
                self.assertEqual(hashlib.sha256(source.read_bytes()).hexdigest(), digest)
                self.assertFalse(list(worker.root.glob('work-*')))
                print(f'fast full task={time.monotonic()-start:.3f}s (local dual-stream synthetic media, warm model)', flush=True)
            finally:
                worker.close()
            self.assertIsNone(worker.fast.process)

    def test_fft_features_match_author_checkpoint_convolution(self):
        import torch
        from torch.nn import functional as F
        from singverse_compute.application.audio_catalog import FAST, manifest
        from singverse_compute.infrastructure.fast_audio import load_model
        torch.set_num_threads(2)
        path = Path(os.environ['SINGVERSE_TEST_FAST_MODEL_REPO']).parent / FAST / manifest(FAST)['files'][0]['name']
        model = load_model(path)
        state = torch.load(path, map_location='cpu', weights_only=True)['model']
        samples = torch.randn((1, 32000), generator=torch.Generator().manual_seed(17)) * .1
        padded = F.pad(samples[:, None], (512, 512), mode='reflect')
        real = F.conv1d(padded, state['spectrogram_extractor.stft.conv_real.weight'], stride=320)
        imag = F.conv1d(padded, state['spectrogram_extractor.stft.conv_imag.weight'], stride=320)
        reference = 10 * torch.log10(torch.matmul((real.square()+imag.square()).transpose(1, 2), state['logmel_extractor.melW']).clamp_min(1e-10))
        captured = []
        hook = model.bn0.register_forward_pre_hook(lambda _, args: captured.append(args[0].detach()))
        try:
            with torch.inference_mode():
                model(samples)
        finally:
            hook.remove()
        self.assertTrue(torch.allclose(captured[0], reference[:, None].transpose(1, 3), atol=.0001, rtol=.0001))

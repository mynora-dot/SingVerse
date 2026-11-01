"""Opt-in actual inference smoke test. A synthetic tone is not a music-quality benchmark."""
import hashlib
import os
from pathlib import Path
import subprocess
import tempfile
import time
import unittest
import uuid
from singverse_compute.bootstrap import create_app
from singverse_compute.core.config import Settings


@unittest.skipUnless(os.environ.get('SINGVERSE_TEST_AUDIO_MODEL_REPO'), 'Explicit local audio model required')
class AudioModelSmokeTest(unittest.TestCase):
    def test_pinned_local_model_produces_independent_aligned_audio(self):
        repo = Path(os.environ['SINGVERSE_TEST_AUDIO_MODEL_REPO'])
        self.assertTrue(repo.is_absolute())
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            media = root / 'media'
            media.mkdir()
            token = root / 'token'
            token.write_text('a' * 64)
            source = media / 'tone.wav'
            subprocess.run(['ffmpeg', '-v', 'error', '-f', 'lavfi', '-i', 'sine=duration=8', '-ac', '2', str(source)], check=True)
            before = hashlib.sha256(source.read_bytes()).hexdigest()
            # This bounded eight-second sample uses a 2 GiB process budget; production defaults remain 4 GiB.
            app = create_app(Settings((media,), token, transcode_root=root / 'cache', minimum_free_bytes=0, audio_model_repo=repo, audio_memory_bytes=2*1024**3))
            worker = app.state.audio_roles
            try:
                self.assertTrue(worker.runtime_available, 'Install exact pinned dependencies and model first')
                self.assertFalse(worker.available, 'Unverified model cannot be advertised')
                worker.models.test('cpu')
                worker.models.thread.join(240)
                self.assertEqual(worker.models.operation['state'], 'SUCCEEDED', worker.models.operation)
                self.assertTrue(worker.available)
                identifier = str(uuid.uuid4())
                worker.submit(identifier, str(source), None, 'SEPARATE')
                deadline = time.monotonic() + 240
                while worker.active and time.monotonic() < deadline:
                    time.sleep(.1)
                result = worker.view(identifier)
                self.assertEqual(result['state'], 'SUCCEEDED', result)
                self.assertAlmostEqual(result['result']['duration'], 8, places=2)
                output = worker.artifact(identifier)
                self.assertEqual(worker.digest(output), result['result']['sha256'])
                self.assertEqual(hashlib.sha256(source.read_bytes()).hexdigest(), before)
                worker.release(identifier)
                self.assertFalse(output.exists())
            finally:
                worker.models.close()
                worker.close()

"""Fast scan decisions never invoke the optional expensive separation path."""
import tempfile
import threading
import time
import unittest
import uuid
from pathlib import Path
from unittest.mock import Mock, PropertyMock, patch
from singverse_compute.application.fast_audio_roles import classify_labels, classify_scores, FastAudioRoles
from singverse_compute.application.audio_roles import AudioTask
from singverse_compute.bootstrap import create_app
from singverse_compute.core.config import Settings


class FastClassificationTest(unittest.TestCase):
    def test_labels_require_an_explicit_pair_and_handle_negation(self):
        self.assertEqual(classify_labels(('No Vocals', 'Vocals'))['original'], 1)
        self.assertEqual(classify_labels(('原唱', '伴奏'))['backing'], 1)
        for titles in (('', ''), ('原唱', ''), ('Vocals', 'Vocals'), ('Stereo', 'Stereo')):
            self.assertIsNone(classify_labels(titles))

    def test_agreement_conflicts_and_weak_evidence(self):
        self.assertEqual(classify_scores([(.8, .05), (.7, .1), (.1, .1)], ('', ''))['state'], 'CONFIDENT')
        self.assertEqual(classify_scores([(.05, .8), (.1, .7)], ('', ''))['original'], 1)
        for samples, reason in (([(.8, .05), (.05, .8)], 'CONFLICTING_WINDOWS'),
                ([(.8, .05), (.8, .7)], 'BOTH_VOCAL_TRACKS'),
                ([(.1, .05), (.1, .05)], 'AMBIGUOUS_VOCALS'),
                ([(.8, .05)], 'INSUFFICIENT_EVIDENCE'),
                ([(float('nan'), .05), (.8, .05)], 'INSUFFICIENT_EVIDENCE')):
            self.assertEqual(classify_scores(samples, ('', ''))['reason'], reason)
        self.assertEqual(classify_scores([(.8, .05), (.7, .05)], ('伴奏', ''))['reason'], 'CONFLICTING_LABELS')

    def test_quiet_vocals_use_relative_track_difference_not_absolute_probability(self):
        # Actual observed score shapes: a .4 absolute threshold rejected every track.
        for pairs in ([ (.158, .020), (.126, .019), (.073, .015) ],
                      [ (.066, .016), (.074, .017), (.090, .167) ],
                      [ (.194, .016), (.029, .027), (.118, .019) ]):
            self.assertEqual(classify_scores(pairs, ('', ''))['original'], 0)
            self.assertEqual(classify_scores([(b, a) for a, b in pairs], ('', ''))['original'], 1)
        self.assertEqual(classify_scores([(.04, .01)] * 3, ('', ''))['state'], 'UNKNOWN')
        self.assertEqual(classify_scores([(.09, .015), (.015, .09)], ('', ''))['reason'], 'CONFLICTING_WINDOWS')

    def test_sparse_evidence_receives_bounded_extra_samples_without_separation(self):
        from types import SimpleNamespace
        import wave
        with tempfile.TemporaryDirectory() as temporary:
            worker = create_app(Settings(transcode_root=Path(temporary))).state.audio_roles
            task = AudioTask(str(uuid.uuid4()))
            audio = [SimpleNamespace(index=0, title=''), SimpleNamespace(index=1, title='')]
            try:
                with patch.object(FastAudioRoles, 'available', new_callable=PropertyMock, return_value=True), patch.object(worker, '_run') as run, patch.object(worker, '_separate') as separate, patch.object(worker.fast, 'predict', side_effect=[[.02,.02,.20,.02,.12,.16], [.14,.02,.13,.02,.10,.02]]) as predict:
                    result = worker._fast_analyze('', False, audio, 120, task)
                    self.assertEqual(result['state'], 'CONFIDENT')
                    self.assertEqual(result['original'], 0)
                    self.assertEqual(predict.call_count, 2)
                    self.assertEqual(run.call_count, 6)
                    self.assertEqual(len(result['evidence']['windows']), 6)
                    windows = sorted(result['evidence']['windows'], key=lambda item: item['start'])
                    for first, second in zip(windows, windows[1:]):
                        self.assertLessEqual(first['start']+first['seconds'], second['start'])
                    separate.assert_not_called()
            finally:
                worker.close()

    def test_fast_worker_reads_three_short_windows_and_never_separates(self):
        import subprocess
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); media = root / 'media'; media.mkdir()
            token = root / 'token'; token.write_text('a' * 64)
            source = media / 'dual.mkv'
            subprocess.run(['ffmpeg', '-v', 'error', '-f', 'lavfi', '-i', 'sine=duration=12',
                '-f', 'lavfi', '-i', 'sine=frequency=880:duration=12', '-map', '0:a', '-map', '1:a',
                '-c:a', 'pcm_s16le', str(source)], check=True)
            app = create_app(Settings((media,), token, transcode_root=root / 'cache', minimum_free_bytes=0))
            worker = app.state.audio_roles
            def scores(paths, cancel):
                import wave
                self.assertEqual(len(paths), 6)
                for path in paths:
                    with wave.open(str(path), 'rb') as audio:
                        self.assertEqual((audio.getnchannels(), audio.getframerate(), audio.getnframes()), (1, 32000, 128000))
                return [.8, .05] * 3
            try:
                with patch.object(FastAudioRoles, 'available', new_callable=PropertyMock, return_value=True), patch.object(worker.fast, 'predict', side_effect=scores), patch.object(worker, '_separate') as separate, patch.object(worker.models, 'selected') as selected:
                    identifier = str(uuid.uuid4()); worker.submit(identifier, str(source), None, 'FAST')
                    worker.thread.join(15)
                    result = worker.view(identifier)
                    self.assertEqual(result['state'], 'SUCCEEDED', result)
                    self.assertEqual(result['result']['original'], 'stream:0')
                    self.assertEqual(result['result']['evidence']['analysisVersion'], 'fast-vocal-v2')
                    self.assertEqual(len(result['result']['evidence']['windows']), 3)
                    separate.assert_not_called(); selected.assert_not_called()
                    self.assertFalse(list(worker.root.glob('work-*')))
            finally:
                worker.close()

    def test_missing_classifier_or_explicit_labels_need_no_inference(self):
        from types import SimpleNamespace
        with tempfile.TemporaryDirectory() as temporary:
            worker = create_app(Settings(transcode_root=Path(temporary))).state.audio_roles
            task = AudioTask(str(uuid.uuid4()))
            audio = [SimpleNamespace(index=0, title=''), SimpleNamespace(index=1, title='')]
            try:
                with patch.object(FastAudioRoles, 'available', new_callable=PropertyMock, return_value=False), patch.object(worker, '_run') as run:
                    self.assertEqual(worker._fast_analyze('', False, audio, 60, task)['reason'], 'FAST_MODEL_MISSING')
                    audio[0].title = '伴奏'; audio[1].title = '原唱'
                    self.assertEqual(worker._fast_analyze('', False, audio, 60, task)['original'], 1)
                    run.assert_not_called()
            finally:
                worker.close()

    def test_cancel_kills_only_classifier_and_clears_persistent_worker(self):
        from types import SimpleNamespace
        from singverse_compute.core.errors import ComputeError
        worker = FastAudioRoles(SimpleNamespace(settings=SimpleNamespace(audio_memory_bytes=1024**3),
            models=SimpleNamespace(model_path=lambda _: Path('/tmp/model'))))
        fake = Mock(); fake.poll.return_value = None; worker.process = fake; worker.stamp = (1, 1)
        cancel = threading.Event(); cancel.set()
        with patch.object(FastAudioRoles, 'available', new_callable=PropertyMock, return_value=True), patch.object(Path, 'stat', return_value=SimpleNamespace(st_mtime_ns=1, st_size=1)), patch('singverse_compute.application.fast_audio_roles.stop') as stop:
            with self.assertRaises(ComputeError):
                worker.predict([Path('/tmp/sample.wav')], cancel)
            stop.assert_called_once_with(fake)
            self.assertIsNone(worker.process)

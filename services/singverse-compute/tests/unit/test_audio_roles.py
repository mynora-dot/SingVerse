import unittest
from singverse_compute.application.audio_roles import classify


class AudioRoleClassificationTest(unittest.TestCase):
    def test_consistent_vocals_are_required(self):
        result = classify([(.6, .004), (.7, .003)], ('原唱', '伴奏'))
        self.assertEqual((result['state'], result['original'], result['backing']), ('CONFIDENT', 0, 1))
        self.assertEqual(classify([(.6, .004), (.004, .6)], ('', ''))['state'], 'UNKNOWN')
        self.assertEqual(classify([(.01, .008), (.012, .009)], ('', ''))['state'], 'UNKNOWN')
        self.assertEqual(classify([(.8, .2), (.7, .15)], ('', ''))['state'], 'UNKNOWN')  # Both vocal.
        self.assertEqual(classify([(.04, .003), (.05, .002)], ('', ''))['state'], 'UNKNOWN')  # Both backing.
        self.assertEqual(classify([(.6, .004), (.7, .003)], ('伴奏', '原唱'))['state'], 'UNKNOWN')

    def test_silent_and_short_samples_are_unknown(self):
        self.assertEqual(classify([(.001, .001), (.001, .001)], ('', ''))['state'], 'UNKNOWN')
        self.assertEqual(classify([(.1, .001)], ('', ''))['state'], 'UNKNOWN')

class AudioRoleWorkerTest(unittest.TestCase):
    def test_busy_worker_preserves_idempotency_before_runtime_and_resource_checks(self):
        import hashlib, json, threading, uuid
        from types import SimpleNamespace
        from singverse_compute.application.audio_roles import AudioRoles, AudioTask
        from singverse_compute.core.errors import ComputeError
        identifier = str(uuid.uuid4())
        task = AudioTask(identifier)
        task.state = 'RUNNING'
        task.signature = hashlib.sha256(json.dumps(['/media/song.wav', None, 'SEPARATE', 'mps']).encode()).hexdigest()
        worker = SimpleNamespace(lock=threading.RLock(), tasks={identifier: task}, active=True)
        self.assertEqual(AudioRoles.submit(worker, identifier, '/media/song.wav', None, 'SEPARATE', 'mps')['state'], 'RUNNING')
        with self.assertRaises(ComputeError) as caught:
            AudioRoles.submit(worker, str(uuid.uuid4()), '/media/next.wav', None, 'SEPARATE', 'mps')
        self.assertEqual(caught.exception.status, 429)
        with self.assertRaises(ComputeError) as caught:
            AudioRoles.submit(worker, identifier, '/media/changed.wav', None, 'SEPARATE', 'mps')
        self.assertEqual(caught.exception.status, 409)

    def test_transient_resource_shortage_stays_retryable_for_batch_queue(self):
        import threading, uuid
        from types import SimpleNamespace
        from unittest.mock import Mock, patch
        from singverse_compute.application.audio_roles import AudioRoles
        from singverse_compute.core.errors import ComputeError
        worker = SimpleNamespace(lock=threading.RLock(), tasks={}, active=False, stopping=False,
            transcoding=SimpleNamespace(available=True), root=__import__('pathlib').Path('/'),
            settings=SimpleNamespace(minimum_free_bytes=0), backend_runtime_available=lambda _: True,
            models=SimpleNamespace(entries=lambda: [{'id':'mps','state':'AVAILABLE'}],
                check_resources=Mock(side_effect=ComputeError(422, 'Available RAM is below the configured audio process budget'))))
        with self.assertRaises(ComputeError) as caught:
            AudioRoles.submit(worker, str(uuid.uuid4()), '/media/song.wav', None, 'SEPARATE', 'mps')
        self.assertEqual(caught.exception.status, 429)
        self.assertIn('RAM', caught.exception.message)

    def test_real_ffmpeg_derivative_restart_and_readonly_source(self):
        from pathlib import Path
        import hashlib
        import tempfile
        import subprocess
        import time
        import uuid
        from unittest.mock import patch
        from singverse_compute.bootstrap import create_app
        from singverse_compute.core.config import Settings
        from singverse_compute.application.audio_roles import AudioRoles
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary); media=root/'media';media.mkdir();token=root/'token';token.write_text('a'*64)
            source=media/'song.wav'
            subprocess.run(['ffmpeg','-v','error','-f','lavfi','-i','sine=duration=21','-ac','2',str(source)],check=True)
            before=hashlib.sha256(source.read_bytes()).hexdigest()
            app=create_app(Settings((media,),token,transcode_root=root/'cache',minimum_free_bytes=0))
            worker=app.state.audio_roles
            # Fake the MODEL only; exercise real extraction, encoding, probing and persistence.
            def stems(wav,output,task): return wav,wav
            with patch.object(AudioRoles,'available',new_callable=__import__('unittest.mock',fromlist=['PropertyMock']).PropertyMock,return_value=True),patch.object(worker,'_separate',side_effect=stems),patch.object(worker.models,'selected',return_value='cpu'),patch.object(worker.models,'entries',return_value=[{'id':'cpu','state':'AVAILABLE'}]),patch.object(worker.models,'check_resources'):
                identifier=str(uuid.uuid4());worker.submit(identifier,str(source),None,'SEPARATE')
                deadline=time.monotonic()+20
                while worker.view(identifier)['state'] in ('QUEUED','RUNNING') and time.monotonic()<deadline:time.sleep(.05)
                self.assertEqual(worker.view(identifier)['state'],'SUCCEEDED',worker.view(identifier))
                self.assertTrue(worker.artifact(identifier).is_file())
                restored=AudioRoles(app.state.transcoding)
                self.assertEqual(restored.view(identifier)['state'],'SUCCEEDED')
                self.assertEqual(hashlib.sha256(source.read_bytes()).hexdigest(),before)
            worker.close()

    def test_probe_without_model_and_realtime_reservation(self):
        from pathlib import Path
        import tempfile, subprocess, time, uuid
        from singverse_compute.bootstrap import create_app
        from singverse_compute.core.config import Settings
        from singverse_compute.domain.transcoding import Target
        from singverse_compute.core.errors import ComputeError
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);media=root/'media';media.mkdir();token=root/'token';token.write_text('b'*64)
            source=media/'stereo.wav'
            subprocess.run(['ffmpeg','-v','error','-f','lavfi','-i','sine=duration=1','-ac','2',str(source)],check=True)
            app=create_app(Settings((media,),token,transcode_root=root/'cache',minimum_free_bytes=0))
            worker=app.state.audio_roles
            self.assertFalse(worker.available)
            identifier=str(uuid.uuid4());worker.submit(identifier,str(source),None,'PROBE')
            deadline=time.monotonic()+10
            while worker.active and time.monotonic()<deadline:time.sleep(.02)
            self.assertEqual(worker.view(identifier)['state'],'SUCCEEDED')
            self.assertEqual(len(worker.view(identifier)['result']['streams']),1)  # Stereo is one stream.
            worker.active=True
            with self.assertRaises(ComputeError) as raised:
                app.state.transcoding.create(str(uuid.uuid4()),str(source),None,'fingerprint',Target())
            self.assertEqual(raised.exception.status,429)
            worker.active=False
            self.assertGreater(app.state.transcoding.realtime_reserved_until,time.monotonic())
            with self.assertRaises(ComputeError) as raised:
                worker.submit(str(uuid.uuid4()),str(source),None,'PROBE')
            self.assertEqual(raised.exception.status,429)
            worker.close()

    def test_restart_marks_inflight_failed_and_removes_own_partial_files(self):
        from pathlib import Path
        import tempfile, json, uuid
        from singverse_compute.bootstrap import create_app
        from singverse_compute.core.config import Settings
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);cache=root/'cache';audio=cache/'audio-roles';audio.mkdir(parents=True)
            identifier=str(uuid.uuid4());(audio/(identifier+'.json')).write_text(json.dumps({'id':identifier,'state':'RUNNING'}))
            (audio/(identifier+'.part')).write_bytes(b'partial')
            work=audio/('work-'+identifier+'-old');work.mkdir();(work/'source.wav').write_bytes(b'partial')
            app=create_app(Settings(transcode_root=cache))
            self.assertEqual(app.state.audio_roles.view(identifier)['error'],'NODE_RESTARTED')
            self.assertFalse(work.exists());self.assertFalse((audio/(identifier+'.part')).exists())
            app.state.audio_roles.close()

    def test_extraction_preserves_delayed_audio_on_original_timeline(self):
        from pathlib import Path
        import tempfile, subprocess, wave, uuid
        from singverse_compute.bootstrap import create_app
        from singverse_compute.core.config import Settings
        from singverse_compute.application.audio_roles import AudioTask
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);media=root/'media';media.mkdir();source=media/'delayed.mkv'
            subprocess.run(['ffmpeg','-v','error','-f','lavfi','-i','color=size=32x32:duration=4',
                '-itsoffset','2','-f','lavfi','-i','sine=duration=2','-map','0:v','-map','1:a','-c:v','ffv1','-c:a','pcm_s16le',str(source)],check=True)
            app=create_app(Settings(transcode_root=root/'cache'))
            worker=app.state.audio_roles;extracted=root/'audio.wav'
            worker._extract(str(source),False,1,0,4,extracted,AudioTask(str(uuid.uuid4())))
            with wave.open(str(extracted),'rb') as wav:
                self.assertAlmostEqual(wav.getnframes()/wav.getframerate(),4,places=2)
                self.assertFalse(any(wav.readframes(44100)))
                wav.setpos(3*44100);self.assertTrue(any(wav.readframes(1000)))
            worker.close()

    def test_cancel_cleans_outputs_and_releases_worker(self):
        from pathlib import Path
        import tempfile, subprocess, threading, time, uuid
        from unittest.mock import patch, PropertyMock
        from singverse_compute.bootstrap import create_app
        from singverse_compute.core.config import Settings
        from singverse_compute.application.audio_roles import AudioRoles
        from singverse_compute.core.errors import ComputeError
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);media=root/'media';media.mkdir();token=root/'token';token.write_text('c'*64)
            source=media/'song.wav'
            subprocess.run(['ffmpeg','-v','error','-f','lavfi','-i','sine=duration=1','-ac','2',str(source)],check=True)
            app=create_app(Settings((media,),token,transcode_root=root/'cache',minimum_free_bytes=0))
            worker=app.state.audio_roles;entered=threading.Event()
            def blocked(wav,output,task):
                entered.set();task.cancel.wait(5)
                raise ComputeError(409,'Cancelled')
            with patch.object(AudioRoles,'available',new_callable=PropertyMock,return_value=True),patch.object(worker,'_separate',side_effect=blocked),patch.object(worker.models,'selected',return_value='cpu'),patch.object(worker.models,'entries',return_value=[{'id':'cpu','state':'AVAILABLE'}]),patch.object(worker.models,'check_resources'):
                identifier=str(uuid.uuid4());worker.submit(identifier,str(source),None,'SEPARATE')
                self.assertTrue(entered.wait(5))
                worker.cancel(identifier);worker.thread.join(5)
                self.assertEqual(worker.view(identifier)['state'],'CANCELLED')
                self.assertFalse(worker.active)
                self.assertFalse(list(worker.root.glob('*.part')))
                self.assertFalse(list(worker.root.glob('*.m4a')))
                self.assertFalse(list(worker.root.glob('work-*')))
            worker.close()

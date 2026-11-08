"""Playback admission and cold-path work, with isolated generated artifacts."""
from dataclasses import replace
import json
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from callback_source import CallbackTestCase
from singverse_compute.application.transcode_artifacts import ready_hls
from singverse_compute.application.transcoding import Transcoding
from singverse_compute.application.audio_models import AudioModels
from singverse_compute.core.config import Settings
from singverse_compute.domain.transcoding import Media, Stream, Target
from singverse_compute.infrastructure.security import InternalAuth
from singverse_compute.infrastructure.transcoding.backends import Backends
from singverse_compute.infrastructure.transcoding.encoder import Encoder
from singverse_compute.infrastructure.transcoding.input import remote_input
from singverse_compute.infrastructure.transcoding.probe import MediaProbe


class PlaybackStartupTest(CallbackTestCase):
    def test_nvenc_avc_reference_cap_does_not_enable_reference_b_frames(self):
        from singverse_compute.infrastructure.transcoding.pipelines import VideoPipeline
        media = Media('mkv', 5, 1000, (Stream('stream:0', 0, 'video', 'hevc', width=160, height=90, fps=25),))
        backend = SimpleNamespace(features=lambda: {}, prefix=lambda name: [], upload=lambda name: '')
        encoder = Encoder('ffmpeg', SimpleNamespace(read=lambda *args: media), backend)
        encoder.pipelines.choose = Mock(return_value=VideoPipeline('fixture'))
        cases = [('nvenc', 'TRANSCODE', 'h264', 41, True),
                 ('software', 'TRANSCODE', 'h264', 41, False),
                 ('nvenc', 'REMUX', 'h264', 41, False),
                 ('nvenc', 'TRANSCODE', 'h264', 0, False),
                 ('nvenc', 'TRANSCODE', 'hevc', 0, False)]
        for purpose in ('REALTIME', 'OFFLINE'):
            for name, mode, codec, level, constrained in cases:
                with self.subTest(purpose=purpose, backend=name, mode=mode, codec=codec, level=level), tempfile.TemporaryDirectory() as folder:
                    directory = Path(folder)
                    def execute(command, **kwargs):
                        if purpose == 'OFFLINE':
                            (directory / 'output.partial.mp4').write_bytes(b'complete fixture')
                        return b''
                    with patch('singverse_compute.infrastructure.transcoding.encoder.run', side_effect=execute) as run:
                        encoder.execute('fixture', False, media,
                                        Target(mode=mode, purpose=purpose, codec=codec, profile='high' if codec == 'h264' else '', level=level),
                                        directory, __import__('threading').Event(), lambda *args: None, name)
                        command = run.call_args.args[0]
                    if constrained:
                        self.assertEqual(command[command.index('-refs') + 1], '1')
                        self.assertIn('-b_ref_mode', command, 'NVENC reference B-frames conflict with the single-reference AVC cap')
                        self.assertEqual(command[command.index('-b_ref_mode') + 1], 'disabled')
                        self.assertNotIn('-bf', command, 'Ordinary B-frames remain available')
                    else:
                        self.assertNotIn('-b_ref_mode', command)

    def test_absent_optional_rknn_weights_do_not_wait_for_the_model_probe_lock(self):
        import threading
        with tempfile.TemporaryDirectory() as folder:
            manager = object.__new__(AudioModels)
            manager.repo = Path(folder) / 'htdemucs'; manager.lock = threading.RLock()
            locked, proceed = threading.Event(), threading.Event()
            def probing():
                with manager.lock:
                    locked.set(); proceed.wait(3)
            holder = threading.Thread(target=probing); holder.start()
            try:
                self.assertTrue(locked.wait(1))
                with __import__('concurrent.futures', fromlist=['ThreadPoolExecutor']).ThreadPoolExecutor(max_workers=1) as executor:
                    result = executor.submit(manager.model_valid, 'uvr-mdxnet-9482-rknn')
                    self.assertFalse(result.result(timeout=1))
            finally:
                proceed.set(); holder.join(2)

    def test_all_advertised_renditions_need_first_segment_and_initialization(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / 'master.m3u8').write_text('#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,URI="stream_audio0.m3u8"\nstream_0.m3u8\n')
            (root / 'stream_0.m3u8').write_text('#EXTM3U\n#EXT-X-MAP:URI="init_0.mp4"\nstream_0_000000.m4s\n')
            (root / 'init_0.mp4').write_bytes(b'init')
            (root / 'stream_0_000000.m4s').write_bytes(b'video')
            self.assertFalse(ready_hls(root), 'Video alone must not publish an unavailable audio rendition')
            (root / 'stream_audio0.m3u8').write_text('#EXTM3U\n#EXT-X-MAP:URI="init_audio0.mp4"\nstream_audio0_000000.m4s\n')
            self.assertFalse(ready_hls(root))
            (root / 'stream_audio0_000000.m4s').write_bytes(b'audio')
            self.assertFalse(ready_hls(root), 'An advertised fMP4 initialization must be present')
            (root / 'init_audio0.mp4').write_bytes(b'init')
            self.assertTrue(ready_hls(root), 'One segment per rendition is sufficient; no ENDLIST or extra buffer')
            (root / 'init_audio0.mp4').unlink()
            (root / 'init_audio0.mp4').symlink_to(root / 'init_0.mp4')
            self.assertFalse(ready_hls(root))

    def test_readiness_rejects_external_and_parent_playlist_references(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            for ref in ('../stream_0.m3u8', 'https://invalid.example/stream_0.m3u8'):
                (root / 'master.m3u8').write_text('#EXTM3U\n' + ref + '\n')
                self.assertFalse(ready_hls(root))

    def test_negotiate_snapshot_is_reused_only_for_same_reference_origin_fingerprint_and_ttl(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); source = root / 'source.mkv'; source.write_bytes(b'fixture')
            auth = InternalAuth(root / 'token'); auth.initialize()
            media = Media('mkv', 60, 1000, (Stream('stream:0', 0, 'video', 'h264', width=16, height=16),))
            probe = SimpleNamespace(read=Mock(return_value=media))
            def encode(source, network, media, target, directory, cancel, progress, backend):
                (directory / 'master.m3u8').write_text('#EXTM3U\nstream_0.m3u8\n')
                (directory / 'stream_0.m3u8').write_text('#EXTM3U\nstream_0_000000.ts\n#EXT-X-ENDLIST\n')
                (directory / 'stream_0_000000.ts').write_bytes(b'complete media')
                return []
            settings = Settings(token_file=root / 'token', transcode_root=root / 'cache', minimum_free_bytes=0,
                                ffmpeg=sys.executable, ffprobe=sys.executable)
            service = Transcoding(settings, auth, probe, SimpleNamespace(execute=encode),
                                  SimpleNamespace(select=lambda *a, **kw: 'software'), remote_input)
            try:
                grant = self.callback(source, auth._token())
                reference, origin = grant['reference'], grant['callbackOrigin']
                for variant in ('same', 'fingerprint', 'reference', 'expired'):
                    with self.subTest(variant=variant):
                        probe.read.reset_mock()
                        service.inspect(reference, origin, 'source-v1')
                        if variant == 'expired':
                            key = (reference, origin, 'source-v1')
                            timestamp, value = service.probes[key]; service.probes[key] = (timestamp - 61, value)
                        alternate = root / 'alternate.mkv'; alternate.write_bytes(b'fixture')
                        actual_ref = self.callback(alternate, auth._token())['reference'] if variant == 'reference' else reference
                        identifier = str(uuid.uuid4())
                        service.create(identifier, actual_ref, 'source-v2' if variant == 'fingerprint' else 'source-v1', Target(), origin)
                        for thread in service.threads: thread.join(2)
                        self.assertEqual(service.view(identifier)['state'], 'SUCCEEDED')
                        self.assertEqual(probe.read.call_count, 1 if variant == 'same' else 2)
                for index in range(140): service.inspect(reference, origin, f'fingerprint-{index}')
                self.assertEqual(len(service.probes), 128)
            finally:
                service.close()

    def test_codec_selection_does_not_probe_unrelated_codecs_and_reuses_its_trial(self):
        backend = Backends('ffmpeg')
        listing = b'libx264 libx265 libsvtav1 h264_videotoolbox hevc_videotoolbox'
        with patch('singverse_compute.infrastructure.transcoding.backends.platform.system', return_value='Darwin'), \
                patch('singverse_compute.infrastructure.transcoding.backends.run', return_value=listing) as run, \
                patch.object(backend, 'detected', return_value=['videotoolbox']), \
                patch.object(backend, 'inspect', side_effect=AssertionError('Playback must not run the complete capabilities probe')):
            self.assertEqual(backend.select('h264'), 'videotoolbox')
            self.assertEqual(backend.select('h264'), 'videotoolbox')
            self.assertEqual(run.call_count, 2, 'One encoder listing and one requested-codec trial')
            self.assertTrue(all('hevc_videotoolbox' not in call.args[0] and 'libsvtav1' not in call.args[0] for call in run.call_args_list))

    def test_probe_recognizes_high_bit_depth_when_raw_sample_depth_is_zero(self):
        for pixel, depth in [('yuv420p12le', 12), ('yuv420p10le', 10), ('p010le', 10), ('p016le', 16), ('yuv420p', 8)]:
            data = {'format': {'format_name':'mov', 'duration':'1'}, 'streams':[
                {'index':0, 'codec_type':'video', 'codec_name':'hevc', 'pix_fmt':pixel, 'bits_per_raw_sample':'0'}]}
            with self.subTest(pixel=pixel), patch('singverse_compute.infrastructure.transcoding.probe.run', return_value=json.dumps(data).encode()):
                self.assertEqual(MediaProbe('ffprobe').read('fixture').streams[0].bit_depth, depth)

    def test_fixed_templates_constrain_avc_and_only_shorten_realtime_encoding_segments(self):
        media = Media('mp4', 60, 1000, (Stream('stream:0', 0, 'video', 'h264', width=160, height=90, fps=25),))
        backend = SimpleNamespace(features=lambda:{}, prefix=lambda name:[], upload=lambda name:'')
        probe = SimpleNamespace(read=lambda *args:media)
        encoder = Encoder('ffmpeg', probe, backend)
        for purpose, mode in [('REALTIME','TRANSCODE'), ('REALTIME','AUDIO_TRANSCODE'), ('REALTIME','REMUX'), ('OFFLINE','TRANSCODE')]:
            with self.subTest(purpose=purpose, mode=mode), tempfile.TemporaryDirectory() as folder:
                directory = Path(folder)
                def execute(command, **kwargs):
                    if purpose == 'OFFLINE': (directory / 'output.partial.mp4').write_bytes(b'complete fixture')
                    return b''
                with patch('singverse_compute.infrastructure.transcoding.encoder.run', side_effect=execute) as run:
                    encoder.execute('fixture', False, media, Target(mode=mode,purpose=purpose,profile='baseline',level=31),
                                    directory, __import__('threading').Event(), lambda *args:None, 'software')
                    command = run.call_args.args[0]
                if mode == 'TRANSCODE':
                    self.assertEqual(command[command.index('-profile:v')+1], 'baseline')
                    self.assertEqual(command[command.index('-level:v')+1], '3.1')
                    self.assertEqual(command[command.index('-bf')+1], '0')
                    self.assertEqual(command[command.index('-coder')+1], '0')
                    self.assertEqual(command[command.index('-force_key_frames')+1], f'expr:gte(t,n_forced*{2 if purpose == "REALTIME" else 4})')
                    self.assertEqual(command[command.index('-bufsize')+1], '8000000')
                else:
                    self.assertNotIn('-profile:v', command)
                    self.assertEqual(command[command.index('-c:v')+1], 'copy')
                if purpose == 'REALTIME':
                    self.assertEqual(command[command.index('-hls_time')+1], '4' if mode == 'REMUX' else '2')
                else:
                    self.assertNotIn('-hls_time', command)

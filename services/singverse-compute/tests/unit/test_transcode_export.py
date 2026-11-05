"""Temporary HLS transfer integrity and lease/release lifecycle without real FFmpeg."""
from pathlib import Path
import hashlib
import json
import sys
import tempfile
import time
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import uuid

from singverse_compute.application.transcoding import Execution, Transcoding
from singverse_compute.core.config import Settings
from singverse_compute.core.errors import ComputeError
from singverse_compute.domain.transcoding import Media, Stream, Target
from singverse_compute.infrastructure.filesystem import RootedMediaPaths
from singverse_compute.infrastructure.security import InternalAuth


class TranscodeExportTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.media = self.root / 'media'
        self.media.mkdir()
        self.source = self.media / 'song.mkv'
        self.source.write_bytes(b'original media')
        self.config = Settings((self.media,), self.root / 'token', transcode_root=self.root / 'work',
                               ffmpeg=sys.executable, ffprobe=sys.executable, minimum_free_bytes=0)
        self.calls = 0
        auth = InternalAuth(self.root / 'token')
        auth.initialize()
        probe = SimpleNamespace(read=lambda *a: Media('mkv', 60, 1000,
                                (Stream('stream:0', 0, 'video', 'h264', width=16, height=16),)))

        def encode(source, network, media, target, directory, cancel, progress, backend):
            self.calls += 1
            if target.purpose == 'OFFLINE':
                (directory / 'output.mp4').write_bytes(b'completed offline')
            else:
                (directory / 'master.m3u8').write_text('#EXTM3U\nstream_0.m3u8\n')
                (directory / 'stream_0.m3u8').write_text('#EXTM3U\n#EXT-X-MAP:URI="init_0.mp4"\nstream_0_000000.m4s\n#EXT-X-ENDLIST\n')
                (directory / 'init_0.mp4').write_bytes(b'initialization')
                (directory / 'stream_0_000000.m4s').write_bytes(b'complete media data')
            return [{'sourceId': 'stream:0', 'kind': 'video', 'outputIndex': 0}]

        self.ports = (RootedMediaPaths((self.media,)), auth, probe,
                      SimpleNamespace(execute=encode), SimpleNamespace(select=lambda *a, **kw: 'software'), None)
        self.service = Transcoding(self.config, *self.ports)

    def tearDown(self):
        self.service.close()
        self.temp.cleanup()

    def create(self, target=None, identifier=None):
        identifier = identifier or str(uuid.uuid4())
        self.service.create(identifier, str(self.source), None, 'same-song', target or Target(fmp4=True))
        for thread in self.service.threads:
            thread.join(2)
        self.assertEqual(self.service.view(identifier)['state'], 'SUCCEEDED')
        return identifier

    def test_distinct_task_ids_do_not_reuse_media_and_same_id_is_idempotent(self):
        first = self.create()
        second = self.create()
        self.assertEqual(self.calls, 2)
        self.assertTrue((self.service.root / first / 'master.m3u8').is_file())
        self.assertTrue((self.service.root / second / 'master.m3u8').is_file())
        self.create(identifier=first)
        self.assertEqual(self.calls, 2)
        self.assertNotIn('cacheHit', self.service.view(second))

    def test_export_has_verified_sizes_digests_and_renews_lease(self):
        identifier = self.create()
        task = self.service.get(identifier)
        task.renewed = 0
        exported = self.service.export(identifier)
        self.assertEqual(set(exported), {'artifacts'})
        self.assertEqual(set(exported['artifacts']), {'master.m3u8', 'stream_0.m3u8', 'init_0.mp4', 'stream_0_000000.m4s'})
        for name, metadata in exported['artifacts'].items():
            data = (self.service.root / identifier / name).read_bytes()
            self.assertEqual(metadata, {'size': len(data), 'sha256': hashlib.sha256(data).hexdigest()})
        self.assertGreater(task.renewed, time.time() - 2)
        with self.assertRaises(ComputeError) as caught:
            self.service.remove(identifier)
        self.assertEqual(caught.exception.status, 409)

    def test_missing_external_and_unterminated_playlists_cannot_export(self):
        identifier = self.create()
        playlist = self.service.root / identifier / 'stream_0.m3u8'
        for reference, ending in [('stream_missing_000000.m4s', '#EXT-X-ENDLIST\n'),
                                  ('../song.mkv', '#EXT-X-ENDLIST\n'),
                                  ('https://example.invalid/media.ts', '#EXT-X-ENDLIST\n'),
                                  ('stream_0_000000.m4s', '')]:
            with self.subTest(reference=reference, ending=ending):
                playlist.write_text('#EXTM3U\n' + reference + '\n' + ending)
                with self.assertRaises(ComputeError) as caught:
                    self.service.export(identifier)
                self.assertEqual(caught.exception.status, 409)

    def test_missing_init_and_symbolic_link_artifacts_cannot_export(self):
        identifier = self.create()
        initialization = self.service.root / identifier / 'init_0.mp4'
        initialization.unlink()
        with self.assertRaises(ComputeError):
            self.service.export(identifier)
        initialization.symlink_to(self.source)
        with self.assertRaises(ComputeError) as caught:
            self.service.export(identifier)
        self.assertEqual(caught.exception.status, 409)
        self.assertEqual(self.source.read_bytes(), b'original media')

    def test_only_completed_realtime_outputs_may_export(self):
        offline = self.create(Target(purpose='OFFLINE'))
        pending = Execution(str(uuid.uuid4()), 'signature', Target())
        self.service.tasks[pending.id] = pending
        for identifier in (offline, pending.id):
            with self.assertRaises(ComputeError) as caught:
                self.service.export(identifier)
            self.assertEqual(caught.exception.status, 409)

    def test_acknowledge_releases_once_and_survives_restart(self):
        identifier = self.create()
        self.service.export(identifier)
        released = self.service.acknowledge(identifier)
        self.assertTrue(released['released'])
        self.assertFalse(released['ready'])
        self.assertEqual(released['state'], 'SUCCEEDED')
        self.assertEqual([p.name for p in (self.service.root / identifier).iterdir()], ['execution.json'])
        self.assertEqual(self.service.acknowledge(identifier), released)
        self.service.close()
        self.service = Transcoding(self.config, *self.ports)
        self.assertFalse(self.service.view(identifier)['ready'])
        for read in (lambda: self.service.export(identifier),
                     lambda: self.service.artifact(identifier, 'master.m3u8')):
            with self.assertRaises(ComputeError) as caught:
                read()
            self.assertEqual(caught.exception.status, 410)

    def test_failed_acknowledgement_can_be_retried(self):
        identifier = self.create()
        with patch.object(Path, 'unlink', side_effect=PermissionError('denied')):
            with self.assertRaises(ComputeError) as caught:
                self.service.acknowledge(identifier)
        self.assertEqual(caught.exception.status, 500)
        self.assertFalse(self.service.get(identifier).released)
        self.assertTrue(self.service.acknowledge(identifier)['released'])

    def test_successful_realtime_outputs_expire_after_retention_not_while_leased(self):
        identifier = self.create()
        task = self.service.get(identifier)
        task.finished = time.time() - 601
        self.service.collect()
        self.assertIn(identifier, self.service.tasks)
        task.renewed = time.time() - 601
        self.service.collect()
        self.assertNotIn(identifier, self.service.tasks)
        self.assertFalse((self.service.root / identifier).exists())

    def test_cleanup_failure_retains_registry_and_maintenance_retries(self):
        identifier = self.create()
        task = self.service.get(identifier)
        task.finished = task.renewed = time.time() - 601
        with patch('singverse_compute.application.transcoding.shutil.rmtree', side_effect=PermissionError('denied')):
            with self.assertLogs('singverse_compute.transcoding', level='WARNING'):
                self.service.maintain()
        self.assertIn(identifier, self.service.tasks)
        self.service.maintain()
        self.assertNotIn(identifier, self.service.tasks)

    def test_legacy_long_term_cache_configuration_is_ignored(self):
        (self.service.root / 'cache-settings.json').write_text('invalid old cache policy')
        self.service.close()
        self.service = Transcoding(self.config, *self.ports)
        self.assertFalse(hasattr(self.service, 'query_cache'))
        self.assertFalse(hasattr(self.service, 'cache_config'))
        self.create()

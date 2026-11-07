"""Real bounded reader tests; require ffmpeg and ffprobe on PATH."""
import hashlib
import subprocess
import tempfile
import unittest
from pathlib import Path
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from callback_source import CallbackTestCase
from singverse_compute.infrastructure.media.ffprobe import normalize
from singverse_compute.infrastructure.media.remote_tags import RangeFile, extract
from singverse_compute.infrastructure.transcoding.process import run
from singverse_compute.core.errors import ComputeError

class RemoteTagsAdapterTest(CallbackTestCase):
    def probe(self, path, executable='ffprobe'):
        grant = self.callback(path, 'fixture-token')
        with RangeFile(grant['callbackOrigin'], grant['reference'], 'fixture-token', path.name, path.stat().st_size) as source:
            return extract(source, executable, 'ffmpeg', 1024**3)['tags']

    def test_priority_and_missing(self):
        self.assertEqual(normalize({'format': {'tags': {'TITLE': '文件歌名'}}, 'streams': [{'tags': {'title': '流歌名', 'ARTIST': '甲;乙'}}]}),
                         {'title': '文件歌名', 'artists': ['甲','乙'], 'album': None, 'releaseDate': None})
    def test_real_audio_video_and_read_only(self):
        with tempfile.TemporaryDirectory() as folder:
            for extension in ['flac', 'mp3', 'm4a', 'mp4', 'mkv']:
                with self.subTest(extension=extension):
                    path = Path(folder) / ('错误歌手-错误歌名.' + extension)
                    command = ['ffmpeg', '-v','error','-f','lavfi','-i','sine=frequency=440:duration=0.1']
                    if extension in ['mp4','mkv']:
                        command += ['-f','lavfi','-i','color=c=black:s=16x16:d=0.1','-c:v','mpeg4','-shortest']
                    command += ['-metadata','title=文件内歌名','-metadata','artist=文件内歌手','-metadata','album=文件内专辑','-metadata','date=2020-03-04',str(path)]
                    subprocess.run(command, check=True)
                    digest = hashlib.sha256(path.read_bytes()).digest()
                    tags = self.probe(path)
                    self.assertEqual(tags['title'], '文件内歌名'); self.assertEqual(tags['artists'], ['文件内歌手'])
                    self.assertEqual(tags['album'], '文件内专辑'); self.assertEqual(tags['releaseDate'], '2020-03-04')
                    self.assertEqual(hashlib.sha256(path.read_bytes()).digest(), digest)
    def test_reader_bounds_timeout_and_large_output(self):
        import sys
        for command, timeout in (([sys.executable, '-c', 'import sys; sys.stdout.write("x" * 400000)'], 3),
                                 ([sys.executable, '-c', 'import time; time.sleep(10)'], .1)):
            with self.assertRaises(ComputeError):
                run(command, timeout=timeout, limit=262144)

    def test_bad_file_missing_tool_and_no_tags(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'broken.flac'; path.write_bytes(b'not audio')
            self.assertFalse(self.probe(path).get('title'))
            self.assertEqual(self.probe(path, '/missing/ffprobe'), {})
            empty = Path(folder) / 'empty.wav'
            subprocess.run(['ffmpeg','-v','error','-f','lavfi','-i','sine=duration=0.1',str(empty)],check=True)
            self.assertFalse(self.probe(empty).get('title'))

if __name__ == '__main__': unittest.main()

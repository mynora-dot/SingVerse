"""Real bounded reader tests; require ffmpeg and ffprobe on PATH."""
import hashlib
import subprocess
import tempfile
import unittest
from pathlib import Path
from singverse_compute.infrastructure.media.ffprobe import normalize, probe

class LocalTagsTest(unittest.TestCase):
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
                    tags = probe(path)
                    self.assertEqual(tags['title'], '文件内歌名'); self.assertEqual(tags['artists'], ['文件内歌手'])
                    self.assertEqual(tags['album'], '文件内专辑'); self.assertEqual(tags['releaseDate'], '2020-03-04')
                    self.assertEqual(hashlib.sha256(path.read_bytes()).digest(), digest)
    def test_reader_bounds_timeout_and_large_output(self):
        with tempfile.TemporaryDirectory() as folder:
            media = Path(folder) / 'input.mp3'; media.write_bytes(b'fixture')
            fake = Path(folder) / 'ffprobe'
            fake.write_text('#!/usr/bin/env python3\nimport sys\nsys.stdout.write("x" * 400000)\n')
            fake.chmod(0o700)
            self.assertEqual(probe(media, str(fake)), {})
            fake.write_text('#!/usr/bin/env python3\nimport time\ntime.sleep(10)\n')
            self.assertEqual(probe(media, str(fake)), {})

    def test_bad_file_missing_tool_and_no_tags(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'broken.flac'; path.write_bytes(b'not audio')
            self.assertFalse(probe(path).get('title'))
            self.assertEqual(probe(path, '/missing/ffprobe'), {})
            self.assertEqual(probe('relative.mp3'), {})
            empty = Path(folder) / 'empty.wav'
            subprocess.run(['ffmpeg','-v','error','-f','lavfi','-i','sine=duration=0.1',str(empty)],check=True)
            self.assertIsNone(probe(empty)['title'])

if __name__ == '__main__': unittest.main()

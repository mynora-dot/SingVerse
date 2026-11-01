"""Bounded HTTP reads and real embedded tag extraction; never contact a cloud provider."""
import base64
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import threading
import unittest
from singverse_compute.infrastructure.media.remote_tags import RangeFile, ReadStopped, extract, BLOCK

TOKEN = 't' * 64
PNG = base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aX1sAAAAASUVORK5CYII=')

class RemoteTagTest(unittest.TestCase):
    def setUp(self):
        self.data = b'a' * (20*BLOCK)
        self.status = 206
        self.calls = []
        outer = self
        class Handler(BaseHTTPRequestHandler):
            def log_message(self,*args): pass
            def do_GET(self):
                outer.calls.append((self.path,self.headers.get('Authorization'),self.headers.get('Range')))
                if outer.status != 206:
                    self.send_response(outer.status); self.send_header('Content-Length','0'); self.end_headers(); return
                start,end = self.headers['Range'].split('=')[1].split('-')
                start,end = int(start),min(int(end),len(outer.data)-1)
                if start >= len(outer.data): self.send_error(416); return
                self.send_response(206); self.send_header('Content-Range',f'bytes {start}-{end}/{len(outer.data)}'); self.send_header('Content-Length',str(end-start+1)); self.end_headers()
                try: self.wfile.write(outer.data[start:end+1])
                except (BrokenPipeError,ConnectionResetError): pass
        self.server = ThreadingHTTPServer(('127.0.0.1',0),Handler)
        self.thread = threading.Thread(target=self.server.serve_forever,daemon=True); self.thread.start()
        self.origin = f'http://127.0.0.1:{self.server.server_port}'

    def tearDown(self):
        self.server.shutdown(); self.server.server_close(); self.thread.join(timeout=2)

    def source(self,size=None,name='test.mp3'):
        return RangeFile(self.origin,'11111111-1111-1111-1111-111111111111',TOKEN,name,size)

    def test_seek_and_shared_block_cache(self):
        with self.source(len(self.data)) as source:
            self.assertEqual(source.read(10),b'a'*10)
            source.seek(0);source.read(20)
            source.seek(-10,2);self.assertEqual(source.read(),b'a'*10)
            self.assertEqual(len(self.calls),2)
            self.assertEqual(source.bytes_read,2*BLOCK)
            self.assertTrue(all(call[1]=='Bearer '+TOKEN for call in self.calls))
            self.assertTrue(all('/metadata-input/' in call[0] for call in self.calls))

    def test_budget_stops_reads_and_large_allocations(self):
        with self.source(len(self.data)) as source:
            with self.assertRaises(ReadStopped): source.read(20*BLOCK)
            self.assertEqual(self.calls,[])
        with self.source(len(self.data)) as source:
            for i in range(16): source.seek(i*BLOCK);source.read(1)
            with self.assertRaises(ReadStopped): source.seek(16*BLOCK);source.read(1)
            self.assertEqual(len(self.calls),16)
            self.assertEqual(source.bytes_read,16*BLOCK)

    def test_ignored_range_auth_and_rate_limit_are_distinct(self):
        for status,reason in [(200,'RANGE_UNSUPPORTED'),(422,'RANGE_UNSUPPORTED'),(403,'AUTH_REQUIRED'),(429,'RATE_LIMITED'),(412,'UNSTABLE')]:
            self.status=status
            with self.source(len(self.data)) as source:
                with self.assertRaises(ReadStopped) as caught: source.read(1)
                self.assertEqual(caught.exception.reason,reason)
                self.assertEqual(source.bytes_read,0)

    def test_size_change_invalidates_session(self):
        with self.source(len(self.data)+1) as source:
            with self.assertRaises(ReadStopped) as caught:source.read(1)
            self.assertEqual(caught.exception.reason,'UNSTABLE')

    def make_audio(self,folder,extension):
        target=Path(folder)/('test.'+extension)
        command=['ffmpeg','-v','error','-f','lavfi','-i','anullsrc=r=44100:cl=stereo','-t','1','-metadata','title=内嵌歌名','-metadata','artist=歌手甲;歌手乙','-metadata','album=测试专辑','-metadata','date=2026',str(target)]
        subprocess.run(command,check=True,timeout=20,capture_output=True)
        return target

    def test_mp3_cover_sync_lyrics_and_technical_share_one_fetch(self):
        from mutagen.id3 import ID3,APIC,SYLT
        with tempfile.TemporaryDirectory() as folder:
            target=self.make_audio(folder,'mp3')
            tags=ID3(target);tags.add(APIC(mime='image/png',type=3,data=PNG));tags.add(SYLT(encoding=3,lang='zho',format=2,type=1,text=[('第一句',100),('第二句',900)]));tags.save(target)
            self.data=target.read_bytes()
            with self.source(len(self.data)) as source:
                result=extract(source,'ffprobe','ffmpeg',1024**3)
            self.assertEqual(result['tags']['title'],'内嵌歌名')
            self.assertEqual(result['lyrics']['lines'][0]['time'],100)
            self.assertEqual(result['lyrics']['timing'],'milliseconds')
            self.assertEqual(base64.b64decode(result['cover']),PNG)
            self.assertTrue(result['technical']['streams'])
            self.assertEqual(len(self.calls),1)

    def test_flac_picture_and_plain_lyrics(self):
        from mutagen.flac import FLAC,Picture
        with tempfile.TemporaryDirectory() as folder:
            target=self.make_audio(folder,'flac');media=FLAC(target)
            picture=Picture();picture.type=3;picture.mime='image/png';picture.data=PNG;media.add_picture(picture);media['lyrics']='[00:01.00]歌词';media.save()
            self.data=target.read_bytes()
            with self.source(len(self.data),target.name) as source:result=extract(source,'ffprobe','ffmpeg',1024**3)
            self.assertEqual(result['lyrics']['timing'],'lrc')
            self.assertEqual(base64.b64decode(result['cover']),PNG)

    def test_mp4_tail_metadata_and_ogg_opus(self):
        from mutagen.mp4 import MP4,MP4Cover
        for extension in ['m4a','ogg','opus']:
            with self.subTest(extension=extension),tempfile.TemporaryDirectory() as folder:
                target=self.make_audio(folder,extension)
                if extension=='m4a':
                    media=MP4(target);media['covr']=[MP4Cover(PNG,imageformat=MP4Cover.FORMAT_PNG)];media['\xa9lyr']=['纯文本歌词'];media.save()
                self.data=target.read_bytes()
                if extension=='m4a':
                    import struct
                    offset=0
                    while offset < len(self.data):
                        length,kind=struct.unpack('>I4s',self.data[offset:offset+8])
                        if kind==b'moov':break
                        offset+=length
                    self.data=self.data[:offset]+struct.pack('>I4s',2*BLOCK+8,b'free')+b'\x00'*(2*BLOCK)+self.data[offset:]
                self.calls=[]
                with self.source(len(self.data),target.name) as source:result=extract(source,'ffprobe','ffmpeg',1024**3)
                self.assertEqual(result['tags']['title'],'内嵌歌名')
                self.assertTrue(result['technical']['streams'])
                self.assertLess(result['elapsedMs'],10000)
                if extension=='m4a':self.assertEqual(result['lyrics']['text'],'纯文本歌词')
                self.assertLessEqual(len(self.calls),3)
                if extension=='m4a':self.assertTrue(any(int(call[2].split('=')[1].split('-')[0])>=2*BLOCK for call in self.calls))

    def test_budget_failure_preserves_available_tags(self):
        with tempfile.TemporaryDirectory() as folder:
            target=self.make_audio(folder,'mp3');self.data=target.read_bytes()+b'\x00'*(2*BLOCK)
            with self.source(len(self.data)) as source:
                source.max_requests=1
                result=extract(source,'ffprobe','ffmpeg',1024**3)
            self.assertEqual(result['tags']['title'],'内嵌歌名')
            self.assertIn(result['state'],('COMPLETE','PARTIAL'))

    def test_mkv_cover_attachment_and_embedded_lyrics(self):
        with tempfile.TemporaryDirectory() as folder:
            image=Path(folder)/'cover.png';image.write_bytes(PNG)
            target=Path(folder)/'test.mkv'
            subprocess.run(['ffmpeg','-v','error','-f','lavfi','-i','anullsrc=r=44100:cl=stereo','-t','1','-metadata','title=视频内嵌','-metadata','LYRICS=[00:00.10]内嵌歌词','-attach',str(image),'-metadata:s:t','mimetype=image/png','-metadata:s:t','filename=cover.png',str(target)],check=True,timeout=20,capture_output=True)
            self.data=target.read_bytes()
            with self.source(len(self.data),target.name) as source:result=extract(source,'ffprobe','ffmpeg',1024**3)
            self.assertEqual(result['tags']['title'],'视频内嵌')
            self.assertEqual(result['lyrics']['timing'],'lrc')
            self.assertEqual(base64.b64decode(result['cover']),PNG)

    def test_oversized_embedded_fields_are_unknown_not_absent(self):
        from mutagen.id3 import ID3,APIC,USLT
        with tempfile.TemporaryDirectory() as folder:
            target=self.make_audio(folder,'mp3');tags=ID3(target)
            tags.add(APIC(mime='image/png',type=3,data=PNG+b'\x00'*2_000_001))
            tags.add(USLT(encoding=3,lang='zho',text='词'*100000));tags.save(target)
            self.data=target.read_bytes()
            with self.source(len(self.data)) as source:result=extract(source,'ffprobe','ffmpeg',1024**3)
            self.assertEqual(result['fields']['cover'],'UNKNOWN')
            self.assertEqual(result['fields']['lyrics'],'UNKNOWN')
            self.assertIsNone(result['cover']);self.assertIsNone(result['lyrics'])
            self.assertEqual(result['state'],'PARTIAL')

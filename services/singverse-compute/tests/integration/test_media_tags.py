"""Real authenticated callback metadata extraction without compute media mounts."""
import json
from pathlib import Path
import socket
import subprocess
import tempfile
import threading
import time
import unittest
from urllib.error import HTTPError
from urllib.request import Request, urlopen
import uvicorn
from singverse_compute.bootstrap import create_app
from singverse_compute.core.config import Settings
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from callback_source import CallbackSource

class TagServiceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(); cls.root = Path(cls.temp.name)
        cls.token = 'isolated-' + 'a'*40
        secret = cls.root/'token'; secret.write_text(cls.token)
        media = cls.root/'business'; media.mkdir()
        cls.media = media/'wrong-name.mkv'
        subprocess.run(['ffmpeg','-v','error','-f','lavfi','-i','sine=duration=0.1',
                        '-metadata','title=视频标签','-metadata','artist=文件歌手',str(cls.media)], check=True)
        cls.callback = CallbackSource(media, cls.token)
        cls.app = create_app(Settings(token_file=secret))
        cls.socket = socket.socket(); cls.socket.bind(('127.0.0.1',0))
        cls.base = f'http://127.0.0.1:{cls.socket.getsockname()[1]}'
        cls.server = uvicorn.Server(uvicorn.Config(cls.app, log_level='error'))
        cls.thread = threading.Thread(target=cls.server.run, kwargs={'sockets':[cls.socket]}, daemon=True); cls.thread.start()
        for _ in range(100):
            if cls.server.started: return
            time.sleep(.02)
        raise RuntimeError('compute did not start')
    @classmethod
    def tearDownClass(cls):
        cls.server.should_exit=True; cls.thread.join(5); cls.socket.close(); cls.callback.close(); cls.temp.cleanup()
    def payload(self):
        return {**self.callback.grant(self.media), 'filename':self.media.name, 'size':self.media.stat().st_size}
    def request(self, payload, authorized=True):
        headers = {'Content-Type':'application/json'}
        if authorized: headers['Authorization']='Bearer '+self.token
        return urlopen(Request(self.base+'/internal/v1/media/remote-tags', data=json.dumps(payload).encode(),headers=headers),timeout=10)
    def test_authenticated_read_and_capability_without_media_roots(self):
        with self.request(self.payload()) as response:
            result=json.load(response)
            self.assertEqual(result['tags']['title'],'视频标签')
            self.assertEqual(result['tags']['artists'],['文件歌手'])
            self.assertTrue(result['technical']['streams'])
        with urlopen(self.base+'/internal/v1/capabilities') as response:
            self.assertEqual(json.load(response)['capabilities'],['media-remote-tags'])
    def test_auth_and_no_paths_or_arbitrary_media_urls(self):
        for payload, auth, status in [(self.payload(),False,401),
                ({**self.payload(),'path':str(self.media)},True,422),
                ({**self.payload(),'url':'http://example.invalid/private'},True,422),
                ({k:v for k,v in self.payload().items() if k!='callbackOrigin'},True,422)]:
            with self.subTest(status=status), self.assertRaises(HTTPError) as raised:
                self.request(payload,auth)
            self.assertEqual(raised.exception.code,status); raised.exception.close()
        with self.assertRaises(HTTPError) as raised:
            urlopen(Request(self.base+'/internal/v1/media/tags',data=json.dumps({'path':str(self.media)}).encode(),
                            headers={'Authorization':'Bearer '+self.token,'Content-Type':'application/json'}))
        self.assertEqual(raised.exception.code,404); raised.exception.close()
    def test_busy_reader_and_safe_validation(self):
        slots = self.app.state.remote_tag_slots
        slots.acquire()
        try:
            with self.assertRaises(HTTPError) as raised: self.request(self.payload())
            self.assertEqual(raised.exception.code,429); raised.exception.close()
        finally: slots.release()
        with self.assertRaises(HTTPError) as raised: self.request({'callbackOrigin':'secret-value','reference':'private-path'})
        self.assertEqual(raised.exception.code,422)
        self.assertNotIn('secret-value',raised.exception.read().decode()); raised.exception.close()
    def test_authenticated_status_identity(self):
        with urlopen(Request(self.base+'/internal/v1/status',headers={'Authorization':'Bearer '+self.token})) as response:
            value=json.load(response)
            self.assertEqual(value['service'],'singverse-compute')
            self.assertIn('media-remote-tags',value['capabilities'])

"""Real authenticated HTTP to the compute-owned FFmpeg worker."""
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import Request, urlopen
import uvicorn
from singverse_compute.bootstrap import create_app

class TagServiceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.root = Path(cls.temp.name)
        cls.secret = cls.root / 'token'; cls.secret.write_text('isolated-' + 'a' * 40)
        cls.env = patch.dict(os.environ, {'COMPUTE_MEDIA_ROOTS': str(cls.root / 'media'), 'COMPUTE_AUTH_TOKEN_FILE': str(cls.secret)})
        cls.env.start(); (cls.root / 'media').mkdir()
        cls.media = cls.root / 'media' / 'wrong-name.mkv'
        subprocess.run(['ffmpeg','-v','error','-f','lavfi','-i','sine=duration=0.1','-metadata','title=视频标签','-metadata','artist=文件歌手',str(cls.media)],check=True)
        cls.socket = socket.socket(); cls.socket.bind(('127.0.0.1',0))
        cls.base = 'http://127.0.0.1:' + str(cls.socket.getsockname()[1])
        cls.app = create_app()
        cls.server = uvicorn.Server(uvicorn.Config(cls.app, log_level='error'))
        cls.thread = threading.Thread(target=cls.server.run, kwargs={'sockets':[cls.socket]}, daemon=True); cls.thread.start()
        for _ in range(100):
            if cls.server.started: return
            time.sleep(.02)
        raise RuntimeError('compute did not start')
    @classmethod
    def tearDownClass(cls):
        cls.server.should_exit=True; cls.thread.join(5); cls.socket.close(); cls.env.stop(); cls.temp.cleanup()
    def request(self, path, authorized=True):
        headers = {'Content-Type':'application/json'}
        if authorized: headers['Authorization']='Bearer ' + self.secret.read_text()
        return urlopen(Request(self.base+'/internal/v1/media/tags',data=json.dumps({'path':str(path)}).encode(),headers=headers),timeout=6)
    def test_authenticated_read_and_capability(self):
        with self.request(self.media) as response:
            body=json.load(response); self.assertEqual(body['title'],'视频标签'); self.assertEqual(body['artists'],['文件歌手'])
        with urlopen(self.base+'/internal/v1/capabilities') as response: self.assertEqual(json.load(response)['capabilities'],['media-tags'])
    def test_unauthorized_and_outside_root(self):
        for path, auth, status in [(self.media,False,401),(self.secret,True,403),('../token',True,403)]:
            with self.subTest(status=status), self.assertRaises(HTTPError) as raised: self.request(path,auth)
            self.assertEqual(raised.exception.code,status); raised.exception.close()
    def test_symlink_and_busy(self):
        link=self.root/'media'/'alias.mkv'; link.symlink_to(self.secret)
        with self.assertRaises(HTTPError) as raised: self.request(link)
        self.assertEqual(raised.exception.code,403); raised.exception.close()
        slots = self.app.state.media_tags.slots
        slots.acquire(); slots.acquire()
        try:
            with self.assertRaises(HTTPError) as raised: self.request(self.media)
            self.assertEqual(raised.exception.code,429); raised.exception.close()
        finally: slots.release(); slots.release()
    def test_large_body_rejected(self):
        headers={'Content-Type':'application/json','Authorization':'Bearer '+self.secret.read_text()}
        with self.assertRaises(HTTPError) as raised:
            urlopen(Request(self.base+'/internal/v1/media/tags', data=b'x'*9000,headers=headers),timeout=2)
        self.assertEqual(raised.exception.code,413); raised.exception.close()

    def test_validation_errors_do_not_echo_sensitive_path(self):
        headers={'Content-Type':'application/json','Authorization':'Bearer '+self.secret.read_text()}
        with self.assertRaises(HTTPError) as raised:
            urlopen(Request(self.base+'/internal/v1/media/tags',data=json.dumps({'path':'sensitive-fixture'*400}).encode(),headers=headers),timeout=2)
        self.assertEqual(raised.exception.code,422)
        body=raised.exception.read().decode(); self.assertNotIn('sensitive-fixture',body)
        self.assertEqual(raised.exception.headers.get_content_type(),'application/problem+json'); raised.exception.close()

    def test_authenticated_status_checks_identity_without_media_execution(self):
        with self.assertRaises(HTTPError) as raised:
            urlopen(self.base+'/internal/v1/status',timeout=2)
        self.assertEqual(raised.exception.code,401); raised.exception.close()
        with urlopen(Request(self.base+'/internal/v1/status',headers={'Authorization':'Bearer '+self.secret.read_text()}),timeout=2) as response:
            body=json.load(response)
            self.assertEqual(body,{'service':'singverse-compute','apiVersion':'1','capabilities':['media-tags']})
            self.assertNotIn(self.secret.read_text(),json.dumps(body))

    def test_remote_tags_use_only_authenticated_java_task_reference(self):
        from dataclasses import replace
        from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
        media=self.media.read_bytes()
        secret=self.secret.read_text()
        calls=[]
        class Reader(BaseHTTPRequestHandler):
            def log_message(self,*args):pass
            def do_GET(self):
                calls.append((self.path,self.headers.get('Authorization')))
                start,end=self.headers['Range'].split('=')[1].split('-')
                start,end=int(start),min(int(end),len(media)-1)
                self.send_response(206);self.send_header('Content-Range',f'bytes {start}-{end}/{len(media)}');self.send_header('Content-Length',str(end-start+1));self.end_headers();self.wfile.write(media[start:end+1])
        callback=ThreadingHTTPServer(('127.0.0.1',0),Reader)
        thread=threading.Thread(target=callback.serve_forever,daemon=True);thread.start()
        previous=self.app.state.settings
        self.app.state.settings=replace(previous,java_origin=f'http://127.0.0.1:{callback.server_port}')
        try:
            payload={'reference':'11111111-1111-1111-1111-111111111111','filename':'wrong-name.mkv','size':len(media),'deep':False}
            def request(body,authorized=True):
                headers={'Content-Type':'application/json'}
                if authorized:headers['Authorization']='Bearer '+secret
                return urlopen(Request(self.base+'/internal/v1/media/remote-tags',data=json.dumps(body).encode(),headers=headers),timeout=10)
            with self.assertRaises(HTTPError) as error:request(payload,False)
            self.assertEqual(error.exception.code,401);error.exception.close()
            with self.assertRaises(HTTPError) as error:request({**payload,'url':'http://example.invalid/private'})
            self.assertEqual(error.exception.code,422);error.exception.close()
            self.assertEqual(calls,[])
            with request(payload) as response:result=json.load(response)
            self.assertEqual(result['tags']['title'],'视频标签')
            self.assertEqual(result['tags']['artists'],['文件歌手'])
            self.assertTrue(result['technical']['streams'])
            self.assertEqual(calls,[('/api/v1/internal/metadata-input/'+payload['reference'],'Bearer '+secret)])
        finally:
            self.app.state.settings=previous
            callback.shutdown();callback.server_close();thread.join(timeout=2)

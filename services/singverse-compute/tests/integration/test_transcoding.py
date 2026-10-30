"""Real media, HTTP and FFmpeg: isolation, HLS renditions and verified offline outputs."""
from dataclasses import replace
import hashlib
import json
from pathlib import Path
import socket
import subprocess
import tempfile
import threading
import time
import unittest
import uuid
from urllib.request import Request, urlopen
from urllib.error import HTTPError
import uvicorn
from singverse_compute.bootstrap import create_app
from singverse_compute.core.config import Settings

class TranscodingHttpTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.root = Path(cls.temp.name)
        (cls.root/'media').mkdir()
        token = cls.root/'token'
        token.write_text('t'*64)
        cls.settings = Settings((cls.root/'media',), token, transcode_root=cls.root/'cache', minimum_free_bytes=0,
                                hardware_backend='software')
        cls.source = cls.root/'media'/'测试.mkv'
        subprocess.run(['ffmpeg','-v','error','-f','lavfi','-i','testsrc2=s=160x90:r=25:d=2',
                        '-f','lavfi','-i','sine=frequency=400:duration=2', '-f','lavfi','-i','sine=frequency=800:duration=2',
                        '-map','0:v','-map','1:a','-map','2:a','-c:v','mpeg4','-c:a','pcm_s16le',
                        '-metadata:s:a:0','title=原唱','-metadata:s:a:1','title=伴奏',str(cls.source)],check=True)
        cls.digest = hashlib.sha256(cls.source.read_bytes()).digest()
        cls.socket = socket.socket(); cls.socket.bind(('127.0.0.1',0))
        cls.base = f'http://127.0.0.1:{cls.socket.getsockname()[1]}/internal/v1/transcoding'
        cls.app = create_app(cls.settings)
        cls.server = uvicorn.Server(uvicorn.Config(cls.app,log_level='error'))
        cls.thread = threading.Thread(target=cls.server.run,kwargs={'sockets':[cls.socket]},daemon=True);cls.thread.start()
        for _ in range(100):
            if cls.server.started:return
            time.sleep(.02)
        raise RuntimeError('HTTP server failed to start')
    @classmethod
    def tearDownClass(cls):
        cls.server.should_exit=True;cls.thread.join(10);cls.socket.close();cls.temp.cleanup()
    def request(self,path='',body=None,method=None,auth=True):
        headers={'Content-Type':'application/json'}
        if auth:headers['Authorization']='Bearer '+'t'*64
        with urlopen(Request(self.base+path,data=json.dumps(body).encode() if body is not None else None,
                             method=method,headers=headers),timeout=30) as response:
            return response.status, response.read()
    def wait(self,identifier):
        for _ in range(200):
            result=json.loads(self.request('/executions/'+identifier)[1])
            if result['state'] not in ('RUNNING','ACCEPTED'):return result
            time.sleep(.05)
        self.fail('execution timed out')
    def create(self,purpose='REALTIME',**options):
        identifier=str(uuid.uuid4())
        body={'id':identifier,'path':str(self.source),'fingerprint':'isolated','target':{'purpose':purpose,**options}}
        self.request('/executions',body)
        return identifier,body
    def test_auth_bounds_and_probe(self):
        for path,body,code,auth in [('/probe',{'path':str(self.source)},401,False),
                                    ('/probe',{'path':str(self.root/'token')},403,True),
                                    ('/probe',{'path':str(self.source),'command':'arbitrary'},422,True)]:
            with self.subTest(code=code), self.assertRaises(HTTPError) as caught:self.request(path,body,auth=auth)
            self.assertEqual(caught.exception.code,code);caught.exception.close()
        result=json.loads(self.request('/probe',{'path':str(self.source)})[1])
        self.assertEqual([s['id'] for s in result['streams']],['stream:0','stream:1','stream:2'])
        negotiated=json.loads(self.request('/negotiate',{'path':str(self.source)})[1])
        self.assertEqual(negotiated['mode'],'TRANSCODE')
    def test_android_mobile_client_negotiation(self):
        client = {'family':'android', 'containers':['mp4','mkv','webm','ts','mp3','flac','ogg','wav'],
                  'video':['h264'], 'audio':['aac','mp3','flac','opus'], 'hls':True, 'fmp4':True,
                  'max_width':1920, 'max_height':1080, 'max_fps':30, 'max_bitrate':8000000,
                  'max_channels':2, 'max_bit_depth':8, 'hdr':False,
                  'profiles':{'h264':['Constrained Baseline','Baseline','Main','High']}, 'levels':{'h264':41}}
        body = {'path':str(self.source), 'client':client}
        status, response = self.request('/negotiate', body)
        self.assertEqual(status, 200)
        result = json.loads(response)
        self.assertEqual(result['mode'], 'TRANSCODE')
        self.assertEqual(len(result['media']['streams']), 3)
        with self.assertRaises(HTTPError) as caught:
            self.request('/negotiate', {**body, 'client':{**client, 'family':'android-mobile'}})
        self.assertEqual(caught.exception.code, 422)
        caught.exception.close()

    def test_hls_and_offline_multi_audio_readonly(self):
        for purpose in ['REALTIME','OFFLINE']:
            identifier,body=self.create(purpose)
            result=self.wait(identifier)
            self.assertEqual(result['state'],'SUCCEEDED',result)
            self.assertEqual([s['sourceId'] for s in result['trackMapping']],['stream:0','stream:1','stream:2'])
            self.assertEqual(json.loads(self.request('/executions',body)[1])['id'],identifier)
            if purpose=='REALTIME':
                master=self.request('/executions/'+identifier+'/artifacts/master.m3u8')[1].decode()
                self.assertIn('EXT-X-MEDIA',master)
                self.assertIn('audio0',master)
                for name in ['stream_0.m3u8','stream_audio0.m3u8','stream_audio1.m3u8']:
                    self.assertIn('#EXT-X-ENDLIST',self.request('/executions/'+identifier+'/artifacts/'+name)[1].decode())
            else:
                output=self.app.state.transcoding.probe.read(self.root/'cache'/identifier/'output.mp4')
                self.assertEqual(sum(s.kind=='audio' for s in output.streams),2)
                self.assertTrue(all(s.codec=='aac' for s in output.streams if s.kind=='audio'))
                restarted=create_app(self.settings)
                self.assertEqual(restarted.state.transcoding.view(identifier)['state'],'SUCCEEDED')
                restarted.state.transcoding.close()
            self.assertEqual(hashlib.sha256(self.source.read_bytes()).digest(),self.digest)
    def test_single_audio_fmp4_has_resolvable_initialization_uri(self):
        audio=self.root/'media'/'single-audio.wav'
        subprocess.run(['ffmpeg','-v','error','-y','-f','lavfi','-i','sine=duration=1',str(audio)],check=True)
        identifier=str(uuid.uuid4())
        self.request('/executions',{'id':identifier,'path':str(audio),'fingerprint':'single-fmp4', 'target':{'fmp4':True}})
        self.assertEqual(self.wait(identifier)['state'],'SUCCEEDED')
        playlist=self.request('/executions/'+identifier+'/artifacts/stream_audio0.m3u8')[1].decode()
        self.assertIn('URI="init_0.mp4"',playlist)
        self.assertNotIn('%v',playlist)
        status,data=self.request('/executions/'+identifier+'/artifacts/init_0.mp4')
        self.assertEqual(status,200)
        self.assertGreater(len(data),0)

    def test_idempotency_and_fmp4(self):
        identifier,body=self.create(fmp4=True)
        result=self.wait(identifier)
        self.assertEqual(result['state'],'SUCCEEDED',result)
        self.assertIn('#EXT-X-MAP',self.request('/executions/'+identifier+'/artifacts/stream_0.m3u8')[1].decode())
        body['fingerprint']='changed'
        with self.assertRaises(HTTPError) as caught:self.request('/executions',body)
        self.assertEqual(caught.exception.code,409);caught.exception.close()
        with self.assertRaises(HTTPError) as caught:self.request('/executions/'+identifier+'/artifacts/execution.json')
        self.assertEqual(caught.exception.code,404);caught.exception.close()
    def test_remux_audio_only_and_seeked_text_subtitles(self):
        audio=self.root/'media'/'audio.flac'
        subprocess.run(['ffmpeg','-v','error','-y','-f','lavfi','-i','sine=duration=1',str(audio)],check=True)
        identifier=str(uuid.uuid4())
        self.request('/executions',{'id':identifier,'path':str(audio),'fingerprint':'audio', 'target':{}})
        self.assertEqual(self.wait(identifier)['state'],'SUCCEEDED')
        self.assertIn('#EXTM3U',self.request('/executions/'+identifier+'/artifacts/master.m3u8')[1].decode())
        subtitle=self.root/'media'/'text.srt'
        subtitle.write_text('1\n00:00:01,000 --> 00:00:01,900\n测试歌词\n',encoding='utf-8')
        video=self.root/'media'/'with-text.mkv'
        subprocess.run(['ffmpeg','-v','error','-y','-i',str(self.source),'-i',str(subtitle),'-map','0','-map','1',
                        '-c:v','libx264','-c:a','aac','-c:s','srt',str(video)],check=True)
        negotiated=json.loads(self.request('/negotiate',{'path':str(video),'client':{'containers':['mp4']}})[1])
        self.assertEqual(negotiated['mode'],'REMUX')
        for burn in [False,True]:
            identifier=str(uuid.uuid4())
            self.request('/executions',{'id':identifier,'path':str(video),'fingerprint':'subtitle',
                         'target':{'mode':'TRANSCODE' if burn else 'REMUX','start':.5,'subtitle_id':'stream:3','burn_subtitle':burn}})
            result=self.wait(identifier)
            self.assertEqual(result['state'],'SUCCEEDED',result)
            if not burn:
                text=self.request('/executions/'+identifier+'/artifacts/subtitle.vtt')[1].decode()
                self.assertIn('WEBVTT',text);self.assertIn('测试歌词',text)
                self.assertIn('00:00.500',text)
        identifier=str(uuid.uuid4())
        self.request('/executions',{'id':identifier,'path':str(video),'fingerprint':'remux','target':{'mode':'REMUX'}})
        self.assertEqual(self.wait(identifier)['state'],'SUCCEEDED')
        self.assertIn('#EXT-X-ENDLIST',self.request('/executions/'+identifier+'/artifacts/stream_0.m3u8')[1].decode())

    def test_budget_and_acknowledgement(self):
        service=self.app.state.transcoding
        identifier,_=self.create('OFFLINE')
        result=self.wait(identifier)
        self.assertRegex(result['output']['sha256'],r'^[a-f0-9]{64}$')
        self.assertEqual(result['output']['size'],(self.root/'cache'/identifier/'output.mp4').stat().st_size)
        self.request('/executions/'+identifier+'/acknowledge',{},method='POST')
        self.assertFalse((self.root/'cache'/identifier/'output.mp4').exists())
        self.assertTrue((self.root/'cache'/identifier/'execution.json').exists())
        self.assertEqual(self.wait(identifier)['state'],'SUCCEEDED')
        previous=service.settings
        service.settings=replace(previous,cache_bytes=1)
        try:
            with self.assertRaises(HTTPError) as caught:self.create()
            self.assertEqual(caught.exception.code,507);caught.exception.close()
        finally:service.settings=previous

    def test_hdr_to_sdr_and_stereo_channels_are_preserved(self):
        hdr=self.root/'media'/'hdr.mkv'
        subprocess.run(['ffmpeg','-v','error','-y','-f','lavfi','-i','testsrc2=s=160x90:r=25:d=1',
            '-vf','format=yuv420p10le','-c:v','libx265','-x265-params','log-level=error:pools=1:frame-threads=1:colorprim=9:transfer=16:colormatrix=9',
            str(hdr)],check=True)
        self.assertEqual(self.app.state.transcoding.probe.read(hdr).streams[0].hdr,'HDR10')
        identifier=str(uuid.uuid4())
        self.request('/executions',{'id':identifier,'path':str(hdr),'fingerprint':'hdr','target':{'purpose':'OFFLINE'}})
        result=self.wait(identifier);self.assertEqual(result['state'],'SUCCEEDED',result)
        converted=self.app.state.transcoding.probe.read(self.root/'cache'/identifier/'output.mp4').streams[0]
        self.assertEqual(converted.hdr,'');self.assertEqual(converted.bit_depth,8)
        self.assertEqual((converted.width,converted.height),(160,90))
        stereo=self.root/'media'/'stereo.mkv'
        subprocess.run(['ffmpeg','-v','error','-y','-f','lavfi','-i','aevalsrc=sin(2*PI*400*t)|sin(2*PI*800*t):s=48000:d=1',
                        '-c:a','pcm_s16le',str(stereo)],check=True)
        identifier=str(uuid.uuid4())
        self.request('/executions',{'id':identifier,'path':str(stereo),'fingerprint':'stereo','target':{'purpose':'OFFLINE'}})
        result=self.wait(identifier);self.assertEqual(result['state'],'SUCCEEDED',result)
        import array
        import math
        output=subprocess.check_output(['ffmpeg','-v','error','-i',str(self.root/'cache'/identifier/'output.mp4'),
                                        '-f','s16le','-acodec','pcm_s16le','-ar','48000','pipe:1'])
        samples=array.array('h',output)
        left,right=samples[4800:14400:2],samples[4801:14401:2]
        def energy(values,frequency):
            real=sum(v*math.cos(2*math.pi*frequency*i/48000) for i,v in enumerate(values))
            imaginary=sum(v*math.sin(2*math.pi*frequency*i/48000) for i,v in enumerate(values))
            return real*real+imaginary*imaginary
        self.assertGreater(energy(left,400),energy(left,800)*100)
        self.assertGreater(energy(right,800),energy(right,400)*100)

    def test_hardware_settings_authenticated_persistent_and_revision_safe(self):
        with self.assertRaises(HTTPError) as caught:self.request('/hardware-settings',auth=False)
        self.assertEqual(caught.exception.code,401);caught.exception.close()
        current=json.loads(self.request('/hardware-settings')[1])
        tested=json.loads(self.request('/hardware-settings/test',{'backend':'software'})[1])
        self.assertTrue(tested['passed']);self.assertEqual(tested['backend'],'software')
        self.assertEqual(json.loads(self.request('/hardware-settings')[1]),current)
        saved=json.loads(self.request('/hardware-settings',{'backend':'software','revision':current['revision']})[1])
        self.assertEqual(saved['effectiveBackend'],'software')
        restarted=create_app(replace(self.settings, hardware_backend='auto'))
        restored_settings=restarted.state.hardware_settings.read()
        self.assertEqual(restored_settings['backend'],'software')
        self.assertEqual(restored_settings['effectiveBackend'],'software')
        self.assertEqual(restored_settings['deploymentBackend'],'auto')
        self.assertEqual(restored_settings['revision'],saved['revision'])
        restarted.state.transcoding.close()
        with self.assertRaises(HTTPError) as caught:self.request('/hardware-settings',{'backend':'auto','revision':current['revision']})
        self.assertEqual(caught.exception.code,409);caught.exception.close()
        with self.assertRaises(HTTPError) as caught:self.request('/hardware-settings',{'backend':'arbitrary','revision':saved['revision']})
        self.assertEqual(caught.exception.code,422);caught.exception.close()
        import platform
        unsupported='videotoolbox' if platform.system() == 'Windows' else 'amf'
        with self.assertRaises(HTTPError) as caught:self.request('/hardware-settings',{'backend':unsupported,'revision':saved['revision']})
        self.assertEqual(caught.exception.code,422);caught.exception.close()
        self.assertEqual(json.loads(self.request('/hardware-settings')[1]),saved)
        restored=json.loads(self.request('/hardware-settings',{'backend':'deployment','revision':saved['revision']})[1])
        self.assertEqual(restored['effectiveBackend'],self.settings.hardware_backend)
        self.assertEqual(restored['backend'],'deployment')

    def test_actual_pgs_and_dvd_bitmap_burn_in_appears_and_disappears_on_time(self):
        import struct
        def segment(kind,payload,pts):
            return b'PG'+struct.pack('>IIBH',pts,0,kind,len(payload))+payload
        def composition(number,count):
            base=struct.pack('>HHBHBBBB',256,144,0x10,number,0x80 if number==0 else 0,0,0,count)
            return base+(struct.pack('>HBBHH',0,0,0,64,100) if count else b'')
        palette=bytes([0,0,0,16,128,128,0,1,235,128,128,255])
        rle=(bytes([1])*64+b'\x00\x00')*16
        obj=struct.pack('>HBB',0,0,0xc0)+(len(rle)+4).to_bytes(3,'big')+struct.pack('>HH',64,16)+rle
        window=bytes([1,0])+struct.pack('>HHHH',64,100,64,16)
        sup=self.root/'media'/'fixture.sup'
        sup.write_bytes(segment(0x16,composition(0,1),90000)+segment(0x17,window,90000)+segment(0x14,palette,90000)+segment(0x15,obj,90000)+segment(0x80,b'',90000)+segment(0x16,composition(1,0),180000)+segment(0x80,b'',180000))
        black=self.root/'media'/'black.mp4';source=self.root/'media'/'bitmap.mkv'
        subprocess.run(['ffmpeg','-v','error','-y','-f','lavfi','-i','color=black:s=256x144:r=25:d=3','-c:v','libx264',str(black)],check=True)
        subprocess.run(['ffmpeg','-v','error','-y','-copyts','-i',str(black),'-f','sup','-i',str(sup),'-map','0:v','-map','1:s','-c','copy',str(source)],check=True)
        dvd=self.root/'media'/'dvd.mkv'
        subprocess.run(['ffmpeg','-v','error','-y','-copyts','-fix_sub_duration','-i',str(source),
                        '-map','0','-c:v','copy','-c:s','dvdsub',str(dvd)],check=True)
        for codec,source in (('hdmv_pgs_subtitle',source),('dvd_subtitle',dvd)):
            with self.subTest(codec=codec):
                actual=json.loads(subprocess.check_output(['ffprobe','-v','error','-select_streams','s','-show_entries','stream=codec_name','-of','json',str(source)]))
                self.assertEqual(actual['streams'][0]['codec_name'],codec)

                identifier=str(uuid.uuid4())
                self.request('/executions',{'id':identifier,'path':str(source),'fingerprint':'pgs',
                            'target':{'purpose':'OFFLINE','width':128,'height':72,'subtitle_id':'stream:1','burn_subtitle':True}})
                result=self.wait(identifier);self.assertEqual(result['state'],'SUCCEEDED',result)
                output=self.root/'cache'/identifier/'output.mp4'
                counts=[]
                for position in ('0.3','1.4','2.6'):
                    frame=subprocess.check_output(['ffmpeg','-v','error','-ss',position,'-i',str(output),'-frames:v','1','-pix_fmt','gray','-f','rawvideo','pipe:1'])
                    counts.append(sum(pixel>180 for pixel in frame))
                self.assertLess(counts[0],10)
                self.assertGreater(counts[1],150)
                self.assertLess(counts[2],10)

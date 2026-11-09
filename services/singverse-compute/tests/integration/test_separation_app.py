"""Opt-in isolated Go/compute roundtrip; DSP requires no model or GPU."""
import hashlib
from http.cookiejar import CookieJar
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from urllib.request import Request, build_opener, HTTPCookieProcessor, urlopen
from urllib.error import HTTPError
from integration.test_macos_app import free_port


@unittest.skipUnless(os.environ.get('SINGVERSE_TEST_SEPARATION_APP_BINARY'), 'Explicit native Go binary required for separation roundtrip')
class SeparationApplicationTest(unittest.TestCase):
    def test_preview_batch_flac_activation_and_optional_clean_cpu(self):
        root=Path(__file__).resolve().parents[4]
        binary=Path(os.environ['SINGVERSE_TEST_SEPARATION_APP_BINARY']).resolve(strict=True)
        self.assertTrue(binary.is_file() and os.access(binary,os.X_OK),'Build a native Go backend binary first')
        with tempfile.TemporaryDirectory() as temporary:
            work=Path(temporary);media=work/'media';media.mkdir()
            source=media/'Synthetic song.flac'
            subprocess.run(['ffmpeg','-v','error','-f','lavfi','-i','aevalsrc=.2*sin(440*2*PI*t)+.1*sin(880*2*PI*t)|.2*sin(440*2*PI*t)-.1*sin(880*2*PI*t):s=44100:d=16',str(source)],check=True)
            original=hashlib.sha256(source.read_bytes()).hexdigest()
            token=work/'token';token.write_text('b'*64);token.chmod(0o600)
            compute_port,backend_port=free_port(),free_port();origin=f'http://127.0.0.1:{backend_port}'
            environment={**os.environ,'COMPUTE_AUTH_TOKEN_FILE':str(token),'COMPUTE_LAN_PAIRING_ENABLED':'false',
                'COMPUTE_AUDIO_MODEL_REPO':os.environ.get('SINGVERSE_TEST_SEPARATION_MODEL_REPO',''),
                'COMPUTE_TRANSCODE_ROOT':str(work/'cache'),'COMPUTE_MINIMUM_FREE_BYTES':'0',
                'COMPUTE_NODE_URL':f'http://127.0.0.1:{compute_port}','SERVER_PORT':str(backend_port),
                'SINGVERSE_DB_DRIVER':'sqlite','SINGVERSE_BIND_ADDRESS':'127.0.0.1','SQLITE_PATH':str(work/'app.db'),
                'SINGVERSE_CALLBACK_ORIGIN':origin,'SINGVERSE_COMPUTE_AUTO_CALLBACK_ORIGIN':'','SINGVERSE_PUBLIC_ORIGIN':'',
                'SINGVERSE_TRUSTED_PROXIES':'','SINGVERSE_DIAGNOSTICS_ADDRESS':'','SINGVERSE_TV_DISCOVERY_ADDRESS':'','SINGVERSE_TV_RELEASE_DIRECTORY':'',
                'SINGVERSE_MIGRATIONS_DIR':str(root/'database/migrations'),'SINGVERSE_AUTH_KEY_FILE':str(work/'master.key'),
                'AUDIO_DERIVED_PATH':str(work/'derived'),'TRANSCODE_VERSION_PATH':str(work/'versions'),
                'SINGVERSE_SONG_CACHE_PATH':str(work/'song-cache'),'SINGVERSE_SONG_CACHE_MINIMUM_FREE_BYTES':'0',
                'METADATA_CACHE_PATH':str(work/'covers'),'WEBDAV_METADATA_PATH':str(work/'metadata'),'DBMATE_BIN':str(root/'.tools/dbmate')}
            processes=[]
            with (work/'compute.log').open('wb') as compute_log,(work/'go.log').open('wb') as backend_log:
                try:
                    processes.append(subprocess.Popen([sys.executable,'-m','singverse_compute','--port',str(compute_port)],cwd=root/'services/singverse-compute',env=environment,stdout=compute_log,stderr=subprocess.STDOUT))
                    processes.append(subprocess.Popen([str(binary)],cwd=root,env=environment,stdout=backend_log,stderr=subprocess.STDOUT))
                    opener=build_opener(HTTPCookieProcessor(CookieJar()))
                    def request(path,method='GET',body=None,binary=False):
                        headers={}
                        if method!='GET':headers['X-CSRF-Token']=request('/api/v1/auth/csrf')['token']
                        if body is not None:headers['Content-Type']='application/json'
                        with opener.open(Request(origin+path,headers=headers,method=method,data=json.dumps(body).encode() if body is not None else None),timeout=45) as response:
                            data=response.read();return data if binary else json.loads(data) if data else None
                    def until(read,done,seconds=90):
                        deadline=time.monotonic()+seconds
                        while time.monotonic()<deadline:
                            value=read()
                            if done(value):return value
                            time.sleep(.2)
                        self.fail('Application state did not converge')
                    for _ in range(240):
                        self.assertTrue(all(p.poll() is None for p in processes),'Isolated service exited')
                        try:
                            if request('/api/health')['status']=='UP':
                                with urlopen(f'http://127.0.0.1:{compute_port}/health',timeout=3) as ready:
                                    if json.load(ready)['status']=='UP':break
                        except OSError:time.sleep(.25)
                    else:self.fail('Services did not become ready')
                    request('/api/v1/auth/login','POST',{'username':'admin','password':'admin'})
                    node=request('/api/v1/audio-processing/node')
                    self.assertTrue(next(p['ready'] for p in node['separation']['profiles'] if p['id']=='DSP_FAST'))
                    created=request('/api/v1/media-sources','POST',{'name':'Separation verification','type':'LOCAL','location':str(media),'parserProfileId':'default'})
                    source_path='/api/v1/media-sources/'+created['id'];request(source_path+'/scan','POST')
                    songs=until(lambda:request(source_path+'/songs')['items'],bool);self.assertEqual(len(songs),1)
                    song=songs[0]['id'];song_path='/api/v1/media-songs/'+song
                    def job(body):
                        started=time.monotonic();queued=request(song_path+'/audio-jobs','POST',{'kind':'SEPARATE',**body})
                        status=until(lambda:request(song_path+'/audio-roles'),lambda s:any(j['id']==queued['id'] and j['state'] in ('SUCCEEDED','FAILED','CANCELLED') for j in s['jobs']))
                        actual=next(j for j in status['jobs'] if j['id']==queued['id']);self.assertEqual(actual['state'],'SUCCEEDED',actual)
                        print(json.dumps({'measurement':'measured-synthetic-go-roundtrip','profile':body,'elapsedMs':round((time.monotonic()-started)*1000,3),'job':actual}),flush=True)
                        return queued['id'],status
                    preview,status=job({'separationProfile':'DSP_FAST','preview':True})
                    self.assertNotIn('backingResource',status)
                    def indexed():
                        try:return request('/api/v1/audio-processing/songs?page=0&size=30')
                        except HTTPError as error:
                            if error.code!=503:raise
                            return None
                    until(indexed,lambda page:page is not None and not page.get('building') and any(item['id']==song for item in page['items']))
                    batch=request('/api/v1/audio-processing/batches/preview','POST',{'ids':[song],'separationProfile':'DSP_FAST','backend':'cpu'})
                    batch_path='/api/v1/audio-processing/batches/'+batch['id']
                    until(lambda:request(batch_path),lambda b:b['state']!='PREPARING')
                    request(batch_path+'/submit','POST')
                    batch=until(lambda:request(batch_path),lambda b:b['state']!='QUEUED')
                    self.assertEqual(batch['counts'].get('SUCCEEDED'),1,batch)
                    status=request(song_path+'/audio-roles');active=status['backingResource']['jobId']
                    export,status=job({'separationProfile':'DSP_FAST','outputFormat':'flac'})
                    self.assertEqual(status['backingResource']['jobId'],active)
                    data=request(song_path+'/accompaniment/'+export,binary=True);self.assertEqual(data[:4],b'fLaC')
                    with self.assertRaises(HTTPError) as rejected:
                        request(song_path+'/audio-jobs','POST',{'kind':'SEPARATE','separationProfile':'DSP_FAST','sourceAudioId':'stream:0:LEFT'})
                    self.assertEqual(rejected.exception.code,400)
                    channel,status=job({'separationProfile':'DSP_FAST','sourceAudioId':'stream:0:LEFT','sourceConfirmed':True})
                    request(song_path+'/accompaniment/'+channel+'/activate','POST')
                    self.assertEqual(request(song_path+'/audio-roles')['backingResource']['jobId'],channel)
                    if environment['COMPUTE_AUDIO_MODEL_REPO']:
                        until(lambda:request('/api/v1/audio-processing/node'),lambda n:any(p['id']=='AI_CLEAN' and p['ready'] for p in n['separation']['profiles']))
                        clean,status=job({'separationProfile':'AI_CLEAN','backend':'cpu'})
                        self.assertEqual(next(j for j in status['jobs'] if j['id']==clean)['backend'],'cpu')
                        recovered,status=job({'separationProfile':'AI_CLEAN','backend':'cuda'})
                        actual=next(j for j in status['jobs'] if j['id']==recovered)
                        self.assertEqual(actual['backend'],'cpu')
                        self.assertEqual(actual['fallbackReason'],'ACCELERATOR_UNAVAILABLE')
                    self.assertEqual(hashlib.sha256(source.read_bytes()).hexdigest(),original)
                except Exception:
                    print((work/'compute.log').read_text(errors='replace')[-8000:],file=sys.stderr)
                    print((work/'go.log').read_text(errors='replace')[-8000:],file=sys.stderr)
                    raise
                finally:
                    for process in reversed(processes):process.terminate()
                    for process in reversed(processes):
                        try:process.wait(timeout=15)
                        except subprocess.TimeoutExpired:process.kill();process.wait(timeout=5)

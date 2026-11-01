"""Opt-in real Java -> native Mac -> MPS -> Java derivative/batch verification."""
import hashlib
from http.cookiejar import CookieJar
import json
import os
from pathlib import Path
import platform
import socket
import subprocess
import sys
import tempfile
import time
import unittest
from urllib.request import Request, build_opener, HTTPCookieProcessor, urlopen


def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


@unittest.skipUnless(os.environ.get('SINGVERSE_TEST_MACOS_APP_JAR'), 'Explicit built Java JAR and local model required')
class MacosAppSmokeTest(unittest.TestCase):
    def test_batch_separation_derivative_and_playback_through_java(self):
        self.assertEqual(platform.system(), 'Darwin')
        root = Path(__file__).resolve().parents[4]
        jar = Path(os.environ['SINGVERSE_TEST_MACOS_APP_JAR']).resolve(strict=True)
        model = Path(os.environ['SINGVERSE_TEST_MPS_MODEL_REPO']).resolve(strict=True)
        java = str(Path(os.environ['JAVA_HOME'])/'bin/java') if os.environ.get('JAVA_HOME') else 'java'
        with tempfile.TemporaryDirectory() as temporary:
            work = Path(temporary)
            media = work/'media'
            media.mkdir()
            originals = {}
            # A multi-segment 60-second sample complements the eight-second qualification.
            for seconds in (8, 60):
                source = media/f'Mac verification-{seconds}.wav'
                subprocess.run(['ffmpeg', '-v', 'error', '-f', 'lavfi', '-i',
                                f'sine=duration={seconds}:frequency=440', '-ac', '2', '-ar', '44100', str(source)], check=True)
                originals[source] = hashlib.sha256(source.read_bytes()).hexdigest()
            token = work/'token'
            token.write_text('a'*64)
            compute_port, java_port = free_port(), free_port()
            origin = f'http://127.0.0.1:{java_port}'
            environment = {**os.environ, 'COMPUTE_AUTH_TOKEN_FILE': str(token),
                           'COMPUTE_MEDIA_ROOTS': str(media), 'COMPUTE_AUDIO_MODEL_REPO': str(model),
                           'COMPUTE_AUDIO_MEMORY_BYTES': str(2*1024**3), 'COMPUTE_TRANSCODE_ROOT': str(work/'cache'),
                           'COMPUTE_JAVA_ORIGIN': origin, 'COMPUTE_MINIMUM_FREE_BYTES': '0',
                           'COMPUTE_NODE_URL': f'http://127.0.0.1:{compute_port}', 'SERVER_PORT': str(java_port),
                           'SPRING_PROFILES_ACTIVE': 'sqlite', 'SQLITE_PATH': str(work/'app.db'),
                           'SINGVERSE_AUTH_KEY_FILE': str(work/'master.key'), 'AUDIO_DERIVED_PATH': str(work/'derived'),
                           'TRANSCODE_VERSION_PATH': str(work/'versions'), 'METADATA_CACHE_PATH': str(work/'covers'),
                           'WEBDAV_METADATA_PATH': str(work/'metadata'), 'DBMATE_BIN': str(root/'.tools/dbmate')}
            processes = []
            with (work/'compute.log').open('wb') as compute_log, (work/'java.log').open('wb') as java_log:
                try:
                    processes.append(subprocess.Popen([sys.executable, '-m', 'singverse_compute', '--port', str(compute_port)],
                        cwd=root/'services/singverse-compute', env=environment, stdout=compute_log, stderr=subprocess.STDOUT))
                    processes.append(subprocess.Popen([java, '-jar', str(jar), '--singverse.classification.worker-enabled=false'],
                        cwd=root/'services/singverse-java-backend', env=environment, stdout=java_log, stderr=subprocess.STDOUT))
                    opener = build_opener(HTTPCookieProcessor(CookieJar()))

                    def request(path, method='GET', body=None, binary=False):
                        headers = {}
                        if method != 'GET':
                            headers['X-CSRF-Token'] = request('/api/v1/auth/csrf')['token']
                        if body is not None:
                            headers['Content-Type'] = 'application/json'
                        with opener.open(Request(origin+path, headers=headers, method=method,
                            data=json.dumps(body).encode() if body is not None else None), timeout=45) as response:
                            data = response.read()
                            return data if binary else json.loads(data) if data else None

                    for _ in range(240):
                        self.assertTrue(all(p.poll() is None for p in processes), 'Isolated service exited before ready')
                        try:
                            if request('/api/health')['status'] == 'UP':
                                break
                        except OSError:
                            time.sleep(.25)
                    else:
                        self.fail('Isolated Mac application did not become healthy')
                    self.assertEqual(request('/api/v1/auth/login', 'POST', {'username': 'admin', 'password': 'admin'})['state'], 'authenticated')
                    request('/api/v1/audio-processing/model/test', 'POST', {'backend': 'mps'})
                    for _ in range(480):
                        node = request('/api/v1/audio-processing/node')
                        if node['operation']['state'] != 'TESTING':
                            break
                        time.sleep(.25)
                    self.assertEqual(node['operation']['state'], 'SUCCEEDED', node['operation'])
                    self.assertEqual(node['selected'], 'mps')
                    source = request('/api/v1/media-sources', 'POST', {'name': 'Isolated Mac verification',
                                     'type': 'LOCAL', 'location': str(media), 'parserProfileId': 'default'})
                    source_path = '/api/v1/media-sources/'+source['id']
                    request(source_path+'/scan', 'POST')
                    songs = request(source_path+'/songs')['items']
                    self.assertEqual(len(songs), 2)
                    batch = request('/api/v1/audio-processing/batches/preview', 'POST',
                                    {'ids': [song['id'] for song in songs], 'backend': 'mps'})
                    self.assertEqual(batch['total'], 2)
                    batch_path = '/api/v1/audio-processing/batches/'+batch['id']
                    request(batch_path+'/submit', 'POST')
                    for _ in range(600):
                        batch = request(batch_path)
                        if batch['state'] not in ('QUEUED', 'RUNNING'):
                            break
                        time.sleep(.5)
                    self.assertEqual(batch['counts'].get('SUCCEEDED'), 2, batch)
                    for song in songs:
                        status = request('/api/v1/media-songs/'+song['id']+'/audio-roles')
                        job = status['backingResource']['jobId']
                        accompaniment = request('/api/v1/media-songs/'+song['id']+'/accompaniment/'+job, binary=True)
                        output = work/(job+'.m4a')
                        output.write_bytes(accompaniment)
                        probe = json.loads(subprocess.check_output(['ffprobe', '-v', 'error', '-show_format', '-show_streams', '-of', 'json', str(output)]))
                        self.assertLess(abs(float(probe['format']['duration'])-float(status['backingResource']['duration'])), .25)
                        self.assertEqual(probe['streams'][0]['channels'], 2)
                        self.assertLess(abs(float(probe['format'].get('start_time', 0))), .025)
                    for path, before in originals.items():
                        self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), before)
                    for item in batch['items']:
                        self.assertEqual(item['execution']['backend'], 'mps')
                        self.assertEqual(item['execution']['pipeline'], 'mps+cpu-stft')
                    # A second snapshot must skip both completed resources.
                    repeated = request('/api/v1/audio-processing/batches/preview', 'POST',
                                       {'ids': [song['id'] for song in songs], 'backend': 'mps'})
                    self.assertEqual(repeated['categories'].get('READY'), 2, repeated)
                    print('Mac Java batch: 8s + 60s MPS, derivatives, timeline, original hashes and duplicate skip passed', flush=True)
                finally:
                    for process in reversed(processes):
                        process.terminate()
                    for process in reversed(processes):
                        try:
                            process.wait(timeout=15)
                        except subprocess.TimeoutExpired:
                            process.kill()
                            process.wait(timeout=5)

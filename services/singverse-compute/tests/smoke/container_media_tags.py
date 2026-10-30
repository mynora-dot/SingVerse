"""Run inside the compute image with --network none and no mounted business volumes."""
import hashlib
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import threading
import time
from urllib.request import Request, urlopen
import uvicorn
from singverse_compute.bootstrap import create_app

with tempfile.TemporaryDirectory() as folder:
    root = Path(folder)
    (root/'media').mkdir()
    os.environ['COMPUTE_MEDIA_ROOTS'] = str(root/'media')
    os.environ['COMPUTE_TRANSCODE_ROOT'] = str(root/'cache')
    os.environ['COMPUTE_AUTH_TOKEN_FILE'] = str(root/'token')
    app = create_app()
    listener = socket.socket(); listener.bind(('127.0.0.1',0))
    base = 'http://127.0.0.1:' + str(listener.getsockname()[1])
    server = uvicorn.Server(uvicorn.Config(app,log_level='error'))
    thread = threading.Thread(target=server.run,kwargs={'sockets':[listener]},daemon=True); thread.start()
    try:
        for _ in range(100):
            if server.started: break
            time.sleep(.02)
        assert server.started
        for suffix in ['flac','mp3','m4a','mp4','mkv']:
            media = root/'media'/('synthetic.'+suffix)
            command=['ffmpeg','-v','error','-f','lavfi','-i','sine=duration=0.1']
            if suffix in ['mp4','mkv']:
                command += ['-f','lavfi','-i','color=c=black:s=16x16:d=0.1','-c:v','mpeg4','-threads','1','-shortest']
            command += ['-metadata','title=容器内测试歌名','-metadata','artist=容器内测试歌手','-metadata','album=测试专辑',str(media)]
            subprocess.run(command,check=True,timeout=10)
            original = hashlib.sha256(media.read_bytes()).digest()
            request=Request(base+'/internal/v1/media/tags',data=json.dumps({'path':str(media)}).encode(),
                            headers={'Content-Type':'application/json','Authorization':'Bearer '+(root/'token').read_text().strip()})
            with urlopen(request,timeout=5) as response:
                result=json.load(response)
                assert result['title']=='容器内测试歌名' and result['artists']==['容器内测试歌手']
                assert response.headers.get('X-Request-ID')
            assert hashlib.sha256(media.read_bytes()).digest()==original
        import uuid
        identifier=str(uuid.uuid4())
        headers={'Content-Type':'application/json','Authorization':'Bearer '+(root/'token').read_text().strip()}
        payload={'id':identifier,'path':str(media),'fingerprint':'container-fixture','target':{'purpose':'OFFLINE'}}
        with urlopen(Request(base+'/internal/v1/transcoding/executions',data=json.dumps(payload).encode(),headers=headers),timeout=30) as response:
            assert response.status==202
        for _ in range(200):
            with urlopen(Request(base+'/internal/v1/transcoding/executions/'+identifier,headers=headers),timeout=5) as response:
                result=json.load(response)
            if result['state'] not in ('ACCEPTED','RUNNING'):break
            time.sleep(.05)
        assert result['state']=='SUCCEEDED',result
        with urlopen(Request(base+'/internal/v1/transcoding/executions/'+identifier+'/artifacts/output.mp4',headers=headers),timeout=5) as response:
            artifact=response.read()
        assert hashlib.sha256(artifact).hexdigest()==result['output']['sha256']
        assert hashlib.sha256(media.read_bytes()).digest()==original
        print('通过：独立计算镜像内真实HTTP标签读取、离线转码、产物SHA-256与文件只读验证')
    finally:
        server.should_exit=True; thread.join(5); listener.close()

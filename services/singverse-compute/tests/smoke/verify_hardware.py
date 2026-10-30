"""Repeatable real-machine harness: `python tests/smoke/verify_hardware.py --backend videotoolbox`."""
import argparse
from dataclasses import replace
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time

sys.path.insert(0,str(Path(__file__).resolve().parents[2]))
from singverse_compute.domain.transcoding import Target
from singverse_compute.infrastructure.transcoding.backends import Backends
from singverse_compute.infrastructure.transcoding.encoder import Encoder
from singverse_compute.infrastructure.transcoding.probe import MediaProbe

parser=argparse.ArgumentParser()
parser.add_argument('--backend',choices=['software','qsv','vaapi','nvenc','amf','videotoolbox','rkmpp'],required=True)
parser.add_argument('--device',default='/dev/dri/renderD128')
parser.add_argument('--fixtures',type=Path)
args=parser.parse_args()
backends=Backends('ffmpeg',args.backend,args.device)
if not backends.test(args.backend)['passed']:
    raise SystemExit('Selected encoder failed the real device test')
probe=MediaProbe('ffprobe');encoder=Encoder('ffmpeg',probe,backends)
with tempfile.TemporaryDirectory() as temporary:
    work=Path(temporary);source=work/'sdr.mp4';hdr=work/'hdr.mkv'
    subprocess.run(['ffmpeg','-v','error','-f','lavfi','-i','testsrc2=s=256x144:r=25:d=2','-c:v','libx264',str(source)],check=True)
    subprocess.run(['ffmpeg','-v','error','-f','lavfi','-i','testsrc2=s=256x144:r=25:d=2','-vf','format=yuv420p10le',
                    '-c:v','libx265','-x265-params','log-level=error:pools=1:frame-threads=1:colorprim=9:transfer=16:colormatrix=9',str(hdr)],check=True)
    samples=[source,hdr]
    if args.fixtures:
        manifest=json.loads((Path(__file__).resolve().parents[2]/'tools/verification-media.lock.json').read_text())
        for sample in manifest['samples']:
            full=args.fixtures/sample['filename']
            with full.open('rb') as stream:assert hashlib.file_digest(stream,'sha256').hexdigest()==sample['sha256']
            cut=work/sample['filename']
            # MP4 needs strict unofficial to retain the DV configuration; silently dropping it invalidates the test.
            subprocess.run(['ffmpeg','-v','error','-i',str(full),'-t','2','-map','0:v:0','-c','copy','-strict','-2',str(cut)],check=True)
            video=probe.read(cut).streams[0]
            assert (video.dv_profile,video.dv_compatibility,video.dv_rpu)==(sample['profile'],sample['compatibility'],True)
            samples.append(cut)
    for number,sample in enumerate(samples):
        media=probe.read(sample);directory=work/str(number);directory.mkdir();reports=[];peaks=[];started=time.monotonic()
        mapping=encoder.execute(sample,False,media,Target(purpose='OFFLINE',width=128,height=72),directory,threading.Event(),
                                lambda key,value:reports.append(value) if key=='pipeline' else peaks.append(value) if key=='memory' else None,args.backend)
        actual=probe.read(directory/'output.mp4').streams[0]
        assert actual.codec=='h264' and actual.bit_depth==8 and not actual.hdr
        assert reports and (not args.backend=='software' or not reports[-1]['hardwareDecode'])
        print(json.dumps({'backend':args.backend,'inputCodec':media.streams[0].codec,'hdr':media.streams[0].hdr,
                          'dvProfile':media.streams[0].dv_profile,'pipeline':reports[-1],
                          'elapsedMs':round((time.monotonic()-started)*1000),'sampledPeakRssBytes':max(peaks) if peaks else None,'gpuMemoryBytes':None,'gpuUtilizationPercent':None,'outputWidth':actual.width,'outputHeight':actual.height}))

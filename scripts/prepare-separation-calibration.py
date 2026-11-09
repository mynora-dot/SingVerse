#!/usr/bin/env python3
"""Make bounded, versioned MDX spectral tiles from a calibration-only manifest."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import numpy as np
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT/'services/singverse-compute'))
from singverse_compute.infrastructure.uvr_audio import CHUNK,TRIM,spectrum


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('manifest',type=Path);parser.add_argument('output',type=Path);args=parser.parse_args()
    args.output.mkdir(parents=True,exist_ok=False);records=[]
    for entry in json.loads(args.manifest.read_text()):
        source=(args.manifest.resolve().parent/entry['path']).resolve(strict=True)
        duration=float(subprocess.check_output(['ffprobe','-v','error','-show_entries','format=duration','-of','csv=p=0',str(source)],text=True))
        with source.open('rb') as stream:digest=hashlib.file_digest(stream,'sha256').hexdigest()
        for fraction in (.15,.4,.7):
            start=max(0,(duration-CHUNK/44100)*fraction)
            raw=subprocess.check_output(['ffmpeg','-nostdin','-v','error','-ss',str(start),'-i',str(source),'-t',str(CHUNK/44100),'-ac','2','-ar','44100','-f','f32le','-'])
            samples=np.frombuffer(raw,dtype='<f4').reshape(-1,2).T
            value=np.zeros((2,CHUNK),dtype='float32');value[:,:min(CHUNK,samples.shape[1])]=samples[:,:CHUNK]
            target=args.output/f'{len(records):06}.npy';np.save(target,spectrum(value),allow_pickle=False)
            records.append({'tile':target.name,'sourceSha256':digest,'start':start,'nfft':4096,'hop':1024,'frames':256,'frequencyBins':2048})
    (args.output/'calibration.json').write_text(json.dumps(records,ensure_ascii=False,indent=2)+'\n')
    print(f'Prepared {len(records)} bounded tiles; keep validation songs separate.')


if __name__=='__main__':main()

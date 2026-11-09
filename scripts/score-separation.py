#!/usr/bin/env python3
"""Aligned SI-SDR against a true backing reference. Reconstruction is not separation quality."""
import argparse
import hashlib
import json
import subprocess
import numpy as np


def read(path):
    result = subprocess.run(['ffmpeg','-nostdin','-v','error','-i',path,'-vn','-ac','2','-ar','44100','-f','f32le','-'], check=True, capture_output=True)
    return np.frombuffer(result.stdout, dtype='<f4').reshape(-1,2).astype('float64')


def main():
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument('reference'); parser.add_argument('estimate'); args=parser.parse_args()
    ref, est = read(args.reference), read(args.estimate)
    if abs(len(ref)-len(est))>441: raise ValueError('Reference timeline mismatch; align the authorized stems before scoring')
    count=min(len(ref),len(est));ref=ref[:count]-ref[:count].mean(0);est=est[:count]-est[:count].mean(0)
    if not np.isfinite(est).all() or np.linalg.norm(ref)<1e-8: raise ValueError('Nonfinite output or silent reference')
    projected=ref*(np.sum(est*ref,0)/np.maximum(np.sum(ref*ref,0),1e-12));noise=est-projected
    scores=10*np.log10(np.maximum(np.sum(projected**2,0),1e-12)/np.maximum(np.sum(noise**2,0),1e-12))
    with open(args.reference,'rb') as stream: digest=hashlib.file_digest(stream,'sha256').hexdigest()
    print(json.dumps({'siSdrDbPerChannel':scores.tolist(),'siSdrDbMean':float(scores.mean()),'referenceSha256':digest,'alignment':'no shift search; <=10ms encoding padding trimmed','listeningApproved':False}))


if __name__=='__main__':main()

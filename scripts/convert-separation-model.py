#!/usr/bin/env python3
"""Offline MDX conversion and engine-cache trial. Candidates are never auto-promoted."""
import argparse
import hashlib
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT/'services/singverse-compute'))
from singverse_compute.domain.audio_separation import MDX_SHA256


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('model',type=Path)
    parser.add_argument('--target',choices=['openvino-fp16','int8','tensorrt','coreml','mlx'],required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--calibration',type=Path,help='Directory of representative float32 spectral tiles [1,4,2048,256]; kept separate from validation')
    args=parser.parse_args()
    with args.model.open('rb') as stream:
        if hashlib.file_digest(stream,'sha256').hexdigest()!=MDX_SHA256:raise ValueError('Expected pinned MDX 9482 source')
    args.output.mkdir(parents=True,exist_ok=False)
    evidence={'sourceSha256':MDX_SHA256,'target':args.target,'productionApproved':False,'realAudioQuality':'pending'}
    if args.target=='openvino-fp16':
        import openvino as ov
        core=ov.Core();model=core.read_model(str(args.model));model.reshape({model.input(0):[1,4,2048,256]})
        ov.save_model(model,str(args.output/'mdx.xml'),compress_to_fp16=True)
        evidence['runtime']=ov.__version__;evidence['note']='FP16 compressed weights; execution precision is device-dependent'
    elif args.target=='int8':
        if args.calibration is None:parser.error('INT8 requires independent representative calibration tiles')
        import numpy as np
        import onnxruntime as ort
        from onnxruntime.quantization import CalibrationDataReader,CalibrationMethod,QuantFormat,QuantType,quantize_static
        files=sorted(args.calibration.glob('*.npy'))
        if len(files)<30:raise ValueError('Use at least 30 calibration tiles, distributed over authorized songs')
        name=ort.InferenceSession(str(args.model),providers=['CPUExecutionProvider']).get_inputs()[0].name
        class Reader(CalibrationDataReader):
            def __init__(self):self.files=iter(files)
            def get_next(self):
                path=next(self.files,None)
                if path is None:return None
                value=np.load(path,allow_pickle=False)
                if value.shape!=(1,4,2048,256) or value.dtype!=np.float32 or not np.isfinite(value).all():raise ValueError('Invalid calibration tile')
                return {name:value}
        quantize_static(str(args.model),str(args.output/'mdx-int8.onnx'),Reader(),quant_format=QuantFormat.QDQ,
            activation_type=QuantType.QInt8,weight_type=QuantType.QInt8,per_channel=True,
            calibrate_method=CalibrationMethod.MinMax,op_types_to_quantize=['Conv','MatMul'])
        evidence['runtime']=ort.__version__;evidence['calibration']=[{'name':p.name,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in files]
    else:
        from singverse_compute.infrastructure.separation_worker import Runtime
        import numpy as np
        runtime=Runtime(args.model,args.target,args.output/'compiled')
        value=np.random.default_rng(9).normal(0,.1,(1,4,2048,256)).astype('float32')
        predicted=runtime.infer(value)
        if predicted.shape!=value.shape or not np.isfinite(predicted).all():raise ValueError('Conversion output is invalid')
        evidence['placement']=runtime.profile()
        if args.target=='mlx':
            import mlx.core as mx
            from mlx.utils import tree_flatten
            mx.save_safetensors(str(args.output/'mdx.safetensors'),dict(tree_flatten(runtime.model.parameters())))
        evidence['note']='Cache/conversion smoke only. Run service CPU-reference qualification and independent real-song quality validation.'
    evidence['files']=[{'path':str(p.relative_to(args.output)),'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in args.output.rglob('*') if p.is_file()]
    (args.output/'conversion.json').write_text(json.dumps(evidence,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(evidence,ensure_ascii=False))


if __name__=='__main__':main()

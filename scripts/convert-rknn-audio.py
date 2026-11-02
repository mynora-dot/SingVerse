#!/usr/bin/env python3
"""Explicit offline conversion of the pinned UVR MDXNET 9482 ONNX model."""
import argparse
import hashlib
import json
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('onnx', type=Path)
    parser.add_argument('--target', required=True, choices=['rk3588', 'rk3566', 'rk3568'])
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    from importlib.metadata import version
    if version('rknn-toolkit2') != '2.3.2':
        raise RuntimeError('Use rknn-toolkit2==2.3.2 in a separate conversion environment')
    with args.onnx.open('rb') as stream:
        source_digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    if source_digest != '9d78f8566fa8198065214ab628be1de966a500c57786695aa4b13e2b27a7727d':
        raise ValueError('Expected the pinned sherpa-onnx UVR_MDXNET_9482.onnx release')
    from rknn.api import RKNN
    # Never replace an existing installation, including partially built artifacts.
    args.output.mkdir(parents=True, exist_ok=False)
    runtime = RKNN(verbose=True)
    try:
        runtime.config(target_platform=args.target)
        if runtime.load_onnx(model=str(args.onnx), inputs=['input'],
                             input_size_list=[[1, 4, 2048, 256]],
                             outputs=['output']) != 0:
            raise RuntimeError('Failed to load UVR MDXNET ONNX')
        if runtime.build(do_quantization=False) != 0:
            raise RuntimeError('NPU graph conversion failed; this graph is not deployable')
        model = args.output / 'uvr.rknn'
        if runtime.export_rknn(str(model)) != 0:
            raise RuntimeError('Failed to export RKNN model')
        with model.open('rb') as stream:
            digest = hashlib.file_digest(stream, 'sha256').hexdigest()
        metadata = {'profile': 'uvr-mdxnet-9482-v1', 'target': args.target,
                    'size': model.stat().st_size, 'sha256': digest, 'toolkit': '2.3.2', 'sourceSha256': source_digest}
        (args.output / 'manifest.json').write_text(json.dumps(metadata, indent=2) + '\n')
    finally:
        runtime.release()


if __name__ == '__main__':
    main()

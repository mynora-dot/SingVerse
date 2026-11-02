"""Experimental local UVR MDXNET 9482 graph on NPU; spectral DSP stays on CPU."""
import argparse
import hashlib
import json
from pathlib import Path


def local_manifest(directory):
    directory = Path(directory)
    data = json.loads((directory / 'manifest.json').read_text())
    if data.get('profile') != 'uvr-mdxnet-9482-v1' or data.get('target') not in ('rk3588', 'rk3566', 'rk3568'):
        raise ValueError('Unsupported RKNN forward profile or target')
    if data.get('sourceSha256') != '9d78f8566fa8198065214ab628be1de966a500c57786695aa4b13e2b27a7727d' or data.get('toolkit') != '2.3.2':
        raise ValueError('Unexpected RKNN source model or toolkit')
    model = directory / 'uvr.rknn'
    size = model.stat().st_size
    if not 0 < size <= 512 * 1024**2 or size != data['size']:
        raise ValueError('RKNN model size mismatch')
    with model.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    if digest != data['sha256']:
        raise ValueError('RKNN model digest mismatch')
    return data, hashlib.sha256(json.dumps(data, sort_keys=True).encode()).hexdigest()


class Forward:
    """Expose the small compiled-model interface used by the common DSP pipeline."""
    def __init__(self, directory):
        from rknnlite.api import RKNNLite
        metadata, _ = local_manifest(directory)
        compatible = Path('/proc/device-tree/compatible').read_bytes().decode(errors='replace').lower()
        if metadata['target'] not in compatible:
            raise RuntimeError('RKNN model target differs from this board')
        self.runtime = RKNNLite()
        try:
            if self.runtime.load_rknn(str(Path(directory) / 'uvr.rknn')) != 0:
                raise RuntimeError('RKNN model load failed')
            if self.runtime.init_runtime() != 0:
                raise RuntimeError('RKNN NPU initialization failed')
        except Exception:
            self.runtime.release()
            raise

    def __call__(self, inputs):
        import numpy as np
        result = self.runtime.inference(inputs=[inputs], data_format=['nchw'])
        if result is None or len(result) != 1 or result[0].shape != inputs.shape or not np.isfinite(result[0]).all():
            raise RuntimeError('RKNN inference returned invalid MDX tensors')
        return result[0]

    def close(self):
        self.runtime.release()


def separate(source, output, model_dir):
    from .uvr_audio import separate as pipeline
    runtime = Forward(model_dir)
    try:
        pipeline(source, output, runtime)
    finally:
        runtime.close()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--model-dir', required=True)
    parser.add_argument('--output', required=True)
    parser.add_argument('source')
    args = parser.parse_args()
    separate(args.source, args.output, args.model_dir)


if __name__ == '__main__':
    main()

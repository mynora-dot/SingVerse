"""Release-owned model manifests. Request bodies never supply URLs or digests."""
import hashlib
import json

BASELINE = 'htdemucs'
OPENVINO = 'htdemucs-openvino'
REVISION = 'efb3d2bc54f93899b13b72b12c57001e19f09ae2'
OV_SOURCE = f'https://huggingface.co/Intel/demucs-openvino/resolve/{REVISION}/htdemucs_v4/'
MODELS = {
    BASELINE: {
        'id': BASELINE, 'version': '955717e8', 'format': 'pytorch', 'license': 'MIT',
        'backends': ['cpu', 'cuda', 'rocm', 'mps'], 'sampleRate': 44100,
        'sources': ['drums', 'bass', 'other', 'vocals'], 'maxBytes': 100 * 1024**2,
        'files': [{'name': '955717e8-8726e21a.th', 'size': 84141911,
                   'sha256': '8726e21a993978c7ba086d3872e7608d7d5bfca646ca4aca459ffda844faa8b4',
                   'url': 'https://dl.fbaipublicfiles.com/demucs/hybrid_transformer/955717e8-8726e21a.th'}],
    },
    OPENVINO: {
        'id': OPENVINO, 'version': REVISION, 'format': 'openvino-ir', 'license': 'MIT',
        'backends': ['openvino'], 'sampleRate': 44100,
        'sources': ['drums', 'bass', 'other', 'vocals'], 'maxBytes': 128 * 1024**2,
        'files': [
            {'name': 'htdemucs_fwd.xml', 'size': 1477599,
             'sha256': '1557f96f11cf1667beca60679926bf43b393d3ff71eb4b9a625c6a07bc23f4a4',
             'url': OV_SOURCE + 'htdemucs_fwd.xml'},
            {'name': 'htdemucs_fwd.bin', 'size': 104552746,
             'sha256': '74c4e1ebd68b648ea5a2aabefb65d5c3ba805658037fc97a37b84ce9c72f4eb1',
             'url': OV_SOURCE + 'htdemucs_fwd.bin'},
        ],
    },
}
MAX_IMPORT_BYTES = max(model['maxBytes'] for model in MODELS.values())


def manifest(model_id):
    from ..core.errors import ComputeError
    if model_id not in MODELS:
        raise ComputeError(400, 'Unknown audio model')
    return MODELS[model_id]


def model_for_backend(backend):
    return OPENVINO if backend == 'openvino' else BASELINE


def model_digest(model_id):
    value = manifest(model_id)
    if model_id == BASELINE:
        return value['files'][0]['sha256']
    return hashlib.sha256(json.dumps(value, sort_keys=True).encode()).hexdigest()

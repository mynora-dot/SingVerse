"""Explicit audio runtime profiles; importing this module never initializes Torch/GPU."""
from importlib.metadata import PackageNotFoundError, version

NATIVE_ROCM_PACKAGES = {
    'torch': '2.11.0+rocm10.0.0',
    'torchaudio': '2.11.0+rocm10.0.0',
    'rocm': '10.0.0',
    'rocm-bootstrap': '0.3.0',
    'rocm-sdk-core': '10.0.0',
    'rocm-sdk-libraries': '10.0.0',
    'rocm-sdk-device-gfx1103': '10.0.0',
    'amd-torch-device-gfx1103': '2.11.0+rocm10.0.0',
    'amd-torch-device-gfx110x': '2.11.0+rocm10.0.0',
    'triton': '3.8.0+git4cff872c.rocm10.0.0',
}


def audio_runtime_profile():
    try:
        if version('demucs') != '4.0.1':
            return None
        if all(version(name).split('+')[0] == '2.5.1' for name in ('torch', 'torchaudio')):
            return 'legacy'
        if all(version(name) == expected for name, expected in NATIVE_ROCM_PACKAGES.items()):
            return 'rocm-native'
    except PackageNotFoundError:
        pass
    return None

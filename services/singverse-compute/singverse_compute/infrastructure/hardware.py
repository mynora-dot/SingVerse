"""Physical accelerator evidence, independent of installed codecs or inference packages."""
from pathlib import Path
import platform
from functools import lru_cache


@lru_cache(maxsize=1)
def accelerators():
    vendors = set()
    for device in Path('/sys/bus/pci/devices').glob('*'):
        try:
            if (device / 'class').read_text().strip().startswith('0x03'):
                vendors.add((device / 'vendor').read_text().strip())
        except OSError:
            continue
    if platform.system() == 'Windows':
        import subprocess
        try:
            listing = subprocess.run(['powershell', '-NoProfile', '-NonInteractive', '-Command',
                'Get-CimInstance Win32_VideoController | Select-Object -ExpandProperty PNPDeviceID'],
                capture_output=True, text=True, timeout=5, check=True).stdout.upper()
            for vendor in ('10DE', '1002', '8086'):
                if 'VEN_' + vendor in listing: vendors.add('0x' + vendor.lower())
        except (OSError, subprocess.SubprocessError):
            pass
    audio, video = set(), set()
    if '0x10de' in vendors:
        audio.add('cuda'); video.add('nvenc')
    if '0x1002' in vendors:
        audio.add('rocm'); video.add('amf' if platform.system() == 'Windows' else 'vaapi')
    if '0x8086' in vendors:
        audio.add('openvino'); video.update(('qsv', 'vaapi') if platform.system() == 'Linux' else ('qsv',))
    if platform.system() == 'Darwin':
        video.add('videotoolbox')
        if platform.machine().lower() in ('arm64', 'aarch64'):
            audio.add('mps')
    try:
        board = Path('/proc/device-tree/model').read_text().lower()
        if 'rockchip' in board or 'rk3566' in board or 'rk3588' in board:
            audio.add('rknn'); video.add('rkmpp')
    except OSError:
        pass
    return {'audio': sorted(audio), 'video': sorted(video)}


def non_accelerator_failure(message):
    return any(word in message.lower() for word in ('no space left', 'invalid data', 'cancel', 'budget', 'permission denied'))


def accelerator_failure(message):
    """Only device/runtime failures qualify; input, cancellation and disk failures do not."""
    message = message.lower()
    if non_accelerator_failure(message):
        return False
    return any(word in message for word in ('cuda error', 'cuda out of memory', 'cudnn', 'hip error', 'mps backend',
        'not currently implemented for the mps', 'output channels > 65536', 'failed to initialise vaapi',
        'cannot load libcuda', 'no capable devices found', 'error initializing an internal mfx',
        'device creation failed', 'failed to create device', 'openvino device', 'gpu device was lost',
        'hardware accelerator execution failed'))

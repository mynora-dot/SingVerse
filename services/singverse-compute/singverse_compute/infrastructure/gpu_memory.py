"""Driver-only queries, matched by UUID rather than physical/logical index guesses."""
import re
import subprocess


def nvidia_free_memory(device_uuid):
    if not isinstance(device_uuid, str) or not re.fullmatch(r'GPU-[0-9a-fA-F-]{16,64}', device_uuid):
        return None
    try:
        result = subprocess.run(['nvidia-smi', '--query-gpu=uuid,memory.free', '--format=csv,noheader,nounits'],
                                capture_output=True, text=True, timeout=2, check=True)
        for line in result.stdout.splitlines():
            identity, free = (item.strip() for item in line.split(','))
            if identity.lower() == device_uuid.lower():
                return int(free) * 1024**2
    except (OSError, ValueError, subprocess.SubprocessError):
        pass
    # MIG and unknown UUID formats use the explicitly selected resident context.
    return None

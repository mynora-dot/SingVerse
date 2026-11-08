"""Immutable deployment configuration; never supplied by an API caller."""
from dataclasses import dataclass
import os
from pathlib import Path

@dataclass(frozen=True)
class Settings:
    token_file: Path | None = None
    media_concurrency: int = 2
    request_limit: int = 8192

    transcode_root: Path | None = None
    ffmpeg: str = 'ffmpeg'
    ffprobe: str = 'ffprobe'
    hardware_backend: str = 'auto'
    hardware_device: str = '/dev/dri/renderD128'
    transcode_concurrency: int = 1
    process_memory_bytes: int = 1024**3
    cache_bytes: int = 10 * 1024**3
    minimum_free_bytes: int = 2 * 1024**3
    lease_seconds: int = 120
    retention_seconds: int = 600
    audio_model_repo: Path | None = None
    audio_memory_bytes: int = 4 * 1024**3
    audio_threads: int = 2
    audio_worker_idle_seconds: int = 60
    lan_pairing_enabled: bool = True

    @classmethod
    def from_env(cls):
        token = os.environ.get('COMPUTE_AUTH_TOKEN_FILE', '')
        if token and not Path(token).is_absolute():
            raise ValueError('Compute token file must be an absolute path')
        cache = os.environ.get('COMPUTE_TRANSCODE_ROOT', '')
        if cache and not Path(cache).is_absolute():
            raise ValueError('Transcode directory must be absolute')
        audio_repo = os.environ.get('COMPUTE_AUDIO_MODEL_REPO', '')
        if audio_repo and not Path(audio_repo).is_absolute():
            raise ValueError('Audio model repository must be absolute')
        concurrency = int(os.environ.get('COMPUTE_TRANSCODE_CONCURRENCY', '1'))
        memory_bytes = int(os.environ.get('COMPUTE_PROCESS_MEMORY_BYTES', str(1024**3)))
        cache_bytes = int(os.environ.get('COMPUTE_CACHE_BYTES', str(10 * 1024**3)))
        free_bytes = int(os.environ.get('COMPUTE_MINIMUM_FREE_BYTES', str(2 * 1024**3)))
        if not 1 <= concurrency <= 16 or cache_bytes <= 0 or free_bytes < 0 or memory_bytes < 64*1024**2:
            raise ValueError('Invalid transcode resource budget')
        audio_memory = int(os.environ.get('COMPUTE_AUDIO_MEMORY_BYTES', str(4 * 1024**3)))
        audio_threads = int(os.environ.get('COMPUTE_AUDIO_THREADS', '2'))
        audio_idle = int(os.environ.get('COMPUTE_AUDIO_WORKER_IDLE_SECONDS', '60'))
        lan_pairing = os.environ.get('COMPUTE_LAN_PAIRING_ENABLED', 'true').lower()
        if lan_pairing not in ('true', 'false'):
            raise ValueError('COMPUTE_LAN_PAIRING_ENABLED must be true or false')
        if audio_memory < 512*1024**2 or not 1 <= audio_threads <= 8 or not 0 <= audio_idle <= 3600:
            raise ValueError('Invalid audio resource budget')
        return cls(token_file=Path(token) if token else None,
                   transcode_root=Path(cache).resolve() if cache else None,
                   ffmpeg=os.environ.get('COMPUTE_FFMPEG', 'ffmpeg'), ffprobe=os.environ.get('COMPUTE_FFPROBE', 'ffprobe'),
                   hardware_backend=os.environ.get('COMPUTE_HARDWARE_BACKEND', 'auto'),
                   hardware_device=os.environ.get('COMPUTE_HARDWARE_DEVICE', '/dev/dri/renderD128'),
                   transcode_concurrency=concurrency, process_memory_bytes=memory_bytes, cache_bytes=cache_bytes,
                   minimum_free_bytes=free_bytes,
                   audio_model_repo=Path(audio_repo).resolve() if audio_repo else None,
                   audio_memory_bytes=audio_memory, audio_threads=audio_threads,
                   audio_worker_idle_seconds=audio_idle,
                   lan_pairing_enabled=lan_pairing == 'true')

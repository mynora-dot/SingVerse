"""Immutable deployment configuration; never supplied by an API caller."""
from dataclasses import dataclass
import os
from pathlib import Path

@dataclass(frozen=True)
class Settings:
    media_roots: tuple[Path, ...] = ()
    token_file: Path | None = None
    media_concurrency: int = 2
    request_limit: int = 8192

    transcode_root: Path | None = None
    ffmpeg: str = 'ffmpeg'
    ffprobe: str = 'ffprobe'
    hardware_backend: str = 'auto'
    hardware_device: str = '/dev/dri/renderD128'
    java_origin: str = ''
    transcode_concurrency: int = 1
    process_memory_bytes: int = 1024**3
    cache_bytes: int = 10 * 1024**3
    minimum_free_bytes: int = 2 * 1024**3
    lease_seconds: int = 120
    retention_seconds: int = 600

    @classmethod
    def from_env(cls):
        roots = tuple(Path(p) for p in os.environ.get('COMPUTE_MEDIA_ROOTS', '').split(os.pathsep) if p)
        token = os.environ.get('COMPUTE_AUTH_TOKEN_FILE', '')
        if any(not root.is_absolute() for root in roots) or token and not Path(token).is_absolute():
            raise ValueError('Compute media roots and token file must be absolute paths')
        cache = os.environ.get('COMPUTE_TRANSCODE_ROOT', '')
        if cache and not Path(cache).is_absolute():
            raise ValueError('Transcode directory must be absolute')
        origin = os.environ.get('COMPUTE_JAVA_ORIGIN', '').rstrip('/')
        from urllib.parse import urlsplit
        parsed = urlsplit(origin)
        if origin and (parsed.scheme not in ('http','https') or not parsed.hostname or parsed.username or parsed.password or parsed.path or parsed.query or parsed.fragment):
            raise ValueError('Java origin must be an HTTP origin without credentials')
        concurrency = int(os.environ.get('COMPUTE_TRANSCODE_CONCURRENCY', '1'))
        memory_bytes = int(os.environ.get('COMPUTE_PROCESS_MEMORY_BYTES', str(1024**3)))
        cache_bytes = int(os.environ.get('COMPUTE_CACHE_BYTES', str(10 * 1024**3)))
        free_bytes = int(os.environ.get('COMPUTE_MINIMUM_FREE_BYTES', str(2 * 1024**3)))
        if not 1 <= concurrency <= 16 or cache_bytes <= 0 or free_bytes < 0 or memory_bytes < 64*1024**2:
            raise ValueError('Invalid transcode resource budget')
        return cls(tuple(root.resolve() for root in roots), Path(token) if token else None,
                   transcode_root=Path(cache).resolve() if cache else None,
                   ffmpeg=os.environ.get('COMPUTE_FFMPEG', 'ffmpeg'), ffprobe=os.environ.get('COMPUTE_FFPROBE', 'ffprobe'),
                   hardware_backend=os.environ.get('COMPUTE_HARDWARE_BACKEND', 'auto'),
                   hardware_device=os.environ.get('COMPUTE_HARDWARE_DEVICE', '/dev/dri/renderD128'),
                   java_origin=origin, transcode_concurrency=concurrency, process_memory_bytes=memory_bytes, cache_bytes=cache_bytes,
                   minimum_free_bytes=free_bytes)

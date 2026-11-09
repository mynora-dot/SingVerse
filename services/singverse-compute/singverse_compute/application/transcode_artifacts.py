"""Validate and describe temporary FFmpeg outputs for transfer to Go."""
import hashlib
import os
import re
import stat

from ..core.errors import ComputeError

ARTIFACT = re.compile(r'(master\.m3u8|stream_[a-zA-Z0-9]+\.m3u8|stream_[a-zA-Z0-9]+_[0-9]+\.(ts|m4s)|init_[a-zA-Z0-9]+\.mp4|output\.mp4|subtitle\.vtt)')


def ready_hls(directory):
    """Publish only when every advertised rendition has its init and first segment."""
    def playlist(name):
        path = directory / name
        if not ARTIFACT.fullmatch(name) or path.is_symlink() or not path.is_file() or not 0 < path.stat().st_size <= 2 * 1024**2:
            return None
        lines = [line.strip() for line in path.read_text(encoding='utf-8').splitlines() if line.strip()]
        if not lines or lines[0] != '#EXTM3U':
            return None
        return [line for line in lines if not line.startswith('#')] + re.findall(r'URI="([^"]+)"', '\n'.join(lines))
    try:
        if directory.is_symlink():
            return False
        renditions = playlist('master.m3u8')
        if not renditions or any(not name.endswith('.m3u8') for name in renditions):
            return False
        for name in renditions:
            references = playlist(name)
            if not references or not any(ref.endswith(('.ts', '.m4s')) for ref in references):
                return False
            # Startup checks only one segment and the initialization map per
            # rendition. Export separately validates every completed artifact.
            first = next(ref for ref in references if ref.endswith(('.ts', '.m4s')))
            for ref in [first] + [ref for ref in references if ref.endswith('.mp4')]:
                path = directory / ref
                if not ARTIFACT.fullmatch(ref) or ref.endswith('.m3u8') or path.is_symlink() or not path.is_file() or path.stat().st_size <= 0:
                    return False
        return True
    except (OSError, UnicodeError):
        return False


def completed_hls(directory):
    """Only complete, local playlists and their fixed-name media can be exported."""
    if directory.is_symlink():
        raise ComputeError(409, 'Execution artifact directory is invalid')
    artifacts = {}
    for path in directory.iterdir():
        if not ARTIFACT.fullmatch(path.name) or path.name == 'output.mp4':
            continue
        if path.is_symlink() or not path.is_file() or path.stat().st_size <= 0:
            raise ComputeError(409, 'Execution contains invalid artifacts')
        artifacts[path.name] = path.stat().st_size
    playlists = [name for name in artifacts if name.endswith('.m3u8')]
    if 'master.m3u8' not in artifacts or len(playlists) < 2 or not any(name.endswith(('.ts', '.m4s')) for name in artifacts):
        raise ComputeError(409, 'Completed HLS artifacts are incomplete')
    for name in playlists:
        if artifacts[name] > 2 * 1024**2:
            raise ComputeError(409, 'Execution playlist exceeds transfer limits')
        text = (directory / name).read_text(encoding='utf-8')
        lines = [line.strip() for line in text.splitlines() if line.strip()]
        references = [line for line in lines if not line.startswith('#')]
        references += re.findall(r'URI="([^"]+)"', text)
        if (not lines or lines[0] != '#EXTM3U' or not references
                or any(not ARTIFACT.fullmatch(ref) or ref not in artifacts for ref in references)
                or name != 'master.m3u8' and '#EXT-X-ENDLIST' not in lines):
            raise ComputeError(409, 'Completed HLS playlist references missing artifacts')
        if name == 'master.m3u8' and not any(ref.endswith('.m3u8') for ref in references):
            raise ComputeError(409, 'Completed HLS master has no media playlists')
        if name != 'master.m3u8' and not any(ref.endswith(('.ts', '.m4s')) for ref in references):
            raise ComputeError(409, 'Completed HLS media playlist has no segments')
    return artifacts


def export_hls(directory):
    artifacts = completed_hls(directory)
    result = {}
    for name, size in sorted(artifacts.items()):
        path = directory / name
        # Completed FFmpeg outputs no longer change. Reject replacement during the digest read.
        descriptor = os.open(path, os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0))
        with os.fdopen(descriptor, 'rb') as stream:
            before = os.fstat(stream.fileno())
            if not stat.S_ISREG(before.st_mode) or before.st_size != size or path.is_symlink():
                raise ComputeError(409, 'Execution artifact changed during export')
            digest = hashlib.sha256()
            while chunk := stream.read(1024**2):
                digest.update(chunk)
            after = os.fstat(stream.fileno())
            if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
                raise ComputeError(409, 'Execution artifact changed during export')
            result[name] = {'size': size, 'sha256': digest.hexdigest()}
    return {'artifacts': result}

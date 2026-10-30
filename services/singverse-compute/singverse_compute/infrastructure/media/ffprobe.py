"""Bounded read-only local tag worker, invoked by the compute service; no database or network access."""
import json
from pathlib import Path
import shutil
from ...domain.media_tags import MediaTags
import subprocess
from ...core.errors import ComputeError

LIMIT = 262144
KEYS = 'title,artist,artists,album,date,year,date_released,album_artist'

def normalize(payload):
    tags = {}
    # Container tags take precedence over stream tags; first nonempty value wins.
    sources = [payload.get('format', {})] + payload.get('streams', [])
    for source in sources:
        for key, value in source.get('tags', {}).items():
            if isinstance(value, str) and value.strip():
                tags.setdefault(key.lower(), value.strip()[:500])
    artists = tags.get('artist') or tags.get('artists')
    return {'title': tags.get('title'), 'artists': [p.strip()[:200] for p in artists.split(';') if p.strip()][:32] if artists else [],
            'album': tags.get('album'), 'releaseDate': (tags.get('date') or tags.get('date_released') or tags.get('year') or '')[:32] or None}

def probe(path, executable='ffprobe'):
    path = Path(path)
    if not path.is_absolute() or not path.is_file() or path.is_symlink():
        return {}
    command = [executable, '-v', 'error', '-protocol_whitelist', 'file',
               '-format_whitelist', 'mp3,flac,ogg,wav,mov,matroska,webm,avi,asf,mpeg,mpegts,aac,ape,aiff',
               '-probesize', '2097152', '-analyzeduration', '1000000',
               '-show_entries', f'format_tags={KEYS}:stream_tags={KEYS}', '-of', 'json', str(path)]
    try:
        from ..transcoding.process import run
        data = run(command, timeout=3, limit=LIMIT)
        return normalize(json.loads(data))
    except (OSError, ValueError, subprocess.TimeoutExpired, ComputeError):
        return {}


class FFprobeReader:
    def __init__(self, executable='ffprobe'):
        self.executable = executable

    @property
    def available(self) -> bool:
        return shutil.which(self.executable) is not None

    def read(self, path: Path) -> MediaTags:
        values = probe(path, self.executable)
        return MediaTags(values.get('title'), tuple(values.get('artists', [])), values.get('album'), values.get('releaseDate'))

"""Read-only normalized metadata; independent of FFmpeg and API schemas."""
from dataclasses import dataclass

@dataclass(frozen=True)
class MediaTags:
    title: str | None = None
    artists: tuple[str, ...] = ()
    album: str | None = None
    release_date: str | None = None

"""Bounded read-only task orchestration. No FastAPI or subprocess dependency."""
from pathlib import Path
from typing import Protocol
import threading
from ..core.errors import ComputeError
from ..domain.media_tags import MediaTags

class TagReader(Protocol):
    @property
    def available(self) -> bool: ...
    def read(self, path: Path) -> MediaTags: ...

class MediaPaths(Protocol):
    @property
    def configured(self) -> bool: ...
    def resolve(self, filename: str) -> Path: ...

class MediaTagService:
    def __init__(self, paths: MediaPaths, reader: TagReader, concurrency: int = 2):
        if concurrency < 1:
            raise ValueError('Media concurrency must be positive')
        self.paths = paths
        self.reader = reader
        self.slots = threading.BoundedSemaphore(concurrency)

    @property
    def available(self) -> bool:
        return self.paths.configured and self.reader.available

    def read(self, filename: str) -> MediaTags:
        if not self.available:
            raise ComputeError(503, 'Media tag reader unavailable')
        path = self.paths.resolve(filename)
        if not self.slots.acquire(blocking=False):
            raise ComputeError(429, 'Media reader busy')
        try:
            return self.reader.read(path)
        finally:
            self.slots.release()

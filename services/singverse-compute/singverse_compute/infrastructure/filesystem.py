"""Filesystem authorization adapter shared by read-only media tasks."""
from pathlib import Path
from ..core.errors import ComputeError

class RootedMediaPaths:
    def __init__(self, roots: tuple[Path, ...]):
        if any(not root.is_absolute() for root in roots):
            raise ValueError('Media roots must be absolute')
        self.roots = tuple(root.resolve() for root in roots)

    @property
    def configured(self) -> bool:
        return bool(self.roots)

    def resolve(self, filename: str) -> Path:
        try:
            original = Path(filename)
            path = original.resolve(strict=True)
            if not original.is_absolute() or original.is_symlink() or not path.is_file() or not any(path.is_relative_to(root) for root in self.roots):
                raise ComputeError(403, 'Media path is not allowed')
            return path
        except (OSError, ValueError):
            raise ComputeError(403, 'Media path is not allowed') from None

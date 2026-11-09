#!/usr/bin/env python3
"""Stage the complete frontend for go:embed; never modifies frontend sources."""
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[1]
source = ROOT / "services/singverse-frontend/dist"
destination = ROOT / "services/singverse-go-backend/internal/web/dist"
for entry in ("admin/index.html", "party/index.html"):
    if not (source / entry).is_file():
        raise SystemExit("Frontend assets missing; run make frontend-build first")
if destination.exists():
    shutil.rmtree(destination)
shutil.copytree(source, destination)

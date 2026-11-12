#!/usr/bin/env python3
"""Exercise Go authorization against a real isolated Python/FFmpeg subprocess."""
from pathlib import Path
import os
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
if not (ROOT / "services/singverse-compute/.venv/bin/python").is_file():
    raise SystemExit("Compute virtual environment missing; run make compute-setup")
for program in ("go", "ffmpeg", "ffprobe"):
    if shutil.which(program) is None:
        raise SystemExit(f"Required real integration program unavailable: {program}")

env = dict(os.environ, SINGVERSE_TEST_REAL_COMPUTE="1")
subprocess.run(["sh", str(ROOT / "scripts/run-go.sh"), "test", "-race", "-count=1", "-timeout=200s",
                "./internal/execution", "-run", "^TestRealPythonComputeRoundTrip$", "-v"],
               cwd=ROOT / "services/singverse-go-backend", env=env, check=True)

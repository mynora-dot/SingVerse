#!/usr/bin/env python3
"""Run Go business acceptance against a disposable, loopback-only MySQL."""
from pathlib import Path
import json
import os
import secrets
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
name = "singverse-go-test-" + uuid.uuid4().hex[:12]
password = secrets.token_urlsafe(32)

def docker(*args, check=True):
    return subprocess.run(["docker", *args], cwd=ROOT, check=check,
                          text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)

try:
    docker("run", "--rm", "--detach", "--name", name,
           "--label", "singverse.test=go-mysql", "--publish", "127.0.0.1::3306",
           "--env", "MYSQL_ROOT_PASSWORD=" + password, "--env", "MYSQL_ROOT_HOST=%",
           "mysql:8.4.8", "--skip-log-bin")
    for attempt in range(120):
        # Initialization uses a socket-only temporary server. Wait for an
        # authenticated TCP query so tests cannot race its shutdown/restart.
        ready = docker("exec", "--env", "MYSQL_PWD=" + password, name,
                       "mysql", "--protocol=TCP", "--host=127.0.0.1", "--port=3306",
                       "--connect-timeout=1", "--user=root", "--batch",
                       "--skip-column-names", "--execute=SELECT 1", check=False)
        if ready.returncode == 0 and ready.stdout.strip() == "1":
            break
        time.sleep(1)
    else:
        raise SystemExit("Isolated MySQL did not become ready")
    state = json.loads(docker("inspect", name).stdout)[0]
    port = state["NetworkSettings"]["Ports"]["3306/tcp"][0]["HostPort"]
    env = dict(os.environ, GOTOOLCHAIN="go1.27.4",
               GOMODCACHE=str(ROOT / ".tools/go-mod-cache"),
               GOCACHE=str(ROOT / ".tools/go-build-cache"),
               GOPATH=str(ROOT / ".tools/go"),
               SINGVERSE_TEST_MYSQL_HOST="127.0.0.1",
               SINGVERSE_TEST_MYSQL_PORT=port,
               SINGVERSE_TEST_MYSQL_PASSWORD=password)
    subprocess.run(["go", "test", "-race", "./...", "-run", "MySQL", "-count=1"],
                   cwd=ROOT / "services/singverse-go-backend", env=env, check=True)
finally:
    docker("rm", "--force", name, check=False)

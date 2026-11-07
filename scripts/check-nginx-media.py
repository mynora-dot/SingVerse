"""Check actual media streaming through Nginx with disposable, isolated fixtures."""

import argparse
import json
from pathlib import Path
import socket
import subprocess
import tempfile
import time
from urllib.request import Request, urlopen
import uuid


FIXTURE = r"""
const http = require('http');
const pending = new Map();
http.createServer((req, res) => {
  const url = new URL(req.url, 'http://fixture');
  const key = url.searchParams.get('check');
  if (url.pathname === '/api/v1/__release') {
    pending.get(key)?.();
    res.end('released');
    return;
  }
  const range = req.headers.range;
  res.writeHead(range ? 206 : 200, {
    'Content-Type': 'application/octet-stream', 'Content-Length': '1024',
    'Accept-Ranges': 'bytes', 'Content-Range': 'bytes 5-1028/1029',
    'X-Test-Range': range || '', 'Cache-Control': 'private, no-store'
  });
  if (req.method === 'HEAD') { res.end(); return; }
  res.write(Buffer.alloc(32, 97));
  const finish = () => { clearTimeout(timer); pending.delete(key); res.end(Buffer.alloc(992, 98)); };
  const timer = setTimeout(finish, 8000);
  pending.set(key, finish);
  res.on('close', () => { clearTimeout(timer); pending.delete(key); });
}).listen(8080, '0.0.0.0');
"""


def docker(*args):
  return subprocess.check_output(['docker', *args], text=True).strip()


def main():
  root = Path(__file__).resolve().parents[1]
  parser = argparse.ArgumentParser(description=__doc__)
  parser.add_argument('--config', type=Path, default=root / 'services/singverse-java-backend/nginx/nginx.conf')
  parser.add_argument('--nginx-image', default='nginx:stable-alpine')
  parser.add_argument('--backend-image', default='node:22-bookworm-slim')
  args = parser.parse_args()
  # Existing local images only. Never start the application or mount its data/configuration.
  for image in (args.nginx_image, args.backend_image):
    docker('image', 'inspect', image)
  network = 'singverse-media-check-' + uuid.uuid4().hex[:12]
  containers = []
  docker('network', 'create', network)
  try:
    with tempfile.TemporaryDirectory(prefix='singverse-nginx-media-') as directory:
      fixture = Path(directory) / 'fixture.cjs'
      fixture.write_text(FIXTURE)
      containers.append(docker('run', '-d', '--pull=never', '--network', network,
        '--network-alias', 'singverse-java-backend', '--read-only',
        '--mount', f'type=bind,src={fixture},dst=/fixture.cjs,readonly',
        args.backend_image, 'node', '/fixture.cjs'))
      scheme = root / 'services/singverse-java-backend/nginx/proxy-scheme.conf'
      containers.append(docker('run', '-d', '--pull=never', '--network', network,
        '-p', '127.0.0.1::80', '--read-only', '--tmpfs', '/var/cache/nginx', '--tmpfs', '/var/run',
        '--mount', f'type=bind,src={args.config.resolve()},dst=/etc/nginx/nginx.conf,readonly',
        '--mount', f'type=bind,src={scheme},dst=/etc/nginx/proxy-scheme.conf,readonly',
        '--entrypoint', 'nginx', args.nginx_image, '-g', 'daemon off;'))
      docker('exec', containers[-1], 'nginx', '-t')
      base = 'http://' + docker('port', containers[-1], '80/tcp')
      deadline = time.monotonic() + 10
      while True:
        try:
          with urlopen(base + '/api/v1/__release', timeout=1) as response:
            response.read()
          break
        except OSError:
          if time.monotonic() >= deadline:
            raise
          time.sleep(.1)
      paths = [
        '/api/v1/media-songs/song/video', '/api/v1/tv/media/song',
        '/api/v1/media-songs/song/accompaniment/job',
        '/api/v1/tv/audio-roles/songs/song/accompaniment/job',
        '/api/v1/internal/transcode-input/grant', '/api/v1/internal/audio-input/grant',
        '/api/v1/transcoding/sessions/session/artifacts/stream_0_000000.ts',
        '/api/v1/tv/transcoding/session/artifacts/stream_0_000000.ts',
        '/api/v1/transcoding/sessions/versions/version/song',
        '/api/v1/tv/transcoding/versions/version/song',
      ]
      timings = []
      for path in paths:
        key = uuid.uuid4().hex
        started = time.monotonic()
        try:
          request = Request(base + path + '?check=' + key, headers={'Range': 'bytes=5-1028'})
          with urlopen(request, timeout=2) as response:
            assert response.status == 206, path
            assert response.headers['Content-Range'] == 'bytes 5-1028/1029', path
            assert response.headers['Content-Length'] == '1024', path
            assert response.headers['X-Test-Range'] == 'bytes=5-1028', path
            # Upstream keeps the remaining 992 bytes gated until a separate release.
            assert response.read(32) == b'a' * 32, path
            elapsed = (time.monotonic() - started) * 1000
            assert elapsed < 2000, f'{path}: first bytes took {elapsed:.2f}ms'
            timings.append(elapsed)
            with urlopen(base + '/api/v1/__release?check=' + key, timeout=2) as release:
              release.read()
            assert response.read() == b'b' * 992, path
        except (TimeoutError, socket.timeout) as error:
          raise AssertionError(f'{path}: first bytes were buffered while upstream was gated') from error
        finally:
          with urlopen(base + '/api/v1/__release?check=' + key, timeout=2) as release:
            release.read()
        with urlopen(Request(base + path, method='HEAD', headers={'Range': 'bytes=5-1028'}), timeout=2) as response:
          assert response.status == 206 and response.read() == b'', path
          assert response.headers['Content-Length'] == '1024', path
      print(json.dumps({'routes': len(paths), 'get_head_checks': len(paths) * 2,
        'first_32_bytes_ms': [round(value, 2) for value in timings]}, ensure_ascii=False))
  finally:
    for container in reversed(containers):
      subprocess.run(['docker', 'rm', '-f', container], check=False, stdout=subprocess.DEVNULL)
    subprocess.run(['docker', 'network', 'rm', network], check=False, stdout=subprocess.DEVNULL)


if __name__ == '__main__':
  main()

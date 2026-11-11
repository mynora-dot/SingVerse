"""Check actual media streaming through Nginx with disposable, isolated fixtures."""

import argparse
from http.client import HTTPConnection
import json
from pathlib import Path
import socket
import subprocess
import tempfile
import time
from urllib.request import Request, urlopen
from urllib.error import HTTPError
import uuid


FIXTURE = r"""
const http = require('http');
const pending = new Map();
http.createServer((req, res) => {
  const url = new URL(req.url, 'http://fixture');
  const key = url.searchParams.get('check');
  if (['POST', 'PATCH'].includes(req.method) && (/^\/api\/v1\/artists\/(artwork-batches|categories)$/.test(url.pathname) ||
      /^\/api\/v1\/media-sources\/[A-Za-z0-9-]+\/embedded-metadata\/(share|import-jobs)$/.test(url.pathname) ||
      /^\/api\/v1\/metadata\/(assets|entities(?:\/[A-Za-z0-9-]+)?|songs\/[A-Za-z0-9-]+\/covers\/upload)$/.test(url.pathname))) {
    const chunks = [];
    req.on('data', chunk => chunks.push(chunk));
    req.on('end', () => {
      res.setHeader('Content-Type', 'application/json');
      res.end(JSON.stringify({ bytes: Buffer.concat(chunks).length,
        csrf: req.headers['x-csrf-token'], origin: req.headers.origin }));
    });
    return;
  }
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
  parser.add_argument('--config', type=Path, default=root / 'infra/docker/build/nginx/nginx.conf')
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
        '--network-alias', 'singverse-go-backend', '--read-only',
        '--mount', f'type=bind,src={fixture},dst=/fixture.cjs,readonly',
        args.backend_image, 'node', '/fixture.cjs'))
      scheme = root / 'infra/docker/build/nginx/proxy-scheme.conf'
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
      # Verify the advertised 1000-artist range through the real proxy, including
      # protected request headers. Other JSON endpoints retain the 16 KiB limit.
      artist_ids = [str(uuid.uuid4()) for _ in range(1000)]
      batches = {
        '/api/v1/artists/artwork-batches': {'requestId': str(uuid.uuid4()),
          'provider': 'AUTO', 'artistIds': artist_ids, 'allMatching': False, 'filter': {}},
        '/api/v1/artists/categories': {'type': 'MALE',
          'artists': [{'id': artist, 'revision': 1} for artist in artist_ids]},
      }
      for path, payload in batches.items():
        body = json.dumps(payload).encode()
        assert 16 * 1024 < len(body) < 128 * 1024
        request = Request(base + path, data=body, method='POST',
          headers={'Content-Type': 'application/json', 'X-CSRF-Token': 'isolated-fixture', 'Origin': base})
        with urlopen(request, timeout=2) as response:
          assert response.status == 200, path
          assert json.load(response) == {'bytes': len(body), 'csrf': 'isolated-fixture', 'origin': base}, path
      # ZIP uploads use the streaming upload location; mapping and task actions
      # continue to use the ordinary bounded JSON location.
      for endpoint in ('share', 'import-jobs'):
        path = '/api/v1/media-sources/source/embedded-metadata/' + endpoint
        body = b'z' * (256 * 1024)
        request = Request(base + path, data=body, method='POST',
          headers={'Content-Type': 'application/zip', 'X-CSRF-Token': 'isolated-fixture', 'Origin': base})
        with urlopen(request, timeout=2) as response:
          assert json.load(response) == {'bytes': len(body), 'csrf': 'isolated-fixture', 'origin': base}, path
      for path, size in [(path, 128 * 1024 + 1) for path in batches] + [('/api/v1/artists/artwork-settings', 16 * 1024 + 1)]:
        try:
          urlopen(Request(base + path, data=b' ' * size, method='POST'), timeout=2)
          raise AssertionError(f'{path}: oversized body was accepted')
        except HTTPError as response:
          assert response.code == 413, path
      # Legal originals and lyrics must survive the production proxy. These
      # disposable routes count bytes only and never write an application DB.
      formal_uploads = [
        ('/api/v1/metadata/assets', 'POST', 32 * 1024 * 1024, 'image/png'),
        ('/api/v1/metadata/songs/song/covers/upload', 'POST', 32 * 1024 * 1024 + 128 * 1024,
         'multipart/form-data; boundary=isolated'),
        ('/api/v1/metadata/entities/song', 'PATCH', 256 * 1024, 'application/json'),
      ]
      for path, method, size, mime in formal_uploads:
        request = Request(base + path, data=b'x' * size, method=method,
          headers={'Content-Type': mime, 'X-CSRF-Token': 'isolated-fixture', 'Origin': base})
        with urlopen(request, timeout=10) as response:
          assert json.load(response) == {'bytes': size, 'csrf': 'isolated-fixture', 'origin': base}, path
      for path, method, size in [
        ('/api/v1/metadata/assets', 'POST', 32 * 1024 * 1024 + 1),
        ('/api/v1/metadata/songs/song/covers/upload', 'POST', 33 * 1024 * 1024 + 1),
        ('/api/v1/metadata/entities/song', 'PATCH', 16 * 1024 * 1024 + 1),
      ]:
        # Nginx rejects an excessive Content-Length before receiving the body.
        connection = HTTPConnection(base.removeprefix('http://'), timeout=2)
        try:
          connection.putrequest(method, path)
          connection.putheader('Content-Length', str(size))
          connection.endheaders()
          response = connection.getresponse()
          assert response.status == 413, path
          response.read()
        finally:
          connection.close()
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
        'artist_batch_limit_checks': 5, 'metadata_upload_checks': 2, 'formal_metadata_body_checks': 6,
        'first_32_bytes_ms': [round(value, 2) for value in timings]}, ensure_ascii=False))
  finally:
    for container in reversed(containers):
      subprocess.run(['docker', 'rm', '-f', container], check=False, stdout=subprocess.DEVNULL)
    subprocess.run(['docker', 'network', 'rm', network], check=False, stdout=subprocess.DEVNULL)


if __name__ == '__main__':
  main()

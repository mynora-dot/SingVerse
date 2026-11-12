#!/usr/bin/env python3
"""Read-only HTTP smoke check of the currently selected business deployment."""
import json
from pathlib import Path
import re
import subprocess
from urllib.parse import urljoin
from urllib.request import build_opener, ProxyHandler

ROOT = Path(__file__).resolve().parents[2]


def main():
    address = subprocess.check_output(['docker', 'compose', '--project-directory', str(ROOT), '-f',
        str(ROOT / 'infra/docker/compose/compose.yaml'), 'port', 'singverse-go-backend', '8080'], text=True).strip()
    host, port = address.rsplit(':', 1)
    if host in ('0.0.0.0', '[::]'):
        host = '127.0.0.1'
    base = f'http://{host}:{port}'
    browser = build_opener(ProxyHandler({}))
    with browser.open(base + '/api/health', timeout=15) as reply:
        assert json.load(reply) == {'status': 'UP'}
    for path in ('/', '/admin/', '/party/'):
        with browser.open(base + path, timeout=15) as reply:
            assert reply.headers.get_content_type() == 'text/html'
            html = reply.read().decode()
        scripts = re.findall(r'<script[^>]+src=["\']([^"\']+)', html)
        assert scripts
        for script in scripts:
            with browser.open(urljoin(base + path, script), timeout=15) as reply:
                assert 'javascript' in reply.headers.get_content_type()
    print('通过：业务健康、三个页面入口及 JavaScript 资源；未修改业务数据。')


if __name__ == '__main__':
    main()

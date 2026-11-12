#!/usr/bin/env python3
"""Exercise both routes in random owned Compose projects; never targets existing data."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import uuid

ROOT = Path(__file__).resolve().parents[2]


def module():
    spec = importlib.util.spec_from_file_location('compose_contract', ROOT / 'scripts/check-backend-contract.py')
    value = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = value
    spec.loader.exec_module(value)
    return value


def run(args):
    contract = module()
    for source in ({"image": (False,), "source": (True,), "both": (False, True)}[args.route]):
        name = 'singverse-compose-check-' + uuid.uuid4().hex[:12]
        command = ['docker', 'compose', '--project-directory', str(ROOT), '--env-file', os.devnull,
                   '-p', name, '-f', str(ROOT / 'infra/docker/compose/compose.yaml')]
        if source:
            command += ['-f', str(ROOT / 'infra/docker/compose/compose.build.yaml')]
        env = {k: v for k, v in os.environ.items() if not k.startswith(('COMPOSE_', 'COMPUTE_', 'SINGVERSE_', 'IMAGE_'))}
        env.update(SINGVERSE_HTTP_BIND='127.0.0.1', SINGVERSE_HTTP_PORT='0')
        def compose(*options):
            return subprocess.check_output(command + list(options), env=env, text=True, stderr=subprocess.PIPE).strip()
        with tempfile.TemporaryDirectory(prefix='singverse-compose-') as directory:
            override = Path(directory) / 'images.json'
            override.write_text(json.dumps({'services': {'singverse-go-backend': {'image': args.backend_image, 'pull_policy': 'never'}}}))
            command += ['-f', str(override)]
            try:
                compose('up', '-d', '--build' if source else '--no-build', '--pull', 'never', '--wait', '--wait-timeout', '180')
                port = compose('port', 'singverse-go-backend', '8080')
                assert port.startswith('127.0.0.1:')
                base = 'http://' + port
                contract.run(base)
                admin = contract.Browser(base)
                admin.call('POST', '/api/v1/auth/login', {'username': 'admin', 'password': 'admin'}, csrf=True)
                node = admin.call('GET', '/api/v1/compute-node').json()
                assert node['automaticEndpoint'] == ''
                assert admin.call('GET', '/api/health').json() == {'status': 'UP'}
                print(('源码构建' if source else '预构建镜像') + '：页面、资源、登录、设备/聚会契约及关闭 compute 检查通过')
            finally:
                # This project and all its volumes are created exclusively by this invocation.
                compose('down', '--volumes', '--remove-orphans')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--backend-image', default='singverse:local')
    parser.add_argument('--route', choices=('image', 'source', 'both'), default='both')
    run(parser.parse_args())

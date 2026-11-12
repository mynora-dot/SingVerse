#!/usr/bin/env python3
"""Verify same-host and independent Compose compute authentication and callbacks."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import uuid
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[2]


def run(args):
    spec = importlib.util.spec_from_file_location('compute_compose_contract', ROOT / 'scripts/check-backend-contract.py')
    contract = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = contract
    spec.loader.exec_module(contract)
    for standalone in (False, True):
        name = 'singverse-compute-check-' + uuid.uuid4().hex[:12]
        env = {k: v for k, v in os.environ.items() if not k.startswith(
            ('COMPOSE_', 'COMPUTE_', 'SINGVERSE_', 'IMAGE_'))}
        env.update(SINGVERSE_HTTP_PORT='0', COMPUTE_HTTP_PORT='0',
                   SINGVERSE_CALLBACK_ORIGIN='http://singverse-go-backend:8080',
                   COMPOSE_PROFILES='' if standalone else 'compute',
                   COMPUTE_NODE_URL='' if standalone else 'http://singverse-compute:8090')
        with tempfile.TemporaryDirectory(prefix=name) as temporary:
            root = Path(temporary)
            def command(project, base, services):
                override = root / (project + '.json')
                override.write_text(json.dumps({'services': services, 'networks': {'default': {'name': name, 'external': True}}}))
                return ['docker', 'compose', '--project-directory', str(ROOT), '--env-file', os.devnull,
                        '-p', project, '-f', str(ROOT / 'infra/docker/compose' / base), '-f', str(override)]
            app = command(name + '-app', 'compose.yaml', {
                'singverse-go-backend': {'image': args.backend_image, 'pull_policy': 'never'},
                'singverse-compute': {'image': args.compute_image, 'pull_policy': 'never'}})
            node = command(name + '-node', 'compose.compute.yaml', {
                'singverse-compute': {'image': args.compute_image, 'pull_policy': 'never'}}) if standalone else app
            def execute(command, *options):
                # Capture output, including the temporary credential, without logging it.
                return subprocess.check_output(command + list(options), env=env, text=True, stderr=subprocess.PIPE).strip()
            created = []
            execute(['docker', 'network', 'create'], '--label', 'singverse.test.run=' + name, name)
            try:
                if standalone:
                    created.append(node)
                    execute(node, 'up', '-d', '--no-build', '--pull', 'never', '--wait', '--wait-timeout', '180')
                    port = execute(node, 'port', 'singverse-compute', '8090')
                    assert port.startswith('127.0.0.1:')
                    with urlopen('http://' + port + '/health', timeout=5) as response:
                        assert response.status == 200
                created.append(app)
                execute(app, 'up', '-d', '--no-build', '--pull', 'never', '--wait', '--wait-timeout', '180')
                base = 'http://' + execute(app, 'port', 'singverse-go-backend', '8080')
                admin = contract.Browser(base)
                admin.call('POST', '/api/v1/auth/login', {'username': 'admin', 'password': 'admin'}, csrf=True)
                settings = admin.call('GET', '/api/v1/compute-node').json()
                payload = {'mode': 'AUTO', 'revision': settings['revision']}
                if standalone:
                    token = execute(node, 'exec', '-T', 'singverse-compute', 'cat', '/run/singverse-compute/token')
                    payload.update(mode='MANUAL', endpoint='http://singverse-compute:8090', token=token, mappings=[])
                else:
                    assert settings['automaticHasToken'] is True
                result = admin.call('POST', '/api/v1/compute-node/test', payload, csrf=True).json()
                assert result['status'] == 'AVAILABLE', 'compute authentication or business callback failed'
                assert 'media-remote-tags' in result['capabilities'] and 'transcode-hls' in result['capabilities']
                if standalone:
                    saved = admin.call('PUT', '/api/v1/compute-node', payload, csrf=True).json()
                    assert saved['hasToken'] and token not in json.dumps(saved)
                    bad = dict(payload, revision=saved['revision'], token='0' * 64)
                    rejected = admin.call('POST', '/api/v1/compute-node/test', bad, csrf=True).json()
                    assert rejected['status'] != 'AVAILABLE', 'invalid compute credential was accepted'
                assert admin.call('GET', '/api/health').json() == {'status': 'UP'}
                print(('独立' if standalone else '同机') + ' compute：认证、实际能力、Go 回源检查通过')
            finally:
                # Only these random projects and their newly created volumes are removed.
                try:
                    for project in reversed(created):
                        execute(project, 'down', '--volumes', '--remove-orphans')
                finally:
                    execute(['docker', 'network', 'rm'], name)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--backend-image', default='singverse:local')
    parser.add_argument('--compute-image', default='singverse-compute:local-base')
    run(parser.parse_args())

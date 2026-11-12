#!/usr/bin/env python3
"""Validate both Docker routes without reading local .env or starting containers."""
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]
COMPOSE = ROOT / 'infra/docker/compose'
RUNTIMES = ('base', 'cpu', 'uvr', 'cuda', 'rocm', 'rocm-native', 'openvino', 'rknn')


def render(files, **values):
    env = {k: v for k, v in os.environ.items() if not k.startswith(
        ('COMPOSE_', 'SINGVERSE_', 'COMPUTE_', 'IMAGE_', 'RKNPU_', 'RKNN_', 'MYSQL_'))}
    env.update(COMPUTE_RENDER_GID='101', COMPUTE_VIDEO_GID='102', RKNPU_CARD_GID='103', RKNPU_GID='104',
               RKNN_MODEL_PATH=str(ROOT), MYSQL_PASSWORD='isolated-test', MYSQL_ROOT_PASSWORD='isolated-root')
    env.update(values)
    command = ['docker', 'compose', '--project-directory', str(ROOT), '--env-file', os.devnull]
    for name in files:
        command += ['-f', str(COMPOSE / name)]
    return json.loads(subprocess.check_output(command + ['config', '--format', 'json'], env=env, text=True))


def require(value, message):
    if not value:
        raise ValueError(message)


def check(config, source, standalone):
    services = config['services']
    require(set(services) == ({'singverse-compute'} if standalone else {'singverse-go-backend', 'singverse-compute'}), 'Unexpected service')
    require(config['name'] == ('singverse-compute-node' if standalone else 'singverse-go-app'), 'Persistent project identity changed')
    for service in services.values():
        require(not service.get('privileged'), 'No privileged deployment allowed')
        if source:
            build = service['build']
            require(Path(build['context']) == ROOT, 'Build context must be repository root')
            require((ROOT / build['dockerfile']).is_file(), 'Missing Dockerfile')
        else:
            require('build' not in service, 'Image route must never compile')
    compute = services['singverse-compute']
    require(compute['environment']['COMPUTE_AUTH_TOKEN_FILE'] == '/run/singverse-compute/token', 'Compute authentication lost')
    require(all(v['target'] != '/media' for v in compute['volumes']), 'Compute must use authorized callbacks')
    if not standalone:
        go = services['singverse-go-backend']
        require(not go.get('depends_on'), 'Optional compute must not block business startup')
        require(compute['profiles'] == ['compute'] and not compute.get('ports'), 'Stack compute must be opt-in and private')
        require(go['environment']['COMPUTE_NODE_URL'] == '', 'Default business must work without compute')
        mounts = {v['target']: v for v in go['volumes']}
        for target, name in (('/data', 'singverse-data'), ('/secrets', 'singverse-secrets'),
                             ('/media', 'singverse-media'), ('/downloads', 'singverse-downloads')):
            require(mounts[target]['source'] == name, 'Persistent volume identity changed')
        require(mounts['/media']['read_only'] and mounts['/run/singverse-compute']['read_only'], 'Read-only protection lost')
        port = go['ports'][0]
        require(port['host_ip'] == '127.0.0.1' and str(port['published']) == '18080' and port['target'] == 8080, 'Business port changed')
    else:
        require(not compute.get('profiles'), 'Standalone compute must start directly')
        require(compute['ports'][0]['host_ip'] == '127.0.0.1', 'Standalone compute defaults to loopback')


def main():
    count = 0
    for standalone in (False, True):
        base = 'compose.compute.yaml' if standalone else 'compose.yaml'
        overlay = 'compose.compute-build.yaml' if standalone else 'compose.build.yaml'
        for source in (False, True):
            for runtime in RUNTIMES:
                hardware = {'cuda': 'nvidia', 'rocm': 'rocm', 'rocm-native': 'rocm', 'openvino': 'dri', 'rknn': 'rknn'}.get(runtime)
                files = [base] + ([overlay] if source else []) + ([f'compose.{hardware}.yaml'] if hardware else [])
                config = render(files, COMPUTE_RUNTIME=runtime, COMPOSE_PROFILES='compute')
                check(config, source, standalone)
                compute = config['services']['singverse-compute']
                require(compute['image'].endswith(('-' + runtime)), 'Runtime tag mismatch')
                if source:
                    require(compute['build']['target'] == runtime, 'Runtime build target mismatch')
                count += 1
            for hardware in ('dri', 'nvidia', 'rockchip'):
                config = render([base] + ([overlay] if source else []) + [f'compose.{hardware}.yaml'], COMPOSE_PROFILES='compute')
                check(config, source, standalone)
                count += 1
    config = render(['compose.yaml', 'compose.media.yaml', 'compose.downloads.yaml'], COMPOSE_PROFILES='compute',
                    SINGVERSE_MEDIA_PATH=str(ROOT), SINGVERSE_DOWNLOAD_PATH=str(ROOT))
    mounts = {v['target']: v for v in config['services']['singverse-go-backend']['volumes']}
    require(mounts['/media']['type'] == 'bind' and mounts['/media']['read_only'], 'Originals must stay read-only')
    require(not mounts['/downloads'].get('read_only'), 'Download bind must be writable')
    require(not mounts['/media']['bind'].get('create_host_path'), 'Missing media directory must fail')
    render(['compose.mysql.yaml'])
    require((ROOT / '.dockerignore').read_bytes() == (ROOT / 'infra/docker/build/.dockerignore').read_bytes(), 'Root Docker ignore must match infra source')
    link = ROOT / 'infra/docker/build/compute.Dockerfile.dockerignore'
    require(link.is_symlink() and link.resolve(strict=True).name == '.dockerignore', 'Broken compute ignore link')
    print(f'通过：{count + 2} 组源码/镜像/硬件/独立节点配置；未启动容器。')


if __name__ == '__main__':
    main()

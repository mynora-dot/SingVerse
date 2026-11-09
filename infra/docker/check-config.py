#!/usr/bin/env python3
"""Validate deployable Compose combinations without starting containers or reading .env."""

import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
COMPOSE = ROOT / 'infra/docker/compose'


def require(condition, message):
    if not condition:
        raise ValueError(message)


def environment(**overrides):
    # Keep Docker CLI/context settings, but never import the user's deployment variables.
    prefixes = ('COMPOSE_', 'COMPUTE_', 'SINGVERSE_', 'MYSQL_', 'SPRING_', 'RKNPU_')
    result = {key: value for key, value in os.environ.items() if not key.startswith(prefixes) and key != 'NAS_BIND_IP'}
    result.update(COMPUTE_RENDER_GID='101', SINGVERSE_RENDER_GID='101', SINGVERSE_VIDEO_GID='102')
    result.update(VERSION=(ROOT / "VERSION").read_text().strip())
    result.update(overrides)
    return result


def render(files, **overrides):
    command = ['docker', 'compose', '--project-directory', str(ROOT), '--env-file', os.devnull]
    for file in files:
        command.extend(['-f', str(file)])
    result = subprocess.run(command + ['config', '--format', 'json'], cwd=ROOT,
                            env=environment(**overrides), capture_output=True, text=True, check=True)
    return json.loads(result.stdout)


def mount(service, target):
    matches = [item for item in service.get('volumes', []) if item['target'] == target]
    require(len(matches) == 1, f'Expected exactly one mount for {target}')
    return matches[0]


def private_port(service, target, published):
    ports = service.get('ports', [])
    require(len(ports) == 1, 'Unexpected additional published port')
    require(ports[0]['host_ip'] == '127.0.0.1' and ports[0]['target'] == target
            and str(ports[0]['published']) == str(published), 'Default port must stay on loopback')


def validate_builds(config):
    for service in config['services'].values():
        require(not service.get('privileged'), 'Deployment must not need privileged containers')
        build = service.get('build')
        if not build:
            continue
        require(Path(build['context']) == ROOT, 'Build context must remain the repository root')
        dockerfile = ROOT / build['dockerfile']
        require(dockerfile.is_file(), f'Missing Dockerfile: {build["dockerfile"]}')
        stages = re.findall(r'^FROM\s+.+\s+AS\s+(\S+)', dockerfile.read_text(), re.IGNORECASE | re.MULTILINE)
        require(build['target'] in stages, f'Unknown Docker target: {build["target"]}')


def validate_compute(config, target, standalone):
    service = config['services']['singverse-compute']
    require(service['build']['target'] == target, f'Incorrect runtime target: {target}')
    suffix = ':local' if target == 'base' else f':local-{target}'
    require(service['image'].endswith(suffix), f'Runtime/image tag mismatch: {target}')
    for path, volume in (('/run/singverse-compute', 'singverse-compute-auth'),
                         ('/var/lib/singverse-models', 'singverse-audio-models'),
                         ('/var/cache/singverse-transcode', 'singverse-transcode-cache')):
        require(mount(service, path)['source'] == volume, f'Changed persistent volume: {volume}')
    require(all(item['target'] != '/media' for item in service['volumes']), 'Compute must use authorized callbacks')
    require(service['environment']['COMPUTE_AUTH_TOKEN_FILE'] == '/run/singverse-compute/token', 'Authentication lost')
    if standalone:
        require(config['name'] == 'singverse-compute-node', 'Standalone project name changed')
        private_port(service, 8090, 18090)
    else:
        require(config['name'] == 'singverse-go-app', 'Application project name changed')
        require(not service.get('ports'), 'Same-stack compute must not publish a host port')
        backend = config['services']['singverse-go-backend']
        require(not backend.get('ports'), 'Go must stay on the private network')
        require(mount(backend, '/run/singverse-compute')['read_only'], 'Go must read the shared credential read-only')
        private_port(config['services']['singverse-frontend'], 80, 18080)
    if target != 'base':
        require(int(service['mem_limit']) == 6 * 1024**3, 'Audio default memory limit changed')
        require(int(service['environment']['COMPUTE_AUDIO_MEMORY_BYTES']) < int(service['mem_limit']), 'No runtime memory headroom')
    devices = {item['source'] for item in service.get('devices', [])}
    if target in ('rocm', 'rocm-native'):
        require({'/dev/kfd', '/dev/dri'} <= devices, 'ROCm requires KFD and DRI')
        require({'101', '102'} <= set(service['group_add']), 'ROCm groups lost')
        require(service['ulimits']['memlock'] == {'soft': -1, 'hard': -1}, 'ROCm memlock lost')
        require(int(service['shm_size']) == 256 * 1024**2, 'ROCm shared memory lost')
    if target == 'rocm-native':
        require(service['environment']['HOME'] == '/tmp' and service['environment']['USER'] == 'singverse', 'Native runtime needs writable HOME and user identity')
    if target == 'openvino':
        require('/dev/dri/renderD128' in devices and '101' in service['group_add'], 'OpenVINO device/group lost')
    if target == 'cuda':
        reservation = service['deploy']['resources']['reservations']['devices']
        require(any(item['driver'] == 'nvidia' and 'gpu' in item['capabilities'] for item in reservation), 'CUDA GPU reservation lost')


def main():
    count = 0
    for standalone in (False, True):
        base = COMPOSE / ('compose.compute.yaml' if standalone else 'compose.app.yaml')
        for target in ('base', 'cpu', 'cuda', 'rocm', 'rocm-native', 'openvino'):
            files = [base]
            if target == 'rocm-native':
                files.append(COMPOSE / 'compose.audio-rocm.yaml')
            if target != 'base':
                files.append(COMPOSE / f'compose.audio-{target}.yaml')
            config = render(files)
            validate_builds(config)
            validate_compute(config, target, standalone)
            count += 1
        for backend in ('qsv', 'vaapi'):
            config = render([base, COMPOSE / 'compose.compute.dri.yaml'],
                            COMPUTE_HARDWARE_BACKEND=backend, COMPUTE_HARDWARE_DEVICE='/dev/dri/renderD129')
            service = config['services']['singverse-compute']
            require(service['environment']['COMPUTE_HARDWARE_BACKEND'] == backend, 'Video backend not passed through')
            require(service['environment']['COMPUTE_HARDWARE_DEVICE'] == '/dev/dri/renderD129'
                    and service['devices'][0]['source'] == '/dev/dri/renderD129', 'Selected DRI device not mapped')
            count += 1
        config = render([base, COMPOSE / 'compose.audio-openvino.yaml'],
                        COMPUTE_HARDWARE_DEVICE='/dev/dri/renderD129', COMPUTE_HARDWARE_BACKEND='qsv')
        service = config['services']['singverse-compute']
        require(service['environment']['COMPUTE_HARDWARE_DEVICE'] == '/dev/dri/renderD129'
                and service['devices'][0]['source'] == '/dev/dri/renderD129'
                and service['environment']['COMPUTE_HARDWARE_BACKEND'] == 'qsv', 'OpenVINO/video must use the same selected device')
        count += 1
        render([base, COMPOSE / 'compose.compute.rockchip.yaml'])
        count += 1

    app = COMPOSE / 'compose.app.yaml'
    config = render([app])
    require(config['name'] == 'singverse-go-app', 'Go application must use its new isolated project')
    require('singverse-java-backend' not in config['services'], 'Default application must not contain Java')
    downloads = mount(config['services']['singverse-go-backend'], '/downloads')
    require(downloads['type'] == 'volume' and downloads['source'] == 'singverse-downloads'
            and not downloads.get('read_only'), 'Downloads need a separate persistent writable volume')
    require(mount(config['services']['singverse-go-backend'], '/media')['read_only'], 'Original media must stay read-only')
    count += 1
    config = render([app, COMPOSE / 'compose.media.yaml', COMPOSE / 'compose.downloads.yaml'],
                    SINGVERSE_MEDIA_PATH=str(ROOT), SINGVERSE_DOWNLOAD_PATH=str(ROOT))
    downloads = mount(config['services']['singverse-go-backend'], '/downloads')
    require(downloads['type'] == 'bind' and downloads['source'] == str(ROOT)
            and not downloads.get('read_only'), 'NAS download bind must be writable')
    require(not downloads.get('bind', {}).get('create_host_path', False), 'A missing download directory must fail')
    require(mount(config['services']['singverse-go-backend'], '/media')['read_only'], 'Download override changed original media permissions')
    count += 1
    config = render([app, COMPOSE / 'compose.media.yaml'], SINGVERSE_MEDIA_PATH=str(ROOT))
    media = mount(config['services']['singverse-go-backend'], '/media')
    require(media['type'] == 'bind' and media['read_only'] and media['source'] == str(ROOT), 'Local media must replace the empty volume read-only')
    require(not media.get('bind', {}).get('create_host_path', False), 'A missing media directory must fail')
    count += 1
    config = render([app, COMPOSE / 'compose.audio-rocm.yaml', COMPOSE / 'compose.audio-rocm-native.yaml',
                     COMPOSE / 'compose.media.yaml', COMPOSE / 'compose.https.yaml'],
                    COMPUTE_HARDWARE_BACKEND='vaapi', SINGVERSE_MEDIA_PATH=str(ROOT),
                    SINGVERSE_PUBLIC_ORIGIN='https://singverse.example', SINGVERSE_TRUSTED_PROXIES='172.31.250.0/24',
                    SINGVERSE_HTTP_BIND='192.0.2.1', SINGVERSE_HTTP_PORT='28080')
    private_port(config['services']['singverse-frontend'], 80, 28080)
    require(config['services']['singverse-go-backend']['environment']['SINGVERSE_PUBLIC_ORIGIN'] == 'https://singverse.example', 'HTTPS origin lost')
    require(mount(config['services']['singverse-go-backend'], '/media')['read_only'], 'Combined media protection lost')
    require(config['services']['singverse-compute']['build']['target'] == 'rocm-native', 'Combined runtime lost')
    count += 1
    config = render([app, COMPOSE / 'compose.compute.macos.yaml'])
    require(config['services']['singverse-go-backend']['environment']['COMPUTE_NODE_URL'] == 'http://host.docker.internal:18090', 'Native macOS route lost')
    count += 1

    config = render([ROOT / 'infra/deploy/app/docker-compose.yaml'])
    validate_builds(config)
    require(set(config['services']) == {'singverse-go-backend', 'singverse-frontend'}, 'Business-only package gained compute')
    private_port(config['services']['singverse-frontend'], 80, 18080)
    require(config['services']['singverse-go-backend']['environment']['SINGVERSE_CALLBACK_ORIGIN'] == '', 'Template must not contain a personal callback address')
    require(mount(config['services']['singverse-go-backend'], '/downloads')['source'] == 'singverse-downloads', 'Business package lost download persistence')
    count += 1
    spec = importlib.util.spec_from_file_location('package_offline', ROOT / 'scripts/package-app-offline.py')
    package = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(package)
    with tempfile.TemporaryDirectory(prefix='singverse-compose-check-') as directory:
        file = Path(directory) / 'offline.yaml'
        file.write_text(package.offline_compose())
        config = render([file])
        for service in config['services'].values():
            require('build' not in service and service['pull_policy'] == 'never', 'Offline deployment must not build or pull')
            require(service['platform'] == 'linux/amd64', 'Offline image architecture changed')
    count += 1
    config = render([COMPOSE / 'compose.compute.nvidia.yaml'])
    validate_builds(config)
    validate_compute(config, 'cuda', True)
    count += 1
    config = render([COMPOSE / 'compose.yaml'], MYSQL_PASSWORD='isolated-check', MYSQL_ROOT_PASSWORD='isolated-root-check')
    private_port(config['services']['mysql'], 3306, 3306)
    count += 1
    rk = ROOT / 'infra/deploy/rk3566'
    for experimental in (False, True):
        files = [rk / 'docker-compose.yaml']
        if experimental:
            files.append(rk / 'compose.rknn.yaml')
        config = render(files, NAS_BIND_IP='127.0.0.1', RKNPU_GID='103', RKNPU_CARD_GID='44')
        validate_builds(config)
        service = config['services']['singverse-compute']
        private_port(service, 8090, 18090)
        require(service['platform'] == 'linux/arm64', 'RK package must stay ARM64')
        require(service['build']['target'] == ('rknn' if experimental else 'uvr'), 'RK target changed')
        count += 1

    legacy = COMPOSE / 'compose.java-rollback.yaml'
    for overlays in ((), ('media',), ('downloads',), ('https',), ('compute.macos',), ('media', 'downloads', 'https')):
        config = render([legacy] + [COMPOSE / f'compose.java-rollback.{name}.yaml' for name in overlays],
                        SINGVERSE_MEDIA_PATH=str(ROOT), SINGVERSE_DOWNLOAD_PATH=str(ROOT),
                        SINGVERSE_PUBLIC_ORIGIN='https://singverse.example', SINGVERSE_TRUSTED_PROXIES='172.31.250.0/24')
        require(config['name'] == 'singverse-app', 'Rollback must retain the legacy project and volumes')
        require('singverse-java-backend' in config['services'] and 'singverse-go-backend' not in config['services'], 'Rollback application identity changed')
        require(all('build' not in service for service in config['services'].values()), 'Rollback must not build source')
        for name in ('singverse-java-backend', 'singverse-frontend'):
            require(config['services'][name]['pull_policy'] == 'never', 'Rollback must use explicitly saved images')
        require(config['volumes']['singverse-data']['name'] == 'singverse-app_singverse-data', 'Rollback data volume changed')
        count += 1

    go_ignore=ROOT/'infra/docker/build/go.Dockerfile.dockerignore'
    require(go_ignore.is_file(), 'Go Docker ignore source missing')
    shared = ROOT / 'infra/docker/build/.dockerignore'
    root_ignore = ROOT / '.dockerignore'
    require(root_ignore.is_file() and not root_ignore.is_symlink() and root_ignore.read_bytes() == shared.read_bytes(), 'Root Docker ignore fallback must match maintained infra rules')
    require(shared.is_file(), 'Shared Docker ignore source is missing')
    for service in ('singverse-compute',):
        link = ROOT / 'services' / service / 'Dockerfile.dockerignore'
        require(link.is_symlink() and link.resolve(strict=True) == shared, f'Broken Docker ignore link: {service}')
    for file in (shared, ROOT / 'infra/docker/build/docker-bake.hcl'):
        ignored = subprocess.run(['git', 'check-ignore', '--no-index', '-q', str(file)], cwd=ROOT, check=False)
        require(ignored.returncode == 1, f'Build source must not be ignored by Git: {file.relative_to(ROOT)}')
    subprocess.run(['docker', 'buildx', 'bake', '-f', 'infra/docker/build/docker-bake.hcl', '--print',
                    'base', 'cpu', 'cuda', 'rocm', 'rocm-native', 'openvino'],
                   cwd=ROOT, env=environment(), capture_output=True, text=True, check=True)
    print(f'通过：{count} 组 Compose 配置，构建上下文、设备/端口/卷保护、离线配置和 Bake 输入；未启动容器，未验证 GPU。')


if __name__ == '__main__':
    try:
        main()
    except subprocess.CalledProcessError as error:
        raise SystemExit(f'配置检查失败：{error.stderr or error}') from error

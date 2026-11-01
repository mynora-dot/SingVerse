#!/usr/bin/env python3
"""Run the Mac GPU node in the current user's launchd domain, with Docker's existing token."""
import argparse
import os
from pathlib import Path
import platform
import plistlib
import subprocess
import tempfile
import time
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[1]
RUNTIME = ROOT / 'data/macos-compute'
LABEL = 'com.mynora.singverse.compute.macos'


def audio_environment(model_repo, memory_mib):
    if not model_repo.is_absolute() or memory_mib < 512:
        raise ValueError('Use an absolute model directory and at least 512 MiB audio budget')
    return {'COMPUTE_AUDIO_MODEL_REPO': str(model_repo),
            'COMPUTE_AUDIO_MEMORY_BYTES': str(memory_mib * 1024**2),
            'COMPUTE_AUDIO_THREADS': '2', 'PYTORCH_ENABLE_MPS_FALLBACK': '0'}


def bootstrap(domain, target):
    # bootout returns before launchd finishes removing its registration. A
    # restart can briefly receive EIO even though print already says not found.
    for _ in range(20):
        result = subprocess.run(['launchctl', 'bootstrap', domain, str(target)], capture_output=True)
        if result.returncode == 0:
            return
        if result.returncode != 5:
            break
        time.sleep(.25)
    raise SystemExit('Mac node could not be loaded; inspect launchd and the private runtime logs')


def login_agent(enable):
    agent = Path.home() / 'Library/LaunchAgents' / (LABEL + '.plist')
    target = RUNTIME / 'compute.plist'
    if agent.exists() or agent.is_symlink():
        if not agent.is_symlink() or agent.resolve() != target.resolve():
            raise SystemExit('Another login agent owns this label; preserve it and reconcile before continuing')
        if not enable:
            agent.unlink()
    elif enable:
        agent.parent.mkdir(parents=True, exist_ok=True)
        agent.symlink_to(target)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('start', 'restart', 'stop', 'status'))
    parser.add_argument('--audio-model-repo', type=Path, default=ROOT / '.tools/demucs-htdemucs')
    parser.add_argument('--audio-memory-mib', type=int, default=None,
                        help='Explicit audio process budget; restart preserves the saved budget, otherwise 4096 MiB')
    args = parser.parse_args()
    if platform.system() != 'Darwin':
        parser.error('This launcher requires macOS; use the platform deployment instructions elsewhere')
    domain = f'gui/{os.getuid()}'
    service = domain + '/' + LABEL
    active = subprocess.run(['launchctl', 'print', service], capture_output=True)
    if args.action == 'status':
        print('loaded' if active.returncode == 0 else 'stopped')
        return
    model_repo = args.audio_model_repo.expanduser().resolve()
    memory_mib = args.audio_memory_mib
    if memory_mib is None:
        previous = RUNTIME / 'compute.plist'
        memory_mib = int(plistlib.loads(previous.read_bytes()).get('EnvironmentVariables', {}).get(
            'COMPUTE_AUDIO_MEMORY_BYTES', str(4096*1024**2))) // 1024**2 if previous.exists() else 4096
    try:
        audio = audio_environment(model_repo, memory_mib)
    except ValueError as error:
        parser.error(str(error))
    if active.returncode == 0:
        # Do not unload another checkout's node under the same label.
        if str(ROOT).encode() not in active.stdout:
            raise SystemExit('Another checkout owns this launchd label; preserve it and stop it explicitly first')
        if args.action == 'start':
            with urlopen('http://127.0.0.1:18090/health', timeout=5) as response:
                assert response.status == 200
            login_agent(True)
            print('Mac compute already running')
            return
        if args.action == 'stop':
            subprocess.run(['launchctl', 'bootout', service], check=True)
    if args.action == 'stop':
        login_agent(False)
        return
    os.umask(0o077)
    RUNTIME.mkdir(parents=True, exist_ok=True)
    for folder in ('media', 'cache'):
        (RUNTIME / folder).mkdir(exist_ok=True)
    # Only the shared media directory is mounted into Java, read-only.
    (RUNTIME / 'media').chmod(0o755)
    python = ROOT / 'services/singverse-compute/.venv/bin/python'
    ffmpeg = ROOT / '.tools/jellyfin-ffmpeg/ffmpeg'
    ffprobe = ROOT / '.tools/jellyfin-ffmpeg/ffprobe'
    for executable in (python, ffmpeg, ffprobe):
        if not executable.is_file():
            raise SystemExit('Run make compute-setup and make transcode-tools before starting the Mac node')
    # Copy the existing shared credential locally without printing it or changing it.
    with tempfile.TemporaryDirectory(dir=RUNTIME) as directory:
        token = Path(directory) / 'token'
        subprocess.run(['docker', 'cp', 'singverse-app-singverse-java-backend-1:/run/singverse-compute/token', str(token)], check=True, stdout=subprocess.DEVNULL)
        destination = RUNTIME / 'token'
        if destination.exists() and destination.read_bytes() != token.read_bytes():
            raise SystemExit('Existing native token differs; preserve it and reconcile credentials before starting')
        token.chmod(0o600)
        token.replace(destination)
    manifest = {
        'Label': LABEL,
        'ProgramArguments': [str(python), '-m', 'singverse_compute', '--host', '127.0.0.1', '--port', '18090'],
        'WorkingDirectory': str(ROOT / 'services/singverse-compute'),
        'EnvironmentVariables': {
            'COMPUTE_AUTH_TOKEN_FILE': str(RUNTIME / 'token'),
            'COMPUTE_MEDIA_ROOTS': str(RUNTIME / 'media'),
            'COMPUTE_TRANSCODE_ROOT': str(RUNTIME / 'cache'),
            'COMPUTE_FFMPEG': str(ffmpeg), 'COMPUTE_FFPROBE': str(ffprobe),
            'COMPUTE_HARDWARE_BACKEND': 'videotoolbox',
            'COMPUTE_JAVA_ORIGIN': 'http://127.0.0.1:18080',
            'COMPUTE_PROCESS_MEMORY_BYTES': '1073741824',
            **audio,
        },
        'RunAtLoad': True, 'KeepAlive': True, 'ThrottleInterval': 10,
        'StandardOutPath': str(RUNTIME / 'node.log'),
        'StandardErrorPath': str(RUNTIME / 'node-error.log'),
    }
    target = RUNTIME / 'compute.plist'
    target.write_bytes(plistlib.dumps(manifest))
    login_agent(True)
    if active.returncode == 0:
        subprocess.run(['launchctl', 'bootout', service], check=True)
    bootstrap(domain, target)
    for _ in range(120):
        try:
            with urlopen('http://127.0.0.1:18090/health', timeout=1) as response:
                if response.status == 200:
                    print('Mac compute ready on loopback port 18090 (VideoToolbox; audio requires model qualification)')
                    return
        except OSError:
            time.sleep(.25)
    raise SystemExit('Mac node did not become healthy; inspect the private runtime logs')


if __name__ == '__main__':
    main()

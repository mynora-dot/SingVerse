#!/usr/bin/env python3
"""Accept real Go/Nginx images using only disposable containers and networking.

Each invocation creates its own network, fresh container filesystems and a
loopback-only random HTTP port. It never opens an existing deployment or volume.
Run once per backend/frontend image pair to verify each actual CPU architecture.
"""
import argparse
from importlib import util
import json
from pathlib import Path
import secrets
import subprocess
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
LABEL = "singverse.test.run"


def contract_module():
    name = "singverse_delivery_contract"
    spec = util.spec_from_file_location(name, ROOT / "scripts/check-backend-contract.py")
    module = util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


def docker(*args, check=True, input_text=None, timeout=45):
    result = subprocess.run(["docker", *args], cwd=ROOT, input=input_text,
                            text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, timeout=timeout)
    if check and result.returncode:
        raise RuntimeError(f"docker {args[0]} failed: {result.stderr[:2000].strip()}")
    return result


class IsolatedContainers:
    def __init__(self):
        self.run = uuid.uuid4().hex
        prefix = "singverse-go-check-" + self.run[:12]
        self.network = prefix + "-network"
        self.backend = prefix + "-backend"
        self.frontend = prefix + "-frontend"

    def owned(self, kind, name):
        field = ".Config.Labels" if kind == "container" else ".Labels"
        result = docker(kind, "inspect", "--format", '{{index ' + field + ' "' + LABEL + '"}}',
                        name, check=False)
        return result.returncode == 0 and result.stdout.strip() == self.run

    def close(self):
        errors = []
        for name in (self.frontend, self.backend):
            if self.owned("container", name):
                result = docker("rm", "--force", "--volumes", name, check=False)
                if result.returncode:
                    errors.append("owned test container could not be removed")
        if self.owned("network", self.network):
            result = docker("network", "rm", self.network, check=False)
            if result.returncode:
                errors.append("owned test network could not be removed")
        if errors:
            raise RuntimeError("; ".join(errors))


def image(reference):
    details = json.loads(docker("image", "inspect", reference).stdout)
    if len(details) != 1:
        raise AssertionError("expected one already-built image")
    value = details[0]
    if value.get("Os") != "linux" or value.get("Architecture") not in ("amd64", "arm64"):
        raise AssertionError("checker requires an actual Linux amd64 or arm64 image")
    if value.get("Config", {}).get("Volumes"):
        raise AssertionError("test image declares automatic volumes; explicit isolated storage is required")
    return value


def wait_ready(module, base, resources, timeout=90):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            browser = module.Browser(base)
            if browser.call("GET", "/api/health").json() == {"status": "UP"}:
                return
        except (AssertionError, OSError, ValueError):
            pass
        for name in (resources.backend, resources.frontend):
            state = json.loads(docker("inspect", name).stdout)[0]["State"]
            if not state.get("Running"):
                raise AssertionError("owned delivery container exited before becoming ready")
        time.sleep(.2)
    raise AssertionError("isolated Nginx/Go deployment did not become ready")


def runtime_only(name):
    command = r"""set -eu
for executable in java javac mvn node npm go gcc clang; do
  if command -v "$executable" >/dev/null 2>&1; then
    echo 'Unexpected development runtime or compiler in final image' >&2
    exit 1
  fi
done
unexpected=$(find /usr /opt /bin /sbin -type f \
  \( -name java -o -name javac -o -name mvn -o -name node -o -name go \) -print)
test -z "$unexpected"
"""
    docker("exec", name, "sh", "-c", command)


def participant_audio_authorization(admin):
    grant = admin.call("POST", "/api/v1/device-authorizations",
                       {"name": "隔离音轨授权电视"}).json()
    admin.call("POST", "/api/v1/admin/device-authorizations/" + grant["id"] + "/approve",
               {"code": grant["code"]}, csrf=True)
    device = admin.call("POST", "/api/v1/device-authorizations/" + grant["id"] + "/poll",
                        {"pollSecret": grant["pollSecret"]}).json()
    headers = {"Authorization": "Bearer " + device["token"]}
    session = admin.call("POST", "/api/v1/tv/sessions", headers=headers).json()
    invitation = admin.call("POST", "/api/v1/tv/sessions/" + session["id"] + "/invitations",
                            headers=headers).json()
    guest = admin.call("POST", "/api/v1/party/join",
                       {"invitation": invitation["invitation"], "name": "隔离音轨亲友"}).json()
    participant = {"Authorization": "Bearer " + guest["token"]}
    missing = str(uuid.uuid4())
    for prefix in ("party", "tv"):
        path = "/api/v1/" + prefix + "/audio-roles/songs/" + missing
        # Unknown-song and validation responses prove the authenticated guest
        # reached the handler, rather than being rejected as a non-TV actor.
        admin.problem(admin.call("GET", path, headers=participant, status=404), 404)
        admin.problem(admin.call("PUT", path, {}, headers=participant, status=400), 400)
    admin.problem(admin.call("GET", "/api/v1/tv/audio-roles/songs/" + missing +
                             "/accompaniment/" + missing, headers=participant, status=404), 404)
    admin.problem(admin.call("GET", "/api/v1/tv/transcoding/versions/" + missing + "/" + missing,
                             headers=participant, status=403), 403)
    admin.call("DELETE", "/api/v1/admin/tv-devices/" + device["deviceId"], csrf=True)
    admin.problem(admin.call("GET", "/api/v1/party/audio-roles/songs/" + missing,
                             headers=participant, status=401), 401)


def run(args):
    backend, frontend = image(args.backend_image), image(args.frontend_image)
    module = contract_module()
    resources = IsolatedContainers()
    try:
        # Internal bridge networks do not publish host ports on current Docker.
        # Use a private test network and keep its only published port on loopback.
        docker("network", "create", "--label", LABEL + "=" + resources.run,
               resources.network)
        environment = {
            "SINGVERSE_DB_DRIVER": "sqlite", "SINGVERSE_BIND_ADDRESS": "0.0.0.0",
            "SERVER_PORT": "8080", "SQLITE_PATH": "/data/singverse.db",
            "SINGVERSE_AUTH_KEY_FILE": "/secrets/master.key",
            "SINGVERSE_MIGRATIONS_DIR": "/opt/singverse/database/migrations",
            "WEBDAV_METADATA_PATH": "/data/metadata/webdav",
            "METADATA_CACHE_PATH": "/data/metadata/covers",
            "TRANSCODE_VERSION_PATH": "/data/transcoding",
            "SINGVERSE_SONG_CACHE_PATH": "/data/song-cache",
            "SINGVERSE_SONG_CACHE_BYTES": "67108864",
            "SINGVERSE_SONG_CACHE_MINIMUM_FREE_BYTES": "0",
            "AUDIO_DERIVED_PATH": "/data/audio-roles",
            "COMPUTE_NODE_URL": "http://127.0.0.1:9",
            "COMPUTE_AUTH_TOKEN_FILE": "/data/check-compute.token",
            "SINGVERSE_CALLBACK_ORIGIN": "", "SINGVERSE_COMPUTE_AUTO_CALLBACK_ORIGIN": "",
            "SINGVERSE_PUBLIC_ORIGIN": "", "SINGVERSE_TRUSTED_PROXIES": "",
            "SINGVERSE_DIAGNOSTICS_ADDRESS": "",
        }
        flags = []
        for key, value in environment.items():
            flags += ["--env", key + "=" + value]
        docker("create", "--name", resources.backend, "--label", LABEL + "=" + resources.run,
               "--network", resources.network, "--network-alias", "singverse-go-backend",
               "--platform", "linux/" + backend["Architecture"],
               *flags, backend["Id"])
        docker("start", resources.backend)
        docker("create", "--name", resources.frontend, "--label", LABEL + "=" + resources.run,
               "--network", resources.network, "--publish", "127.0.0.1::80",
               "--platform", "linux/" + frontend["Architecture"], frontend["Id"])
        docker("start", resources.frontend)
        details = json.loads(docker("inspect", resources.frontend).stdout)[0]
        published = details["NetworkSettings"]["Ports"].get("80/tcp")
        if not published or len(published) != 1 or published[0]["HostIp"] != "127.0.0.1":
            raise AssertionError("isolated frontend must publish one loopback-only HTTP port")
        base = "http://127.0.0.1:" + published[0]["HostPort"]
        wait_ready(module, base, resources)

        # Execute the complete shared contract before making any persistent edits.
        acceptance = subprocess.run([sys.executable, str(ROOT / "scripts/check-backend-contract.py"),
                                     "--base-url", base, "--isolated"], cwd=ROOT,
                                    text=True, stdout=subprocess.PIPE,
                                    stderr=subprocess.PIPE, timeout=150)
        if acceptance.returncode:
            raise AssertionError("complete backend contract failed: " + acceptance.stderr[:2000].strip())
        contract = json.loads(acceptance.stdout.strip())
        if contract.get("result") != "passed" or contract.get("assertions", 0) < 166:
            raise AssertionError("the complete 166-assertion backend contract did not run")

        admin = module.Browser(base)
        admin.call("POST", "/api/v1/auth/login", {"username": "admin", "password": "admin"}, csrf=True)
        participant_audio_authorization(admin)
        # A configured but unreachable optional compute node must not affect health.
        docker("exec", "-i", resources.backend, "sh", "-c",
               "umask 077; cat > /data/check-compute.token", input_text=secrets.token_hex(32))
        node = admin.call("GET", "/api/v1/compute-node").json()
        admin.check(node["automaticHasToken"] is True, "isolated automatic compute credential was not loaded")
        unavailable = admin.call("POST", "/api/v1/compute-node/test",
                                 {"mode": "AUTO", "revision": node["revision"]}, csrf=True).json()
        admin.check(unavailable["status"] == "UNAVAILABLE", "isolated unavailable compute node was not exercised")
        admin.check(module.Browser(base).call("GET", "/api/health").json() == {"status": "UP"},
                    "business health depends on an optional unavailable compute node")

        playlist_id = str(uuid.uuid4())
        name = "隔离交付持久化验收"
        saved = admin.call("POST", "/api/v1/playlists",
                           {"requestId": playlist_id, "name": name, "description": "owned disposable data", "songIds": []},
                           csrf=True, status=201).json()
        admin.check(saved["id"] == playlist_id, "owned playlist was not persisted")
        username = "delivery_" + resources.run[:12]
        password = "Delivery acceptance " + secrets.token_urlsafe(24)
        admin.call("POST", "/api/v1/auth/username", {"password": "admin", "newUsername": username}, csrf=True)
        admin.call("POST", "/api/v1/auth/login", {"username": username, "password": "admin"}, csrf=True)
        admin.call("POST", "/api/v1/auth/password", {"password": "admin", "newPassword": password}, csrf=True)
        admin.call("POST", "/api/v1/auth/login", {"username": username, "password": password}, csrf=True)
        key_before = docker("exec", resources.backend, "sha256sum", "/secrets/master.key").stdout
        before = json.loads(docker("inspect", resources.backend).stdout)[0]
        docker("restart", "--time", "25", resources.backend, timeout=45)
        wait_ready(module, base, resources)
        after = json.loads(docker("inspect", resources.backend).stdout)[0]
        admin.check(before["Id"] == after["Id"], "restart unexpectedly replaced the container filesystem")
        admin.check(key_before == docker("exec", resources.backend, "sha256sum", "/secrets/master.key").stdout,
                    "restart replaced the credential master key")
        session = admin.call("GET", "/api/v1/auth/session").json()
        admin.check(session["username"] == username and session["defaultPassword"] is False,
                    "restart lost the account or a still-valid session")
        admin.check(admin.call("GET", "/api/v1/playlists/" + playlist_id).json()["name"] == name,
                    "restart lost committed business data")
        fresh = module.Browser(base)
        fresh.call("POST", "/api/v1/auth/login", {"username": username, "password": password}, csrf=True)
        fresh.call("POST", "/api/v1/auth/logout", csrf=True)
        module.Browser(base).call("POST", "/api/v1/auth/login", {"username": "admin", "password": "admin"}, csrf=True, status=401)

        runtime_only(resources.backend)
        runtime_only(resources.frontend)
        expected = ["62", "0"] if backend["Architecture"] == "amd64" else ["183", "0"]
        binary = "/usr/local/bin/singverse"
        machine = docker("exec", resources.backend, "od", "-An", "-t", "u1", "-j", "18", "-N", "2", binary).stdout.split()
        admin.check(machine == expected, "Go binary ELF architecture differs from the tested image architecture")
        process_machine = docker("exec", resources.backend, "od", "-An", "-t", "u1", "-j", "18", "-N", "2", "/proc/1/exe").stdout.split()
        execution_mode = "native"
        if process_machine != expected:
            # Docker Desktop's Rosetta exposes its ARM64 interpreter as exe,
            # while cmdline and mappings identify the running AMD64 Go guest.
            interpreter = docker("exec", resources.backend, "readlink", "/proc/1/exe").stdout.strip()
            command = docker("exec", resources.backend, "cat", "/proc/1/cmdline").stdout.rstrip("\0").split("\0")
            mappings = docker("exec", resources.backend, "cat", "/proc/1/maps").stdout.splitlines()
            interpreter_name = Path(interpreter).name
            known = {"rosetta", "qemu-x86_64", "qemu-x86_64-static", "qemu-aarch64", "qemu-aarch64-static"}
            mapped = any(len(fields := line.split()) >= 6 and fields[5] == binary for line in mappings)
            admin.check(interpreter_name in known and command == [binary] and mapped,
                        "running process is neither the expected Go ELF nor a verified Go guest under a known emulator")
            execution_mode = interpreter_name
        result = {"result": "passed", "backendArchitecture": backend["Architecture"],
                          "frontendArchitecture": frontend["Architecture"],
                          "backendImageId": backend["Id"], "frontendImageId": frontend["Id"],
                          "executionMode": execution_mode,
                          "contractAssertions": contract["assertions"],
                          "deliveryAssertions": admin.assertions + fresh.assertions,
                          "checks": ["real-nginx-go-images", "loopback-random-port", "optional-compute-health",
                                     "participant-audio-authorization",
                                     "same-container-restart", "account-session-data-key-persistence",
                                     "runtime-only-images", "running-elf-architecture"]}
    finally:
        resources.close()
    print(json.dumps(result, ensure_ascii=False))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--backend-image", required=True, help="already-built Linux Go backend image")
    parser.add_argument("--frontend-image", required=True, help="already-built Nginx frontend image")
    args = parser.parse_args()
    try:
        run(args)
    except (AssertionError, OSError, ValueError, KeyError, RuntimeError, subprocess.TimeoutExpired) as error:
        print("isolated Go container acceptance failed: " + str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

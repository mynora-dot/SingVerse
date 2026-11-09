#!/usr/bin/env python3
"""Verify real terminal-only administrator recovery in owned temporary data.

Starts the native Go binary on a random loopback port, drives RESET and both
masked password prompts through a real PTY, and removes only its temporary files.
"""
import argparse
import base64
import hashlib
import hmac
from importlib import util
import json
import os
from pathlib import Path
import pty
import secrets
import select
import struct
import subprocess
import sys
import tempfile
import termios
import threading
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]


def contract_module():
    name = "singverse_recovery_contract"
    spec = util.spec_from_file_location(name, ROOT / "scripts/check-backend-contract.py")
    module = util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


class NativeBackend:
    def __init__(self, binary, env, browser_class):
        self.process = subprocess.Popen([str(binary)], cwd=ROOT, env=env,
                                        stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        self.endpoint = ""
        self.ready = threading.Event()
        self.reader = threading.Thread(target=self.read, daemon=True)
        self.reader.start()
        try:
            deadline = time.monotonic() + 50
            while time.monotonic() < deadline:
                if self.process.poll() is not None:
                    raise AssertionError("owned native backend exited before HTTP startup")
                if self.ready.wait(.1):
                    browser = browser_class(self.endpoint)
                    if browser.call("GET", "/api/health").json() != {"status": "UP"}:
                        raise AssertionError("owned native backend health was not UP")
                    return
            raise AssertionError("owned native backend did not start on a random loopback port")
        except BaseException:
            self.close()
            raise

    def read(self):
        for line in self.process.stdout:
            try:
                value = json.loads(line)
            except (ValueError, UnicodeError):
                continue
            if value.get("msg") == "backend listening":
                address = value.get("address", "")
                if address.startswith("127.0.0.1:") and address.split(":")[-1].isdigit():
                    self.endpoint = "http://" + address
                    self.ready.set()

    def close(self):
        if self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=25)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=5)
        self.reader.join(timeout=2)
        self.process.stdout.close()


def terminal_recovery(binary, env, password=None, confirmation="RESET"):
    master, slave = pty.openpty()
    process = subprocess.Popen([str(binary), "--reset-admin"], cwd=ROOT, env=env,
                               stdin=slave, stdout=slave, stderr=slave,
                               start_new_session=True)
    os.close(slave)
    replies = []
    if password is not None:
        replies = [("输入 RESET 确认管理员恢复：".encode(), confirmation.encode() + b"\n")]
        if confirmation == "RESET":
            replies += [("新密码：".encode(), password.encode() + b"\n"),
                        ("再次输入新密码：".encode(), password.encode() + b"\n")]
    transcript, pending = bytearray(), bytearray()
    index = 0
    try:
        deadline = time.monotonic() + 35
        while time.monotonic() < deadline:
            if select.select([master], [], [], .1)[0]:
                try:
                    data = os.read(master, 16384)
                except OSError:
                    break
                if not data:
                    break
                transcript.extend(data)
                pending.extend(data)
                if len(transcript) > 65536:
                    raise AssertionError("terminal recovery produced excessive output")
                if index < len(replies):
                    prompt, reply = replies[index]
                    position = pending.find(prompt)
                    if position >= 0:
                        del pending[:position + len(prompt)]
                        if index > 0 and termios.tcgetattr(master)[3] & (termios.ECHO | termios.ECHONL):
                            raise AssertionError("password prompt appeared before terminal echo was disabled")
                        os.write(master, reply)
                        index += 1
            elif process.poll() is not None:
                break
        if process.poll() is None:
            process.wait(timeout=3)
        return process.returncode, bytes(transcript), index
    except subprocess.TimeoutExpired as error:
        raise AssertionError("real terminal recovery command timed out") from error
    finally:
        if process.poll() is None:
            process.kill()
            process.wait(timeout=5)
        os.close(master)


def login(browser, username, password, status=200):
    return browser.call("POST", "/api/v1/auth/login", {"username": username, "password": password},
                        csrf=True, status=status).json()


def totp(secret):
    key = base64.b32decode(secret + "=" * (-len(secret) % 8))
    digest = hmac.new(key, struct.pack(">Q", int(time.time()) // 30), hashlib.sha1).digest()
    offset = digest[-1] & 15
    return str((struct.unpack(">I", digest[offset:offset + 4])[0] & 0x7fffffff) % 1000000).zfill(6)


def run(binary):
    if not binary.is_file() or not os.access(binary, os.X_OK):
        raise AssertionError("native Go binary missing; run make go-backend-build or pass --binary")
    dbmate = ROOT / ".tools/dbmate"
    if not dbmate.is_file() or not os.access(dbmate, os.X_OK):
        raise AssertionError("checksum-pinned dbmate missing; run make dbmate-setup")
    module = contract_module()
    with tempfile.TemporaryDirectory(prefix="singverse-go-recovery-") as directory:
        data = Path(directory)
        env = dict(os.environ, SINGVERSE_DB_DRIVER="sqlite", SQLITE_PATH=str(data / "business.sqlite"),
                   SINGVERSE_AUTH_KEY_FILE=str(data / "secrets/master.key"), SINGVERSE_BIND_ADDRESS="127.0.0.1",
                   SERVER_PORT="0", DBMATE_BIN=str(dbmate), SINGVERSE_MIGRATIONS_DIR=str(ROOT / "database/migrations"),
                   WEBDAV_METADATA_PATH=str(data / "metadata/files"), METADATA_CACHE_PATH=str(data / "metadata/covers"),
                   TRANSCODE_VERSION_PATH=str(data / "transcoding"), AUDIO_DERIVED_PATH=str(data / "audio"),
                   SINGVERSE_SONG_CACHE_PATH=str(data / "cache"), SINGVERSE_SONG_CACHE_BYTES="67108864",
                   SINGVERSE_SONG_CACHE_MINIMUM_FREE_BYTES="0", COMPUTE_NODE_URL="", COMPUTE_AUTH_TOKEN_FILE="",
                   SINGVERSE_COMPUTE_AUTO_CALLBACK_ORIGIN="", SINGVERSE_CALLBACK_ORIGIN="", SINGVERSE_PUBLIC_ORIGIN="",
                   SINGVERSE_TRUSTED_PROXIES="", SINGVERSE_DIAGNOSTICS_ADDRESS="", SINGVERSE_TV_RELEASE_DIRECTORY="",
                   SINGVERSE_TV_DISCOVERY_ADDRESS="")
        service = None
        try:
            service = NativeBackend(binary, env, module.Browser)
            browser = module.Browser(service.endpoint)
            browser.check(login(browser, "admin", "admin")["state"] == "authenticated", "fresh administrator failed to login")
            username = "recovery_" + uuid.uuid4().hex[:12]
            browser.call("POST", "/api/v1/auth/username", {"password": "admin", "newUsername": username}, csrf=True)
            login(browser, username, "admin")
            playlist_id = str(uuid.uuid4())
            browser.call("POST", "/api/v1/playlists", {"requestId": playlist_id, "name": "终端恢复业务数据",
                         "description": "temporary recovery acceptance", "songIds": []}, csrf=True, status=201)
            setup = browser.call("POST", "/api/v1/system/parameters/mfa/setup", {"password": "admin"}, csrf=True).json()
            codes = browser.call("POST", "/api/v1/system/parameters/mfa/confirm", {"code": totp(setup["secret"])}, csrf=True).json()["recoveryCodes"]
            browser.check(len(codes) == 10, "MFA fixture did not issue recovery codes")
            browser.check(login(browser, username, "admin")["state"] == "mfaRequired", "MFA fixture did not challenge login")
            browser.call("POST", "/api/v1/auth/mfa/verify", {"code": codes[0]}, csrf=True)
            session = browser.call("GET", "/api/v1/auth/session").json()
            browser.check(session["mfaEnabled"] is True, "MFA fixture was not active before recovery")
            old_cookie = next("singverse_session=" + cookie.value for cookie in browser.jar if cookie.name == "singverse_session")
            before_key = hashlib.sha256((data / "secrets/master.key").read_bytes()).digest()

            # A real TTY does not bypass the deployment lock of a live server.
            code, output, prompts = terminal_recovery(binary, env)
            browser.check(code != 0 and b"backend is already running" in output and prompts == 0,
                          "terminal recovery did not reject a live deployment lock")
            browser.check("新密码：".encode() not in output, "locked recovery reached a password prompt")
            browser.call("GET", "/api/v1/auth/session")
            service.close()
            service = None

            # Even otherwise correct confirmation and passwords cannot be piped.
            password = "Recovered terminal " + secrets.token_urlsafe(24)
            rejected = subprocess.run([str(binary), "--reset-admin"], cwd=ROOT, env=env,
                                      input="RESET\n" + password + "\n" + password + "\n",
                                      text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=35)
            browser.check(rejected.returncode != 0 and "requires an interactive TTY" in rejected.stderr,
                          "piped recovery did not reject non-TTY stdin")
            browser.check(password not in rejected.stdout + rejected.stderr, "non-TTY rejection revealed the supplied password")
            service = NativeBackend(binary, env, module.Browser)
            browser.base = service.endpoint
            browser.check(browser.call("GET", "/api/v1/auth/session").json()["mfaEnabled"] is True,
                          "rejected recovery changed the account or existing session")
            service.close()
            service = None

            cancelled, output, prompts = terminal_recovery(binary, env, password, confirmation="CANCEL")
            browser.check(cancelled != 0 and prompts == 1 and b"recovery cancelled" in output,
                          "recovery did not require the exact RESET confirmation")
            code, output, prompts = terminal_recovery(binary, env, password)
            browser.check(code == 0 and prompts == 3 and "管理员恢复完成，所有会话已撤销。".encode() in output,
                          "real terminal administrator recovery failed")
            browser.check(password.encode() not in output, "terminal echoed the new password")
            browser.check(before_key == hashlib.sha256((data / "secrets/master.key").read_bytes()).digest(),
                          "local administrator recovery replaced the credential master key")

            service = NativeBackend(binary, env, module.Browser)
            browser.base = service.endpoint
            browser.call("GET", "/api/v1/auth/session", status=401)
            stale = module.Browser(service.endpoint)
            stale.call("GET", "/api/v1/auth/session", headers={"Cookie": old_cookie}, status=401)
            fresh = module.Browser(service.endpoint)
            login(fresh, username, "admin", status=401)
            browser.check(login(fresh, username, password)["state"] == "authenticated", "new password did not authenticate directly after recovery")
            account = fresh.call("GET", "/api/v1/auth/session").json()
            browser.check(account["username"] == username and account["defaultPassword"] is False
                          and account["mfaEnabled"] is False and account["recoveryCodesRemaining"] == 0,
                          "terminal recovery did not preserve username and clear MFA/recovery codes")
            browser.check(fresh.call("GET", "/api/v1/playlists/" + playlist_id).json()["name"] == "终端恢复业务数据",
                          "local recovery removed unrelated business data")
            fresh.call("POST", "/api/v1/auth/logout", csrf=True)
            print(json.dumps({"result": "passed", "assertions": browser.assertions + fresh.assertions + stale.assertions,
                              "checks": ["real-pty-reset-confirmation", "masked-double-password", "running-lock-rejection",
                                         "non-tty-rejection", "old-session-revocation", "new-password-login",
                                         "mfa-recovery-code-reset", "username-business-data-key-preservation"]}, ensure_ascii=False))
        finally:
            if service is not None:
                service.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--binary", type=Path, default=ROOT / "services/singverse-go-backend/bin/singverse",
                        help="native Go executable; defaults to make go-backend-build output")
    args = parser.parse_args()
    try:
        run(args.binary.resolve())
    except (AssertionError, OSError, ValueError, KeyError, RuntimeError, StopIteration, subprocess.TimeoutExpired) as error:
        print("isolated Go terminal recovery acceptance failed: " + str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

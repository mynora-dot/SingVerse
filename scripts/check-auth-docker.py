"""Exercise destructive authentication operations ONLY in a new, owned Compose project.

Uses existing local app images. Never targets singverse-app or the development MySQL.
"""
import base64
import hashlib
import hmac
import json
import os
from pathlib import Path
import pty
import select
import struct
import subprocess
import time
import uuid
from http.cookiejar import CookieJar
from urllib.error import HTTPError
from urllib.request import HTTPCookieProcessor, Request, build_opener

root = Path(__file__).resolve().parents[1]
project = "singverse-auth-check-" + uuid.uuid4().hex[:10]
command = ["docker", "compose", "-f", str(root / "compose.app.yaml"), "-p", project]
env = dict(os.environ, SINGVERSE_HTTP_PORT="0", SINGVERSE_HTTP_BIND="127.0.0.1",
           SINGVERSE_PUBLIC_ORIGIN="", SINGVERSE_TRUSTED_PROXIES="")


def compose(*args, capture=False):
  return subprocess.run(command + list(args), env=env, check=True,
                        stdout=subprocess.PIPE if capture else subprocess.DEVNULL,
                        stderr=subprocess.PIPE, text=True).stdout


def client():
  return build_opener(HTTPCookieProcessor(CookieJar()))


def endpoint():
  return "http://" + compose("port", "singverse-frontend", "80", capture=True).strip()


def run():
  compose("up", "-d", "--no-build", "--wait", "--wait-timeout", "120")
  base, browser = endpoint(), client()

  def api(path, method="GET", data=None, status=200):
    headers = {"Accept": "application/json"}
    if method != "GET":
      headers["X-CSRF-Token"] = api("/auth/csrf")["token"]
    if data is not None:
      headers["Content-Type"] = "application/json"
    try:
      response = browser.open(Request(base + "/api/v1" + path, method=method,
                                      headers=headers, data=json.dumps(data).encode() if data is not None else None), timeout=15)
    except HTTPError as error:
      response = error
    with response:
      assert response.code == status, (path, response.code, status)
      content = response.read()
      return json.loads(content) if content else None

  api("/system/parameters", status=401)
  assert api("/auth/login", "POST", {"username": "admin", "password": "admin"})["state"] == "authenticated"
  assert api("/auth/session")["defaultPassword"] is True
  new_password = "Isolated Docker password " + uuid.uuid4().hex
  api("/auth/password", "POST", {"password": "admin", "newPassword": new_password})
  api("/auth/session", status=401)
  api("/auth/login", "POST", {"username": "admin", "password": "admin"}, status=401)
  api("/auth/login", "POST", {"username": "admin", "password": new_password})
  setup = api("/system/parameters/mfa/setup", "POST", {"password": new_password})
  assert setup["qrDataUrl"].startswith("data:image/png;base64,")
  secret = base64.b32decode(setup["secret"])
  digest = hmac.new(secret, struct.pack(">Q", int(time.time()) // 30), hashlib.sha1).digest()
  offset = digest[-1] & 15
  code = str((struct.unpack(">I", digest[offset:offset + 4])[0] & 0x7fffffff) % 1000000).zfill(6)
  codes = api("/system/parameters/mfa/confirm", "POST", {"code": code})["recoveryCodes"]
  assert len(codes) == 10
  before = compose("exec", "-T", "singverse-java-backend", "sha256sum", "/secrets/master.key", capture=True)
  compose("up", "-d", "--no-build", "--force-recreate", "--wait", "--wait-timeout", "120")
  base, browser = endpoint(), client()
  after = compose("exec", "-T", "singverse-java-backend", "sha256sum", "/secrets/master.key", capture=True)
  assert before == after
  assert api("/auth/login", "POST", {"username": "admin", "password": new_password})["state"] == "mfaRequired"
  api("/system/parameters", status=401)
  api("/auth/mfa/verify", "POST", {"code": codes[0]})
  assert api("/auth/session")["recoveryCodesRemaining"] == 9
  print("通过：隔离 Docker 改密、TOTP绑定、重建保留密码/MFA/主密钥、恢复码登录")

  # Exercise the real interactive local command, with a terminal and masked password prompts.
  compose("stop", "singverse-java-backend")
  master, slave = pty.openpty()
  process = subprocess.Popen(command + ["run", "--rm", "--no-deps", "singverse-java-backend", "--reset-admin"],
                             env=env, stdin=slave, stdout=slave, stderr=slave)
  os.close(slave)
  recovery_password = "Recovered Docker password " + uuid.uuid4().hex
  prompts = [(b"Type RESET to continue:", b"RESET\n"),
             (b"New password (15-128 characters):", recovery_password.encode() + b"\n"),
             (b"Repeat new password:", recovery_password.encode() + b"\n")]
  output, index, deadline = b"", 0, time.monotonic() + 90
  try:
    while time.monotonic() < deadline and process.poll() is None:
      if select.select([master], [], [], 1)[0]:
        try:
          output += os.read(master, 65536)
        except OSError:
          break
        if index < len(prompts) and prompts[index][0] in output:
          os.write(master, prompts[index][1])
          index += 1
    assert process.wait(timeout=10) == 0, "本机交互恢复命令失败"
    assert index == 3 and b"Administrator recovered" in output
    assert recovery_password.encode() not in output, "恢复密码不应被终端回显"
  finally:
    if process.poll() is None:
      process.kill()
    os.close(master)
  compose("up", "-d", "--no-build", "--wait", "--wait-timeout", "120")
  base, browser = endpoint(), client()
  assert api("/auth/login", "POST", {"username": "admin", "password": recovery_password})["state"] == "authenticated"
  account = api("/auth/session")
  assert account["mfaEnabled"] is False and account["defaultPassword"] is False
  assert account["recoveryCodesRemaining"] == 0
  print("通过：真实TTY本机恢复、密码无回显、清除MFA和恢复码、恢复后登录")


if __name__ == "__main__":
  try:
    run()
  finally:
    # This random project and its volumes were created exclusively by this test.
    compose("down", "--volumes", "--remove-orphans")
    print("已清理本次隔离测试项目；现有应用与开发数据库未操作")

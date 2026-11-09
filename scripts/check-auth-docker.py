"""Exercise destructive authentication operations ONLY in a new, owned Compose project.

Uses existing local app images. Never targets singverse-go-app, the legacy singverse-app or the development MySQL.
"""
import argparse
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
import tempfile
import time
import uuid
from http.cookiejar import CookieJar
from urllib.error import HTTPError
from urllib.request import HTTPCookieProcessor, ProxyHandler, Request, build_opener

root = Path(__file__).resolve().parents[1]
project = "singverse-auth-check-" + uuid.uuid4().hex[:10]
command = ["docker", "compose", "--project-directory", str(root), "--env-file", "/dev/null",
           "-f", str(root / "infra/docker/compose/compose.app.yaml"), "-p", project]
env = dict(os.environ, SINGVERSE_HTTP_PORT="0", SINGVERSE_HTTP_BIND="127.0.0.1",
           SINGVERSE_PUBLIC_ORIGIN="", SINGVERSE_TRUSTED_PROXIES="")


def compose(*args, capture=False):
  return subprocess.run(command + list(args), env=env, check=True,
                        stdout=subprocess.PIPE if capture else subprocess.DEVNULL,
                        stderr=subprocess.PIPE, text=True).stdout


def client():
  jar = CookieJar()
  browser = build_opener(ProxyHandler({}), HTTPCookieProcessor(jar))
  browser.jar = jar
  return browser


def endpoint():
  address = compose("port", "singverse-frontend", "80", capture=True).strip()
  assert address.startswith("127.0.0.1:") and address.removeprefix("127.0.0.1:").isdigit(), "测试入口必须是回环随机端口"
  return "http://" + address


def up(*options):
  compose("up", "-d", "--no-build", "--pull", "never", *options, "--wait", "--wait-timeout", "120",
          "singverse-go-backend", "singverse-frontend")


def run():
  up()
  base, browser = endpoint(), client()

  def api(path, method="GET", data=None, status=200, extra_headers=None):
    headers = {"Accept": "application/json"}
    if extra_headers:
      headers.update(extra_headers)
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
  before = compose("exec", "-T", "singverse-go-backend", "sha256sum", "/secrets/master.key", capture=True)
  up("--force-recreate")
  base, browser = endpoint(), client()
  after = compose("exec", "-T", "singverse-go-backend", "sha256sum", "/secrets/master.key", capture=True)
  assert before == after
  assert api("/auth/login", "POST", {"username": "admin", "password": new_password})["state"] == "mfaRequired"
  api("/system/parameters", status=401)
  api("/auth/mfa/verify", "POST", {"code": codes[0]})
  assert api("/auth/session")["recoveryCodesRemaining"] == 9
  old_cookie = next("singverse_session=" + value.value for value in browser.jar if value.name == "singverse_session")
  print("通过：隔离 Docker 改密、TOTP绑定、重建保留密码/MFA/主密钥、恢复码登录")

  # Exercise the real interactive local command, with a terminal and masked password prompts.
  compose("stop", "singverse-go-backend")
  master, slave = pty.openpty()
  process = subprocess.Popen(command + ["run", "--rm", "--no-deps", "--pull", "never", "singverse-go-backend", "--reset-admin"],
                             env=env, stdin=slave, stdout=slave, stderr=slave)
  os.close(slave)
  recovery_password = "Recovered Docker password " + uuid.uuid4().hex
  prompts = [("输入 RESET 确认管理员恢复：".encode(), b"RESET\n"),
             ("新密码：".encode(), recovery_password.encode() + b"\n"),
             ("再次输入新密码：".encode(), recovery_password.encode() + b"\n")]
  output, pending, index, deadline = b"", b"", 0, time.monotonic() + 90
  try:
    while time.monotonic() < deadline:
      if select.select([master], [], [], 1)[0]:
        try:
          data = os.read(master, 65536)
        except OSError:
          break
        if not data:
          break
        output += data
        pending += data
        assert len(output) <= 131072, "恢复终端输出超过限制"
        if index < len(prompts):
          position = pending.find(prompts[index][0])
          if position >= 0:
            pending = pending[position + len(prompts[index][0]):]
            os.write(master, prompts[index][1])
            index += 1
      elif process.poll() is not None:
        break
    assert process.wait(timeout=10) == 0, "本机交互恢复命令失败"
    assert index == 3 and "管理员恢复完成，所有会话已撤销。".encode() in output
    assert recovery_password.encode() not in output, "恢复密码不应被终端回显"
  finally:
    if process.poll() is None:
      process.kill()
      process.wait(timeout=10)
    os.close(master)
  up()
  base, browser = endpoint(), client()
  assert before == compose("exec", "-T", "singverse-go-backend", "sha256sum", "/secrets/master.key", capture=True)
  api("/auth/session", status=401, extra_headers={"Cookie": old_cookie})
  api("/auth/login", "POST", {"username": "admin", "password": new_password}, status=401)
  assert api("/auth/login", "POST", {"username": "admin", "password": recovery_password})["state"] == "authenticated"
  account = api("/auth/session")
  assert account["mfaEnabled"] is False and account["defaultPassword"] is False
  assert account["recoveryCodesRemaining"] == 0
  print("通过：真实TTY本机恢复、密码无回显、清除MFA和恢复码、恢复后登录")


if __name__ == "__main__":
  parser = argparse.ArgumentParser(description=__doc__)
  parser.add_argument("--backend-image", default=os.environ.get("GO_BACKEND_IMAGE", "singverse-go-backend:local"))
  parser.add_argument("--frontend-image", default=os.environ.get("GO_FRONTEND_IMAGE", "singverse-frontend:local"))
  args = parser.parse_args()
  services = {}
  for service, reference in (("singverse-go-backend", args.backend_image), ("singverse-frontend", args.frontend_image)):
    details = json.loads(subprocess.check_output(["docker", "image", "inspect", reference], text=True))
    assert len(details) == 1 and details[0]["Os"] == "linux" and details[0]["Architecture"] in ("amd64", "arm64"), "需使用已构建的 Linux Go/Nginx 镜像"
    services[service] = {"image": details[0]["Id"], "platform": "linux/" + details[0]["Architecture"], "pull_policy": "never"}
  services["singverse-go-backend"]["environment"] = {"COMPUTE_NODE_URL": "", "SINGVERSE_COMPUTE_AUTO_CALLBACK_ORIGIN": ""}
  with tempfile.TemporaryDirectory(prefix="singverse-auth-compose-") as directory:
    override = Path(directory) / "images.json"
    override.write_text(json.dumps({"services": services}))
    command += ["-f", str(override)]
    try:
      run()
    finally:
      # This random project and its volumes were created exclusively by this test.
      compose("down", "--volumes", "--remove-orphans")
      print("已清理本次隔离测试项目；现有应用与开发数据库未操作")

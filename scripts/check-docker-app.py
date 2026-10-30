"""验证当前应用 Compose 的公开 HTTP 入口；只使用 Python 标准库。"""

import json
import os
from http.cookiejar import CookieJar
from html.parser import HTMLParser
from pathlib import Path
import subprocess
from urllib.error import HTTPError
from urllib.parse import urljoin, urlparse
from urllib.request import Request, urlopen, build_opener, HTTPCookieProcessor


class Resources(HTMLParser):
  def __init__(self):
    super().__init__()
    self.urls = []

  def handle_starttag(self, tag, attrs):
    for key, value in attrs:
      if key in {"href", "src"} and value and value.endswith((".js", ".css")):
        self.urls.append(value)


def main():
  root = Path(__file__).resolve().parents[1]
  macos = os.environ.get("SINGVERSE_MACOS_COMPUTE") == "1"
  compose = ["docker", "compose", "-f", str(root / "compose.app.yaml")]
  if macos:
    compose += ["-f", str(root / "compose.compute.macos.yaml")]
  endpoint = subprocess.check_output(
    compose + ["port", "singverse-frontend", "80"],
    text=True,
  ).strip().splitlines()[0]
  host, port = endpoint.rsplit(":", 1)
  if host in {"0.0.0.0", "[::]"}:
    host = "127.0.0.1"
  base = f"http://{host}:{port}"
  compute_check = """
import json, sys, urllib.request, urllib.error
from pathlib import Path
origin, token_file = sys.argv[1:]
with urllib.request.urlopen(origin+'/internal/v1/capabilities',timeout=3) as response:
    assert 'media-tags' in json.load(response)['capabilities']
url=origin+'/internal/v1/media/tags'
for authorized, expected in [(False,401),(True,403)]:
    headers={'Content-Type':'application/json'}
    if authorized: headers['Authorization']='Bearer '+Path(token_file).read_text().strip()
    try:
        urllib.request.urlopen(urllib.request.Request(url,data=b'{"path":"/tmp/__outside_media_root__"}',headers=headers),timeout=4)
        raise AssertionError('internal media path should be rejected')
    except urllib.error.HTTPError as error:
        assert error.code==expected; error.close()
"""
  if macos:
    command = [str(root / "services/singverse-compute/.venv/bin/python"), "-c", compute_check, "http://127.0.0.1:18090", str(root / "data/macos-compute/token")]
  else:
    command = compose + ["exec", "-T", "singverse-compute", "python", "-c", compute_check, "http://127.0.0.1:8090", "/run/singverse-compute/token"]
  subprocess.run(command, check=True)
  subprocess.run(compose + ["exec", "-T", "singverse-java-backend", "sh", "-c", "! command -v ffmpeg && ! command -v ffprobe"], check=True)
  print("通过：独立计算服务标签能力、内部鉴权和目录限制；Java镜像无FFmpeg")
  smoke = (root / "services/singverse-compute/tests/smoke/container_media_tags.py").read_text()
  subprocess.run(["docker", "run", "--rm", "--network", "none", "-i", "singverse-compute:local", "python", "-"], input=smoke, text=True, check=True)



  opener = build_opener(HTTPCookieProcessor(CookieJar()))

  def request(path, method="GET", data=None):
    headers = {"Accept": "application/json" if path.startswith("/api") else "*/*"}
    if method != "GET":
      with request("/api/v1/auth/csrf") as response:
        headers["X-CSRF-Token"] = json.load(response)["token"]
    if data is not None:
      headers["Content-Type"] = "application/json"
    return opener.open(Request(urljoin(base + "/", path), method=method, headers=headers,
                               data=json.dumps(data).encode() if data is not None else None), timeout=10)

  for path, expected in [("/", "NAS 管理中心"), ("/admin/", "NAS 管理中心"),
                         ("/party/", "SingVerse · 一起点歌")]:
    with request(path) as response:
      assert response.status == 200
      assert response.headers.get_content_type() == "text/html"
      html = response.read().decode("utf-8")
      assert expected in html, path
      document_url = response.geturl()
    parser = Resources()
    parser.feed(html)
    assert parser.urls, f"{path}: 没有引用任何本地 JS/CSS"
    for reference in parser.urls:
      resource = urljoin(document_url, reference)
      assert urlparse(resource).netloc == urlparse(base).netloc, resource
      with urlopen(resource, timeout=10) as response:
        assert response.status == 200
        content_type = response.headers.get_content_type()
        if reference.endswith(".css"):
          assert content_type == "text/css", resource
        else:
          assert "javascript" in content_type, resource
        body = response.read()
        assert body and b"<!doctype html>" not in body, resource
    print(f"通过：{path} 与 {len(parser.urls)} 个本地资源")

  with request("/api/health") as response:
    assert response.status == 200
    assert json.load(response) == {"status": "UP"}
  print("通过：Nginx → Java /api/health 为 UP")

  with request("/api/v1/auth/login", "POST", {"username": "admin", "password": os.environ.get("SINGVERSE_CHECK_PASSWORD", "admin")}) as response:
    state = json.load(response)["state"]
  if state == "mfaRequired":
    code = os.environ.get("SINGVERSE_CHECK_CODE")
    assert code, "账号已开启 MFA，请通过 SINGVERSE_CHECK_CODE 提供动态码或恢复码"
    with request("/api/v1/auth/mfa/verify", "POST", {"code": code}) as response:
      assert json.load(response)["state"] == "authenticated"
  print("通过：管理员登录与 Cookie 会话")
  # A >16KiB invalid image must reach Java's format validation without writing data.
  with request("/api/v1/auth/csrf") as response:
    csrf = json.load(response)["token"]
  boundary = "singverse-classification-cover-check"
  invalid_image = ("--" + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="invalid.png"\r\nContent-Type: image/png\r\n\r\n').encode() + b"x" * 20000 + ("\r\n--" + boundary + "--\r\n").encode()
  try:
    opener.open(Request(base + "/api/v1/classification/tags/genre-0/cover", data=invalid_image, method="PUT", headers={"Content-Type": "multipart/form-data; boundary=" + boundary, "X-CSRF-Token": csrf}), timeout=10)
    raise AssertionError("Invalid classification image should fail validation")
  except HTTPError as error:
    assert error.code == 400, "Classification cover upload must pass Nginx's managed multipart route"
    assert "图片" in json.load(error)["detail"]
    error.close()
  print("通过：分类封面大请求到达Java格式校验；未写入图片或修改词表")


  with request("/api/v1/compute-node") as response:
    configuration = json.load(response)
    assert configuration["automaticEndpoint"] == ("http://host.docker.internal:18090" if macos else "http://singverse-compute:8090")
    assert configuration["automaticHasToken"] is True
    assert "token" not in configuration and "secret_encrypted" not in configuration
  # Test AUTO draft without saving or replacing the user's selected node.
  with request("/api/v1/compute-node/test", "POST", {"mode":"AUTO", "revision":configuration["revision"]}) as response:
    check = json.load(response)
    assert check["status"] == "AVAILABLE", check["message"]
    assert "media-tags" in check["capabilities"]
  print("通过：管理员性能节点接口自动识别所选部署节点，认证检查可用且不修改配置")

  for method in ["GET", "POST"]:
    try:
      with request("/api/v1/__container_check_missing__", method):
        raise AssertionError(f"{method} 未知接口应返回 404")
    except HTTPError as error:
      assert error.code == 404
      assert "json" in error.headers.get_content_type()
      json.load(error)
      error.close()
    print(f"通过：{method} 未知 API 保持真实 404/JSON")

  try:
    with request("/assets/__container_check_missing__.js"):
      raise AssertionError("缺失资源应返回 404")
  except HTTPError as error:
    assert error.code == 404
    error.close()
  with request("/api/v1/auth/logout", "POST") as response:
    assert response.status == 200
  print(f"通过：缺失资源保持 404、退出撤销会话；测试入口 {base}")


if __name__ == "__main__":
  main()

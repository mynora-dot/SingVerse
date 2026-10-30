"""随机端口真实HTTP；不接触开发数据库、原媒体或外部模型。"""

import json
from pathlib import Path
import socket
import threading
import time
import unittest
from urllib.error import HTTPError
from urllib.request import Request, urlopen

import uvicorn

from singverse_compute.app import app


class ComputeHttpTest(unittest.TestCase):
  @classmethod
  def setUpClass(cls):
    cls.socket = socket.socket()
    cls.socket.bind(("127.0.0.1", 0))
    cls.base = f"http://127.0.0.1:{cls.socket.getsockname()[1]}"
    cls.server = uvicorn.Server(uvicorn.Config(app, log_level="error"))
    cls.thread = threading.Thread(target=cls.server.run, kwargs={"sockets": [cls.socket]}, daemon=True)
    cls.thread.start()
    for _ in range(100):
      if cls.server.started:
        return
      time.sleep(0.02)
    cls.tearDownClass()
    raise RuntimeError("compute test server did not start")

  @classmethod
  def tearDownClass(cls):
    cls.server.should_exit = True
    cls.thread.join(timeout=5)
    cls.socket.close()

  def test_health_is_process_health(self):
    with urlopen(self.base + "/health", timeout=2) as response:
      self.assertEqual(response.status, 200)
      self.assertRegex(response.headers["X-Request-ID"], r"^[a-f0-9]{32}$")
      self.assertEqual(response.headers.get_content_type(), "application/json")
      self.assertEqual(json.load(response), {"status": "UP"})

  def test_unimplemented_capabilities_are_not_advertised(self):
    with urlopen(self.base + "/internal/v1/capabilities", timeout=2) as response:
      self.assertEqual(json.load(response), {
        "service": "singverse-compute", "apiVersion": "1", "capabilities": [],
      })

  def test_no_placeholder_tasks_or_mutations(self):
    for path, method, status in [("/internal/v1/tasks", "POST", 404), ("/health", "POST", 405)]:
      with self.subTest(path=path), self.assertRaises(HTTPError) as raised:
        urlopen(Request(self.base + path, method=method), timeout=2)
      self.assertEqual(raised.exception.code, status)
      self.assertEqual(raised.exception.headers.get_content_type(), "application/problem+json")
      self.assertEqual(json.load(raised.exception)["status"], status)
      raised.exception.close()

  def test_checked_in_contract_matches_served_openapi(self):
    contract = Path(__file__).resolve().parents[4] / "contracts/openapi/compute-internal.openapi.json"
    with urlopen(self.base + "/openapi.json", timeout=2) as response:
      self.assertEqual(json.load(response), json.loads(contract.read_text(encoding="utf-8")))

"""本地启动入口，默认只监听回环地址。"""

import argparse

import uvicorn


def main() -> None:
  parser = argparse.ArgumentParser(description="SingVerse Python 计算节点")
  parser.add_argument("--host", default="127.0.0.1")
  parser.add_argument("--port", type=int, default=8090)
  args = parser.parse_args()
  if not 1 <= args.port <= 65535:
    parser.error("port must be between 1 and 65535")
  uvicorn.run("singverse_compute.app:app", host=args.host, port=args.port, workers=1)


if __name__ == "__main__":
  main()

"""仅提供进程健康与真实能力声明，不执行占位计算任务。"""

from typing import Literal

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel
from starlette.exceptions import HTTPException


class Health(BaseModel):
  status: Literal["UP"] = "UP"


class Capabilities(BaseModel):
  service: Literal["singverse-compute"] = "singverse-compute"
  apiVersion: Literal["1"] = "1"
  # 未安装或实现的编解码/AI能力不能声明可用。
  capabilities: list[str] = []


app = FastAPI(title="SingVerse Compute Internal API", version="0.1.0", docs_url=None, redoc_url=None)


@app.exception_handler(HTTPException)
async def http_error(request: Request, error: HTTPException) -> JSONResponse:
  return JSONResponse(
    status_code=error.status_code,
    media_type="application/problem+json",
    headers=error.headers,
    content={
      "type": "about:blank",
      "title": str(error.detail),
      "status": error.status_code,
      "instance": request.url.path,
    },
  )


@app.get("/health", response_model=Health, summary="进程健康，不表示计算能力可用")
async def health() -> Health:
  return Health()


@app.get("/internal/v1/capabilities", response_model=Capabilities, summary="已实现并可使用的计算能力")
async def capabilities() -> Capabilities:
  return Capabilities()

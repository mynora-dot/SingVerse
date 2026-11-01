"""Remote extraction accepts task references only, never caller-controlled URLs."""
import threading
from fastapi import APIRouter, Depends, Request
from pydantic import BaseModel, ConfigDict, Field
from ...infrastructure.media.remote_tags import RangeFile, extract
from ...core.errors import ComputeError
from ..dependencies import require_internal_auth

PATH = '/internal/v1/media/remote-tags'
router = APIRouter(dependencies=[Depends(require_internal_auth)])
slots = threading.BoundedSemaphore(1)

class Input(BaseModel):
    model_config = ConfigDict(extra='forbid')
    reference: str = Field(pattern=r'^[a-f0-9-]{36}$')
    filename: str = Field(min_length=1,max_length=2048)
    size: int | None = Field(default=None,ge=0)
    deep: bool = False

@router.post(PATH)
def remote_tags(body: Input, request: Request):
    if not slots.acquire(blocking=False): raise ComputeError(429,'Remote metadata reader busy')
    try:
        config = request.app.state.settings
        with RangeFile(config.java_origin,body.reference,request.app.state.internal_auth._token(),body.filename,body.size,body.deep) as source:
            return extract(source,config.ffprobe,config.ffmpeg,config.process_memory_bytes)
    finally: slots.release()

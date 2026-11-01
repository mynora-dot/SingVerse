"""Internal, task-scoped audio analysis and derivative access."""
from fastapi import APIRouter, Depends, Request
from fastapi.responses import FileResponse
from pydantic import BaseModel, ConfigDict, Field
from typing import Literal
from ..dependencies import require_internal_auth
from .transcoding import Input, ID
from pathlib import Path
import hashlib
from ...application.audio_models import MAX_BYTES
from ...application.audio_catalog import BASELINE, manifest
from ...core.errors import ComputeError

router = APIRouter(prefix='/internal/v1/audio-roles', dependencies=[Depends(require_internal_auth)])


class Submit(Input):
    model_config = ConfigDict(extra='forbid')
    id: str = Field(pattern=ID)
    mode: Literal['PROBE', 'FAST', 'ANALYZE', 'SEPARATE']
    backend: Literal['cpu', 'cuda', 'rocm', 'mps', 'openvino'] | None = None


@router.post('/tasks', status_code=202, description='FAST reads paired explicit role labels first; otherwise compares paired vocal scores on three short dual-track excerpts with optional PANNs Cnn6 (fast-vocal-v2), adding up to three excerpts only for sparse evidence. Missing classifier or ambiguous evidence remains UNKNOWN, with no automatic separation fallback. ANALYZE is explicit deep recognition and uses up to five disjoint windows, first trying two. Task result includes state, reason for UNKNOWN, and evidence with analysisVersion=vocal-activity-v2, vocalRatio pairs, votes, informativeWindows and window activity. Silent intervals abstain; conflicting directions or labels remain UNKNOWN. Ratios are evidence, not probabilities. Existing task identifiers retain idempotency; Java creates a new identifier for explicit re-analysis of an UNKNOWN result.')
def submit(request: Request, body: Submit):
    return request.app.state.audio_roles.submit(body.id, body.path, body.reference, body.mode, body.backend)


@router.get('/models')
def models(request: Request, refresh: bool = False):
    return request.app.state.audio_roles.models.status(refresh)


class Backend(BaseModel):
    model_config = ConfigDict(extra='forbid')
    backend: Literal['auto', 'cpu', 'cuda', 'rocm', 'mps', 'openvino', 'xpu', 'rknn']


@router.post('/models/download', status_code=202)
def download(request: Request, model: str = BASELINE):
    return request.app.state.audio_roles.models.download(model)


@router.post('/models/cancel')
def stop_model(request: Request):
    return request.app.state.audio_roles.models.stop()


@router.post('/models/test', status_code=202)
def test_model(request: Request, body: Backend):
    return request.app.state.audio_roles.models.test(body.backend)


@router.post('/models/select')
def select_model(request: Request, body: Backend):
    return request.app.state.audio_roles.models.choose(body.backend)


@router.post('/models/import')
async def import_model(request: Request, model: str = BASELINE):
    manager = request.app.state.audio_roles.models
    specification = manifest(model)
    if not manager.model_writable(model):
        raise ComputeError(409, 'Model directory is missing or read only')
    manager.begin('IMPORTING', model)
    try:
        with manager.stage() as temporary:
            stage = Path(temporary)
            digest = hashlib.sha256()
            filename = '955717e8-8726e21a.th' if model == BASELINE else 'upload.zip'
            size = 0
            with (stage / filename).open('wb') as target:
                async for block in request.stream():
                    if manager.cancel.is_set():
                        raise ComputeError(409, 'Model import cancelled')
                    size += len(block)
                    if size > specification['maxBytes']:
                        raise ComputeError(413, 'Model exceeds manifest size limit')
                    manager.operation['bytes'] = size
                    target.write(block)
                    digest.update(block)
            if model == BASELINE:
                manager.finish_install(stage, digest.hexdigest())
            else:
                # Parsing, hashing and disk IO are isolated from the HTTP event loop.
                from starlette.concurrency import run_in_threadpool
                bundle = stage / 'bundle'
                bundle.mkdir()
                manager.operation['bytes'] = 0
                await run_in_threadpool(manager.import_bundle, stage / filename, bundle, model)
    except BaseException as error:
        manager.failed(error)
        raise
    return dict(manager.operation)


@router.get('/tasks/{identifier}')
def view(request: Request, identifier: str):
    return request.app.state.audio_roles.view(identifier)


@router.post('/tasks/{identifier}/cancel')
def cancel(request: Request, identifier: str):
    return request.app.state.audio_roles.cancel(identifier)


@router.get('/tasks/{identifier}/artifact')
def artifact(request: Request, identifier: str):
    return FileResponse(request.app.state.audio_roles.artifact(identifier), media_type='audio/mp4',
                        headers={'Cache-Control': 'private, no-store', 'X-Content-Type-Options': 'nosniff'})


@router.post('/tasks/{identifier}/release')
def release(request: Request, identifier: str):
    return request.app.state.audio_roles.release(identifier)

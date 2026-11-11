from fastapi import APIRouter, Request, Depends
from ..dependencies import require_internal_auth
from ..schemas import Health, Capabilities
from ..errors import problem_responses

router = APIRouter()

@router.get('/health', response_model=Health, summary='进程健康，不表示计算能力可用')
async def health() -> Health:
    return Health()

@router.get('/internal/v1/capabilities', response_model=Capabilities, summary='已实现并可使用的计算能力')
def capabilities(request: Request) -> Capabilities:
    values = []
    import shutil
    if request.app.state.internal_auth.available and shutil.which(request.app.state.settings.ffprobe) and shutil.which(request.app.state.settings.ffmpeg):
        values += ['media-remote-tags']
    if request.app.state.transcoding.available:
        values += ['media-probe', 'transcode-hls', 'transcode-offline', 'audio-role-fast']
        if request.app.state.audio_roles.fast.available:
            values += ['audio-role-fast-classifier', 'audio-role-fast-v2']
    if request.app.state.audio_roles.available:
        values += ['audio-role-analysis', 'audio-separation']
    return Capabilities(capabilities=values)


@router.get('/internal/v1/status', response_model=Capabilities, dependencies=[Depends(require_internal_auth)], summary='鉴权后的服务身份与能力', responses=problem_responses({401:'Unauthorized', 503:'Node credential file unavailable'}))
def status(request: Request) -> Capabilities:
    return capabilities(request)


from pydantic import BaseModel, ConfigDict, Field

class CallbackCheck(BaseModel):
    model_config = ConfigDict(extra='forbid')
    callbackOrigin: str = Field(min_length=1, max_length=512)
    reference: str = Field(pattern=r'^[a-f0-9-]{36}$')

@router.post('/internal/v1/callback/check', dependencies=[Depends(require_internal_auth)])
def callback_check(request: Request, body: CallbackCheck):
    from ...infrastructure.transcoding.input import validate_origin, NoRedirect
    from ...core.errors import ComputeError
    from urllib.request import Request as HttpRequest, build_opener
    validate_origin(body.callbackOrigin)
    try:
        req = HttpRequest(body.callbackOrigin + '/api/v1/internal/compute-callback/' + body.reference,
                          headers={'Authorization': 'Bearer ' + request.app.state.internal_auth._token()})
        with build_opener(NoRedirect()).open(req, timeout=5) as response:
            if response.status != 200 or response.read(128) != body.reference.encode():
                raise ComputeError(422, 'Business callback identity check failed')
    except OSError as error:
        raise ComputeError(422, 'Business callback unreachable or authentication failed') from error
    return {'available': True}

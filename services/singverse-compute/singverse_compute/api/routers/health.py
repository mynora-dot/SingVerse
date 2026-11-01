from fastapi import APIRouter, Request, Depends
from ..dependencies import require_internal_auth
from ..schemas import Health, Capabilities
from ..errors import problem_responses

router = APIRouter()

@router.get('/health', response_model=Health, summary='进程健康，不表示计算能力可用')
async def health() -> Health:
    return Health()

@router.get('/internal/v1/capabilities', response_model=Capabilities, summary='已实现并可使用的计算能力')
async def capabilities(request: Request) -> Capabilities:
    ready = request.app.state.internal_auth.available and request.app.state.media_tags.available
    values = ['media-tags'] if ready else []
    import shutil
    if request.app.state.internal_auth.available and request.app.state.settings.java_origin and shutil.which(request.app.state.settings.ffprobe) and shutil.which(request.app.state.settings.ffmpeg):
        values += ['media-remote-tags']
    if request.app.state.transcoding.available:
        values += ['media-probe', 'transcode-hls', 'transcode-offline']
    if request.app.state.audio_roles.available:
        values += ['audio-role-analysis', 'audio-separation']
    return Capabilities(capabilities=values)


@router.get('/internal/v1/status', response_model=Capabilities, dependencies=[Depends(require_internal_auth)], summary='鉴权后的服务身份与能力', responses=problem_responses({401:'Unauthorized'}))
async def status(request: Request) -> Capabilities:
    return await capabilities(request)

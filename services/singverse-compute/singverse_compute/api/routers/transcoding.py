"""Authenticated internal execution API. Business authorization remains in Java."""
from dataclasses import asdict
from typing import Literal
from fastapi import APIRouter, Depends, Request
from fastapi.responses import FileResponse
from pydantic import BaseModel, ConfigDict, Field, model_validator
from ...domain.transcoding import Client, Target, decide
from ..dependencies import require_internal_auth

router = APIRouter(prefix='/internal/v1/transcoding', dependencies=[Depends(require_internal_auth)])
ID = r'^[a-f0-9-]{36}$'

class Input(BaseModel):
    model_config = ConfigDict(extra='forbid')
    path: str | None = Field(default=None, min_length=1, max_length=4096)
    reference: str | None = Field(default=None, pattern=ID)
    @model_validator(mode='after')
    def one_source(self):
        if bool(self.path) == bool(self.reference):
            raise ValueError('Exactly one media input is required')
        return self

class ClientInput(BaseModel):
    family: Literal['generic','browser','android','android-tv','fire-tv','ios','ipados','tvos','roku','tizen','webos','kodi','desktop'] = 'generic'
    model_config = ConfigDict(extra='forbid')
    containers: list[str] = Field(default=['mp4'], max_length=32)
    video: list[str] = Field(default=['h264'], max_length=32)
    audio: list[str] = Field(default=['aac'], max_length=32)
    subtitles: list[str] = Field(default=['webvtt'], max_length=32)
    max_width: int = Field(default=1920, ge=2, le=16384)
    max_height: int = Field(default=1080, ge=2, le=16384)
    max_fps: float = Field(default=30, ge=1, le=240)
    max_bitrate: int = Field(default=8000000, ge=64000, le=500000000)
    max_channels: int = Field(default=2, ge=2, le=16)
    max_bit_depth: int = Field(default=8, ge=8, le=16)
    hdr: bool = False
    hdr_formats: list[Literal['HDR10','HLG']] = Field(default=['HDR10','HLG'], max_length=2)
    dovi_profiles: list[int] = Field(default_factory=list, max_length=16)
    dovi_compatibility: list[int] = Field(default_factory=list, max_length=16)
    hls: bool = True
    fmp4: bool = False
    profiles: dict[str,list[str]] = Field(default_factory=dict, max_length=32)
    levels: dict[str,int] = Field(default_factory=dict, max_length=32)

class Negotiate(Input):
    client: ClientInput = Field(default_factory=ClientInput)
    burn_subtitle: bool = False

class TargetInput(BaseModel):
    model_config = ConfigDict(extra='forbid')
    mode: Literal['REMUX','AUDIO_TRANSCODE','TRANSCODE'] = 'TRANSCODE'
    purpose: Literal['REALTIME','OFFLINE'] = 'REALTIME'
    codec: Literal['h264','hevc','av1'] = 'h264'
    width: int = Field(default=1920, ge=2, le=7680)
    height: int = Field(default=1080, ge=2, le=4320)
    bitrate: int = Field(default=8000000, ge=64000, le=100000000)
    audio_bitrate: int = Field(default=192000, ge=32000, le=512000)
    max_channels: int = Field(default=2, ge=2, le=16)
    fps: float = Field(default=30, ge=1, le=120)
    fmp4: bool = False
    start: float = Field(default=0, ge=0, le=86400)
    audio_id: str | None = Field(default=None, pattern=r'^stream:[0-9]{1,3}$')
    subtitle_id: str | None = Field(default=None, pattern=r'^stream:[0-9]{1,3}$')
    burn_subtitle: bool = False
    @model_validator(mode='after')
    def valid_pipeline(self):
        if self.burn_subtitle and (not self.subtitle_id or self.mode != 'TRANSCODE'):
            raise ValueError('Burn-in requires a subtitle and video transcoding')
        return self

class Create(Input):
    id: str = Field(pattern=ID)
    fingerprint: str = Field(min_length=1, max_length=128)
    target: TargetInput = Field(default_factory=TargetInput)

@router.get('/capabilities')
def capabilities(request: Request):
    service = request.app.state.transcoding
    features = service.backends.features()
    backends = service.backends.inspect(refresh=True)
    for backend in backends:
        verified = service.encoder.pipelines.verified(backend['backend'])
        backend['verifiedPipelines'] = verified
        ready=any(c['samplePassed'] for c in backend['codecs'].values())
        backend['decodeAcceleration'] = ready and any(p['hardwareDecode'] for p in verified)
        backend['toneMappingAcceleration'] = ready and any(p['gpuToneMapping'] for p in verified)
        backend['zeroCopy'] = ready and any(p['zeroCopy'] for p in verified)
    hdr = ['HDR10_SOFTWARE','HLG_SOFTWARE'] if features.get('tonemapx') or features.get('zscale') and features.get('tonemap') else []
    return {'available':service.available, 'backends':backends,
            'outputCodecs':sorted({codec for backend in backends for codec, value in backend['codecs'].items() if value['samplePassed']}),
            'pipelineVersion':'2', 'hardwareDecode':any(b['decodeAcceleration'] for b in backends),
            'hdr':hdr, 'dolbyVisionProfiles':[5,8] if features.get('tonemapx') else [],
            'filters':features, 'limits':{'processMemoryBytes':service.settings.process_memory_bytes,'transcodeConcurrency':service.settings.transcode_concurrency,'cacheBytes':service.settings.cache_bytes},
            'subtitleBurnIn':(['ass','ssa','subrip','webvtt','mov_text'] if features['subtitles'] else []) + (['hdmv_pgs_subtitle','dvd_subtitle','dvb_subtitle','xsub'] if features['overlay'] else [])}

@router.post('/probe')
def probe(request: Request, body: Input):
    return asdict(request.app.state.transcoding.inspect(body.path, body.reference))

@router.post('/negotiate')
def negotiate(request: Request, body: Negotiate):
    media = request.app.state.transcoding.inspect(body.path, body.reference)
    plan = decide(media, Client(**body.client.model_dump()), body.burn_subtitle)
    return {**asdict(plan), 'media':asdict(media)}

@router.post('/executions', status_code=202)
def create(request: Request, body: Create):
    return request.app.state.transcoding.create(body.id, body.path, body.reference, body.fingerprint, Target(**body.target.model_dump()))

@router.get('/executions/{identifier}')
def status(request: Request, identifier: str):
    return request.app.state.transcoding.view(identifier)

@router.post('/executions/{identifier}/lease')
def lease(request: Request, identifier: str):
    return request.app.state.transcoding.renew(identifier)

@router.post('/executions/{identifier}/cancel')
def cancel(request: Request, identifier: str):
    return request.app.state.transcoding.cancel(identifier)

@router.delete('/executions/{identifier}', status_code=204)
def remove(request: Request, identifier: str):
    request.app.state.transcoding.remove(identifier)

@router.get('/executions/{identifier}/artifacts/{name}')
def artifact(request: Request, identifier: str, name: str):
    service = request.app.state.transcoding
    path = service.artifact(identifier, name)
    service.renew(identifier)
    mime = {'m3u8':'application/vnd.apple.mpegurl','ts':'video/mp2t','m4s':'video/iso.segment','mp4':'video/mp4','vtt':'text/vtt'}[path.suffix[1:]]
    return FileResponse(path, media_type=mime, headers={'Cache-Control':'private, no-store','X-Content-Type-Options':'nosniff'})

@router.post('/executions/{identifier}/acknowledge')
def acknowledge(request: Request, identifier: str):
    return request.app.state.transcoding.acknowledge(identifier)

class HardwareSelection(BaseModel):
    model_config = ConfigDict(extra='forbid')
    backend: Literal['deployment','auto','software','qsv','vaapi','nvenc','amf','videotoolbox','rkmpp']

class HardwareSave(HardwareSelection):
    cpuFallback: bool | None = None
    revision: int = Field(ge=0)

@router.get('/hardware-settings')
def hardware_settings(request: Request):
    return request.app.state.hardware_settings.read()

@router.post('/hardware-settings/test')
def test_hardware(request: Request, body: HardwareSelection):
    return request.app.state.hardware_settings.test(body.backend)

@router.post('/hardware-settings')
def save_hardware(request: Request, body: HardwareSave):
    return request.app.state.hardware_settings.save(body.backend, body.revision, body.cpuFallback)

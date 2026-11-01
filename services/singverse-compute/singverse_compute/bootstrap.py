"""Composition root: configuration, adapters, services, then HTTP surface."""
from fastapi import FastAPI
from .core.config import Settings
from .application.media_tags import MediaTagService
from .infrastructure.security import InternalAuth
from .infrastructure.filesystem import RootedMediaPaths
from .infrastructure.media.ffprobe import FFprobeReader
from .api.errors import register_errors
from .api.middleware import InternalTaskBoundary, RequestContext
from .api.routers import health, media, transcoding, remote_tags, audio_roles
from .application.audio_roles import AudioRoles
from .application.fast_audio_roles import FastAudioRoles
from .application.audio_models import AudioModels
from .application.transcoding import Transcoding
from .infrastructure.transcoding.probe import MediaProbe
from .infrastructure.transcoding.backends import Backends
from .infrastructure.transcoding.encoder import Encoder
from .infrastructure.transcoding.input import remote_input
from .infrastructure.transcoding.settings import HardwareSettings
from contextlib import asynccontextmanager

def create_app(settings: Settings | None = None) -> FastAPI:
    config = settings if settings is not None else Settings.from_env()
    auth = InternalAuth(config.token_file)
    auth.initialize()
    paths = RootedMediaPaths(config.media_roots)
    probe = MediaProbe(config.ffprobe, memory_limit=config.process_memory_bytes)
    backends = Backends(config.ffmpeg, config.hardware_backend, config.hardware_device, memory_limit=config.process_memory_bytes)
    execution = Transcoding(config, paths, auth, probe, Encoder(config.ffmpeg, probe, backends, memory_limit=config.process_memory_bytes), backends, remote_input)
    hardware_settings = HardwareSettings(config.transcode_root, backends)
    execution.hardware_settings = hardware_settings
    @asynccontextmanager
    async def lifespan(app):
        execution.start()
        app.state.audio_roles.models.start()
        try:
            yield
        finally:
            app.state.audio_roles.models.close()
            app.state.audio_roles.close()
            execution.close()
    app = FastAPI(lifespan=lifespan, title='SingVerse Compute Internal API', version='0.1.0', docs_url=None, redoc_url=None)
    app.state.settings = config
    app.state.internal_auth = auth
    app.state.media_tags = MediaTagService(RootedMediaPaths(config.media_roots), FFprobeReader(config.ffprobe), config.media_concurrency)
    app.state.transcoding = execution
    app.state.audio_roles = AudioRoles(execution)
    app.state.audio_roles.models = AudioModels(app.state.audio_roles)
    app.state.audio_roles.fast = FastAudioRoles(app.state.audio_roles)
    app.state.hardware_settings = hardware_settings
    register_errors(app)
    app.include_router(health.router)
    app.include_router(media.router)
    app.include_router(remote_tags.router)
    app.include_router(transcoding.router)
    app.include_router(audio_roles.router)
    app.add_middleware(InternalTaskBoundary, auth=auth, paths=frozenset({media.TAG_PATH, remote_tags.PATH, '/internal/v1/transcoding/probe', '/internal/v1/transcoding/negotiate', '/internal/v1/transcoding/executions', '/internal/v1/transcoding/hardware-settings', '/internal/v1/transcoding/hardware-settings/test', '/internal/v1/audio-roles/tasks', '/internal/v1/audio-roles/models/download', '/internal/v1/audio-roles/models/cancel', '/internal/v1/audio-roles/models/test', '/internal/v1/audio-roles/models/select'}), limit=config.request_limit)
    app.add_middleware(RequestContext)
    return app

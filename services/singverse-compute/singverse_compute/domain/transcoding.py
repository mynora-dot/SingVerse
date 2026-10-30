"""Immutable media descriptions and playback plans, independent of HTTP and tools."""
from dataclasses import dataclass, field

@dataclass(frozen=True)
class Stream:
    id: str
    index: int
    kind: str
    codec: str
    profile: str = ''
    level: int = 0
    width: int = 0
    height: int = 0
    fps: float = 0
    bit_depth: int = 8
    channels: int = 0
    language: str = 'und'
    title: str = ''
    hdr: str = ''
    dv_profile: int = 0
    dv_compatibility: int = 0
    dv_rpu: bool = False
    dv_el: bool = False
    interlaced: bool = False
    rotation: int = 0

@dataclass(frozen=True)
class Media:
    container: str
    duration: float
    bitrate: int
    streams: tuple[Stream, ...]

@dataclass(frozen=True)
class Client:
    family: str = 'generic'
    containers: tuple[str, ...] = ('mp4',)
    video: tuple[str, ...] = ('h264',)
    audio: tuple[str, ...] = ('aac',)
    subtitles: tuple[str, ...] = ('webvtt',)
    max_width: int = 1920
    max_height: int = 1080
    max_fps: float = 30
    max_bitrate: int = 8000000
    max_channels: int = 2
    max_bit_depth: int = 8
    hdr: bool = False
    hdr_formats: tuple[str, ...] = ('HDR10','HLG')
    dovi_profiles: tuple[int, ...] = ()
    dovi_compatibility: tuple[int, ...] = ()
    hls: bool = True
    fmp4: bool = False
    profiles: dict[str, tuple[str, ...]] = field(default_factory=dict)
    levels: dict[str, int] = field(default_factory=dict)

@dataclass(frozen=True)
class Target:
    mode: str = 'TRANSCODE'
    purpose: str = 'REALTIME'
    codec: str = 'h264'
    width: int = 1920
    height: int = 1080
    bitrate: int = 8000000
    audio_bitrate: int = 192000
    max_channels: int = 2
    fps: float = 30
    fmp4: bool = False
    start: float = 0
    audio_id: str | None = None
    subtitle_id: str | None = None
    burn_subtitle: bool = False

@dataclass(frozen=True)
class Plan:
    mode: str
    reasons: tuple[str, ...]


def decide(media: Media, client: Client, burn_subtitle: bool = False) -> Plan:
    video = next((s for s in media.streams if s.kind == 'video'), None)
    audio = [s for s in media.streams if s.kind == 'audio']
    reasons = []
    if video:
        checks = {'VIDEO_CODEC': video.codec not in client.video,
                  'VIDEO_PROFILE': bool(client.profiles.get(video.codec)) and video.profile not in client.profiles[video.codec],
                  'VIDEO_LEVEL': video.level > client.levels.get(video.codec, 999),
                  'RESOLUTION': video.width > client.max_width or video.height > client.max_height,
                  'FRAMERATE': video.fps > client.max_fps,
                  'BIT_DEPTH': video.bit_depth > client.max_bit_depth,
                  'HDR': bool(video.hdr) and (not client.hdr or (video.hdr == 'DOVI' and (video.dv_profile not in client.dovi_profiles or video.dv_compatibility not in client.dovi_compatibility)) or (video.hdr != 'DOVI' and video.hdr not in client.hdr_formats)),
                  'SUBTITLE_BURN_IN': burn_subtitle}
        reasons.extend(k for k, v in checks.items() if v)
    if media.bitrate > client.max_bitrate:
        reasons.append('BITRATE')
    if reasons:
        return Plan('TRANSCODE', tuple(reasons))
    if any(s.codec not in client.audio or s.channels > client.max_channels for s in audio):
        return Plan('AUDIO_TRANSCODE', ('AUDIO_INCOMPATIBLE',))
    if media.container not in client.containers:
        return Plan('REMUX', ('CONTAINER',))
    return Plan('DIRECT', ())

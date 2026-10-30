import { authRequest } from '../../core/auth/api'

export type SourceTrack = { id: string; kind: string; codec: string; title: string; language: string; channels: number }
export type Resource = { subtitleUrl?: string; id?: string; mode: string; status: string; state?: string; error?: string; url: string; offset: number;
  media?: { duration: number; streams: SourceTrack[] };
  execution?: { pipeline?: { name: string; hardwareDecode: boolean; zeroCopy: boolean; gpuToneMapping: boolean; dolbyVision: boolean; fallbackReason: string }; error?: string; media?: { duration: number; streams: SourceTrack[] }; reasons?: string[] } }
export type OfflineTask = { id: string; songId: string; state: string; error: string }
export function browserCapabilities() {
  const media = document.createElement('video')
  const supports = (mime: string) => !!media.canPlayType(mime)
  const nativeHls = supports('application/vnd.apple.mpegurl')
  const mse = typeof MediaSource !== 'undefined' && MediaSource.isTypeSupported('video/mp4; codecs="avc1.42E01E"')
  return {
    family: 'browser',
    containers: ['mp4', ...(supports('video/webm') ? ['webm'] : []), ...(supports('audio/mpeg') ? ['mp3'] : []), ...(supports('audio/flac') ? ['flac'] : [])],
    video: [ ...(supports('video/mp4; codecs="avc1.42E01E"') ? ['h264'] : []), ...(supports('video/webm; codecs="vp9"') ? ['vp9'] : []),
      ...(supports('video/mp4; codecs="hvc1.1.6.L93.B0"') ? ['hevc'] : []), ...(supports('video/mp4; codecs="av01.0.05M.08"') ? ['av1'] : []) ],
    audio: [ ...(supports('audio/mp4; codecs="mp4a.40.2"') ? ['aac'] : []), ...(supports('audio/mpeg') ? ['mp3'] : []),
      ...(supports('audio/flac') ? ['flac'] : []), ...(supports('audio/webm; codecs="opus"') ? ['opus'] : []) ],
    hls: nativeHls || mse, fmp4: mse, max_width: 1920, max_height: 1080, max_fps: 30,
    max_bitrate: 8000000, max_bit_depth: 8, max_channels: 2, hdr: false,
    profiles: { h264: ['Constrained Baseline', 'Baseline', 'Main', 'High'] }, levels: { h264: 41 },
  }
}
export const playback = {
  start: (song: string, options: object) => authRequest<Resource>(`/api/v1/transcoding/playback/${encodeURIComponent(song)}`, 'POST', { client: browserCapabilities(), ...options }, 45000),
  status: (id: string) => authRequest<Resource>(`/api/v1/transcoding/sessions/${id}`),
  cancel: (id: string) => authRequest(`/api/v1/transcoding/sessions/${id}/cancel`, 'POST'),
  offline: (songIds: string[], quality: string) => authRequest<string[]>('/api/v1/transcoding/offline', 'POST', { songIds, quality }),
  tasks: () => authRequest<OfflineTask[]>('/api/v1/transcoding/offline'),
  cancelOffline: (id: string) => authRequest(`/api/v1/transcoding/offline/${id}/cancel`, 'POST'),
  deleteOffline: (id: string) => authRequest(`/api/v1/transcoding/offline/${id}`, 'DELETE'),
}

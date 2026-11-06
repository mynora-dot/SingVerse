import { authRequest } from '../../core/auth/api'

export type Period = '7d' | '30d' | 'all'
export type PlaybackStatus = 'PLAYING' | 'COMPLETED' | 'SKIPPED' | 'STOPPED' | 'ERROR' | 'INTERRUPTED'
export interface PlaybackEntry {
  id: string
  songId: string
  title: string
  artists: string[]
  versionLabel: string
  sourceName: string
  clientType: 'TV' | 'MOBILE' | 'ADMIN'
  deviceName: string
  requestedBy: string
  startedAt: string
  endedAt: string | null
  playedMs: number
  durationMs: number | null
  status: PlaybackStatus
  effective: boolean
}
export interface PopularSong {
  songId: string
  title: string
  artists: string[]
  versionLabel: string
  sourceName: string
  playCount: number
  effectivePlayCount: number
  completedCount: number
  lastPlayedAt: string
}
export interface PlaybackSummary { totalPlays: number; effectivePlays: number; playedSongs: number; playedMs: number }
export interface Page<T> { items: T[]; page: number; size: number; totalElements: number }
export interface PlaybackQuery { q?: string; period?: Period; songId?: string; page?: number; size?: number }
function query(input: PlaybackQuery) {
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(input)) if (value !== undefined && value !== '') params.set(key, String(value))
  return params.toString()
}
export const playbackHistory = {
  list: (input: PlaybackQuery) => authRequest<Page<PlaybackEntry>>(`/api/v1/playback-history?${query(input)}`),
  popular: (input: PlaybackQuery) => authRequest<Page<PopularSong>>(`/api/v1/playback-history/popular?${query(input)}`),
  summary: (input: Pick<PlaybackQuery, 'q' | 'period' | 'songId'>) => authRequest<PlaybackSummary>(`/api/v1/playback-history/summary?${query(input)}`),
}

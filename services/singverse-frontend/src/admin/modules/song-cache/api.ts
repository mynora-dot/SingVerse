import { authRequest } from '../../core/auth/api'

export type CachePolicy = 'HYBRID' | 'MANUAL' | 'LRU' | 'LFU' | 'TTL' | (string & {})
export interface CacheSettings {
  maxBytes: number
  policy: CachePolicy
  ttlHours: number
  revision: number
  highWatermarkPercent?: number
  lowWatermarkPercent?: number
}
export interface CacheEntry {
  id: string
  songId?: string
  title?: string
  artist?: string
  mediaType: 'VIDEO' | 'AUDIO'
  bytes: number
  cachedBytes?: number
  residueBytes?: number
  sourceBytes?: number
  complete?: boolean
  createdAt: string
  lastAccessAt: string
  hits: number
  leased: boolean
  pinned?: boolean
  pinRevision?: number
  protectionReasons?: string[]
  lifecycle?: string
  H?: number
  R?: number
  F?: number
  score?: number
  priority?: number
  playCount7d?: number
  lastPlayedAt?: string | null
  state?: 'ACTIVE' | 'DELETING'
  target: { mode?: string; codec?: string; width?: number; height?: number; start?: number; audio_id?: string | null; sourceBytes?: number }
}
export interface CachePin { songId: string; title?: string; artist?: string; pinned: boolean; revision: number; hasCache?: boolean }
export interface CachePins { items: CachePin[]; page: number; size: number; totalElements: number }
export type CleanupState = 'QUEUED' | 'RUNNING' | 'CANCELLING' | 'SUCCEEDED' | 'PARTIAL' | 'BLOCKED' | 'FAILED' | 'CANCELLED' | 'INTERRUPTED'
export interface CleanupJob {
  id: string
  state: CleanupState
  removed: number
  skipped: number
  failed: number
  freedBytes: number
  usageBeforeBytes: number
  usageAfterBytes: number
  targetBytes: number
  targetReached: boolean
  spaceInsufficient: boolean
  shortfallBytes: number
  skippedByReason: Record<string, number>
  errors: ({ id?: string; entryId?: string; code?: string; message?: string } | string)[]
}
export interface CleanupRequest { mode: 'POLICY' | 'CLEAR' | 'DELETE'; entryId?: string; confirmRecent?: boolean }
export interface CachePage {
  settings: CacheSettings
  usageBytes: number
  entryCount: number
  items: CacheEntry[]
  page: number
  size: number
  totalElements: number
  usageReady?: boolean
  statisticsReady?: boolean
  committedBytes?: number
  reservedBytes?: number
  cleanupGeneration?: number
}

const base = '/api/v1/transcoding/cache'
export const songCache = {
  list: (page = 0, size = 20) => authRequest<CachePage>(`${base}?page=${page}&size=${size}`),
  save: (settings: CacheSettings) => authRequest<CacheSettings>(`${base}/settings`, 'PUT', settings),
  remove: (id: string, confirmRecent = false) => authRequest<void>(`${base}/${encodeURIComponent(id)}${confirmRecent ? '?confirmRecent=true' : ''}`, 'DELETE'),
  clear: () => authRequest<{ removed: number; skipped: number }>(`${base}/clear`, 'POST'),
  pins: (page = 0, size = 20) => authRequest<CachePins>(`${base}/pins?page=${page}&size=${size}`),
  pin: (songId: string, pinned: boolean, revision: number) => authRequest<{ pinned: boolean; revision: number }>(`${base}/songs/${encodeURIComponent(songId)}/pin`, 'PUT', { pinned, revision }),
  cleanup: (input: CleanupRequest) => authRequest<CleanupJob>(`${base}/cleanup-jobs`, 'POST', input),
  job: (id: string) => authRequest<CleanupJob>(`${base}/cleanup-jobs/${encodeURIComponent(id)}`),
  cancelJob: (id: string) => authRequest<CleanupJob>(`${base}/cleanup-jobs/${encodeURIComponent(id)}/cancel`, 'POST'),
}

import { authRequest } from '../../core/auth/api'

export type CachePolicy = 'LRU' | 'LFU' | 'TTL' | 'MANUAL'
export interface CacheSettings {
  maxBytes: number
  policy: CachePolicy
  ttlHours: number
  revision: number
}
export interface CacheEntry {
  id: string
  songId?: string
  title?: string
  artist?: string
  mediaType: 'VIDEO' | 'AUDIO'
  bytes: number
  createdAt: string
  lastAccessAt: string
  hits: number
  leased: boolean
  target: { mode?: string; codec?: string; width?: number; height?: number; start?: number; audio_id?: string | null }
}
export interface CachePage {
  settings: CacheSettings
  usageBytes: number
  entryCount: number
  items: CacheEntry[]
  page: number
  size: number
  totalElements: number
}

const base = '/api/v1/transcoding/cache'
export const songCache = {
  list: (page = 0, size = 20) => authRequest<CachePage>(`${base}?page=${page}&size=${size}`),
  save: (settings: CacheSettings) => authRequest<CacheSettings>(`${base}/settings`, 'PUT', settings),
  remove: (id: string) => authRequest<void>(`${base}/${encodeURIComponent(id)}`, 'DELETE'),
  clear: () => authRequest<{ removed: number; skipped: number }>(`${base}/clear`, 'POST'),
}

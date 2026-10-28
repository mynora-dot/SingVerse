import { authRequest } from '../../../core/auth/api'
import type { AppliedMetadata, MetadataBatch, MetadataSettings, MusicProvider, SearchResult } from '../types'
const base = '/api/v1/metadata'
const segment = encodeURIComponent
export const metadata = {
  settings: () => authRequest<MetadataSettings>(`${base}/settings`),
  saveSettings: (input: MetadataSettings) => authRequest<MetadataSettings>(`${base}/settings`, 'PUT', input),
  search: (songId: string, keyword: string, providers: MusicProvider[]) => authRequest<SearchResult>(`${base}/songs/${segment(songId)}/search`, 'POST', { keyword, providers }, 90000),
  apply: (songId: string, candidateId: string, fields: string[]) => authRequest<AppliedMetadata>(`${base}/songs/${segment(songId)}/apply`, 'POST', { candidateId, fields }),
  start: (all: boolean, songIds: string[]) => authRequest<MetadataBatch>(`${base}/batches`, 'POST', { all, songIds }, 30000),
  latest: () => authRequest<MetadataBatch | undefined>(`${base}/batches/latest`),
  batch: (id: string, page = 0, status = '', q = '', sort = 'DEFAULT') => authRequest<MetadataBatch>(`${base}/batches/${segment(id)}?${new URLSearchParams({ page: String(page), size: '20', status, q, sort })}`),
  pause: (id: string) => authRequest<MetadataBatch>(`${base}/batches/${segment(id)}/pause`, 'POST'),
  resume: (id: string) => authRequest<MetadataBatch>(`${base}/batches/${segment(id)}/resume`, 'POST'),
  cancel: (id: string) => authRequest<MetadataBatch>(`${base}/batches/${segment(id)}/cancel`, 'POST'),
  retry: (id: string, itemId: string) => authRequest<MetadataBatch>(`${base}/batches/${segment(id)}/items/${segment(itemId)}/retry`, 'POST'),
  applyItem: (id: string, itemId: string, candidateId: string, fields: string[]) => authRequest<AppliedMetadata>(`${base}/batches/${segment(id)}/items/${segment(itemId)}/apply`, 'POST', { candidateId, fields }),
  candidateCover: (id: string) => `${base}/candidates/${segment(id)}/cover`,
  songCover: (id: string) => `${base}/songs/${segment(id)}/cover`,
}

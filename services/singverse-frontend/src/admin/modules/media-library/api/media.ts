import { authRequest } from '../../../core/auth/api'

import type { MediaSource, SourceInput, SourcePage, SongPage, ScanResult, ScanTask, DirectoryPage } from '../types'
export type { MediaSource, SourceInput, SourcePage, SourceType, ScanTask, DirectoryPage } from '../types'

const base = '/api/v1/media-sources'
export const media = {
  directories: (path = '', page = 0) => authRequest<DirectoryPage>(`${base}/local-directories?${new URLSearchParams({ path, page: String(page) })}`),
  list: (page = 0, size = 20, filters?: { q: string; type: string; profileId: string }) => {
    const query = new URLSearchParams({ page: String(page), size: String(size) })
    if (filters) for (const [key, value] of Object.entries(filters)) if (value.trim()) query.set(key, value.trim())
    return authRequest<SourcePage>(`${base}?${query}`)
  },
  save: (input: SourceInput, id?: string) => authRequest<MediaSource>(id ? `${base}/${id}` : base, id ? 'PUT' : 'POST', input),
  scan: (id: string) => authRequest<ScanResult>(`${base}/${encodeURIComponent(id)}/scan`, 'POST', undefined, 60000),
  createTask: (id: string) => authRequest<ScanTask>(`${base}/${encodeURIComponent(id)}/scan-tasks`, 'POST'),
  latestTask: (id: string) => authRequest<ScanTask | undefined>(`${base}/${encodeURIComponent(id)}/scan-tasks/latest`),
  task: (id: string, taskId: string) => authRequest<ScanTask>(`${base}/${encodeURIComponent(id)}/scan-tasks/${encodeURIComponent(taskId)}`),
  cancelTask: (id: string, taskId: string) => authRequest<ScanTask>(`${base}/${encodeURIComponent(id)}/scan-tasks/${encodeURIComponent(taskId)}/cancel`, 'POST'),
  songs: (id: string, page = 0, size = 20) => authRequest<SongPage>(`${base}/${encodeURIComponent(id)}/songs?page=${page}&size=${size}`),
  remove: (id: string) => authRequest(`${base}/${id}`, 'DELETE'),
}

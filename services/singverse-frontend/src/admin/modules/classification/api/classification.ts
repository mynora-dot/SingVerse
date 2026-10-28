import { authRequest } from '../../../core/auth/api'
import type { ClassState, Dimension, History, Preview, SaveSettings, Settings, Start, Tag, Task } from '../types'
const base = '/api/v1/classification'
const send = <T>(path: string, method: string, body?: unknown) => authRequest<T>(base + path, method, body, path === '/settings/test' ? 190000 : 60000)
export const classification = {
  tags: () => authRequest<Tag[]>(base + '/tags'),
  saveTag: (tag: Omit<Tag, 'id' | 'count'>, id?: string) => send<Tag>('/tags' + (id ? '/' + id : ''), id ? 'PUT' : 'POST', tag),
  settings: () => authRequest<Settings>(base + '/settings'),
  saveSettings: (settings: SaveSettings) => send<Settings>('/settings', 'PUT', settings),
  test: () => send<{ success: boolean; message: string }>('/settings/test', 'POST'),
  state: (id: string) => authRequest<ClassState>(base + '/songs/' + id),
  change: (id: string, request: { requestId: string; revision: number; dimensions: Partial<Record<Dimension, string[]>>; lockedDimensions: Dimension[] }) => send<ClassState>('/songs/' + id, 'PUT', request),
  history: (id: string, page = 0) => authRequest<History[]>(`${base}/songs/${id}/history?page=${page}&size=20`),
  undo: (id: string, requestId: string, revision: number) => send<ClassState>(`/history/${id}/undo`, 'POST', { requestId, revision }),
  preview: (input: Start) => send<Preview>('/preview', 'POST', input),
  start: (input: Start) => send<Task>('/tasks', 'POST', input),
  tasks: (page = 0) => authRequest<Task[]>(`${base}/tasks?page=${page}&size=20`),
  task: (id: string, page = 0, status = '') => authRequest<Task>(`${base}/tasks/${id}?page=${page}&size=20${status ? '&status=' + status : ''}`),
  control: (id: string, action: string) => send<Task>(`/tasks/${id}/${action}`, 'POST'),
  retry: (task: string, item: string) => send<Task>(`/tasks/${task}/items/${item}/retry`, 'POST'),
  apply: (task: string, item: string, requestId: string, revision: number, dimensions: Dimension[]) => send<ClassState>(`/tasks/${task}/items/${item}/apply`, 'POST', { requestId, revision, dimensions }),
}

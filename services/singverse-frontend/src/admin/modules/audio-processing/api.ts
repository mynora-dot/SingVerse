import { authRequest } from '../../core/auth/api'

export const base = '/api/v1/audio-processing'
export interface AudioRow {
  id: string; title: string; artists: string[]; sourceId: string; sourceName: string; relativePath: string
  category: string; audio: { state: string; origin?: string; originalId?: string; backingId?: string; streams?: { id: string }[]; jobs: { id: string; kind: string; state: string; error?: string }[] }
}
export interface Filter { title: string; artist: string; sourceId: string; streams: string; state: string; songId: string }
export interface Batch {
  id: string; state: string; backend: string; total: number; counts: Record<string, number>; categories: Record<string, number>
  items?: { batch_id: string; song_id: string; title: string; state: string; category: string; job_id?: string; job_state?: string; execution?: { backend?: string; modelVersion?: string }; error: string }[]
}
export interface Model {
  id: string; version: string; format?: string; installed: boolean; writable: boolean; maxBytes: number
  files?: { name: string; size: number; sha256: string; url: string }[]
}
export interface Node {
  environment: { system: string; architecture: string; processor: string; hardware?: { vendor?: string; device?: string; board?: string }[]; versions: Record<string, string>; error?: string }
  build?: { version: string; revision: string; variant: string; runtimeLock: string }
  deployment?: { note: string; profiles: { backend: string; image: string; command: string; included: boolean; requiresRedeploy: boolean }[] }
  models?: Model[]
  memoryAvailableBytes: number; diskFreeBytes: number; selected: string | null; selection: string
  resourceReady?: boolean; limits?: { audioProcessBudgetBytes: number; minimumFreeBytes: number }
  model: Model & { id: string; version: string; filename: string; sha256: string; maxBytes: number; downloadUrl: string; installed: boolean; writable: boolean }
  operation: { state: string; bytes: number; error?: string; backend?: string }
  instructions: Record<string, string>
  backends: { id: string; state: string; reason: string; device?: { name: string }; elapsedMs?: number }[]
}
export function payload(filter: Filter) { return { ...filter, streams: filter.streams === '' ? null : Number(filter.streams) } }
export function query(filter: Filter, page: number) {
  const params = new URLSearchParams({ page: String(page), size: '30' })
  for (const [key, value] of Object.entries(filter)) if (value !== '') params.set(key, value)
  return params.toString()
}
export async function importModel(file: File, model = 'htdemucs') {
  const csrf = await authRequest<{ token: string }>('/api/v1/auth/csrf')
  const result = await fetch(`${base}/model/import${model === 'htdemucs' ? '' : '?model=' + encodeURIComponent(model)}`, { method: 'POST', credentials: 'same-origin', redirect: 'error',
    headers: { 'Content-Type': 'application/octet-stream', 'X-CSRF-Token': csrf.token }, body: file, signal: AbortSignal.timeout(900000) })
  if (!result.ok) { const problem = await result.json().catch(() => ({})); throw new Error(problem.detail || `导入失败（${result.status}）`) }
}

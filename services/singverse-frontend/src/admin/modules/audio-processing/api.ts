import { authRequest } from '../../core/auth/api'

export const base = '/api/v1/audio-processing'
export interface AudioStream { id: string; index?: number; codec?: string; channels?: number; language?: string; title?: string }
export interface AnalysisEvidence {
  method?: 'LABELS' | 'PANNS_CNN6'; vocalScores?: [number, number][]
  analysisVersion?: string; vocalRatio?: [number, number][]; informativeWindows?: number; votes?: number[]
  windows?: { start: number; seconds: number; tracks?: { level: number; activeFrames: number; totalFrames: number; vocalFrames: number }[] }[]
}
export interface AudioExecution {
  backend?: string; modelVersion?: string; fallbackReason?: string; stage?: string
  timingsMs?: Record<string, number>
  progress?: { completedFrames: number; frames: number; completedForwards?: number }
  inference?: { modelReused?: boolean; sampledPeakRssBytes?: number; timingsMs?: Record<string, number> }
}
export type SeparationProfile = 'DSP_FAST' | 'AI_CLEAN' | 'LEGACY_DEMUCS'
export interface SeparationRecipe { separationProfile?: SeparationProfile; modelId?: string; outputFormat?: 'aac' | 'flac'; preview?: boolean }
export interface AudioVersion extends SeparationRecipe { jobId: string; mediaType?: string; duration?: number; active?: boolean }
export interface AudioJob extends AudioExecution, SeparationRecipe { id: string; kind: string; state: string; error?: string; resultState?: string; reason?: string; evidence?: AnalysisEvidence }
/** `/api/v1/media-songs/{id}/audio-roles` 与列表行 `audio` 共用的音轨角色视图。 */
export interface AudioRoles {
  state: string; revision?: number; origin?: string; originalId?: string; backingId?: string
  backingResource?: AudioVersion
  versions?: AudioVersion[]
  streams?: AudioStream[]; jobs: AudioJob[]
}
export interface AudioRow {
  id: string; title: string; artists: string[]; sourceId: string; sourceName: string; relativePath: string
  coverAvailable?: boolean; category: string; audio: AudioRoles
}
export interface Filter { title: string; artist: string; sourceId: string; streams: string; state: string; songId: string }
export interface BatchItem { batch_id: string; song_id: string; title: string; state: string; category: string; job_id?: string; job_state?: string; execution?: AudioExecution; error: string }
export interface Batch extends SeparationRecipe {
  id: string; state: string; backend: string; createdAt?: number; total: number; counts: Record<string, number>; categories: Record<string, number>
  items?: BatchItem[]
  prepared?: number; selectionTotal?: number; error?: string
}
export interface Model {
  id: string; version: string; format?: string; backends?: string[]; localOnly?: boolean; installed: boolean; writable: boolean; maxBytes: number
  files?: { name: string; size: number; sha256: string; url: string }[]
}
export interface Backend { id: string; state: string; reason: string; detected?: boolean; device?: { name: string }; elapsedMs?: number }
export interface SeparationCapabilities {
  version: number; profiles: { id: SeparationProfile; ready: boolean; modelId: string; backend?: string; reason?: string }[]
  backends: Backend[]; selected?: string; previewSeconds?: number
  operation?: { state: string; backend?: string; error?: string }
}
export interface Node {
  separation?: SeparationCapabilities
  startupCheck?: { state: string; backend?: string; reason?: string }
  fastClassifier?: Model & { ready: boolean }
  environment: { system: string; architecture: string; processor: string; hardware?: { vendor?: string; device?: string; board?: string }[]; versions: Record<string, string>; error?: string }
  build?: { version: string; revision: string; variant: string; runtimeLock: string }
  deployment?: { note: string; profiles: { backend: string; image: string; command: string; included: boolean; requiresRedeploy: boolean }[] }
  models?: Model[]
  cpuFallback?: boolean; memoryAvailableBytes: number; diskFreeBytes: number; selected: string | null; selection: string
  resourceReady?: boolean; limits?: { audioProcessBudgetBytes: number; minimumFreeBytes: number }
  model: Model & { id: string; version: string; filename: string; sha256: string; maxBytes: number; downloadUrl: string; installed: boolean; writable: boolean }
  operation: { state: string; bytes: number; error?: string; backend?: string }
  instructions: Record<string, string>
  backends: Backend[]
}
export function payload(filter: Filter) { return { ...filter, streams: filter.streams === '' ? null : Number(filter.streams) } }
export function query(filter: Filter, page: number, size = 30) {
  const params = new URLSearchParams({ page: String(page), size: String(size) })
  for (const [key, value] of Object.entries(filter)) if (value !== '') params.set(key, value)
  return params.toString()
}
export function songRoles(songId: string) { return `/api/v1/media-songs/${encodeURIComponent(songId)}` }
export function accompanimentUrl(songId: string, jobId: string) { return `${songRoles(songId)}/accompaniment/${encodeURIComponent(jobId)}` }
export async function importModel(file: File, model = 'htdemucs') {
  const csrf = await authRequest<{ token: string }>('/api/v1/auth/csrf')
  const result = await fetch(`${base}/model/import${model === 'htdemucs' ? '' : '?model=' + encodeURIComponent(model)}`, { method: 'POST', credentials: 'same-origin', redirect: 'error',
    headers: { 'Content-Type': 'application/octet-stream', 'X-CSRF-Token': csrf.token }, body: file, signal: AbortSignal.timeout(900000) })
  if (!result.ok) { const problem = await result.json().catch(() => ({})); throw new Error(problem.detail || `导入失败（${result.status}）`) }
}

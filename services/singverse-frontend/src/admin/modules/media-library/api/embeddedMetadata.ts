import { authRequest } from '../../../core/auth/api'
export interface EmbeddedSummary {
  files?: { pending: number; failed: number; attachments?: number; directory: string }
  remoteReady?: boolean; nodeStatus?: string; nodeMessage?: string
  enabled: boolean; paused: boolean; accessGroup: string; privateOrigins: string[]
  blockedUntil?: string; blockedReason?: string; emptyScans: number; canConfirmEmpty: boolean
  counts: Record<string, number>; presence: { missing?: number; archived?: number; changed?: number }
  budget: { files_started: number; bytes_read: number; blocked_until?: string }
}
export interface EmbeddedDetail {
  state: string; generation?: number; changed?: number; missing_scans?: number
  bytes_read?: number; request_count?: number; updated_at?: string
  result?: { tags?: { title?: string; artists?: string[]; album?: string; releaseDate?: string }; evidence?: string; state: string; reason?: string; technical?: Record<string, unknown>; fields?: Record<string, string>
    cover?: string; lyrics?: { text: string; timing: string; language?: string; lines?: { text: string; time: number }[] } }
}
const base = '/api/v1/media-sources'
export const embeddedMetadata = {
  summary: (id: string) => authRequest<EmbeddedSummary>(`${base}/${encodeURIComponent(id)}/embedded-metadata`),
  configure: (id: string, input: Pick<EmbeddedSummary, 'enabled' | 'paused' | 'accessGroup' | 'privateOrigins'>) => authRequest<EmbeddedSummary>(`${base}/${encodeURIComponent(id)}/embedded-metadata`, 'PUT', input),
  confirmEmpty: (id: string) => authRequest(`${base}/${encodeURIComponent(id)}/embedded-metadata/confirm-empty`, 'POST'),
  detail: (id: string) => authRequest<EmbeddedDetail>(`/api/v1/media-songs/${encodeURIComponent(id)}/embedded-metadata`),
  refresh: (songIds: string[], deep = false) => authRequest<{ accepted: number }>('/api/v1/media-songs/embedded-metadata/refresh', 'POST', { songIds, deep }),
}
export const embeddedState = (value: string) => ({ IMPORTED: '来自共享索引（未经本地媒体验证）', PENDING: '等待提取', QUEUED: '等待提取', RUNNING: '正在提取', COMPLETE: '提取完成', EMPTY: '没有内嵌标签', PARTIAL: '部分完成', BUDGET_EXCEEDED: '已达读取上限', UNSUPPORTED: '格式暂不支持', RANGE_UNSUPPORTED: '来源不支持分段读取', UNSTABLE: '读取期间文件发生变化', NETWORK_ERROR: '网络异常', AUTH_REQUIRED: '需要检查来源权限', RATE_LIMITED: '访问冷却中' }[value] ?? value)

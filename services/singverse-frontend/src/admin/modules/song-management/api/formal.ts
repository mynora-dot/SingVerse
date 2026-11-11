import { requestId as newRequestId } from '../../../../shared/request-id'
import { authRequest, ApiError } from '../../../core/auth/api'

export interface FormalState {
  decision: 'AUTO' | 'SET' | 'CLEAR'
  status: 'PRESENT' | 'UNREAD' | 'MISSING' | 'NOT_APPLICABLE' | 'CLEARED'
  revision: string
  origin: { kind: string; provider?: string; collectionId?: string; revision?: string }
}
export interface FormalDocument {
  schemaVersion: '1.0'; id: string; type: 'WORK' | 'SONG' | 'VARIANT' | 'MEDIA' | 'ARTIST' | 'RELEASE' | 'TAG'
  revision: string; parentId?: string; fields: Record<string, unknown>; states: Record<string, FormalState>
}
export interface FormalLocation { id: string; mediaId: string; sourceId: string | null; relativePath: string; state: string }
export interface FormalDetail { document: FormalDocument; children: FormalDocument[]; childrenPage?: { totalElements: number; page: number; size: number }; locations?: { id: string; media_id: string; source_id: string | null; relative_path: string; state: string }[]; saveStatus?: { attachmentsPending: number; derivativesPending: number } }
export interface FieldOperation { field: string; op: 'SET' | 'CLEAR' | 'RESET'; value?: unknown }
const entity = (id: string) => `/api/v1/metadata/entities/${encodeURIComponent(id)}`
export const formalLibrary = {
  uploadAsset: async (file: File): Promise<{ id: string; mime: string }> => {
    const csrf = await authRequest<{ token: string }>('/api/v1/auth/csrf')
    const response = await fetch('/api/v1/metadata/assets', { method: 'POST', credentials: 'same-origin', redirect: 'error', headers: { 'X-CSRF-Token': csrf.token, 'Content-Type': file.type }, body: file, signal: AbortSignal.timeout(120000) })
    const value = await response.json()
    if (!response.ok) { if (response.status === 401) window.dispatchEvent(new Event('singverse:session-expired')); throw new ApiError(response.status, value.detail || '附件保存失败') }
    return value
  },
	statistics: () => authRequest<Record<string, number>>('/api/v1/metadata/statistics'),
  list: (search = '', page = 0, sourceId = '') => authRequest<{ items: FormalDocument[]; totalElements: number }>(`/api/v1/songs?${new URLSearchParams({ search, page: String(page), size: '20', ...(sourceId ? { sourceId } : {}) })}`),
  detail: (id: string, page = 0) => authRequest<FormalDetail>(entity(id) + `?page=${page}&size=20`),
  update: (document: FormalDocument, operations: FieldOperation[], parentId?: string, requestId: string = newRequestId()) => authRequest<FormalDocument>(entity(document.id), 'PATCH', { expectedRevision: document.revision, requestId, operations, ...(parentId ? { parentId } : {}) }),
  createVariant: (songId: string, title: string, kind: string, requestId = newRequestId()) => authRequest<FormalDocument>('/api/v1/metadata/entities', 'POST', { type: 'VARIANT', parentId: songId, requestId, fields: { title, kind } }),
  split: (variant: FormalDocument, mediaId: string, requestId = newRequestId()) => authRequest<FormalDocument>(entity(variant.id) + '/split', 'POST', { expectedRevision: variant.revision, requestId, mediaIds: [mediaId] }),
  locations: (id: string) => authRequest<{ items: FormalLocation[] }>(entity(id) + '/locations'),
  rebind: (document: FormalDocument, sourceId: string, relativePath: string, requestId = newRequestId()) => authRequest<{ state: string }>(entity(document.id) + '/location', 'POST', { sourceId, relativePath, expectedRevision: document.revision, requestId }),
  replaceMedia: (document: FormalDocument, requestId = newRequestId()) => authRequest<FormalDocument>(entity(document.id) + '/replacement', 'POST', { expectedRevision: document.revision, requestId }),
  differences: (id: string) => authRequest<{ items: { field_name: string; source_key: string; incoming_json: string }[] }>(entity(id) + '/differences'),
}
export function formalTitle(doc: FormalDocument) { return String(doc.fields.title || doc.fields.name || (doc.type === 'VARIANT' ? '尚未命名的版本' : '尚未识别歌名')) }
export function formalArtists(doc: FormalDocument) {
  const credits = doc.fields.credits as { name: string }[] | undefined
  return credits?.map(c => c.name).join('、') || (Array.isArray(doc.fields.artists) ? doc.fields.artists.join('、') : '演唱者待补齐')
}
export const formalStateLabel = (state?: FormalState) => state?.decision === 'CLEAR' ? '已人工清空' : state?.status === 'CLEARED' ? '来源已清空' : state?.decision === 'SET' ? '已人工确认' : state?.status === 'MISSING' ? '确实缺失' : state?.status === 'NOT_APPLICABLE' ? '不适用' : state?.status === 'UNREAD' ? '尚未读取' : state ? ({ FILE: '文件标签', FILENAME: '文件名推断', SHARED: '共享资料', PLATFORM: '平台资料' }[state.origin.kind] || '已保存') : '尚未读取'

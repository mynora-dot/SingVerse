import { authRequest } from '../../../core/auth/api'

export interface ShareImportJob {
  id?: string; state: string; phase: string; error?: string
  totalCount: number; processedCount: number; addedCount: number; updatedCount: number
  deletionCount?: number; preservedCount: number; unchangedCount: number; verifiedCount: number
  presentCount: number; missingCount: number; mismatchCount: number; unreachableCount: number
  removePrefix: string; addPrefix: string
  collectionId?: string; collectionRevision?: number
  samples?: { originalPath: string; relativePath: string; mediaURL: string; incoming?: Record<string, unknown>; local?: Record<string, unknown> }[]
  preview?: { total: number; newSongs: number; existingSongs: number }
}
export interface ImportDifference {
  ordinal: number; relativePath: string; fields: string[]; outcome: string
  verification?: string; verificationError?: string
  local?: Record<string, unknown>; incoming?: Record<string, unknown>
}
const base = (id: string) => `/api/v1/media-sources/${encodeURIComponent(id)}/embedded-metadata/import-jobs`
export const shareImports = {
  latest: (id: string) => authRequest<ShareImportJob>(base(id) + '/latest'),
  get: (id: string, job: string) => authRequest<ShareImportJob>(base(id) + '/' + encodeURIComponent(job)),
  mapping: (id: string, job: string, removePrefix: string, addPrefix: string) => authRequest<ShareImportJob>(base(id) + '/' + encodeURIComponent(job) + '/mapping', 'PUT', { removePrefix, addPrefix }),
  action: (id: string, job: string, action: 'apply' | 'cancel' | 'retry') => authRequest<ShareImportJob>(base(id) + '/' + encodeURIComponent(job) + '/' + action, 'POST'),
  differences: (id: string, job: string, cursor = 0) => authRequest<{ items: ImportDifference[]; nextCursor: number; hasMore: boolean }>(base(id) + '/' + encodeURIComponent(job) + '/differences?cursor=' + cursor),
  async upload(id: string, file: File, signal: AbortSignal): Promise<ShareImportJob> {
    if (file.size > 8 * 1024 ** 3) throw new Error('分享包不能超过 8 GiB')
    const csrf = await authRequest<{ token: string }>('/api/v1/auth/csrf')
    const response = await fetch(base(id), {
      method: 'POST', credentials: 'same-origin', redirect: 'error',
      headers: { 'Content-Type': 'application/zip', 'X-CSRF-Token': csrf.token },
      body: file, signal,
    })
    const result = await response.json()
    if (!response.ok) throw new Error(result.detail || '上传失败，请重新选择资料包')
    return result
  },
}
export const mediaVerification = (state?: string) => ({ PENDING: '待核对媒体路径', PRESENT: '目录已核对', MISSING: '未找到媒体文件', MISMATCH: '媒体大小不符', UNREACHABLE: '无法连接或读取目录' }[state ?? ''] ?? '')

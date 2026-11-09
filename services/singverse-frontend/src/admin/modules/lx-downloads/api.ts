import { authRequest } from '../../core/auth/api'

export const platforms: Record<string, string> = { wy: '网易云音乐', tx: 'QQ 音乐', kg: '酷狗音乐', kw: '酷我音乐', mg: '咪咕音乐' }
export const qualities: Record<string, string> = { auto: '自动 · 按音源可用音质选择', flac24bit: 'Hi-Res / 24 bit', flac: '无损 FLAC', '320k': '高品质 320 kbps', '128k': '标准 128 kbps' }
export interface LxSource { id: string; name: string; version: string; environment: 'mobile' | 'desktop'; capabilities: Record<string, string[]>; active: boolean; createdAt: string }
export interface Status { target: { id: string | null; name: string | null; path: string | null; ready: boolean; reason: string }; activeSource: LxSource | null; ready: boolean; platforms: Record<string, string>; activeBatches: number }
export interface Track { id: string; platform: string; title: string; artists: string[]; album: string; durationMs: number | null; coverUrl: string; inLibrary: boolean; downloading: boolean; songId: string | null }
export interface SearchResult { tracks: Track[]; errors: { platform: string; message: string }[]; warnings: string[]; page: number; hasMore: boolean }
export interface Preview { platform: string; kind: 'playlist' | 'album'; name: string; coverUrl: string; tracks: Track[]; page: number; total: number; hasMore: boolean; warning: string }
export interface Batch { id: string; name: string; sourceId: string; path: string; scriptId: string; quality: string; status: string; createdAt: string; updatedAt: string; counts: Record<string, number>; total: number }
export interface Item { id: string; track: Track; status: string; requestedQuality: string; resolvedQuality: string | null; receivedBytes: number; totalBytes: number | null; songId: string | null; message: string }
export interface Page<T> { items: T[]; total: number; page: number; size: number }
export interface Created { batch: Batch; merged: number; relatedBatchIds: string[] }
const base = '/api/v1/lx-downloads'
export const lx = {
  status: () => authRequest<Status>(base + '/status'),
  sources: () => authRequest<LxSource[]>(base + '/sources'),
  importSource: (input: { name?: string; script?: string; url?: string; environment: string }) => authRequest<LxSource>(base + '/sources', 'POST', input, 45000),
  activate: (id: string) => authRequest(base + `/sources/${encodeURIComponent(id)}/activate`, 'POST', undefined, 25000),
  test: (id: string) => authRequest<{ message: string }>(base + `/sources/${encodeURIComponent(id)}/test`, 'POST', undefined, 25000),
  remove: (id: string) => authRequest(base + `/sources/${encodeURIComponent(id)}`, 'DELETE'),
  search: (query: string, platform: string, page = 1) => authRequest<SearchResult>(base + '/catalog/search', 'POST', { query, platform, page }, 180000),
  preview: (url: string, page = 1) => authRequest<Preview>(base + '/catalog/preview', 'POST', { url, page }, 90000),
  trackStates: (trackIds: string[]) => authRequest<Track[]>(base + '/catalog/states', 'POST', { trackIds }),
  create: (trackIds: string[], quality: string, name?: string) => authRequest<Created>(base + '/batches', 'POST', { trackIds, quality, name }, 30000),
  batches: (page = 0) => authRequest<Page<Batch>>(base + `/batches?page=${page}&size=20`),
  batch: (id: string) => authRequest<Batch>(base + `/batches/${encodeURIComponent(id)}`),
  items: (id: string, page = 0) => authRequest<Page<Item>>(base + `/batches/${encodeURIComponent(id)}/items?page=${page}&size=20`),
  cancel: (id: string) => authRequest(base + `/batches/${encodeURIComponent(id)}/cancel`, 'POST'),
  retry: (id: string) => authRequest<Created>(base + `/batches/${encodeURIComponent(id)}/retry`, 'POST', undefined, 30000),
}
export const active = (status: string) => ['QUEUED', 'RUNNING', 'CANCELLING'].includes(status)
export const stateNames: Record<string, string> = { QUEUED: '排队中', RESOLVING: '获取歌曲地址', DOWNLOADING: '正在下载', PUBLISHING: '正在加入曲库', RUNNING: '下载中', CANCELLING: '正在取消', SUCCESS: '已加入曲库', SKIPPED: '已在曲库', COMPLETED: '全部完成', PARTIAL: '部分完成', FAILED: '失败', CANCELLED: '已取消', INTERRUPTED: '已中断' }
export const message = (e: unknown) => e instanceof Error ? e.message : '操作失败，请重试'
export const duration = (ms: number | null) => ms == null ? '时长未知' : `${Math.floor(ms / 60000)}:${String(Math.floor(ms / 1000) % 60).padStart(2, '0')}`
export const bytes = (size: number) => size >= 1024 ** 2 ? `${(size / 1024 ** 2).toFixed(1)} MiB` : `${Math.ceil(size / 1024)} KiB`

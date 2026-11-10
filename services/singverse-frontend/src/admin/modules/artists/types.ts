export interface Artist {
  automatic?: boolean; fetchState?: string; portraitFetchState?: string; backgroundFetchState?: string; portraitOrigin?: string; backgroundOrigin?: string; portraitSource?: string; backgroundSource?: string; portraitAttribution?: string; backgroundAttribution?: string; id: string; name: string; aliases: string[]; type: 'PERSON' | 'MALE' | 'FEMALE' | 'GROUP' | 'UNKNOWN'; biography: string; active: boolean; revision: number; managed: boolean; count?: number; portraitPath?: string; portraitVersion?: string; backgroundPath?: string; backgroundVersion?: string }
export interface Page<T> { items: T[]; page: number; size: number; totalElements: number }
export type ImageKind = 'portrait' | 'background'
export const artistTypes = { PERSON: '待分类', MALE: '男歌手', FEMALE: '女歌手', GROUP: '乐队 / 组合', UNKNOWN: '待分类' }
export type ArtworkPlatform = 'AUTO' | 'NETEASE' | 'QQ' | 'KUGOU' | 'KUWO' | 'QISHUI' | 'THEAUDIODB'
export interface ArtistFilter { q: string; type: string; countLessThan: number | null; artwork: string }
export interface ArtworkBatchInput { requestId: string; artistIds: string[]; allMatching: boolean; filter: ArtistFilter; provider: ArtworkPlatform }
export interface ArtworkBatch {
  id: string; provider: ArtworkPlatform; status: 'RUNNING' | 'COMPLETED'; createdAt: string; updatedAt: string
  total: number; completed: number; counts: Record<string, number>; page: number; size: number; totalElements: number
  items: Array<{ id: string; artistId: string; name: string; status: string; message: string }>
}
export const artworkPlatforms: Record<ArtworkPlatform, string> = { AUTO: '自动匹配', NETEASE: '网易云音乐', QQ: 'QQ 音乐', KUGOU: '酷狗音乐', KUWO: '酷我音乐', QISHUI: '汽水音乐', THEAUDIODB: 'TheAudioDB' }
export const artworkItemStates: Record<string, string> = { PENDING: '等待获取', PROCESSING: '正在获取', APPLIED: '已获取', REVIEW: '待确认', NO_MATCH: '未找到匹配', FAILED: '获取失败', SKIPPED: '已保留 / 跳过' }

export type MusicProvider = 'NETEASE' | 'QQ' | 'KUGOU'
export interface MetadataSettings { enabled: boolean; providers: MusicProvider[]; autoApply: boolean; threshold: number; minIntervalSeconds?: number; maxIntervalSeconds?: number }
export interface ExternalTrack {
  provider: MusicProvider; externalId: string; title: string; artists: string[]; album: string | null
  durationMs: number | null; releaseDate: string | null; aliases: string[]; coverUrl: string | null
  availability: string; fetchedAt: string
}
export interface AppliedMetadata { track: Omit<ExternalTrack, 'provider' | 'externalId' | 'title'> & { provider: MusicProvider | null; externalId: string | null; title: string | null }; fields: string[]; appliedAt: string; stale: boolean; origins: Record<string, { provider: MusicProvider | null; externalId: string | null; appliedAt: string; kind?: string }> }
export interface MetadataCandidate { id: string; track: ExternalTrack; score: number }
export interface SearchResult { candidates: MetadataCandidate[]; errors: { provider: MusicProvider; message: string }[] }
export interface MetadataItem { id: string; songId: string; title: string | null; artists: string[]; status: string; candidates: MetadataCandidate[]; error: string | null; updatedAt: string; confidence?: 'HIGH' | 'LOW' | 'NONE'; coverAvailable?: boolean; sourceId?: string | null; relativePath?: string | null }
export interface MetadataBatch {
  id: string; mode: string; status: string; total: number; completed: number; pending: number; processing: number
  review: number; applied: number; failed: number; skippedExisting: number; autoApply: boolean; threshold: number
  createdAt: string; updatedAt: string; items: MetadataItem[]; page: number; size: number; totalElements: number; processableTotal?: number; legacySkipped?: number; observedAt?: string
}
export const providerNames: Record<MusicProvider, string> = { NETEASE: '网易云音乐', QQ: 'QQ 音乐', KUGOU: '酷狗音乐' }
export const fieldNames: Record<string, string> = { title: '歌名', artists: '歌手', album: '专辑', releaseDate: '发行日期', aliases: '别名', durationMs: '平台时长', cover: '封面' }
export const itemStatusNames: Record<string, string> = { PENDING: '等待处理', PROCESSING: '匹配中', REVIEW: '待审核', FAILED: '失败', AUTO_APPLIED: '已自动应用', MANUAL_APPLIED: '已确认应用', SKIPPED_EXISTING: '已跳过' }

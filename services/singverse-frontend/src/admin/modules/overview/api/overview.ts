import { authRequest } from '../../../core/auth/api'
import type { MediaSong } from '../../media-library/types'
import type { BackgroundTask } from '../../background-tasks/api'

export interface OverviewLibrary { totalSongs: number; reviewSongs: number; formalSongs: number; formalVariants: number; formalMedia: number }
export interface OverviewFailedSource { id: string; name: string; lastScanError: string }
export interface OverviewSources { total: number; failedTotal: number; neverScanned: number; failed: OverviewFailedSource[] }
export interface OverviewMetadataBatch { id: string; status: string; review: number; completed: number; total: number }
export interface OverviewClassification { activeTasks: number; reviewItems: number; latestTaskId: string }
export interface OverviewReview { metadataBatch: OverviewMetadataBatch | null; classification: OverviewClassification; missingFeedbackPending: number }
export interface OverviewTv { activeDevices: number; pending: number }
export interface OverviewTasks { activeTotal: number; counts: Record<string, number>; items: BackgroundTask[]; observedAt: string }
export interface OverviewPlayback { totalPlays: number; effectivePlays: number; playedSongs: number; playedMs: number }
export interface OverviewCache { usageBytes: number; entryCount: number }
export interface OverviewCompute { configured: boolean; mode: string }
export interface OverviewAi { configured: boolean; enabledFeatures: string[] }
export interface OverviewReadiness { compute: OverviewCompute; ai: OverviewAi }
export interface OverviewPlaylist { id: string; name: string; count: number }

/** 首页聚合：每块独立采集，失败块为 null 并写入 errors，绝不伪造数字。 */
export interface AdminOverview {
  library: OverviewLibrary | null
  sources: OverviewSources | null
  review: OverviewReview | null
  tv: OverviewTv | null
  tasks: OverviewTasks | null
  recentSongs: MediaSong[] | null
  playback30d: OverviewPlayback | null
  cache: OverviewCache | null
  readiness: OverviewReadiness | null
  playlists: OverviewPlaylist[] | null
  observedAt: string
  errors: Record<string, string>
}

function pick<T>(data: Record<string, unknown>, key: string): T | null {
  const value = data[key]
  return value && typeof value === 'object' ? (value as T) : null
}

/** 防御式归一：响应缺块或形状不符时置 null，视图按块降级而非崩溃。 */
export function normalizeOverview(raw: unknown): AdminOverview {
  const data = raw && typeof raw === 'object' ? (raw as Record<string, unknown>) : {}
  const errors = data.errors && typeof data.errors === 'object' ? (data.errors as Record<string, string>) : {}
  return {
    library: pick<OverviewLibrary>(data, 'library'),
    sources: pick<OverviewSources>(data, 'sources'),
    review: pick<OverviewReview>(data, 'review'),
    tv: pick<OverviewTv>(data, 'tv'),
    tasks: pick<OverviewTasks>(data, 'tasks'),
    recentSongs: Array.isArray(data.recentSongs) ? (data.recentSongs as MediaSong[]) : null,
    playback30d: pick<OverviewPlayback>(data, 'playback30d'),
    cache: pick<OverviewCache>(data, 'cache'),
    readiness: pick<OverviewReadiness>(data, 'readiness'),
    playlists: Array.isArray(data.playlists) ? (data.playlists as OverviewPlaylist[]) : null,
    observedAt: typeof data.observedAt === 'string' ? data.observedAt : '',
    errors,
  }
}

export async function loadOverview(): Promise<AdminOverview> {
  return normalizeOverview(await authRequest<unknown>('/api/v1/admin/overview'))
}
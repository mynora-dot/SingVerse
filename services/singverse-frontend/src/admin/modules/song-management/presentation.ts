import type { MediaSong } from '../media-library/types'
import type { Tag, Dimension } from '../classification/types'
import { dimensionNames } from '../classification/types'
export const songColumns = [
  { id: 'album', label: '专辑与发行信息' }, { id: 'classification', label: '分类标签' },
  { id: 'file', label: '文件格式与大小' }, { id: 'location', label: '媒体库' },
  { id: 'status', label: '解析与元数据状态' }, { id: 'parsed', label: '文件名解析细节' },
  { id: 'timestamps', label: '扫描与修改时间' },
] as const
export type SongColumn = typeof songColumns[number]['id']
export const defaultSongColumns: SongColumn[] = ['album', 'classification', 'file', 'location', 'status']
export function confirmedField(song: MediaSong, field: 'album' | 'releaseDate' | 'durationMs') {
  return song.metadata && !song.metadata.stale && song.metadata.fields.includes(field) ? song.metadata.track[field] : null
}
export function fileSize(value: number | null) {
  if (value === null || !Number.isFinite(value) || value < 0) return '大小未提供'
  if (value < 1024) return `${value} B`
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`
  if (value < 1024 * 1024 * 1024) return `${(value / 1024 / 1024).toFixed(1)} MB`
  return `${(value / 1024 / 1024 / 1024).toFixed(2)} GB`
}
export function fileFormat(song: MediaSong) { return (song.result.extension || song.relativePath.split('.').pop() || '未知格式').toUpperCase() }
export function platformDuration(song: MediaSong) {
  const value = confirmedField(song, 'durationMs')
  if (typeof value !== 'number' || !Number.isFinite(value) || value <= 0) return ''
  const seconds = Math.floor(value / 1000)
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`
}
export function displayTime(value: string | null) {
  if (!value) return '未提供'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false })
}
export function songTags(song: MediaSong, tags: Tag[]) {
  return Object.entries(song.classification?.dimensions ?? {}).flatMap(([dim, value]) => (value?.tagIds ?? []).map(id => {
    const tag = tags.find(t => t.id === id)
    return { id, name: tag?.name ?? '标签信息未加载', dimension: dimensionNames[dim as Dimension] ?? dim, active: tag?.active ?? false, locked: !!value?.locked }
  }))
}
export const languageLabels: Record<string, string> = { MANDARIN: '国语', CANTONESE: '粤语', ENGLISH: '英语', JAPANESE: '日语', KOREAN: '韩语', MULTILINGUAL: '多语种', OTHER: '其他', UNKNOWN: '语种未知' }
export const versionLabels: Record<string, string> = { ORIGINAL: '原唱', ACCOMPANIMENT: '伴奏', LIVE: '现场', COVER: '翻唱', UNKNOWN: '版本未知' }

// 原始解析证据保持不变；只有有效且明确确认的核心身份信息才解除业务复核提示。
export function sharedIdentity(song: MediaSong): boolean {
  return !song.metadata?.stale && ['title', 'artists'].some(field => song.metadata?.origins[field]?.kind === 'SHARED')
}
export function identityReviewed(song: MediaSong): boolean {
  const metadata = song.metadata
  return !!metadata && !metadata.stale && !sharedIdentity(song) && metadata.fields.includes('title') && metadata.fields.includes('artists')
    && !!metadata.track.title?.trim() && metadata.track.artists.some(artist => !!artist.trim())
}
export function needsSongReview(song: MediaSong): boolean { return sharedIdentity(song) || song.result.needsReview && !identityReviewed(song) }
export function songReviewLabel(song: MediaSong): string {
  return sharedIdentity(song) ? '共享信息待核对' : identityReviewed(song) ? '歌曲信息已复核' : song.result.needsReview ? '文件名待复核' : '文件名完整'
}

import type { PlaybackStatus } from './api'

export const statusNames: Record<PlaybackStatus, string> = {
  PLAYING: '播放中', COMPLETED: '自然播完', SKIPPED: '已切歌', STOPPED: '已停止', ERROR: '播放失败', INTERRUPTED: '播放中断',
}
export const clientNames = { TV: '电视', MOBILE: '手机', ADMIN: '后台试听' }
export function date(value: string | null) {
  if (!value) return '—'
  const time = new Date(value)
  return Number.isNaN(time.getTime()) ? '时间未知' : time.toLocaleString('zh-CN', { hour12: false })
}
export function duration(ms: number) {
  const seconds = Math.max(0, Math.floor(ms / 1000))
  const hours = Math.floor(seconds / 3600)
  return hours ? `${hours} 小时 ${Math.floor(seconds % 3600 / 60)} 分` : `${Math.floor(seconds / 60)} 分 ${seconds % 60} 秒`
}

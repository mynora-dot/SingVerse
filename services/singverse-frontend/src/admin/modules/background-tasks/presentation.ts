import type { BackgroundTask, TaskExecution } from './api'

export const typeNames: Record<string, string> = {
  ARTIST_ARTWORK: '歌手封面获取', LX_DOWNLOAD: '歌曲下载', LOCAL_SCAN: '本地扫描', WEBDAV_SCAN: 'WebDAV 扫描', METADATA: '元数据整理', CLASSIFICATION: 'AI 分类',
  AUDIO_DISCOVERY: '自动音轨识别', AUDIO_BATCH: '批量伴奏生成', AUDIO_JOB: '单曲音频处理', TRANSCODE: '离线转码', CACHE_CLEANUP: '缓存清理', SHARE_EXPORT: '分享包导出',
}
export const statusNames: Record<string, string> = {
  QUEUED: '排队中', RUNNING: '运行中', PAUSED: '已暂停', CANCELLING: '取消中', AWAITING_CONFIRMATION: '等待开始',
  COMPLETED: '处理完成', PARTIAL: '部分完成', FAILED: '失败', CANCELLED: '已取消', INTERRUPTED: '已中断', BLOCKED: '未能继续', EXPIRED: '已过期',
}
export const itemStatusNames: Record<string, string> = {
  RESOLVING: '获取歌曲地址', DOWNLOADING: '正在下载', PUBLISHING: '正在加入曲库', SUCCESS: '已加入曲库',
  NO_MATCH: '未找到匹配', ...statusNames, PENDING: '等待处理', PROCESSING: '处理中', PROBING: '读取音轨', REVIEW: '待审核',
  AUTO_APPLIED: '已自动保存', MANUAL_APPLIED: '已人工保存', APPLIED: '已保存', SKIPPED_EXISTING: '保留已有结果',
  SKIPPED: '已跳过', UNCHANGED: '无需更改', SUCCEEDED: '处理成功',
}
const stages: Record<string, string> = {
  FETCHING_ARTWORK: '获取歌手封面', RESOLVING: '获取歌曲地址', DOWNLOADING: '正在下载', PUBLISHING: '正在加入曲库',
  DISCOVERING: '发现待处理歌曲', RECOGNIZING: '处理扫描后的音轨任务', WAITING_RECOGNITION: '等待后续音轨处理', LEGACY: '未记录原始批次',
  ...statusNames, SCANNING: '遍历目录', READING_TAGS: '读取文件标签', COMMITTING: '保存索引', MATCHING: '匹配元数据',
  CLASSIFYING: '生成分类建议', PREPARING: '准备批次', PREVIEW: '等待确认开始', PROCESSING: '处理歌曲',
  WAITING: '等待文件准备', STARTING: '正在启动', SUCCEEDED: '处理完成', CLEAR: '清理全部', POLICY: '按策略回收', DELETE: '清理指定缓存',
  probe: '读取歌曲', extract: '准备音频', separate: '生成伴奏', encode: '保存结果', validate: '校验结果', hash: '校验结果',
  download: '读取素材', transcode: '转换媒体', analyze: '分析音轨', inference: '模型推理',
}
export const activeTask = (status: string) => ['QUEUED', 'RUNNING', 'PAUSED', 'CANCELLING', 'AWAITING_CONFIRMATION'].includes(status)
export const stageName = (stage: string) => stages[stage] || stage || '等待更新'
export function tone(status: string): 'success' | 'warning' | 'danger' | 'info' | 'primary' {
  if (status === 'COMPLETED') return 'success'
  if (['FAILED', 'INTERRUPTED'].includes(status)) return 'danger'
  if (['PARTIAL', 'BLOCKED', 'PAUSED'].includes(status)) return 'warning'
  return status === 'RUNNING' ? 'primary' : 'info'
}
export function date(value?: string | null) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '未记录' }
export function progressText(task: BackgroundTask) {
  const { completed, total, unit } = task.progress
  const suffix = unit === 'SONGS' ? '首' : unit === 'ARTISTS' ? '位' : '项'
  const scope = task.stage === 'PREPARING' ? '已准备' : task.stage === 'READING_TAGS' ? '已读取' : '已处理'
  if (completed !== null && total !== null) return scope + ' ' + completed.toLocaleString() + ' / ' + total.toLocaleString() + ' ' + suffix
  if (task.type.endsWith('SCAN')) return '已发现 ' + Number(task.statistics.total_songs || 0).toLocaleString() + ' 首歌曲'
  if (task.type === 'SHARE_EXPORT') return '已导出 ' + Number(task.statistics.exportedSongs || 0).toLocaleString() + ' 首 · ' + Number(task.statistics.parts || 0) + ' 个分包'
  if (completed !== null) return scope + ' ' + completed.toLocaleString() + ' ' + suffix
  return activeTask(task.status) ? '正在处理，整体进度暂不可用' : '查看处理结果'
}
export function frameProgress(execution?: TaskExecution) {
  const progress = execution?.progress
  if (execution?.stage !== 'separate' || !progress || progress.frames <= 0) return ''
  return '当前阶段 ' + Math.floor(Math.max(0, Math.min(1, progress.completedFrames / progress.frames)) * 100) + '% · ' + progress.completedFrames.toLocaleString() + ' / ' + progress.frames.toLocaleString() + ' 帧'
}
export function bytes(value: number) {
  if (value >= 1024 ** 3) return (value / 1024 ** 3).toFixed(2) + ' GiB'
  if (value >= 1024 ** 2) return (value / 1024 ** 2).toFixed(1) + ' MiB'
  return Math.max(0, value).toLocaleString() + ' B'
}
const statisticNames: Record<string, string> = {
  noMatch: '未找到匹配', interrupted: '已中断', cancelled: '已取消',
  pending: '等待处理', processing: '正在处理', review: '待审核', failed: '失败', skipped: '跳过', applied: '已保存',
  prepared: '已准备', directories: '已遍历目录', entries: '已访问条目', total_songs: '发现歌曲',
  matched_songs: '匹配歌曲', review_songs: '待复核歌曲', skipped_files: '跳过文件', added_songs: '新增歌曲',
  reused_songs: '复用歌曲', missing_songs: '本次未发现', removed_songs: '移出索引', removed: '已清理缓存',
  freedBytes: '已释放空间', usageBeforeBytes: '清理前占用', usageAfterBytes: '清理后占用', targetBytes: '目标占用',
  shortfallBytes: '空间缺口', exportedSongs: '已导出歌曲', snapshotSongs: '快照歌曲', parts: '已生成分包',
}
export function statistics(task: BackgroundTask) {
  return Object.entries(task.statistics).flatMap(([key, value]) => typeof value === 'number' && statisticNames[key]
    ? [{ key, label: statisticNames[key]!, value: key.endsWith('Bytes') ? bytes(value) : value.toLocaleString() }] : [])
}
export const skipReasons: Record<string, string> = {
  PLAYING: '正在播放', DOWNLOADING: '正在下载', PINNED: '歌曲已固定', RECENT_PLAY: '近期播放保护', PREFETCH: '正在预取',
  DELETING: '正在删除', BUILDING: '正在接收', CHANGED: '缓存状态变化', STATISTICS_NOT_READY: '统计尚未就绪', MANUAL: '当前采用手动策略',
}

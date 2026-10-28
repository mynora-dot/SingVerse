import type { MetadataItem } from './types'
export function confidence(item: MetadataItem, threshold: number) {
  const first = item.candidates[0]
  return !first || first.score < .8 ? 'NONE' : first.score >= threshold ? 'HIGH' : 'LOW' 
}
export function itemLabel(item: MetadataItem, threshold: number, cancelled: boolean) {
  if (item.status === 'REVIEW') return confidence(item, threshold) === 'HIGH' ? '高相似度 · 待确认' : confidence(item, threshold) === 'LOW' ? '低相似度 · 待核对' : '无可信候选'
  return ({ PENDING: cancelled ? '已终止 · 未处理' : '等待处理', PROCESSING: cancelled ? '停止中' : '正在匹配', FAILED: '匹配失败', AUTO_APPLIED: '已自动应用', MANUAL_APPLIED: '已确认应用', SKIPPED_EXISTING: '已跳过' } as Record<string, string>)[item.status] ?? item.status
}

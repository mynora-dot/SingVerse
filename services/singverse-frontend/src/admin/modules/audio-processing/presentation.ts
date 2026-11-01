import type { AudioRoles, AudioStream, Batch, Node } from './api'

/** 原唱与伴奏的用户文案集中在这里，列表、单曲面板和歌曲详情共用同一套说法。 */
export type Tone = 'success' | 'warning' | 'danger' | 'info' | 'primary'
export interface RolesSummary { tag: string; title: string; hint: string; tone: Tone }

const active = (state: string) => ['QUEUED', 'RUNNING'].includes(state)
export function analysisReason(reason?: string) {
  const reasons: Record<string, string> = {
    NO_RELIABLE_VOCALS: '采样片段未发现可靠人声，可能是间奏或弱人声段。',
    AMBIGUOUS_VOCALS: '原唱与伴奏的人声差异不足，或一致的有效片段不够。',
    BOTH_VOCAL_TRACKS: '采样中两条音轨都有较强人声，不能直接认定其中一条是伴奏。',
    CONFLICTING_WINDOWS: '不同片段对原唱的判断相反，请试听确认。',
    CONFLICTING_LABELS: '音频分析与音轨标签冲突，请试听确认。',
    INSUFFICIENT_EVIDENCE: '有效采样片段不足。', INVALID_EVIDENCE: '分析数值无效，请重新分析。',
    FAST_MODEL_MISSING: '未安装快速识别模型或固定运行环境；可人工标记，或前往性能节点配置安装。',
    INSUFFICIENT_DURATION: '歌曲太短，无法完成此识别方式所需的采样。', NOT_DUAL_AUDIO: '当前文件不是双音轨。',
  }
  return reasons[reason || ''] || 'AI 没能确定哪条是原唱，请试听后人工选择。'
}
export function latestJob(roles: AudioRoles | undefined, kind: string) { return roles?.jobs.find(job => job.kind === kind) }

export function latestAnalysis(roles: AudioRoles | undefined) {
  const jobs = roles?.jobs.filter(job => ['FAST', 'ANALYZE'].includes(job.kind))
  return jobs?.find(job => active(job.state)) || jobs?.[0]
}

/** 依据音轨数量、角色标记和最近一次任务，给出普通用户能看懂的状态。 */
export function rolesSummary(roles: AudioRoles | undefined): RolesSummary {
  if (!roles) return { tag: '读取中', title: '正在读取音轨信息', hint: '', tone: 'info' }
  const streams = roles.streams, separate = latestJob(roles, 'SEPARATE'), analyze = latestAnalysis(roles), probe = latestJob(roles, 'PROBE')
  if (!streams) {
    const reading = [analyze, probe].some(job => job && active(job.state))
    return reading
      ? { tag: '识别中', title: '正在读取音轨', hint: '读取完成后会显示这首歌有几条音轨。', tone: 'primary' }
      : { tag: '待识别', title: '还没有读取音轨', hint: '读取后才能知道它自带伴奏轨，还是需要用 AI 生成伴奏。', tone: 'info' }
  }
  if (!streams.length) return { tag: '无音轨', title: '文件里没有音频', hint: '这个文件没有可用的音频，无法设置原唱或伴奏。', tone: 'info' }
  if (streams.length === 1) {
    if (roles.backingResource) return { tag: '已有伴奏', title: '已生成独立伴奏', hint: '点歌时可以在原唱和伴奏之间切换。', tone: 'success' }
    if (separate && active(separate.state)) return { tag: '生成中', title: '正在生成伴奏', hint: '由计算节点在后台分离人声，完成后自动可用。', tone: 'primary' }
    if (separate?.state === 'FAILED') return { tag: '生成失败', title: '上次生成伴奏失败', hint: separate.error || '可以检查节点后重新生成。', tone: 'danger' }
    return { tag: '可生成伴奏', title: '只有一条原唱音轨', hint: '可以用 AI 分离人声，生成一份独立伴奏。', tone: 'warning' }
  }
  if (streams.length === 2) {
    if (roles.state === 'CONFIRMED') return { tag: '自带伴奏', title: '自带原唱和伴奏两条音轨', hint: `已${originLabel(roles.origin)}原唱和伴奏，点歌时可以直接切换。`, tone: 'success' }
    if (analyze && active(analyze.state)) return { tag: '识别中', title: '正在识别哪条是原唱', hint: '识别完成后会自动标记；不确定时需要你来确认。', tone: 'primary' }
    return { tag: '待标记', title: '有两条音轨，需要确认哪条是原唱', hint: analyze?.state === 'SUCCEEDED' ? analysisReason(analyze.reason) : analyze?.state === 'FAILED' ? analyze.error || '音轨识别失败，可以检查节点后重试。' : '选出原唱，另一条会作为伴奏。', tone: 'warning' }
  }
  return { tag: '多条音轨', title: `有 ${streams.length} 条音轨`, hint: '目前只支持两条音轨的原唱/伴奏标记，也不会为多音轨歌曲生成伴奏。', tone: 'info' }
}

export function originLabel(origin?: string) {
  return { AUTO: '自动识别', MANUAL: '人工标记', SHARED: '从共享曲库导入', SOURCE: '按文件确定' }[origin || ''] || '标记'
}

const channelNames: Record<number, string> = { 1: '单声道', 2: '立体声', 6: '5.1 声道', 8: '7.1 声道' }
const languageNames: Record<string, string> = { chi: '中文', zho: '中文', cmn: '普通话', yue: '粤语', eng: '英语', jpn: '日语', kor: '韩语' }
/** 把源音轨 ID 换成“音轨 1 · AAC 立体声 · 中文 · 标题”这样的描述。 */
export function streamLabel(stream: AudioStream, position: number) {
  const parts = [`音轨 ${position + 1}`]
  const format = [stream.codec?.toUpperCase(), stream.channels ? channelNames[stream.channels] || `${stream.channels} 声道` : ''].filter(Boolean).join(' ')
  if (format) parts.push(format)
  if (stream.language && stream.language !== 'und') parts.push(languageNames[stream.language] || stream.language)
  if (stream.title) parts.push(stream.title)
  return parts.join(' · ')
}

export const jobKinds: Record<string, string> = { PROBE: '读取音轨', FAST: '快速识别原唱/伴奏', ANALYZE: '深度识别原唱/伴奏', SEPARATE: '生成伴奏' }
export const jobStates: Record<string, string> = { QUEUED: '排队中', RUNNING: '进行中', SUCCEEDED: '已完成', FAILED: '失败', CANCELLED: '已取消' }
export const batchStates: Record<string, string> = { PREVIEW: '待确认', QUEUED: '进行中', COMPLETED: '已结束', CANCELLED: '已取消' }
export const itemStates: Record<string, string> = { PENDING: '等待中', PROBING: '读取音轨', PROCESSING: '生成中', SUCCEEDED: '已完成', SKIPPED: '已跳过', FAILED: '失败', CANCELLED: '已取消' }
export const batchTones: Record<string, Tone> = { PREVIEW: 'warning', QUEUED: 'primary', COMPLETED: 'success', CANCELLED: 'info' }
export const itemTones: Record<string, Tone> = { PENDING: 'info', PROBING: 'primary', PROCESSING: 'primary', SUCCEEDED: 'success', SKIPPED: 'info', FAILED: 'danger', CANCELLED: 'info' }
export const backendStates: Record<string, string> = { AVAILABLE: '可用', UNVERIFIED: '待试跑', FAILED: '试跑失败', TESTING: '试跑中', IMAGE_MISSING: '当前镜像未包含', RUNTIME_MISSING: '缺少运行环境', MODEL_MISSING: '缺少模型或固定版本依赖', MODEL_UNSUPPORTED: '当前模型未适配' }
export const operationStates: Record<string, string> = { IDLE: '空闲', DOWNLOADING: '下载中', IMPORTING: '导入中', VERIFYING: '校验中', TESTING: '试跑中', SUCCEEDED: '已完成', FAILED: '失败', CANCELLED: '已取消' }
const backendNames: Record<string, string> = { cpu: 'CPU', cuda: 'NVIDIA 显卡 (CUDA)', rocm: 'AMD 显卡 (ROCm)', mps: 'Apple 芯片 (MPS)', openvino: 'Intel 核显 (OpenVINO)', rknn: '瑞芯微 NPU (RKNN)' }
export function backendName(id?: string | null) { return id ? backendNames[id] || id.toUpperCase() : '' }
export const modelNames: Record<string, { name: string; hint: string }> = {
  'panns-cnn6': { name: '快速人声分类模型', hint: '扫描时识别双音轨，不分离人声，无需 GPU' },
  htdemucs: { name: '标准分离模型', hint: '适用于 CPU、NVIDIA、AMD 和 Apple 芯片' },
  'htdemucs-openvino': { name: 'Intel 优化模型', hint: '用于 Intel 核显的 OpenVINO 转换版本' },
}

/** 批次进度：已完成/跳过/失败/取消都算处理完毕。 */
export function batchProgress(batch: Batch) {
  const c = batch.counts, done = (c.SUCCEEDED || 0) + (c.SKIPPED || 0) + (c.FAILED || 0) + (c.CANCELLED || 0)
  return { done, pending: (c.PENDING || 0) + (c.PROBING || 0) + (c.PROCESSING || 0), percent: batch.total ? Math.round(done / batch.total * 100) : 0 }
}

export interface Readiness { ready: boolean; connected: boolean; modelInstalled: boolean; backendSelected: boolean; resourceReady: boolean; title: string; hint: string }
export function readiness(node: Node | undefined, nodeError: string): Readiness {
  // 已读取过节点时，偶发的轮询失败不应让页面在“可用/不可用”之间闪烁；提交时 Java 仍会再次校验。
  const connected = !!node
  // 已有试跑通过的设备说明模型可用（可能来自只读预置目录）。
  const modelInstalled = connected && ((node!.models || [node!.model]).some(model => model.installed) || !!node!.selected)
  const backendSelected = connected && !!node!.selected
  const resourceReady = connected && node!.resourceReady !== false
  const ready = backendSelected && resourceReady
  const [title, hint] = !connected ? ['尚未连接计算节点', nodeError || '生成伴奏需要可用的计算节点；人工标记原唱不受影响。']
    : !ready && ['CHECKING', 'WAITING', 'TESTING'].includes(node!.startupCheck?.state ?? '') ? [node!.startupCheck?.state === 'WAITING' ? '等待空闲后自动试跑' : '正在自动检测并试跑模型', '节点启动后会自动检查已安装模型；播放任务优先，无需手动开始。']
    : !modelInstalled ? ['还没有安装分离模型', '下载或导入模型后，再做一次试跑即可开始生成。']
    : !backendSelected ? ['还需要做一次模型试跑', '试跑通过的设备才会用来生成伴奏。']
    : !resourceReady ? ['节点内存或磁盘不足', '当前空闲内存或磁盘低于预算，暂时不能生成，请释放资源后重新检测。']
    : [`可以生成伴奏 · ${backendName(node!.selected)}`, '']
  return { ready, connected, modelInstalled, backendSelected, resourceReady, title, hint }
}

export function failure(error: unknown, fallback = '操作失败') { return error instanceof Error ? error.message : fallback }
export function bytes(value: number) { return `${(value / 1024 ** 3).toFixed(1)} GiB` }
export function time(value?: number) { return value ? new Date(value).toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }) : '' }

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElAlert, ElButton, ElTag } from 'element-plus'
import AudioTrackPreview from './AudioTrackPreview.vue'
import { authRequest } from '../../../core/auth/api'
import { accompanimentUrl, songRoles, type AudioJob, type AudioRoles, type Backend, type Node, type SeparationCapabilities, type SeparationProfile } from '../../audio-processing/api'
import { analysisReason, backendName, failure, jobKinds, jobStates, latestAnalysis, latestJob, originLabel, profileBackends, profileName, profileReady, rolesSummary, streamLabel } from '../../audio-processing/presentation'
import SeparationControls from '../../audio-processing/components/SeparationControls.vue'

const props = withDefaults(defineProps<{ songId: string; canProcess?: boolean; separation?: SeparationCapabilities; backends?: Backend[] }>(), { canProcess: true })
const emit = defineEmits<{ changed: []; setup: [] }>()
const detail = ref<AudioRoles>(), original = ref(''), error = ref(''), notice = ref(''), busy = ref(false)
const node = ref<Node>(), profile = ref<SeparationProfile>('DSP_FAST'), preferred = ref('')
const sourceAudio = ref('')
const capabilities = computed(() => props.separation || node.value?.separation)
const devices = computed(() => profileBackends(capabilities.value, profile.value, props.backends || node.value?.backends || []))
const reusingSource = computed(() => !!sourceAudio.value || (detail.value?.state === 'CONFIRMED' && !!detail.value.backingId))
const ready = computed(() => profileReady(capabilities.value, reusingSource.value ? 'DSP_FAST' : profile.value, props.canProcess))
watch(devices, value => { if (!value.some(item => item.id === preferred.value)) preferred.value = '' })
async function loadCapabilities() {
  if (props.separation) return
  try { node.value = await authRequest<Node>('/api/v1/audio-processing/node', 'GET', undefined, 60000) } catch { /* Existing nodes retain their legacy controls. */ }
}
let timer: ReturnType<typeof setTimeout> | undefined, generation = 0
const base = () => songRoles(props.songId)
const inFlight = (job?: AudioJob) => !!job && ['QUEUED', 'RUNNING'].includes(job.state)
const summary = computed(() => rolesSummary(detail.value))
const streams = computed(() => detail.value?.streams ?? [])
const separate = computed(() => latestJob(detail.value, 'SEPARATE'))
const deepAnalyze = computed(() => latestJob(detail.value, 'ANALYZE'))
const analyze = computed(() => latestAnalysis(detail.value))
const quick = computed(() => latestJob(detail.value, 'FAST'))
const canQuick = computed(() => detail.value?.state !== 'CONFIRMED')
const running = computed(() => detail.value?.jobs.filter(inFlight) ?? [])
const history = computed(() => detail.value?.jobs.filter(job => !inFlight(job)) ?? [])
const reading = computed(() => running.value.some(job => job.kind !== 'SEPARATE'))
const canAnalyze = computed(() => !deepAnalyze.value || ['FAILED', 'CANCELLED'].includes(deepAnalyze.value.state) ||
  (deepAnalyze.value.state === 'SUCCEEDED' && detail.value?.state !== 'CONFIRMED' && deepAnalyze.value.resultState !== 'CONFIDENT'))
const backing = computed(() => original.value ? streams.value.find(stream => stream.id !== original.value)?.id ?? '' : '')
const confirmed = computed(() => detail.value?.state === 'CONFIRMED' && original.value === detail.value.originalId)
const saveLabel = computed(() => confirmed.value && detail.value?.origin === 'AUTO' ? '确认自动标记' : '保存标记')
const needsNode = computed(() => !detail.value?.streams || (streams.value.length === 1 && !detail.value.backingResource) || (streams.value.length === 2 && canAnalyze.value))
async function load(turn: number) {
  try {
    const value = await authRequest<AudioRoles>(`${base()}/audio-roles`)
    if (turn !== generation) return
    if (!detail.value || detail.value.revision !== value.revision) original.value = value.state === 'CONFIRMED' ? value.originalId ?? '' : ''
    detail.value = value
  } catch (e) { if (turn === generation) error.value = failure(e, '读取失败') }
}
async function action(run: () => Promise<unknown>, message: string) {
  const turn = generation
  busy.value = true; error.value = ''; notice.value = ''
  try { await run(); if (turn === generation) { notice.value = message; emit('changed'); await load(turn) } }
  catch (e) { if (turn === generation) error.value = failure(e) }
  finally { if (turn === generation) busy.value = false }
}
const submit = (kind: string, message: string) => action(() => authRequest(`${base()}/audio-jobs`, 'POST', { kind }), message)
const generate = (preview: boolean, outputFormat: 'aac' | 'flac' = 'aac') => action(() => authRequest(`${base()}/audio-jobs`, 'POST', {
  kind: 'SEPARATE', separationProfile: reusingSource.value ? 'DSP_FAST' : profile.value, backend: reusingSource.value ? '' : preferred.value, preview, outputFormat,
  ...(sourceAudio.value ? { sourceAudioId: sourceAudio.value, sourceConfirmed: true } : {}),
}), preview ? '已加入短片试听队列，完成后可在下方播放' : outputFormat === 'flac' ? '已加入 FLAC 导出队列，将从源文件重新按所选配方生成' : '已加入整曲生成队列；已有伴奏会保留，可试听后切换')
const activate = (jobId: string) => action(() => authRequest(`${base()}/accompaniment/${encodeURIComponent(jobId)}/activate`, 'POST'), '已切换当前伴奏，下次播放生效')
const cancel = (id: string) => action(() => authRequest(`${base()}/audio-jobs/${encodeURIComponent(id)}/cancel`, 'POST'), '已取消任务')
const save = () => action(() => authRequest(`${base()}/audio-roles`, 'PUT', { originalId: original.value, backingId: backing.value, revision: detail.value?.revision }), '已保存，下次点这首歌时生效')
async function poll(turn: number) {
  await load(turn)
  if (turn === generation) timer = setTimeout(() => { void poll(turn) }, 4000)
}
watch(() => props.songId, () => {
  generation++; clearTimeout(timer); detail.value = undefined; original.value = ''; sourceAudio.value = ''; busy.value = false; error.value = ''; notice.value = ''
  void poll(generation)
  void loadCapabilities()
}, { immediate: true })
onBeforeUnmount(() => { generation++; clearTimeout(timer) })
</script>

<template>
  <section class="audio-roles-panel" aria-label="原唱与伴奏">
    <ElAlert v-if="error" :title="error" type="error" show-icon :closable="false" role="alert" />
    <p v-if="notice" class="roles-notice" role="status">{{ notice }}</p>
    <div class="roles-status" :class="'is-' + summary.tone">
      <ElTag :type="summary.tone" effect="light" round>{{ summary.tag }}</ElTag>
      <div><h3>{{ summary.title }}</h3><p v-if="summary.hint">{{ summary.hint }}</p></div>
    </div>

    <div v-if="detail && !detail.streams" class="roles-actions">
      <ElButton type="primary" :disabled="busy || reading" @click="submit('PROBE', '已开始读取音轨')">读取音轨</ElButton>
    </div>
    <section v-else-if="streams.length === 1" class="roles-block" aria-label="伴奏">
      <template v-if="detail?.backingResource">
        <h4>试听伴奏</h4>
        <audio class="backing-player" :src="accompanimentUrl(songId, detail.backingResource.jobId)" controls preload="none" aria-label="试听生成的伴奏" />
      </template>
      <div v-else-if="!capabilities && !inFlight(separate)" class="roles-actions">
        <ElButton type="primary" :disabled="busy || !canProcess" @click="submit('SEPARATE', '已加入生成队列，可以关闭此窗口，完成后自动可用')">{{ separate?.state === 'FAILED' ? '重新生成伴奏' : '生成独立伴奏' }}</ElButton>
      </div>
    </section>
    <fieldset v-else-if="streams.length === 2" class="roles-block track-choice" :disabled="busy">
      <legend>哪一条是原唱？</legend>
      <p class="muted">扫描默认快速识别；不确定时可试听标记，或手动深度识别（耗时较长）。选出带人声的原唱，另一条会自动作为伴奏。</p>
      <label v-for="(stream, index) in streams" :key="stream.id" class="track-option" :class="{ 'is-checked': original === stream.id }">
        <input v-model="original" type="radio" :name="'original-' + songId" :value="stream.id">
        <span class="track-name">{{ streamLabel(stream, index) }}</span>
        <span v-if="original" class="track-role" :class="{ 'is-original': original === stream.id }">{{ original === stream.id ? '原唱' : '伴奏' }}</span>
      </label>
      <AudioTrackPreview :song-id="songId" :streams="streams" />
      <div class="roles-actions">
        <ElButton type="primary" :disabled="busy || !backing || (confirmed && detail?.origin !== 'AUTO')" @click="save">{{ saveLabel }}</ElButton>
        <ElButton v-if="canQuick" :disabled="busy || reading" @click="submit('FAST', '已开始快速识别；不确定时保留人工确认')">{{ quick ? '重新快速识别' : '快速识别' }}</ElButton>
        <ElButton v-if="canAnalyze" :disabled="busy || !canProcess || reading" @click="submit('ANALYZE', '已开始深度识别，将分离多个片段的人声，耗时较长')">深度识别</ElButton>
      </div>
      <p class="muted">标记对手机、电视和亲友点歌通用，下次点这首歌时生效，不会打断正在播放的歌曲。<template v-if="detail?.state === 'CONFIRMED'">当前为{{ originLabel(detail.origin) }}。</template></p>
    </fieldset>

    <section v-if="capabilities && streams.length" class="roles-block" aria-label="生成与试听伴奏">
      <h4>{{ detail?.state === 'CONFIRMED' && detail?.backingId ? '提取已确认的伴奏' : '选择伴奏方案' }}</h4>
      <p v-if="detail?.state === 'CONFIRMED' && detail?.backingId" class="muted">优先提取已确认的伴奏音轨，不再进行人声分离。</p>
      <label class="muted">已确认的源伴奏<select v-model="sourceAudio" aria-label="已确认的源伴奏" :disabled="busy || inFlight(separate)"><option value="">{{ detail?.backingId ? '使用已标记的伴奏音轨' : '没有源伴奏，按方案生成' }}</option><template v-for="(stream, index) in streams" :key="stream.id"><option :value="stream.id">{{ streamLabel(stream, index) }} · 整条</option><option v-if="stream.channels === 2" :value="stream.id + ':LEFT'">音轨 {{ index + 1 }} · 左声道</option><option v-if="stream.channels === 2" :value="stream.id + ':RIGHT'">音轨 {{ index + 1 }} · 右声道</option></template></select></label>
      <p v-if="sourceAudio" class="muted">仅当您已试听确认此音轨或声道是伴奏时选择。点击生成即按此标记直接提取，不做消人声；不改变原有音轨标记。</p>
      <SeparationControls v-else-if="!reusingSource" v-model:profile="profile" v-model:backend="preferred" :backends="devices" :disabled="busy || inFlight(separate)" />
      <p v-if="!ready" class="muted">所选方案尚未通过节点试跑。<ElButton link type="primary" @click="emit('setup')">准备环境</ElButton></p>
      <div class="roles-actions">
        <ElButton :disabled="busy || !ready || inFlight(separate)" @click="generate(true)">生成 12 秒试听</ElButton>
        <ElButton type="primary" :disabled="busy || !ready || inFlight(separate)" @click="generate(false)">生成整曲伴奏</ElButton>
        <ElButton :disabled="busy || !ready || inFlight(separate)" @click="generate(false, 'flac')">生成 FLAC 导出</ElButton>
      </div>
      <p class="muted">FLAC 从分离结果保存，不会把已有 AAC 转成 FLAC；分离本身仍可能损失音质。</p>
    </section>
    <section v-if="detail?.versions?.length" class="roles-block" aria-label="伴奏版本">
      <h4>伴奏版本</h4>
      <div v-for="version in detail.versions" :key="version.jobId" class="version">
        <p class="muted">{{ profileName(version.separationProfile) }} · {{ version.preview ? '短片试听' : '整曲' }} · {{ version.outputFormat === 'flac' ? 'FLAC' : 'AAC' }}<span v-if="version.active"> · 当前使用</span></p>
        <audio class="backing-player" :src="accompanimentUrl(songId, version.jobId)" controls preload="none" :aria-label="profileName(version.separationProfile) + (version.preview ? '短片试听' : '整曲试听')" />
        <div class="roles-actions"><a :href="accompanimentUrl(songId, version.jobId)" :download="`${songId}-${version.jobId}.${version.outputFormat === 'flac' ? 'flac' : 'm4a'}`">下载{{ version.outputFormat === 'flac' ? ' FLAC' : '' }}</a>
          <ElButton v-if="!version.preview && version.outputFormat !== 'flac' && !version.active" size="small" :disabled="busy" @click="activate(version.jobId)">设为当前伴奏</ElButton></div>
      </div>
    </section>

    <details v-if="analyze?.evidence" class="job-history" aria-label="识别依据"><summary>识别依据</summary>
      <p class="muted">{{ analyze.evidence.analysisVersion || '旧版分析' }} · {{ analyze.evidence.method === 'LABELS' ? '音轨标签' : `${analyze.evidence.vocalScores?.length || analyze.evidence.vocalRatio?.length || 0} 个片段` }}；{{ analyze.evidence.vocalScores ? '数值为人声分类分数' : '数值为人声与输入的能量比' }}，不是准确率。</p>
      <p v-if="analyze.reason" class="muted">{{ analysisReason(analyze.reason) }}</p>
      <ul><li v-for="(pair, index) in (analyze.evidence.vocalScores || analyze.evidence.vocalRatio)" :key="index">片段 {{ index + 1 }}<template v-if="analyze.evidence.windows?.[index]">（{{ Math.round(analyze.evidence.windows[index]!.start) }} 秒起）</template> · 音轨 1：{{ pair[0].toFixed(3) }} · 音轨 2：{{ pair[1].toFixed(3) }}</li></ul>
    </details>

    <p v-if="!canProcess && needsNode && !capabilities" class="roles-setup">深度识别和生成伴奏需要先准备分离环境；快速识别与人工标记可独立使用。<ElButton link type="primary" @click="emit('setup')">去准备环境</ElButton></p>

    <ul v-if="running.length" class="job-list" aria-label="进行中的任务">
      <li v-for="job in running" :key="job.id"><span class="sv-status-dot busy" aria-hidden="true" />{{ jobKinds[job.kind] || job.kind }} · {{ jobStates[job.state] }}<span v-if="job.error" class="muted"> · {{ job.error }}</span><ElButton link :disabled="busy" @click="cancel(job.id)">取消</ElButton></li>
    </ul>
    <details v-if="history.length" class="job-history"><summary>处理记录（{{ history.length }}）</summary>
      <ul><li v-for="job in history" :key="job.id">{{ jobKinds[job.kind] || job.kind }} · <span :class="{ danger: job.state === 'FAILED' }">{{ jobStates[job.state] || job.state }}</span><span v-if="job.backend"> · {{ backendName(job.backend) }}</span><span v-if="job.error" class="danger"> · {{ job.error }}</span></li></ul>
    </details>
    <p class="muted roles-footnote">原始媒体文件保持只读，生成的伴奏单独保存。</p>
  </section>
</template>

<style scoped>
.audio-roles-panel{display:grid;gap:16px}.roles-status{display:flex;gap:12px;align-items:flex-start;padding:14px 16px;border-radius:var(--sv-radius-md);background:var(--sv-surface-3)}.roles-status.is-success{background:var(--sv-semantic-ok-soft)}.roles-status.is-warning{background:var(--sv-semantic-warn-soft)}.roles-status.is-danger{background:var(--sv-semantic-danger-soft)}.roles-status h3{margin:1px 0 0;font-size:var(--sv-font-body);font-weight:600;color:var(--sv-text-primary)}.roles-status p{margin:4px 0 0;font-size:var(--sv-font-meta);line-height:1.6;color:var(--sv-text-secondary)}.roles-status :deep(.el-tag){flex:none}
.roles-block{display:grid;gap:10px;margin:0;padding:0;border:0;min-width:0}.roles-block h4,.track-choice legend{margin:0 0 2px;padding:0;font-size:var(--sv-font-body);font-weight:600;color:var(--sv-text-primary)}.backing-player{width:100%}
.roles-block select{display:block;width:100%;margin-top:6px;min-height:40px;padding:6px 10px;border:1px solid var(--sv-border-default);border-radius:var(--sv-radius-sm);color:var(--sv-text-primary);background:var(--sv-surface-2)}.version{display:grid;gap:8px;padding:12px 0;border-bottom:1px solid var(--sv-border-subtle)}
.track-option{display:flex;align-items:center;gap:10px;min-height:44px;padding:8px 14px;border:1px solid var(--sv-border-default);border-radius:var(--sv-radius-md);cursor:pointer;font-size:var(--sv-font-body)}.track-option:hover{border-color:var(--sv-accent-primary)}.track-option.is-checked{border-color:var(--sv-accent-primary);background:var(--sv-accent-soft)}.track-option:focus-within{outline:2px solid var(--sv-accent-primary);outline-offset:2px}.track-option input{accent-color:var(--sv-accent-primary);width:16px;height:16px;margin:0}.track-name{flex:1;min-width:0;overflow-wrap:anywhere}.track-role{flex:none;padding:2px 8px;border-radius:var(--sv-radius-full);font-size:12px;color:var(--sv-text-secondary);background:var(--sv-surface-3)}.track-role.is-original{color:var(--sv-accent-primary);background:var(--sv-surface-1)}.track-choice[disabled] .track-option{cursor:not-allowed;opacity:.7}
.roles-actions{display:flex;flex-wrap:wrap;gap:8px}.roles-actions :deep(.el-button){margin:0}.roles-notice{margin:0;padding:8px 12px;border-radius:var(--sv-radius-sm);background:var(--sv-accent-soft);color:var(--sv-accent-primary);font-size:var(--sv-font-meta)}.roles-setup{margin:0;font-size:var(--sv-font-meta);color:var(--sv-text-secondary)}
.job-list,.job-history ul{display:grid;gap:6px;margin:0;padding:0;list-style:none;font-size:var(--sv-font-meta);color:var(--sv-text-secondary)}.job-list li{display:flex;align-items:center;gap:8px;flex-wrap:wrap}.job-history summary{cursor:pointer;min-height:28px;line-height:28px;font-size:var(--sv-font-meta);color:var(--sv-text-secondary)}.job-history ul{padding:6px 0 0 4px}.muted{margin:0;font-size:var(--sv-font-meta);line-height:1.6;color:var(--sv-text-secondary)}.danger{color:var(--sv-semantic-danger)}.roles-footnote{font-size:12px;color:var(--sv-text-tertiary)}
</style>

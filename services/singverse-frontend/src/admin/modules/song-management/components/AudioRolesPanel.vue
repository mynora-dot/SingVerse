<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { authRequest } from '../../../core/auth/api'
const props = withDefaults(defineProps<{ songId: string; canProcess?: boolean }>(), { canProcess: true })
interface Job { id: string; kind: string; state: string; error?: string }
interface Roles { state: string; revision: number; originalId?: string; backingId?: string; backingResource?: {jobId:string}; streams?: {id:string;title?:string}[]; jobs: Job[] }
const detail = ref<Roles>(), original = ref(''), backing = ref(''), error = ref(''), busy = ref(false)
let timer: ReturnType<typeof setTimeout> | undefined, generation = 0
const base = () => `/api/v1/media-songs/${encodeURIComponent(props.songId)}`
const phase: Record<string, string> = { QUEUED: '排队中', RUNNING: '处理中', SUCCEEDED: '完成', FAILED: '失败', CANCELLED: '已取消' }
async function load(turn: number) {
  try {
    const value = await authRequest<Roles>(`${base()}/audio-roles`)
    if (turn !== generation) return
    if (!detail.value || detail.value.revision !== value.revision) {
      original.value = value.originalId ?? ''; backing.value = value.backingId ?? ''
    }
    detail.value = value
  } catch (e) { if (turn === generation) error.value = e instanceof Error ? e.message : '读取失败' }
}
async function action(run: () => Promise<unknown>) {
  const turn = generation
  busy.value = true; error.value = ''
  try { await run(); if (turn === generation) await load(turn) }
  catch (e) { if (turn === generation) error.value = e instanceof Error ? e.message : '操作失败' }
  finally { if (turn === generation) busy.value = false }
}
async function poll(turn: number) {
  await load(turn)
  if (turn === generation) timer = setTimeout(() => { void poll(turn) }, 4000)
}
watch(() => props.songId, () => {
  generation++; clearTimeout(timer); detail.value = undefined; original.value = ''; backing.value = ''; busy.value = false; error.value = ''
  void poll(generation)
}, { immediate: true })
onBeforeUnmount(() => { generation++; clearTimeout(timer) })
</script>
<template>
  <section class="audio-roles-panel">
    <h3>原唱与伴奏</h3><p v-if="error" role="alert">{{ error }}</p>
    <p>{{ detail?.state === 'CONFIRMED' ? `原唱 ${detail.originalId} · ${detail.backingResource ? '独立伴奏已生成' : detail.backingId ? `伴奏 ${detail.backingId}` : '伴奏待生成'}` : '音轨角色待确认' }}</p>
    <button :disabled="busy || !canProcess" @click="action(() => authRequest(`${base()}/audio-jobs`, 'POST', { kind: 'ANALYZE' }))">识别音轨</button>
    <button :disabled="busy || !canProcess || detail?.streams?.length !== 1" @click="action(() => authRequest(`${base()}/audio-jobs`, 'POST', { kind: 'SEPARATE' }))">生成独立伴奏</button>
    <p v-if="!canProcess">请先在“节点与模型”中准备运行环境、下载模型并完成试跑。人工纠正仍可使用。</p>
    <p v-if="!detail?.streams?.length">等待音轨探测完成后，单音轨歌曲可生成独立伴奏。</p>
    <p v-else-if="detail.streams.length > 1">当前歌曲包含多条音轨，请识别或纠正原唱与伴奏；独立伴奏生成仅适用于单音轨歌曲。</p>
    <p>分离需要计算节点安装本地模型，原媒体保持只读。</p>
    <label>原唱 <select v-model="original"><option value="">请选择</option><option v-for="stream in detail?.streams" :key="stream.id" :value="stream.id">{{ stream.title || stream.id }}</option></select></label>
    <label>伴奏 <select v-model="backing"><option value="">请选择</option><option v-for="stream in detail?.streams" :key="stream.id" :value="stream.id">{{ stream.title || stream.id }}</option></select></label>
    <button :disabled="busy || !original || !backing || original === backing" @click="action(() => authRequest(`${base()}/audio-roles`, 'PUT', { originalId: original, backingId: backing, revision: detail?.revision }))">保存到全曲库</button>
    <p v-for="job in detail?.jobs" :key="job.id">{{ job.kind === 'SEPARATE' ? '伴奏分离' : job.kind === 'PROBE' ? '音轨探测' : '音轨分析' }} · {{ phase[job.state] || job.state }} {{ job.error }} <button v-if="['QUEUED', 'RUNNING'].includes(job.state)" @click="action(() => authRequest(`${base()}/audio-jobs/${job.id}/cancel`, 'POST'))">取消</button></p>
  </section>
</template>
<style scoped>.audio-roles-panel{margin:20px 0;padding:16px;border:1px solid #ddd;border-radius:12px}.audio-roles-panel button,.audio-roles-panel label{margin:8px}.audio-roles-panel p{margin:8px 0}</style>

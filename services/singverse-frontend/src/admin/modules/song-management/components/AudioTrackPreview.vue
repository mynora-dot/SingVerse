<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { ElButton } from 'element-plus'
import type Hls from 'hls.js'
import type { AudioStream } from '../../audio-processing/api'
import { playback, type Resource } from '../../transcoding/playback'

const props = defineProps<{ songId: string; streams: AudioStream[] }>()
const player = ref<HTMLAudioElement>(), selected = ref(''), loading = ref(false), error = ref(''), notice = ref('')
let generation = 0, resource: Resource | undefined, hls: Hls | undefined
let heartbeat: ReturnType<typeof setInterval> | undefined
function release() {
  clearInterval(heartbeat); hls?.destroy(); hls = undefined
  const element = player.value
  if (element?.hasAttribute('src')) { element.pause(); element.removeAttribute('src'); element.load() }
  if (resource?.id) void playback.cancel(resource.id).catch(() => {})
  resource = undefined
}
function stop() { generation++; release(); selected.value = ''; loading.value = false }
function failed(message: string) { stop(); error.value = message }
async function preview(id: string) {
  const position = (resource?.offset ?? 0) + (player.value?.currentTime ?? 0)
  const turn = ++generation
  release(); selected.value = id; loading.value = true; error.value = ''; notice.value = ''
  try {
    let value = await playback.start(props.songId, { audio_id: id, quality: 'auto', start: position })
    if (turn !== generation) { if (value.id) void playback.cancel(value.id).catch(() => {}); return }
    resource = value
    const deadline = Date.now() + 60000
    while (value.status !== 'READY') {
      if (['FAILED', 'CANCELLED'].includes(value.state ?? '')) throw new Error(value.execution?.error || value.error || '音轨准备失败')
      if (!value.id || Date.now() > deadline) throw new Error('音轨准备超时，请重试')
      await new Promise(resolve => setTimeout(resolve, 500))
      if (turn !== generation) return
      value = await playback.status(value.id)
      if (turn !== generation) return
      resource = value
    }
    // Direct source playback cannot guarantee the selected stream (e.g. compute unavailable).
    if (value.mode !== 'HLS') throw new Error('当前无法选择原始音轨，请检查计算节点连接后重试')
    await nextTick()
    if (turn !== generation || !player.value) return
    const element = player.value
    const play = () => {
      if (turn !== generation) return
      void element.play().catch(() => { if (turn === generation) notice.value = '点击播放器的播放按钮开始试听' })
    }
    if (element.canPlayType('application/vnd.apple.mpegurl')) {
      element.addEventListener('loadedmetadata', play, { once: true }); element.src = value.url
    } else {
      const { default: Hls } = await import('hls.js')
      if (turn !== generation) return
      if (!Hls.isSupported()) throw new Error('当前浏览器不支持音轨试听，请使用支持 HLS 的浏览器')
      hls = new Hls()
      hls.on(Hls.Events.MANIFEST_PARSED, play)
      hls.on(Hls.Events.ERROR, (_event, data) => { if (turn === generation && data.fatal) failed('音轨播放中断，请重新试听') })
      hls.loadSource(value.url); hls.attachMedia(element)
    }
    if (value.id) heartbeat = setInterval(() => {
      if (resource?.id) void playback.status(resource.id).catch(() => {})
    }, 10000)
  } catch (e) { if (turn === generation) failed(e instanceof Error ? e.message : '试听失败') }
  finally { if (turn === generation) loading.value = false }
}
watch(() => props.songId, () => { stop(); error.value = ''; notice.value = '' })
onBeforeUnmount(stop)
</script>

<template>
  <section class="track-preview" aria-label="原始音轨试听">
    <div class="preview-actions">
      <ElButton v-for="(stream, index) in streams" :key="stream.id" :type="selected === stream.id ? 'primary' : 'default'" :loading="selected === stream.id && loading" @click="preview(stream.id)">试听音轨 {{ index + 1 }}</ElButton>
      <ElButton v-if="selected" @click="stop">停止试听</ElButton>
    </div>
    <p v-if="loading" role="status">正在准备音轨…</p>
    <p v-if="error" role="alert">{{ error }}</p><p v-if="notice" role="status">{{ notice }}</p>
    <audio v-show="selected && !loading" ref="player" controls preload="none" :aria-label="'试听音轨 ' + (streams.findIndex(stream => stream.id === selected) + 1)" @error="selected && failed('音轨解码失败，请重新试听')" />
    <p>切换音轨会从当前时间继续试听，不会更改原唱标记。</p>
  </section>
</template>

<style scoped>
.track-preview{display:grid;gap:8px}.preview-actions{display:flex;flex-wrap:wrap;gap:8px}.preview-actions :deep(.el-button){margin:0}.track-preview audio{width:100%}.track-preview p{margin:0;font-size:var(--sv-font-meta);color:var(--sv-text-secondary)}.track-preview [role=alert]{color:var(--sv-semantic-danger)}
</style>

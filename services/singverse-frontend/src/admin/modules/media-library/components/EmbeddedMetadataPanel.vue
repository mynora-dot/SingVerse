<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElButton, ElMessageBox } from 'element-plus'
import { embeddedMetadata, embeddedState, type EmbeddedDetail } from '../api/embeddedMetadata'
const props = withDefaults(defineProps<{ songId: string | null; compact?: boolean }>(), { compact: false })
const emit = defineEmits<{ loaded: [value: EmbeddedDetail | undefined] }>()
const detail = ref<EmbeddedDetail>(), error = ref(''), busy = ref(false)
const technical = computed(() => (detail.value?.result?.technical ?? {}) as { container?: string; duration?: string | number; streams?: { codec_type?: string; codec_name?: string; width?: number; height?: number; channels?: number; sample_rate?: string }[] })
const duration = computed(() => { const seconds = Number(technical.value.duration); return Number.isFinite(seconds) && seconds > 0 ? Math.floor(seconds / 60) + ' 分 ' + Math.round(seconds % 60) + ' 秒' : '未取得' })
let generation = 0
async function load() {
  if (!props.songId) return
  const token = ++generation
  try { const value = await embeddedMetadata.detail(props.songId); if (token === generation) { detail.value = value; error.value = ''; emit('loaded', value) } }
  catch (e) { if (token === generation) { error.value = e instanceof Error ? e.message : '读取内嵌元数据失败'; emit('loaded', undefined) } }
}
watch(() => props.songId, () => { generation++; detail.value = undefined; error.value = ''; void load() }, { immediate: true })
onBeforeUnmount(() => { generation++ })
async function refresh(deep = false) {
  const id = props.songId
  if (!id || busy.value) return
  if (deep) try { await ElMessageBox.confirm('深度提取可能读取最多 128 MiB，将继续遵守来源请求间隔与冷却。', '深度提取内嵌元数据', { confirmButtonText: '加入提取队列', cancelButtonText: '取消' }) } catch { return }
  busy.value = true
  try { await embeddedMetadata.refresh([id], deep); if (id === props.songId) await load() }
  catch (e) { if (id === props.songId) error.value = e instanceof Error ? e.message : '刷新失败' }
  finally { busy.value = false }
}
</script>
<template>
  <section v-if="detail && detail.state !== 'NOT_WEBDAV' || error" :class="['embedded-panel', { compact }]" aria-label="文件内嵌元数据">
    <h3 v-if="!compact">文件内嵌元数据</h3><p v-if="error" role="alert">{{ error }} <ElButton text @click="load">重试</ElButton></p>
    <template v-if="detail">
      <p v-if="detail.result?.evidence === 'FILENAME'">信息来自分享者的文件名解析，尚未读取媒体内嵌标签。</p><p>{{ embeddedState(detail.state) }}<span v-if="detail.changed"> · 文件可能已变化，仍使用已提取缓存</span><span v-if="detail.missing_scans"> · 文件待确认删除</span></p>
      <p v-if="detail.bytes_read !== undefined">累计读取 {{ ((detail.bytes_read ?? 0) / 1048576).toFixed(1) }} MiB · {{ detail.request_count ?? 0 }} 次请求</p>
      <img v-if="!compact && detail.result?.cover" :src="`/api/v1/media-songs/${encodeURIComponent(songId!)}/embedded-metadata/cover`" alt="文件内嵌封面" loading="lazy">
      <component :is="compact ? 'div' : 'details'" v-if="detail.result?.technical"><summary v-if="!compact">音视频技术信息</summary><dl><dt>文件格式</dt><dd>{{ technical.container || '未取得' }}</dd><dt>时长</dt><dd>{{ duration }}</dd><template v-for="(stream, index) in technical.streams" :key="index"><dt>{{ stream.codec_type === 'audio' ? '音轨' : stream.codec_type === 'video' ? '画面' : '媒体流' }} {{ index + 1 }}</dt><dd>{{ stream.codec_name || '未知编码' }}<span v-if="stream.width"> · {{ stream.width }} × {{ stream.height }}</span><span v-if="stream.channels"> · {{ stream.channels }} 声道</span><span v-if="stream.sample_rate"> · {{ stream.sample_rate }} Hz</span></dd></template></dl></component>
      <details v-if="!compact && detail.result?.lyrics"><summary>内嵌歌词</summary><p>{{ detail.result.lyrics.timing === 'milliseconds' || detail.result.lyrics.timing === 'lrc' ? '同步歌词' : detail.result.lyrics.timing === 'mpeg-frames' ? '按音频帧同步' : '文本歌词' }}</p><pre>{{ detail.result.lyrics.text }}</pre></details>
      <p v-if="detail.result?.fields && Object.values(detail.result.fields).includes('UNKNOWN')">部分字段尚未确定，可选择深度提取；普通重扫不会重复尝试。</p>
      <div class="actions"><ElButton :disabled="busy" @click="refresh()">刷新内嵌元数据</ElButton><ElButton :disabled="busy || detail.state === 'RUNNING'" @click="refresh(true)">深度提取</ElButton><ElButton :disabled="busy" @click="load">刷新状态</ElButton></div>
    </template>
  </section>
</template>
<style scoped>
.embedded-panel.compact{border:0;margin:16px 0 0;padding:0}.embedded-panel{border-top:1px solid var(--sv-border-subtle);margin-top:20px;padding-top:12px}h3{font-size:13px}p{font-size:12px;line-height:1.6}img{max-width:180px;max-height:180px;border-radius:8px}pre{white-space:pre-wrap;overflow-wrap:anywhere;font-size:12px}details{margin:12px 0}dl{display:grid;grid-template-columns:90px 1fr;gap:8px;font-size:12px}dd{margin:0}.actions{display:flex;gap:8px;flex-wrap:wrap}.actions :deep(.el-button){margin:0}
</style>

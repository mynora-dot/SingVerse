<script setup lang="ts">
import { computed, ref, watch, onBeforeUnmount, nextTick } from 'vue'
import { ElAlert, ElButton, ElDialog, ElSlider, ElMessage } from 'element-plus'
import type Hls from 'hls.js'
import type { MediaSong } from '../../media-library/types'
import { songTitle } from '../../metadata/utils'
import { playback, type Resource, type OfflineTask, type SourceTrack } from '../../transcoding/playback'

const props = defineProps<{ song: MediaSong | null }>()
const emit = defineEmits<{ close: [] }>()
const isAudio = computed(() => new Set(['mp3','flac','wav','m4a','aac','ogg','oga','opus','wma','aif','aiff','ape']).has(props.song?.relativePath.split('.').pop()?.toLowerCase() ?? ''))
const failed = ref(false), loading = ref(false), message = ref(''), quality = ref('auto'), mediaElement = ref<HTMLMediaElement | null>(null)
const resource = ref<Resource | null>(null), position = ref(0), tracks = ref<SourceTrack[]>([]), sourceDuration = ref(0), audioId = ref(''), subtitleId = ref('')
const tasks = ref<OfflineTask[]>([])
let generation = 0, hls: Hls | undefined, timer: ReturnType<typeof setInterval> | undefined
const currentTasks = computed(() => tasks.value.filter(t => t.songId === props.song?.id))
function release() { hls?.destroy(); hls = undefined; if (timer) clearInterval(timer); timer = undefined; if (resource.value?.id) void playback.cancel(resource.value.id).catch(() => {}); resource.value = null }
function mediaFailed(reason: string) { failed.value = true; message.value = reason; release() }
async function start(force = false, startAt = 0) {
  const song = props.song; if (!song) return
  const turn = ++generation; release(); failed.value = false; loading.value = true; message.value = '正在选择播放方式…'
  try {
    let value = await playback.start(song.id, { quality: quality.value, force, start: startAt, ...(audioId.value ? { audio_id: audioId.value } : {}), ...(subtitleId.value ? { subtitle_id: subtitleId.value, burn_subtitle: ['ass','ssa','hdmv_pgs_subtitle','dvd_subtitle','dvb_subtitle','xsub'].includes(tracks.value.find(t => t.id === subtitleId.value)?.codec ?? '') } : {}) })
    if (turn !== generation) { if (value.id) void playback.cancel(value.id).catch(() => {}); return }
    resource.value = value
    tracks.value = value.execution?.media?.streams ?? value.media?.streams ?? tracks.value
    sourceDuration.value = value.execution?.media?.duration ?? value.media?.duration ?? sourceDuration.value
    const deadline = Date.now() + 60000
    while (value.status !== 'READY') {
      if (['FAILED','CANCELLED'].includes(value.state ?? '')) throw new Error(value.execution?.error || value.error || '转换失败')
      if (!value.id || Date.now() > deadline) throw new Error('转换准备超时，请降低画质或生成兼容版本')
      await new Promise(resolve => setTimeout(resolve, 500))
      if (turn !== generation) return
      value = await playback.status(value.id)
      if (turn !== generation) return
      resource.value = value
    }
    if (turn !== generation) return
    tracks.value = value.execution?.media?.streams ?? value.media?.streams ?? tracks.value
    sourceDuration.value = value.execution?.media?.duration ?? value.media?.duration ?? sourceDuration.value
    loading.value = false; message.value = ({ DIRECT: '原始文件', VERSION: '兼容版本', HLS: '兼容播放' } as Record<string,string>)[value.mode] ?? value.mode
    await nextTick(); if (turn !== generation) return
    const element = mediaElement.value; if (!element) return
    if (value.mode === 'HLS' && !element.canPlayType('application/vnd.apple.mpegurl')) {
      const { default: Hls } = await import('hls.js')
      if (turn !== generation) return
      if (!Hls.isSupported()) throw new Error('当前浏览器无法播放 HLS，请使用兼容版本')
      hls = new Hls(); hls.loadSource(value.url); hls.attachMedia(element)
      hls.on(Hls.Events.ERROR, (_event, data) => { if (data.fatal) mediaFailed('兼容播放中断，请重新加载') })
    } else { element.src = value.url; if (startAt && value.mode !== 'HLS') element.addEventListener('loadedmetadata', () => { element.currentTime = startAt }, { once: true }) }
    timer = setInterval(() => {
      if (element) { position.value = element.currentTime + (resource.value?.offset ?? 0); if (Number.isFinite(element.duration) && !sourceDuration.value) sourceDuration.value = element.duration }
      if (resource.value?.id) void playback.status(resource.value.id).catch(() => {})
      void refreshTasks()
    }, 10000)
  } catch (error) { if (turn === generation) mediaFailed(error instanceof Error ? error.message : '播放失败') }
  finally { if (turn === generation) loading.value = false }
}
async function refreshTasks() { try { tasks.value = await playback.tasks() } catch { /* Playback remains independent of background task listing. */ } }
async function offline() { if (!props.song) return; try { await playback.offline([props.song.id], quality.value); await refreshTasks(); ElMessage.success('已加入兼容版本转换队列') } catch (error) { ElMessage.error(error instanceof Error ? error.message : '提交失败') } }
async function manageTask(id: string, remove: boolean) { try { if (remove) await playback.deleteOffline(id); else await playback.cancelOffline(id); await refreshTasks() } catch (error) { ElMessage.error(error instanceof Error ? error.message : '操作失败') } }
function seek(value: number | number[]) { if (typeof value !== 'number') return; if (resource.value?.mode === 'HLS') void start(false, value); else if (mediaElement.value) mediaElement.value.currentTime = value }
watch(() => props.song?.id, () => { ++generation; release(); quality.value = 'auto'; audioId.value = ''; subtitleId.value = ''; tracks.value = []; sourceDuration.value = 0; position.value = 0; if (props.song) { void start(); void refreshTasks() } }, { immediate: true })
onBeforeUnmount(() => { ++generation; release() })
</script>

<template>
  <ElDialog :model-value="!!song" :title="song ? `在线播放 · ${songTitle(song)}` : '在线播放'" :width="isAudio ? 'min(680px, calc(100vw - 32px))' : 'min(1000px, calc(100vw - 32px))'" top="6vh" :close-on-click-modal="false" @close="emit('close')">
    <template v-if="song">
      <p class="video-path">{{ song.sourceName }} / {{ song.relativePath }}</p>
      <div class="playback-tools">
        <select v-model="quality" aria-label="播放画质" @change="start(false, position)"><option v-for="q in ['auto','1080p','720p','480p']" :key="q" :value="q">{{ q === 'auto' ? '自动画质' : q }}</option></select>
        <select v-if="tracks.some(t => t.kind === 'audio')" v-model="audioId" aria-label="源音轨" @change="start(false, position)"><option value="">全部音轨</option><option v-for="t in tracks.filter(t => t.kind === 'audio')" :key="t.id" :value="t.id">{{ t.title || `${t.language} · ${t.id}` }}</option></select>
        <ElButton :disabled="loading" @click="start(true, position)">使用兼容播放</ElButton>
        <ElButton @click="offline">生成兼容版本</ElButton>
      </div>
      <ElAlert v-if="failed" title="媒体暂时无法播放" type="error" :closable="false" show-icon>{{ message }}<ElButton text @click="start(true, position)">重新加载</ElButton></ElAlert>
      <p v-else class="video-note">{{ message }}</p>
      <p v-if="resource?.execution?.pipeline" class="video-note">当前流水线：{{ resource.execution.pipeline.name }} · {{ resource.execution.pipeline.hardwareDecode ? '硬件解码' : '软件解码' }} · {{ resource.execution.pipeline.zeroCopy ? '零拷贝' : '兼容滤镜' }}<span v-if="resource.execution.pipeline.gpuToneMapping"> · GPU 色调映射</span></p>
      <div v-if="isAudio" class="audio-heading">♫ 音频播放</div>
      <select v-if="tracks.some(t => t.kind === 'subtitle')" v-model="subtitleId" aria-label="字幕" @change="start(false, position)"><option value="">关闭字幕</option><option v-for="t in tracks.filter(t => t.kind === 'subtitle')" :key="t.id" :value="t.id">{{ t.title || `${t.language} · ${t.codec}` }}</option></select>
      <component :is="isAudio ? 'audio' : 'video'" v-if="!failed && !loading" ref="mediaElement" :class="isAudio ? 'song-audio' : 'song-video'" controls :playsinline="!isAudio" preload="metadata" @timeupdate="position = (mediaElement?.currentTime ?? 0) + (resource?.offset ?? 0)" @error="mediaFailed('播放器无法解码此媒体，请使用兼容播放')"><track v-if="resource?.subtitleUrl" kind="subtitles" :src="resource.subtitleUrl" label="字幕" default />当前浏览器不支持此媒体播放。</component>
      <ElSlider v-if="sourceDuration > 0" :model-value="position" :max="sourceDuration" :step="1" aria-label="歌曲播放位置" @change="seek" />
      <div v-for="task in currentTasks" :key="task.id" class="video-note">兼容版本：{{ task.state }} {{ task.error }} <ElButton v-if="!['SUCCEEDED','FAILED','CANCELLED'].includes(task.state)" text @click="manageTask(task.id, false)">取消</ElButton><ElButton v-else text @click="manageTask(task.id, true)">删除</ElButton></div>
      <p class="video-note">原始媒体保持只读；转换版本保存在独立目录。双声道内容保留左右声道。</p>
    </template>
  </ElDialog>
</template>

<style scoped>
.playback-tools { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 12px; }
.song-audio { display: block; width: 100%; margin: 16px 0; }
.audio-heading { padding: 24px; text-align: center; color: var(--el-color-primary); background: var(--el-color-primary-light-9); border-radius: var(--sv-radius-sm); font-size: 20px; }
.song-video { display: block; width: 100%; max-height: 68vh; aspect-ratio: 16 / 9; background: var(--sv-text-primary); }
.video-path { overflow-wrap: anywhere; margin: 0 0 12px; color: var(--el-text-color-secondary); }
.video-note { margin: 12px 0 0; color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.7; }
</style>

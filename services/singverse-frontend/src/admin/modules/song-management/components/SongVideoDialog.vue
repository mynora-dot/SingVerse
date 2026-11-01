<script setup lang="ts">
import { computed, ref, watch, onBeforeUnmount, nextTick } from 'vue'
import { ElAlert, ElButton, ElDialog, ElIcon, ElMessage } from 'element-plus'
import { VideoPlay, VideoPause, Microphone, Headset, Setting, FullScreen, MuteNotification, Bell, Loading } from '@element-plus/icons-vue'
import type Hls from 'hls.js'
import type { MediaSong } from '../../media-library/types'
import { accompanimentUrl, type AudioRoles } from '../../audio-processing/api'
import { songTitle } from '../../metadata/utils'
import { playback, type Resource, type OfflineTask, type SourceTrack } from '../../transcoding/playback'

const props = defineProps<{ song: MediaSong | null }>()
const emit = defineEmits<{ close: [] }>()
const isAudio = computed(() => new Set(['mp3', 'flac', 'wav', 'm4a', 'aac', 'ogg', 'oga', 'opus', 'wma', 'aif', 'aiff', 'ape']).has(props.song?.relativePath.split('.').pop()?.toLowerCase() ?? ''))
const failed = ref(false), loading = ref(false), message = ref(''), notice = ref('')
const quality = ref('auto'), encoding = ref('auto'), role = ref<'original' | 'backing'>('original'), subtitleId = ref('')
const settingsOpen = ref(false), playing = ref(false), buffering = ref(false), volume = ref(70), muted = ref(false)
const mediaElement = ref<HTMLMediaElement | null>(null), backingElement = ref<HTMLAudioElement | null>(null), playerElement = ref<HTMLElement | null>(null)
const resource = ref<Resource | null>(null), position = ref(0), tracks = ref<SourceTrack[]>([]), sourceDuration = ref(0), roles = ref<AudioRoles>()
const tasks = ref<OfflineTask[]>([])
let generation = 0, songGeneration = 0, waitingForBacking = false, hls: Hls | undefined, timer: ReturnType<typeof setInterval> | undefined
let compatibilityRetried = false
let activeMedia: { element: HTMLMediaElement; turn: number; compatible: boolean; resumePlayback: boolean } | undefined
const currentTasks = computed(() => tasks.value.filter(t => t.songId === props.song?.id))
const backingAvailable = computed(() => !!roles.value?.backingResource || (roles.value?.state === 'CONFIRMED' && !!roles.value.backingId))
const subtitles = computed(() => tracks.value.filter(t => t.kind === 'subtitle'))
const stateLabel = computed(() => failed.value ? '播放失败' : loading.value ? '正在准备…' : buffering.value ? '正在缓冲…' : `${playing.value ? '正在播放' : '已暂停'} · ${role.value === 'backing' ? '伴奏' : '原唱'}`)
const taskLabels: Record<string, string> = { QUEUED: '等待中', RUNNING: '生成中', STARTING: '准备中', SUCCEEDED: '已生成', FAILED: '生成失败', CANCELLED: '已取消' }
function formatTime(value: number) {
  const s = Number.isFinite(value) ? Math.max(0, Math.floor(value)) : 0
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`
}
function release() {
  // Invalidate callbacks before load()/destroy() can queue abort and error events.
  activeMedia = undefined
  hls?.destroy(); hls = undefined; clearInterval(timer); timer = undefined
  for (const element of [mediaElement.value, backingElement.value]) {
    if (element) { element.pause(); element.removeAttribute('src'); element.load() }
  }
  if (resource.value?.id) void playback.cancel(resource.value.id).catch(() => {})
  resource.value = null; playing.value = false; buffering.value = false; waitingForBacking = false
}
function mediaFailed(reason: string) { ++generation; failed.value = true; loading.value = false; message.value = reason; release() }
function decodeFailed(reason: string, turn: number) {
  const active = activeMedia
  if (!active || active.turn !== turn || turn !== generation || loading.value) return
  if (encoding.value === 'auto' && !active.compatible && !compatibilityRetried) {
    compatibilityRetried = true
    void start(true, position.value, playing.value || active.resumePlayback, true)
  } else mediaFailed(reason)
}
function onMediaError(event: Event) {
  const element = event.currentTarget as HTMLMediaElement
  if (!activeMedia || element !== activeMedia.element || activeMedia.turn !== generation || loading.value) return
  const code = element.error?.code
  if (code === 1) return // MEDIA_ERR_ABORTED is expected when replacing/closing a source.
  if (code === 2) { mediaFailed('媒体读取失败，请检查网络或媒体来源后重试'); return }
  const reason = '播放器无法解码此媒体，请选择兼容编码重试'
  if (code === 3 || code === 4) decodeFailed(reason, activeMedia.turn)
  else mediaFailed('媒体加载失败，请检查媒体来源或重新加载')
}
function onBackingError(event: Event) {
  if (event.currentTarget !== backingElement.value || loading.value || !activeMedia || activeMedia.turn !== generation || backingElement.value?.error?.code === 1) return
  mediaFailed('伴奏暂时无法播放，请重新加载或切换原唱')
}
function applyVolume() {
  if (mediaElement.value) { mediaElement.value.volume = volume.value / 100; mediaElement.value.muted = !!resource.value?.backingUrl || muted.value }
  if (backingElement.value) { backingElement.value.volume = volume.value / 100; backingElement.value.muted = muted.value }
}
function syncBacking() {
  const main = mediaElement.value, backing = backingElement.value
  if (!main || !backing || backing.readyState === 0) return
  const time = main.currentTime + (resource.value?.offset ?? 0)
  if (Math.abs(backing.currentTime - time) > .25) backing.currentTime = time
  backing.playbackRate = main.playbackRate
}
function updatePosition() {
  const element = mediaElement.value; if (!element) return
  position.value = element.currentTime + (resource.value?.offset ?? 0)
  if (Number.isFinite(element.duration) && !sourceDuration.value) sourceDuration.value = element.duration + (resource.value?.offset ?? 0)
  syncBacking()
}
function pause() { waitingForBacking = false; mediaElement.value?.pause(); backingElement.value?.pause(); playing.value = false; buffering.value = false }
function onPause(event: Event) {
  if (event.target !== mediaElement.value || loading.value || mediaElement.value?.error) return
  backingElement.value?.pause()
  if (!waitingForBacking) playing.value = false
}
function backingWaiting() {
  if (!mediaElement.value || mediaElement.value.paused) return
  waitingForBacking = true; buffering.value = true; mediaElement.value.pause()
}
function backingReady() {
  syncBacking()
  if (waitingForBacking) { waitingForBacking = false; buffering.value = false; void resume() }
  else void playBacking()
}
async function playBacking() {
  const backing = backingElement.value, turn = generation
  if (!backing || mediaElement.value?.paused || buffering.value) return
  syncBacking()
  try { await backing.play() }
  catch { if (turn === generation && backing === backingElement.value) { pause(); notice.value = '伴奏未能播放，请点击播放重试' } }
}
function onPlaying() { buffering.value = false; playing.value = true; void playBacking() }
function onWaiting() { buffering.value = true; backingElement.value?.pause() }
async function resume() {
  const element = mediaElement.value, turn = generation; if (!element) return
  try { await element.play() }
  catch { if (turn === generation && element === mediaElement.value) { pause(); notice.value = '请点击播放继续' } }
}
function togglePlay() { if (playing.value) pause(); else void resume() }
async function start(force = false, startAt = position.value, resumePlayback = playing.value, recovering = false) {
  const song = props.song; if (!song) return
  const turn = ++generation
  if (!recovering) compatibilityRetried = false
  loading.value = true; failed.value = false; notice.value = recovering ? '当前格式不兼容，正在自动切换兼容播放…' : ''; release(); position.value = startAt
  message.value = '正在选择播放方式…'
  try {
    const selectedId = roles.value?.state === 'CONFIRMED' ? (role.value === 'original' ? roles.value.originalId : roles.value.backingId) : undefined
    // Raw browser playback cannot select a source stream; Java must select/remux it.
    if (encoding.value === 'source' && ((selectedId && (roles.value?.streams?.length ?? 0) > 1) || subtitleId.value || quality.value !== 'auto')) {
      encoding.value = 'auto'; notice.value = '当前音轨、画质或字幕需要处理，已切换为自动编码'
    }
    let value: Resource
    if (isAudio.value && role.value === 'backing' && roles.value?.backingResource) {
      value = { mode: 'DIRECT', status: 'READY', offset: 0, url: accompanimentUrl(song.id, roles.value.backingResource.jobId) }
    } else if (encoding.value === 'source') {
      value = { mode: 'DIRECT', status: 'READY', offset: 0, url: `/api/v1/media-songs/${encodeURIComponent(song.id)}/video`,
        ...(role.value === 'backing' && roles.value?.backingResource ? { backingUrl: accompanimentUrl(song.id, roles.value.backingResource.jobId) } : {}) }
    } else {
      value = await playback.start(song.id, {
        quality: quality.value, force: force || encoding.value === 'compatible', start: startAt, audioRole: role.value,
        ...((force || encoding.value === 'compatible') && !isAudio.value ? { codec: 'h264' } : {}),
        ...(subtitleId.value ? { subtitle_id: subtitleId.value, burn_subtitle: ['ass', 'ssa', 'hdmv_pgs_subtitle', 'dvd_subtitle', 'dvb_subtitle', 'xsub'].includes(tracks.value.find(t => t.id === subtitleId.value)?.codec ?? '') } : {}),
      })
    }
    if (turn !== generation) { if (value.id) void playback.cancel(value.id).catch(() => {}); return }
    // Session polling omits the role snapshot and authorized independent backing URL.
    const initialRoles = value.audioRoles, initialBacking = value.backingUrl
    const applyResource = (next: Resource) => {
      value = { ...next, audioRoles: next.audioRoles ?? initialRoles, backingUrl: next.backingUrl ?? initialBacking }
      resource.value = value
      if (value.audioRoles) roles.value = value.audioRoles
      tracks.value = value.execution?.media?.streams ?? value.media?.streams ?? tracks.value
      sourceDuration.value = value.execution?.media?.duration ?? value.media?.duration ?? sourceDuration.value
    }
    applyResource(value)
    const deadline = Date.now() + 60000
    while (value.status !== 'READY') {
      if (['FAILED', 'CANCELLED'].includes(value.state ?? '')) throw new Error(value.execution?.error || value.error || '转换失败')
      if (!value.id || Date.now() > deadline) throw new Error('转换准备超时，请降低画质或生成兼容版本')
      await new Promise(resolve => setTimeout(resolve, 500))
      if (turn !== generation) return
      const next = await playback.status(value.id)
      if (turn !== generation) return
      applyResource(next)
    }
    if (role.value === 'backing' && !value.backingUrl && !roles.value?.backingId && !(isAudio.value && roles.value?.backingResource)) throw new Error('伴奏已失效，请重新加载歌曲')
    loading.value = false
    await nextTick(); if (turn !== generation) return
    const element = mediaElement.value; if (!element) return
    element.addEventListener('loadedmetadata', () => {
      if (turn !== generation) return
      if (value.mode !== 'HLS' && startAt) element.currentTime = startAt
      applyVolume(); updatePosition(); if (resumePlayback) void resume()
    }, { once: true })
    applyVolume()
    if (value.mode === 'HLS' && !element.canPlayType('application/vnd.apple.mpegurl')) {
      const { default: Hls } = await import('hls.js')
      if (turn !== generation) return
      if (!Hls.isSupported()) throw new Error('当前浏览器无法播放 HLS，请生成兼容版本')
      hls = new Hls()
      hls.on(Hls.Events.ERROR, (_event, data) => {
        if (turn !== generation || !data.fatal) return
        if (data.type === 'mediaError') decodeFailed('兼容播放解码失败，请生成兼容版本或检查媒体文件', turn)
        else mediaFailed(data.type === 'networkError' ? '兼容播放网络中断，请检查连接后重新加载' : '兼容播放中断，请重新加载')
      })
      activeMedia = { element, turn, compatible: force || encoding.value === 'compatible', resumePlayback }
      hls.loadSource(value.url); hls.attachMedia(element)
    } else {
      activeMedia = { element, turn, compatible: force || encoding.value === 'compatible', resumePlayback }
      element.src = value.url
    }
    if (turn !== generation) return
    if (recovering) notice.value = '已自动切换为兼容播放'
    timer = setInterval(() => {
      if (turn !== generation) return
      if (resource.value?.id) void playback.status(resource.value.id).catch(() => {})
      void refreshTasks(songGeneration)
    }, 10000)
  } catch (error) { if (turn === generation) mediaFailed(error instanceof Error ? error.message : '播放失败') }
  finally { if (turn === generation) loading.value = false }
}
async function loadRoles(turn: number) {
  if (!props.song) return
  try { const value = await playback.roles(props.song.id); if (turn === songGeneration && value && !roles.value) roles.value = value }
  catch { /* Role lookup must not prevent original-file playback. */ }
}
async function refreshTasks(turn = songGeneration) {
  try { const value = await playback.tasks(); if (turn === songGeneration) tasks.value = value }
  catch { /* Playback remains independent of background tasks. */ }
}
async function offline() {
  if (!props.song) return
  const turn = songGeneration
  try { await playback.offline([props.song.id], quality.value); if (turn === songGeneration) { await refreshTasks(turn); ElMessage.success('已加入兼容版本转换队列') } }
  catch (error) { if (turn === songGeneration) ElMessage.error(error instanceof Error ? error.message : '提交失败') }
}
async function manageTask(id: string, remove: boolean) {
  const turn = songGeneration
  try { if (remove) await playback.deleteOffline(id); else await playback.cancelOffline(id); await refreshTasks(turn) }
  catch (error) { if (turn === songGeneration) ElMessage.error(error instanceof Error ? error.message : '操作失败') }
}
function changeRole(value: 'original' | 'backing') {
  if (value === role.value || loading.value || (value === 'backing' && !backingAvailable.value)) return
  role.value = value; void start()
}
function seek(event: Event) {
  const value = Number((event.target as HTMLInputElement).value)
  if (resource.value?.mode === 'HLS') void start(false, value)
  else if (mediaElement.value) { mediaElement.value.currentTime = value; position.value = value; syncBacking() }
}
function retry() { encoding.value = 'compatible'; void start(true) }
async function fullscreen() {
  try {
    if (document.fullscreenElement) await document.exitFullscreen()
    else if (playerElement.value?.requestFullscreen) await playerElement.value.requestFullscreen()
    else notice.value = '当前浏览器不支持全屏播放'
  }
  catch { notice.value = '当前浏览器不支持全屏播放' }
}
watch([volume, muted], applyVolume)
watch(() => props.song?.id, () => {
  ++generation; ++songGeneration; loading.value = false; release()
  quality.value = 'auto'; encoding.value = 'auto'; role.value = 'original'; subtitleId.value = ''; tracks.value = []; roles.value = undefined
  sourceDuration.value = 0; position.value = 0; tasks.value = []; notice.value = ''; settingsOpen.value = false
  if (props.song) { void start(false, 0, false); void loadRoles(songGeneration); void refreshTasks(songGeneration) }
}, { immediate: true })
onBeforeUnmount(() => { ++generation; ++songGeneration; release() })
</script>

<template>
  <ElDialog :model-value="!!song" class="song-player-dialog" :width="isAudio ? 'min(580px, calc(100vw - 24px))' : 'min(860px, calc(100vw - 24px))'" top="5vh" :close-on-click-modal="false" @close="emit('close')">
    <template #header><div class="player-title"><span>在线播放</span><h2>{{ song ? songTitle(song) : '' }}</h2></div></template>
    <section v-if="song" ref="playerElement" class="song-player" aria-label="歌曲播放器">
      <div class="media-stage" :class="{ 'is-audio': isAudio }">
        <component :is="isAudio ? 'audio' : 'video'" v-if="!failed && !loading" :key="generation" ref="mediaElement" :class="isAudio ? 'song-audio' : 'song-video'" :playsinline="!isAudio" preload="metadata"
          @timeupdate="updatePosition" @loadedmetadata="updatePosition" @play="playing = true" @playing="onPlaying" @pause="onPause" @ended="pause" @waiting="onWaiting" @seeking="backingElement?.pause()" @seeked="syncBacking(); playBacking()" @ratechange="syncBacking" @error="onMediaError">
          <track v-if="resource?.subtitleUrl" kind="subtitles" :src="resource.subtitleUrl" label="字幕" default />当前浏览器不支持此媒体播放。
        </component>
        <audio v-if="!failed && !loading && resource?.backingUrl" :key="generation" ref="backingElement" class="backing-audio" :src="resource.backingUrl" preload="auto" aria-label="独立伴奏" @loadedmetadata="syncBacking" @canplay="backingReady" @waiting="backingWaiting" @error="onBackingError" />
        <ElAlert v-if="failed" class="player-error" title="媒体暂时无法播放" type="error" :closable="false" show-icon><span>{{ message }}</span><ElButton text @click="retry">重新加载</ElButton></ElAlert>
        <div v-else-if="loading" class="stage-overlay" role="status"><ElIcon class="is-loading"><Loading /></ElIcon><span>正在准备播放…</span></div>
        <button v-else-if="isAudio" type="button" class="audio-visual" :aria-label="playing ? '暂停' : '播放'" @click="togglePlay"><span class="audio-disc"><ElIcon><Headset /></ElIcon></span><span>音频播放</span></button>
        <button v-else type="button" class="video-play-overlay" :class="{ 'is-playing': playing }" :aria-label="playing ? '暂停' : '播放'" @click="togglePlay"><ElIcon><VideoPause v-if="playing" /><VideoPlay v-else /></ElIcon></button>
      </div>
      <div class="player-controls">
        <div class="timeline"><span>{{ formatTime(position) }}</span><input type="range" :value="position" :max="sourceDuration || 1" min="0" step="1" :disabled="loading || failed || !sourceDuration" aria-label="播放进度" @change="seek"><span>{{ formatTime(sourceDuration) }}</span></div>
        <div class="transport"><button type="button" class="main-play" :disabled="loading || failed" :aria-label="playing ? '暂停' : '播放'" @click="togglePlay"><ElIcon><VideoPause v-if="playing" /><VideoPlay v-else /></ElIcon></button><span class="transport-state" role="status">{{ stateLabel }}</span><div class="transport-space" /><button type="button" class="icon-button" :aria-label="muted ? '取消静音' : '静音'" @click="muted = !muted"><ElIcon><MuteNotification v-if="muted || volume === 0" /><Bell v-else /></ElIcon></button><input v-model.number="volume" class="volume" type="range" min="0" max="100" aria-label="音量"><button v-if="!isAudio" type="button" class="icon-button" aria-label="全屏" @click="fullscreen"><ElIcon><FullScreen /></ElIcon></button></div>
        <div class="feature-row"><div class="role-picker" aria-label="声音切换"><button type="button" :aria-pressed="role === 'original'" :disabled="loading" @click="changeRole('original')"><ElIcon><Microphone /></ElIcon>原唱</button><button type="button" :aria-pressed="role === 'backing'" :disabled="loading || !backingAvailable" @click="changeRole('backing')"><ElIcon><Headset /></ElIcon>伴奏</button></div><label class="encoding"><span>编码</span><select v-model="encoding" aria-label="播放编码" :disabled="loading" @change="start()"><option value="auto">自动选择</option><option value="source">原始编码</option><option value="compatible">{{ isAudio ? 'AAC · 兼容' : 'H.264 / AAC · 兼容' }}</option></select></label><button type="button" class="settings-button" :aria-expanded="settingsOpen" @click="settingsOpen = !settingsOpen"><ElIcon><Setting /></ElIcon>设置</button></div>
        <p v-if="!backingAvailable && !loading" class="player-notice">伴奏未就绪</p>
        <section v-if="settingsOpen" class="play-settings" aria-label="播放设置"><label v-if="!isAudio">画质<select v-model="quality" aria-label="播放画质" :disabled="loading" @change="start()"><option value="auto">自动</option><option v-for="q in ['1080p', '720p', '480p']" :key="q" :value="q">{{ q }}</option></select></label><label v-if="subtitles.length">字幕<select v-model="subtitleId" aria-label="字幕" :disabled="loading" @change="start()"><option value="">关闭</option><option v-for="t in subtitles" :key="t.id" :value="t.id">{{ t.title || `${t.language || '字幕'} · ${t.codec}` }}</option></select></label><ElButton :disabled="loading" @click="offline">生成兼容版本</ElButton><div v-for="task in currentTasks" :key="task.id" class="offline-task"><span>兼容版本：{{ taskLabels[task.state] || task.state }} {{ task.error }}</span><ElButton v-if="!['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(task.state)" text @click="manageTask(task.id, false)">取消</ElButton><ElButton v-else text @click="manageTask(task.id, true)">删除</ElButton></div></section>
        <p v-if="notice" class="player-notice" role="status">{{ notice }}</p>
      </div>
    </section>
  </ElDialog>
</template>

<style scoped>
:global(.song-player-dialog.el-dialog){padding:0;border-radius:16px;overflow:hidden}
:global(.song-player-dialog .el-dialog__header){padding:20px 52px 20px 24px;margin:0}
:global(.song-player-dialog .el-dialog__headerbtn){top:16px;right:12px}
:global(.song-player-dialog .el-dialog__body){padding:0;max-height:calc(90dvh - 95px);overflow-y:auto}
.player-title>span{font-size:12px;color:var(--sv-text-tertiary)}.player-title h2{margin:4px 0 0;font-size:20px;font-weight:600;line-height:1.4;overflow-wrap:anywhere}
.song-player{background:var(--sv-surface-2)}.song-player:fullscreen{display:flex;flex-direction:column;justify-content:center;overflow:auto}.song-player:fullscreen .media-stage{flex:1;min-height:0}.song-player:fullscreen .song-video{position:absolute;inset:0;width:100%;height:100%;max-height:none}.song-player:fullscreen .player-controls{flex:none}
.media-stage{position:relative;display:grid;place-items:center;aspect-ratio:16/9;background:var(--sv-sidebar-bg);color:var(--sv-text-inverse)}.media-stage.is-audio{aspect-ratio:auto;min-height:200px;background:linear-gradient(150deg,var(--sv-accent-soft),var(--sv-surface-2));color:var(--sv-text-tertiary)}
.song-video{display:block;width:100%;height:100%;max-height:48dvh;object-fit:contain}.song-audio,.backing-audio{display:none}.stage-overlay{display:flex;align-items:center;gap:10px;font-size:13px}.stage-overlay .el-icon{font-size:20px}
.video-play-overlay{position:absolute;inset:0;display:grid;place-items:center;background:transparent;border:0;color:var(--sv-text-inverse);cursor:pointer}.video-play-overlay>.el-icon{width:56px;height:56px;border-radius:50%;background:rgb(255 255 255 / 12%);font-size:30px}.video-play-overlay.is-playing>.el-icon{opacity:0}.video-play-overlay.is-playing:hover>.el-icon,.video-play-overlay:focus-visible>.el-icon{opacity:1}
.audio-visual{display:flex;align-items:center;flex-direction:column;gap:18px;border:0;background:transparent;color:var(--sv-text-tertiary);font-size:12px;padding:28px;cursor:pointer}.audio-disc{display:grid;place-items:center;width:100px;height:100px;border-radius:50%;background:var(--sv-accent-soft);box-shadow:0 0 0 10px rgb(37 99 235 / 4%);color:var(--sv-accent-primary)}.audio-disc .el-icon{font-size:36px}.player-error{width:calc(100% - 40px);margin:20px}.player-error :deep(.el-alert__description){display:flex;align-items:center;flex-wrap:wrap;gap:8px}
.player-controls{padding:22px 24px 24px}.timeline{display:flex;align-items:center;gap:12px;color:var(--sv-text-tertiary);font-size:12px;font-variant-numeric:tabular-nums}.timeline input{flex:1;min-width:0;accent-color:var(--sv-accent-primary)}.timeline>span{white-space:nowrap}
.transport{display:flex;align-items:center;gap:12px;margin-top:14px}.main-play{display:grid;place-items:center;width:42px;height:42px;border-radius:50%;border:0;background:var(--sv-accent-primary);color:var(--sv-text-inverse);font-size:24px;cursor:pointer}.transport-state{font-size:12px;color:var(--sv-text-tertiary)}.transport-space{flex:1}.icon-button{display:grid;place-items:center;width:36px;height:36px;border:0;border-radius:6px;background:transparent;color:var(--sv-text-secondary);font-size:19px;cursor:pointer}.icon-button:hover{background:var(--sv-surface-3)}.volume{width:64px;accent-color:var(--sv-accent-primary)}
.feature-row{display:flex;align-items:center;gap:16px;flex-wrap:wrap;margin-top:22px;padding-top:20px;border-top:1px solid var(--sv-border-subtle)}.role-picker{display:flex;gap:3px;padding:4px;border-radius:9px;background:var(--sv-surface-3)}.role-picker button{display:flex;align-items:center;justify-content:center;gap:6px;min-height:36px;padding:8px 13px;border:0;border-radius:6px;background:transparent;color:var(--sv-text-tertiary);font-size:13px;cursor:pointer}.role-picker button[aria-pressed=true]{background:var(--sv-surface-2);color:var(--sv-accent-primary);box-shadow:var(--sv-shadow-sm)}.encoding{display:flex;align-items:center;gap:9px;flex:1;min-width:180px;font-size:12px;color:var(--sv-text-tertiary)}.encoding>span{white-space:nowrap}.encoding select{flex:1;min-width:0}select{max-width:100%;padding:8px 10px;border:1px solid var(--sv-border-default);border-radius:6px;background:var(--sv-surface-2);color:var(--sv-text-primary);font:inherit;font-size:13px}.settings-button{display:flex;align-items:center;gap:5px;padding:10px 0;background:transparent;border:0;color:var(--sv-text-tertiary);font:inherit;font-size:12px;cursor:pointer}
button:disabled{opacity:.45;cursor:not-allowed}.play-settings{display:flex;align-items:center;gap:16px;flex-wrap:wrap;margin-top:16px;padding:16px;background:var(--sv-surface-3);border-radius:8px}.play-settings label{display:flex;align-items:center;gap:8px;font-size:13px;color:var(--sv-text-secondary)}.offline-task{flex-basis:100%;display:flex;align-items:center;gap:8px;font-size:12px;overflow-wrap:anywhere}.player-notice{margin:12px 0 0;font-size:12px;line-height:1.6;color:var(--sv-text-tertiary)}
@media(max-width:600px){.feature-row{gap:12px}.role-picker{flex:1}.role-picker button{flex:1}.encoding{order:3;flex-basis:100%}.volume{display:none}.player-controls{padding:18px}.transport{gap:8px}:global(.song-player-dialog .el-dialog__header){padding:16px 48px 16px 18px}}
@media(pointer:coarse){button{min-height:44px}select{font-size:16px}}
</style>

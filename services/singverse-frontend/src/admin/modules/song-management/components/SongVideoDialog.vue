<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElAlert, ElButton, ElDialog } from 'element-plus'
import type { MediaSong } from '../../media-library/types'
import { songTitle } from '../../metadata/utils'

const props = defineProps<{ song: MediaSong | null }>()
const emit = defineEmits<{ close: [] }>()
const isAudio = computed(() => new Set(['mp3', 'flac', 'wav', 'm4a', 'aac', 'ogg', 'oga', 'opus', 'wma', 'aif', 'aiff', 'ape']).has(props.song?.relativePath.split('.').pop()?.toLowerCase() ?? ''))
const failed = ref(false), attempt = ref(0)
watch(() => props.song?.id, () => { failed.value = false; attempt.value = 0 })
function retry() { failed.value = false; attempt.value++ }
</script>

<template>
  <ElDialog :model-value="!!song" :title="song ? `在线播放 · ${songTitle(song)}` : '在线播放'" :width="isAudio ? 'min(680px, calc(100vw - 32px))' : 'min(1000px, calc(100vw - 32px))'" top="6vh" :close-on-click-modal="false" @close="emit('close')">
    <template v-if="song">
      <p class="video-path">{{ song.sourceName }} / {{ song.relativePath }}</p>
      <ElAlert v-if="failed" title="媒体暂时无法播放" type="error" :closable="false" show-icon>请检查来源连接、文件权限及登录状态；浏览器也可能不支持该视频或音频编码。当前不提供转码。<ElButton text @click="retry">重新加载</ElButton></ElAlert>
      <div v-if="isAudio" class="audio-heading">♫ 音频播放</div>
      <component :is="isAudio ? 'audio' : 'video'" v-if="!failed" :key="`${song.id}:${attempt}`" :class="isAudio ? 'song-audio' : 'song-video'" controls :playsinline="!isAudio" preload="metadata" :src="`/api/v1/media-songs/${encodeURIComponent(song.id)}/video`" @error="failed = true">当前浏览器不支持此媒体播放。</component>
      <p class="video-note">播放原始文件，不修改原件。{{ isAudio ? 'MP3、FLAC、WAV、M4A 等能否播放取决于浏览器支持的编码；WMA、APE 等可能无法直接播放。' : 'MP4、WebM 等能否播放取决于浏览器支持的编码；MKV、AVI 等可能无法直接播放。' }}</p>
    </template>
  </ElDialog>
</template>

<style scoped>
.song-audio { display: block; width: 100%; margin: 16px 0; }
.audio-heading { padding: 24px; text-align: center; color: var(--el-color-primary); background: var(--el-color-primary-light-9); border-radius: var(--sv-r-sm); font-size: 20px; }
.song-video { display: block; width: 100%; max-height: 68vh; aspect-ratio: 16 / 9; background: var(--sv-deep-900); }
.video-path { overflow-wrap: anywhere; margin: 0 0 12px; color: var(--el-text-color-secondary); }
.video-note { margin: 12px 0 0; color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.7; }
</style>

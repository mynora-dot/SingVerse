<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { MediaSong } from '../../media-library/types'
import { songTitle } from '../../metadata/utils'
const props = defineProps<{ song: MediaSong; fallbackOnly?: boolean }>()
const failed = ref(false)
const palette = computed(() => {
  const colors = [['#e7dcc3','#8a6d3b'],['#e3d3c3','#8c5a3c'],['#dfe0d0','#6f7350'],['#e9d9c8','#9c6b45'],['#e0d4d0','#7d5a52']]
  return colors[Array.from(props.song.id).reduce((n, c) => n + c.charCodeAt(0), 0) % colors.length]!
})
const artwork = computed(() => {
  const metadata = props.song.metadata
  return metadata && !metadata.stale && metadata.fields.includes('cover') && metadata.track.coverUrl
    ? `/api/v1/metadata/songs/${encodeURIComponent(props.song.id)}/cover?variant=thumbnail` : ''
})
watch(() => [props.song.id, props.song.metadata, artwork.value], () => { failed.value = false })
</script>
<template>
  <span class="song-cover">
    <img v-if="artwork && !failed && !fallbackOnly" :src="artwork" :alt="`${songTitle(song)}的封面`" loading="lazy" decoding="async" @error="failed = true">
    <span v-else class="song-cover-placeholder" role="img" aria-label="暂无歌曲封面" :style="{ '--cover-paper': palette[0], '--cover-ink': palette[1] }"><span class="record-grooves"><span>{{ songTitle(song).slice(0, 1) }}</span></span></span>
  </span>
</template>
<style scoped>
.song-cover {
  display: block;
  flex-shrink: 0;
  width: 46px;
  height: 46px;
  overflow: hidden;
  border-radius: 6px;
  background: var(--sv-surface-3)
}

.song-cover img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover
}

.song-cover-placeholder {
  display: grid;
  place-items: center;
  width: 100%;
  height: 100%;
  background: var(--cover-paper);
  position: relative;
  overflow: hidden
}

.record-grooves {
  width: 83%;
  aspect-ratio: 1;
  display: grid;
  place-items: center;
  border-radius: 50%;
  background: repeating-radial-gradient(circle, var(--cover-ink) 0 1px, transparent 2px 4px);
  transform: translate(15%, 9%);
  opacity: .8
}

.record-grooves>span {
  width: 35%;
  aspect-ratio: 1;
  display: grid;
  place-items: center;
  border-radius: 50%;
  background: var(--cover-paper);
  color: var(--cover-ink);
  font: 600 clamp(10px, 2vw, 22px) var(--sv-font-editorial);
  border: 1px solid var(--cover-ink)
}
</style>

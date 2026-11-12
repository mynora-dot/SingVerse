<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { MediaSong } from '../../media-library/types'
import { songTitle } from '../../metadata/utils'
const props = defineProps<{ song: MediaSong; fallbackOnly?: boolean }>()
const failed = ref(false)
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
    <span v-else class="song-cover-placeholder" role="img" aria-label="暂无歌曲封面"><span class="record-grooves"><span>{{ songTitle(song).slice(0, 1) }}</span></span></span>
  </span>
</template>
<style scoped>
.song-cover {
  display: block;
  flex-shrink: 0;
  width: 48px;
  height: 48px;
  overflow: hidden;
  border-radius: 8px;
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
  background: var(--sv-surface-3);
  position: relative;
  overflow: hidden
}

.record-grooves {
  width: 83%;
  aspect-ratio: 1;
  display: grid;
  place-items: center;
  border-radius: 50%;
  background: var(--sv-record-groove), var(--sv-surface-3);
  transform: translate(15%, 9%);
  opacity: 1
}

.record-grooves>span {
  width: 35%;
  aspect-ratio: 1;
  display: grid;
  place-items: center;
  border-radius: 50%;
  background: var(--sv-surface-3);
  color: var(--sv-text-tertiary);
  font: 500 clamp(12px, 2vw, 20px) var(--sv-font-editorial);
  border: 1px solid var(--sv-border-default)
}
</style>

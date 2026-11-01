<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElIcon } from 'element-plus'
import { Headset } from '@element-plus/icons-vue'
import type { MediaSong } from '../../media-library/types'
import { songTitle } from '../../metadata/utils'
const props = defineProps<{ song: MediaSong }>()
const failed = ref(false)
const artwork = computed(() => {
  const metadata = props.song.metadata
  return metadata && !metadata.stale && metadata.fields.includes('cover') && metadata.track.coverUrl
    ? `/api/v1/metadata/songs/${encodeURIComponent(props.song.id)}/cover` : ''
})
watch(() => [props.song.id, props.song.metadata, artwork.value], () => { failed.value = false })
</script>
<template>
  <span class="song-cover">
    <img v-if="artwork && !failed" :src="artwork" :alt="`${songTitle(song)}的封面`" loading="lazy" decoding="async" @error="failed = true">
    <span v-else class="song-cover-placeholder" role="img" aria-label="暂无歌曲封面"><ElIcon><Headset /></ElIcon></span>
  </span>
</template>
<style scoped>
.song-cover{display:block;flex-shrink:0;width:42px;height:42px;overflow:hidden;border-radius:7px;background:var(--sv-surface-3);color:var(--sv-accent-primary)}.song-cover img{display:block;width:100%;height:100%;object-fit:cover}.song-cover-placeholder{display:grid;place-items:center;width:100%;height:100%;font-size:21px}
</style>

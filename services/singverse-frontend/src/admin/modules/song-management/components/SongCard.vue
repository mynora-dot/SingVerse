<script setup lang="ts">
import { ElButton } from 'element-plus'
import type { MediaSong } from '../../media-library/types'
defineProps<{ song: MediaSong; selectionMode: boolean; selected: boolean }>()
const emit = defineEmits<{ click: []; play: []; edit: []; select: [song: MediaSong, selected: boolean] }>()
</script>

<template>
  <article class="song-card" @click="emit('click')">
    <label v-if="selectionMode" class="selection-checkbox" @click.stop>
      <input type="checkbox" :checked="selected" :aria-label="`选择 ${song.result.title || song.relativePath}`" @change="emit('select', song, ($event.target as HTMLInputElement).checked)">
    </label>
    <button class="song-card-title" @click.stop="emit('click')">
      <strong>{{ song.result.title || song.relativePath }}</strong>
      <span>{{ song.result.artists.join('、') || '未知歌手' }}</span>
    </button>
    <span>{{ song.result.needsReview ? '待复核' : '完整' }}</span>
    <div class="song-card-actions" @click.stop>
      <ElButton @click="emit('play')">播放</ElButton>
      <ElButton @click="emit('edit')">{{ song.result.needsReview ? '复核' : '编辑元数据' }}</ElButton>
    </div>
  </article>
</template>

<style scoped>
.song-card { display: flex; flex-wrap: wrap; align-items: center; gap: var(--sv-space-3, 12px); padding: var(--sv-space-4, 16px); border: 1px solid var(--el-border-color); border-radius: var(--el-border-radius-base); }
.song-card-title { display: grid; gap: 4px; flex: 1; min-width: 0; text-align: left; color: inherit; background: transparent; border: 0; cursor: pointer; overflow-wrap: anywhere; }
.song-card-title span { color: var(--el-text-color-secondary); }
.song-card-actions { display: flex; }
</style>

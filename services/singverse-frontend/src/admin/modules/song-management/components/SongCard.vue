<script setup lang="ts">
import { ElButton, ElIcon } from 'element-plus'
import { VideoPlay, EditPen } from '@element-plus/icons-vue'
import SongCover from './SongCover.vue'
import { needsSongReview, songReviewLabel, platformDuration, songTags } from '../presentation'
import { songTitle, songArtists } from '../../metadata/utils'
import type { Tag } from '../../classification/types'
import type { MediaSong } from '../../media-library/types'
withDefaults(defineProps<{ song: MediaSong; tags?: Tag[]; selectionMode?: boolean; selected?: boolean }>(), { tags: () => [], selectionMode: false, selected: false })
const emit = defineEmits<{ click: []; play: []; edit: []; select: [song: MediaSong, selected: boolean] }>()
</script>
<template>
  <article class="song-card" :class="{ selected }">
    <div class="card-artwork">
      <button class="artwork-open" :aria-label="`查看 ${songTitle(song)} 的详情`" @click="emit('click')"><SongCover :song="song" /></button>
      <label v-if="selectionMode" class="selection-checkbox"><input type="checkbox" :checked="selected" :aria-label="`选择 ${songTitle(song)}`" @change="emit('select', song, ($event.target as HTMLInputElement).checked)"></label>
      <span v-if="platformDuration(song)" class="cover-duration">{{ platformDuration(song) }}</span>
      <button class="cover-play" :aria-label="`播放 ${songTitle(song)}`" @click="emit('play')"><ElIcon><VideoPlay /></ElIcon></button>
    </div>
    <button class="song-card-title" @click="emit('click')"><strong :title="songTitle(song)">{{ songTitle(song) }}</strong><span :title="songArtists(song).join('、')">{{ songArtists(song).join('、') || '歌手待补充' }}</span></button>
    <div class="song-card-context"><span v-if="song.metadata?.stale" class="song-status review">信息已过期 · 需重审</span><span v-else class="song-status" :class="{ review: needsSongReview(song) }">{{ songReviewLabel(song) }}</span><span v-if="songTags(song, tags)[0]" class="card-tag">{{ songTags(song, tags)[0]?.name }}</span></div>
    <div class="song-card-actions"><span :title="song.sourceName">{{ song.sourceName }}</span><ElButton text :icon="EditPen" :aria-label="`${needsSongReview(song) ? '复核' : '编辑'} ${songTitle(song)}`" @click="emit('edit')">{{ needsSongReview(song) ? '复核' : '编辑' }}</ElButton></div>
  </article>
</template>
<style scoped>
.song-card {
  min-width: 0;
  position: relative;
  padding: 12px;
  background: var(--sv-surface-2);
  border: 1px solid var(--sv-border-subtle);
  border-radius: 13px;
  transition: border-color .18s, box-shadow .18s
}

.song-card:hover {
  border-color: var(--sv-border-default);
  box-shadow: var(--sv-shadow-md)
}

.song-card.selected {
  border-color: var(--sv-accent-primary);
  background: var(--sv-accent-soft)
}

.card-artwork {
  position: relative;
  aspect-ratio: 1;
  isolation: isolate
}

.artwork-open {
  width: 100%;
  height: 100%;
  padding: 0;
  border: 0;
  background: none;
  display: block;
  border-radius: 8px;
  cursor: pointer;
  overflow: hidden
}

.artwork-open :deep(.song-cover) {
  width: 100%;
  height: 100%;
  border-radius: 0
}

.artwork-open :deep(.song-cover-placeholder) {
  font-size: 40px
}

.cover-play {
  position: absolute;
  right: 10px;
  bottom: 10px;
  display: grid;
  place-items: center;
  width: 38px;
  height: 38px;
  border: 1px solid #ffffff75;
  border-radius: 50%;
  background: #fff;
  color: var(--sv-accent-primary);
  box-shadow: 0 3px 12px rgb(36 31 23 / 18%);
  cursor: pointer;
  font-size: 22px
}

.cover-play:hover {
  background: var(--sv-accent-primary);
  color: white
}

.selection-checkbox {
  position: absolute;
  left: 10px;
  top: 10px;
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  border-radius: 7px;
  background: white;
  box-shadow: var(--sv-shadow-sm)
}

.selection-checkbox input {
  width: 18px;
  height: 18px;
  margin: 0;
  accent-color: var(--sv-accent-primary);
  cursor: pointer
}

.cover-duration {
  position: absolute;
  left: 10px;
  bottom: 12px;
  padding: 1px 5px;
  background: rgb(32 26 18 / 72%);
  color: white;
  border-radius: 4px;
  font-size: 10px
}

.song-card-title {
  display: grid;
  gap: 5px;
  min-width: 0;
  width: 100%;
  text-align: left;
  background: transparent;
  color: inherit;
  border: 0;
  padding: 15px 0 10px;
  cursor: pointer
}

.song-card-title strong {
  font-size: 15px;
  font-weight: 600;
  line-height: 1.6;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis
}

.song-card-title span {
  font-size: 12px;
  color: var(--sv-text-secondary);
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis
}

.song-card-context {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 6px;
  font-size: 10px;
  min-height: 23px
}

.song-status {
  color: var(--sv-text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis
}

.song-status.review {
  color: var(--sv-semantic-warn)
}

.card-tag {
  max-width: 75px;
  text-overflow: ellipsis;
  overflow: hidden;
  white-space: nowrap;
  padding: 1px 6px;
  border-radius: 4px;
  background: var(--sv-surface-3);
  color: var(--sv-text-secondary)
}

.song-card-actions {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 6px;
  margin-top: 9px;
  padding-top: 8px;
  border-top: 1px solid var(--sv-border-subtle)
}

.song-card-actions>span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 10px;
  color: var(--sv-text-tertiary)
}

.song-card-actions :deep(.el-button) {
  margin: 0;
  padding: 4px 0;
  min-height: 28px;
  height: auto;
  font-size: 11px
}

@media(max-width:480px) {
  .song-card {
    padding: 8px;
    border-radius: 10px
  }

  .song-card-title {
    padding: 10px 0 6px
  }

  .song-card-title strong {
    font-size: 14px
  }

  .song-card-title span {
    font-size: 11px
  }

  .song-card-context {
    display: block
  }

  .card-tag {
    display: none
  }

  .song-status {
    display: block;
    font-size: 11px
  }

  .cover-play {
    width: 44px;
    height: 44px;
    right: 7px;
    bottom: 7px
  }

  .selection-checkbox {
    width: 40px;
    height: 40px
  }

  .song-card-actions>span {
    font-size: 10px
  }

  .song-card-actions {
    margin-top: 5px;
    padding-top: 5px
  }

  .song-card-actions :deep(.el-button) {
    font-size: 11px;
    min-height: 40px;
    min-width: 44px
  }
}
</style>

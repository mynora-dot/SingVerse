<script setup lang="ts">
import { ElButton, ElIcon } from 'element-plus'
import { Headset, VideoPlay, EditPen } from '@element-plus/icons-vue'
import { needsSongReview, songReviewLabel } from '../presentation'
import { songTitle, songArtists } from '../../metadata/utils'
import type { Tag } from '../../classification/types'
import { fileSize, fileFormat, songTags, confirmedField } from '../presentation'
import type { MediaSong } from '../../media-library/types'
withDefaults(defineProps<{ song: MediaSong; tags?: Tag[]; selectionMode: boolean; selected: boolean }>(), { tags: () => [] })
const emit = defineEmits<{ click: []; play: []; edit: []; select: [song: MediaSong, selected: boolean] }>()
</script>
<template>
  <article class="song-card" :class="{ selected }" @click="emit('click')">
    <div class="song-identity">
      <label v-if="selectionMode" class="selection-checkbox" @click.stop><input type="checkbox" :checked="selected" :aria-label="`选择 ${songTitle(song)}`" @change="emit('select', song, ($event.target as HTMLInputElement).checked)"></label>
      <span class="song-art" aria-hidden="true"><ElIcon><Headset /></ElIcon></span>
      <button class="song-card-title" @click.stop="emit('click')"><strong>{{ songTitle(song) }}</strong><span>{{ songArtists(song).join('、') || '未知歌手' }}</span></button>
    </div>
    <div class="mobile-song-details"><span>{{ fileFormat(song) }} · {{ fileSize(song.fileSize) }}</span><span v-if="confirmedField(song, 'album')">{{ confirmedField(song, 'album') }}</span><div v-if="songTags(song, tags).length" class="mobile-tags"><span v-for="tag in songTags(song, tags)" :key="tag.id">{{ tag.name }}</span></div><span v-else>尚未分类</span></div>
    <div class="song-source"><strong>{{ song.sourceName }}</strong><span :title="song.relativePath">{{ song.relativePath }}</span></div>
    <div class="song-status-group"><span class="song-status" :class="{ review: needsSongReview(song) }">{{ songReviewLabel(song) }}</span><span class="mobile-metadata-status">{{ song.metadata?.stale ? '元数据需重审' : song.metadata?.fields.length ? `已确认 ${song.metadata.fields.length} 项信息` : '元数据未确认' }}</span></div>
    <div class="song-card-actions" @click.stop><ElButton :icon="VideoPlay" :aria-label="`播放 ${songTitle(song)}`" @click="emit('play')">播放</ElButton><ElButton text :icon="EditPen" @click="emit('edit')">{{ needsSongReview(song) ? '复核' : '编辑' }}</ElButton></div>
  </article>
</template>
<style scoped>
.song-status-group{display:grid;gap:5px;text-align:right}.mobile-metadata-status{font-size:10px;color:var(--sv-text-secondary)}.mobile-song-details{grid-column:1/-1;display:grid;gap:6px;font-size:12px;color:var(--sv-text-secondary)}.mobile-tags{display:flex;flex-wrap:wrap;gap:5px}.mobile-tags span{padding:2px 6px;border-radius:4px;background:var(--sv-accent-soft);color:var(--sv-accent-primary)}
.song-card { display: grid; grid-template-columns: minmax(0, 1fr) 20% 90px 180px; gap: 16px; align-items: center; padding: 18px 20px; border-bottom: 1px solid var(--sv-border-subtle); background: var(--sv-surface-2); transition: background 160ms; cursor: pointer; }.song-card:last-child { border-bottom: 0; }.song-card:hover,.song-card.selected { background: var(--sv-accent-soft); }.song-identity { display: flex; align-items: center; gap: 14px; min-width: 0; }.song-art { display: grid; place-items: center; flex-shrink: 0; width: 42px; height: 42px; border-radius: 10px; background: var(--sv-surface-3); color: var(--sv-accent-primary); font-size: 21px; }.song-card-title { display: grid; gap: 4px; min-width: 0; padding: 0; text-align: left; font: inherit; color: inherit; background: transparent; border: 0; cursor: pointer; }.song-card-title strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 14px; font-weight: 600; }.song-card-title span,.song-source span { color: var(--sv-text-secondary); font-size: 12px; }.song-source { display: grid; gap: 4px; min-width: 0; }.song-source strong { font-size: 12px; font-weight: 500; }.song-source span { overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }.song-status { font-size: 11px; color: var(--sv-semantic-ok); }.song-status.review { color: var(--sv-semantic-warn); }.song-card-actions { display: flex; align-items: center; justify-content: flex-end; }.selection-checkbox input { accent-color: var(--sv-accent-primary); width: 16px; height: 16px; }
@media(max-width:768px) { .song-card { grid-template-columns: minmax(0,1fr) auto; gap: 14px; padding: 16px; border: 1px solid var(--sv-border-subtle); border-radius: var(--sv-radius-md); }.song-identity { grid-column: 1 / -1; }.song-source { grid-column: 1; grid-row: auto; }.song-status { grid-row: auto; }.song-card-actions { grid-column: 1 / -1; padding-top: 12px; border-top: 1px solid var(--sv-border-subtle); }.song-card-title strong { white-space: normal; overflow-wrap: anywhere; }.song-art { width: 44px; height: 44px; } }
</style>

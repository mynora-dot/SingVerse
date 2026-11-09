<script setup lang="ts">
import { computed } from 'vue'
import { ElButton, ElCheckbox, ElTag } from 'element-plus'
import { duration, platforms, type Track } from './api'
const props = defineProps<{ tracks: Track[]; selected: string[]; disabled?: boolean }>()
const emit = defineEmits<{ 'update:selected': [value: string[]]; download: [tracks: Track[]] }>()
const available = computed(() => props.tracks.filter(t => !t.downloading && !t.inLibrary))
const all = computed(() => available.value.length > 0 && available.value.every(t => props.selected.includes(t.id)))
function toggle(id: string, checked: boolean) { emit('update:selected', checked ? [...new Set([...props.selected, id])] : props.selected.filter(v => v !== id)) }
function toggleAll(checked: boolean) { const ids = available.value.map(t => t.id); emit('update:selected', checked ? [...new Set([...props.selected, ...ids])] : props.selected.filter(id => !ids.includes(id))) }
</script>
<template>
  <div class="picker">
    <div class="selection"><ElCheckbox :model-value="all" :indeterminate="!all && selected.some(id => available.some(t => t.id === id))" :disabled="disabled || !available.length" @change="value => toggleAll(!!value)">选择已读取歌曲</ElCheckbox><span aria-live="polite">已选 {{ selected.length }} 首</span><ElButton type="primary" :disabled="disabled || !selected.length" @click="emit('download', tracks.filter(t => selected.includes(t.id)))">下载所选</ElButton></div>
    <ul class="track-list" aria-label="歌曲列表">
      <li v-for="track in tracks" :key="track.id" class="track">
        <ElCheckbox :model-value="selected.includes(track.id)" :disabled="disabled || track.downloading || track.inLibrary" :aria-label="'选择 ' + track.title" @change="value => toggle(track.id, !!value)" />
        <div class="identity"><strong>{{ track.title }}</strong><span>{{ track.artists.join(' / ') }}</span></div>
        <span class="album">{{ track.album || '专辑未知' }}</span><span class="duration">{{ duration(track.durationMs) }}</span><ElTag size="small" effect="plain">{{ platforms[track.platform] }}</ElTag>
        <span v-if="track.downloading" class="state" role="status">下载中</span><a v-else-if="track.inLibrary && track.songId" :href="'#/library/songs?detailSongId=' + encodeURIComponent(track.songId)" class="state">已在曲库 ↗</a><ElButton v-else :disabled="disabled" @click="emit('download', [track])">下载</ElButton>
      </li>
    </ul>
  </div>
</template>
<style scoped>
.selection{display:flex;align-items:center;gap:16px;flex-wrap:wrap;padding:12px 0;border-bottom:1px solid var(--el-border-color-lighter)}.selection>span{font-size:13px;color:var(--el-text-color-secondary)}.selection>.el-button{margin-left:auto}.track-list{list-style:none;margin:0;padding:0}.track{display:grid;grid-template-columns:24px minmax(180px,2fr) minmax(100px,1fr) 80px 100px 96px;align-items:center;gap:16px;padding:16px 0;border-bottom:1px solid var(--el-border-color-lighter)}.identity{display:flex;flex-direction:column;gap:6px;min-width:0}.identity strong,.identity span,.album{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.identity span,.album,.duration{font-size:13px;color:var(--el-text-color-secondary)}.state{font-size:13px;color:var(--el-color-primary)}a:focus-visible{outline:2px solid var(--el-color-primary);outline-offset:4px}@media(max-width:850px){.track{grid-template-columns:24px minmax(0,1fr) auto;gap:10px;padding:16px 4px}.identity{grid-column:2}.album{grid-column:2;grid-row:2}.duration{grid-column:2;grid-row:3}.el-tag{grid-column:3;grid-row:2}.state,.track>.el-button{grid-column:3;grid-row:1}.track>.el-checkbox{grid-row:1/4;align-self:start}.identity strong{white-space:normal}.track>.el-button{margin:0}}
</style>

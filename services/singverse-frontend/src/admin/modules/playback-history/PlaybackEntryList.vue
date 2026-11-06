<script setup lang="ts">
import { ElButton, ElTag } from 'element-plus'
import type { PlaybackEntry } from './api'
import { clientNames, date, duration, statusNames } from './presentation'
defineProps<{ items: PlaybackEntry[]; showHistory?: boolean }>()
defineEmits<{ history: [song: { songId: string; title: string }] }>()
</script>

<template>
  <div class="playback-entry-list">
    <article v-for="entry in items" :key="entry.id" class="playback-entry">
      <div class="entry-song"><h3>{{ entry.title || '未命名歌曲' }}</h3><p>{{ entry.artists.join(' / ') || '未知歌手' }}</p><small>{{ [entry.versionLabel, entry.sourceName].filter(Boolean).join(' · ') || '未记录版本与来源' }}</small></div>
      <div class="entry-time"><time :datetime="entry.startedAt">{{ date(entry.startedAt) }}</time><span>实际播放 {{ duration(entry.playedMs) }}</span><small v-if="entry.endedAt">结束于 {{ date(entry.endedAt) }}</small></div>
      <div class="entry-device"><span>{{ entry.deviceName || clientNames[entry.clientType] }}</span><small>点歌人：{{ entry.requestedBy || '未记录' }}</small></div>
      <div class="entry-result"><ElTag :type="entry.status === 'ERROR' ? 'danger' : entry.status === 'COMPLETED' ? 'success' : entry.status === 'PLAYING' ? 'primary' : 'info'" size="small">{{ statusNames[entry.status] || entry.status }}</ElTag><small>{{ entry.effective ? '计入热门排行' : '未计入热门排行' }}</small><ElButton v-if="showHistory" text type="primary" :aria-label="`查看${entry.title}的全部播放历史`" @click="$emit('history', entry)">单曲历史</ElButton></div>
    </article>
  </div>
</template>

<style scoped>
.playback-entry{display:grid;grid-template-columns:minmax(160px,1.3fr) minmax(160px,1.2fr) minmax(120px,1fr) 115px;gap:16px;padding:17px 20px;border-bottom:1px solid var(--sv-border-subtle);align-items:center}.playback-entry:last-child{border-bottom:0}.entry-song,.entry-time,.entry-device,.entry-result{min-width:0;display:grid;gap:4px;overflow-wrap:anywhere}.entry-song h3{font-size:14px;font-weight:600;margin:0}.entry-song p,.entry-time,.entry-device{font-size:12px}.playback-entry small{font-size:12px;color:var(--sv-text-secondary)}.entry-song p{margin:0;color:var(--sv-text-secondary)}.entry-result{justify-items:start}.entry-result .el-button{padding:0;height:auto;margin:0}.entry-time{font-variant-numeric:tabular-nums}.entry-time span{color:var(--sv-text-secondary)}
@media(max-width:1000px){.playback-entry{grid-template-columns:minmax(150px,1fr) minmax(150px,1fr)}.entry-result{justify-items:start;display:flex;flex-wrap:wrap;align-items:center;gap:8px}}@media(max-width:580px){.playback-entry{grid-template-columns:minmax(0,1fr);gap:10px;padding:16px}.entry-device{display:flex;gap:12px;flex-wrap:wrap}}
</style>

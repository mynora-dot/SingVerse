<script setup lang="ts">
import { ElButton, ElDrawer } from 'element-plus'
import { needsSongReview, songReviewLabel } from '../presentation'
import { songTitle, songArtists } from '../../metadata/utils'
import type { Tag } from '../../classification/types'
import { confirmedField, fileFormat, fileSize, platformDuration, displayTime, songTags } from '../presentation'
import type { MediaSong } from '../../media-library/types'
withDefaults(defineProps<{ song: MediaSong | null; visible: boolean; tags?: Tag[] }>(), { tags: () => [] })
const emit = defineEmits<{ close: []; play: []; edit: []; classify: [] }>()

</script>

<template>
  <ElDrawer :model-value="visible" title="歌曲详情" size="min(560px, 100vw)" :append-to-body="false" @update:model-value="value => { if (!value) emit('close') }">
    <template v-if="song">
      <div class="detail-song-heading"><span class="detail-eyebrow">SONG DETAILS</span><h2>{{ songTitle(song) }}</h2><p>{{ songArtists(song).join('、') || '未知歌手' }}</p></div><h3 class="detail-section-title">文件与来源</h3>
      <dl>

        <dt>来源</dt><dd>{{ song.sourceName }}</dd>
        <dt>文件路径</dt><dd>{{ song.relativePath }}</dd>
        <dt>文件大小</dt><dd>{{ fileFormat(song) }} · {{ fileSize(song.fileSize) }}</dd>
        <dt>修改时间</dt><dd>{{ displayTime(song.modifiedAt) }}</dd>
        <dt>扫描时间</dt><dd>{{ displayTime(song.scannedAt) }}</dd>
        <dt>整理状态</dt><dd>{{ songReviewLabel(song) }}</dd><dt>原始解析</dt><dd>{{ song.result.needsReview ? '文件名待复核（保留解析记录）' : '文件名完整' }}</dd>
      </dl><h3 class="detail-section-title">已确认元数据</h3><p class="detail-note">{{ song.metadata ? (song.metadata.stale ? '已有元数据已过期，请重新审核。' : (Object.values(song.metadata.origins).some(origin => origin.kind === 'FILE') ? '优先使用文件内嵌标签，缺失字段使用其他已确认信息或文件名。' : '已保存平台或人工确认的信息。')) : '尚未确认元数据，可通过编辑补充歌曲信息。' }}</p>
      <dl v-if="song.metadata && !song.metadata.stale"><dt>专辑</dt><dd>{{ confirmedField(song, 'album') || '尚未补充' }}</dd><dt>发行日期</dt><dd>{{ confirmedField(song, 'releaseDate') || '尚未补充' }}</dd><dt>平台时长</dt><dd>{{ platformDuration(song) || '尚未补充' }}</dd></dl>
      <h3 class="detail-section-title">歌曲分类</h3><dl v-if="songTags(song, tags).length"><template v-for="tag in songTags(song, tags)" :key="tag.id"><dt>{{ tag.dimension }}</dt><dd>{{ tag.name }}{{ tag.locked ? ' · 已锁定' : '' }}{{ !tag.active ? ' · 未启用或未加载' : '' }}</dd></template></dl><p v-else class="detail-note">尚未分类，可手动补充或使用 AI 分类。</p>
      <div class="detail-actions">
        <ElButton @click="emit('play')">播放</ElButton>
        <ElButton @click="emit('edit')">{{ needsSongReview(song) ? '复核' : '编辑元数据' }}</ElButton>
      <ElButton @click="emit('classify')">编辑分类</ElButton></div>
    </template>
  </ElDrawer>
</template>

<style scoped>
dd { margin: 4px 0 16px; overflow-wrap: anywhere; }
.detail-song-heading { padding: 8px 0 24px; }.detail-eyebrow { font-size: 10px; letter-spacing: 2px; color: var(--sv-accent-primary); }.detail-song-heading h2 { margin: 12px 0 8px; overflow-wrap: anywhere; }.detail-song-heading p,.detail-note { color: var(--sv-text-secondary); font-size: 13px; }.detail-section-title { font-size: 13px; padding: 16px 0; border-top: 1px solid var(--sv-border-subtle); }dl { display: grid; grid-template-columns: 80px minmax(0,1fr); gap: 12px; }dd { margin: 0; font-size: 13px; }dt { font-size: 12px; }.detail-actions { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 28px; }.detail-actions :deep(.el-button) { margin: 0; }dt { color: var(--el-text-color-secondary); }
</style>

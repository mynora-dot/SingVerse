<script setup lang="ts">
import { ElButton, ElDrawer } from 'element-plus'
import type { MediaSong } from '../../media-library/types'
defineProps<{ song: MediaSong | null; visible: boolean }>()
const emit = defineEmits<{ close: []; play: []; edit: [] }>()
function size(value: number | null) { return value === null ? '远端未提供' : `${(value / 1024 / 1024).toFixed(2)} MB` }
</script>

<template>
  <ElDrawer :model-value="visible" title="歌曲详情" size="min(560px, 100vw)" :append-to-body="false" @update:model-value="value => { if (!value) emit('close') }">
    <template v-if="song">
      <h2>{{ song.result.title || song.relativePath }}</h2>
      <dl>
        <dt>歌手</dt><dd>{{ song.result.artists.join('、') || '未知歌手' }}</dd>
        <dt>来源</dt><dd>{{ song.sourceName }}</dd>
        <dt>文件路径</dt><dd>{{ song.relativePath }}</dd>
        <dt>文件大小</dt><dd>{{ size(song.fileSize) }}</dd>
        <dt>修改时间</dt><dd>{{ song.modifiedAt || '远端未提供' }}</dd>
        <dt>扫描时间</dt><dd>{{ song.scannedAt }}</dd>
        <dt>解析状态</dt><dd>{{ song.result.needsReview ? '待复核' : '完整' }}</dd>
      </dl>
      <div class="detail-actions">
        <ElButton @click="emit('play')">播放</ElButton>
        <ElButton @click="emit('edit')">{{ song.result.needsReview ? '复核' : '编辑元数据' }}</ElButton>
      </div>
    </template>
  </ElDrawer>
</template>

<style scoped>
dd { margin: 4px 0 16px; overflow-wrap: anywhere; }
dt { color: var(--el-text-color-secondary); }
</style>

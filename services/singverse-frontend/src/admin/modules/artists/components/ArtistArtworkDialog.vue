<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElAlert, ElButton, ElDialog } from 'element-plus'
import { requestId } from '../../../../shared/request-id'
import { artists } from '../api/artists'
import { artworkPlatforms, type ArtistFilter, type ArtworkBatch, type ArtworkPlatform } from '../types'

const props = defineProps<{ open: boolean; selectedIds: string[]; filter: ArtistFilter; total: number; singleName?: string }>()
const emit = defineEmits<{ close: []; created: [batch: ArtworkBatch] }>()
const provider = ref<ArtworkPlatform>('AUTO'), scope = ref<'SELECTED' | 'FILTER'>('SELECTED')
const enabled = ref<ArtworkPlatform[]>([]), loading = ref(false), busy = ref(false), error = ref('')
const amount = computed(() => scope.value === 'SELECTED' ? props.selectedIds.length : props.total)
const descriptions: Record<ArtworkPlatform, string> = {
  AUTO: '依次尝试已启用的音乐平台、酷我和汽水，再由 TheAudioDB 补充。',
  NETEASE: '只从网易云音乐查找，通过曲库歌曲核对歌手身份。',
  QQ: '只从 QQ 音乐查找，通过曲库歌曲核对歌手身份。',
  KUGOU: '只从酷狗音乐查找，通过歌曲与歌手 ID 核对身份。',
  KUWO: '只从酷我音乐查找，未能核对身份时由你确认候选。',
  QISHUI: '只从汽水音乐查找，未能核对身份时由你确认候选。',
  THEAUDIODB: '国际歌手与组合的补充来源，使用 MusicBrainz 核对身份。',
}
let active = true, generation = 0
const requests = new Map<string, string>()
watch(() => props.open, async open => {
  const epoch = ++generation
  if (!open) return
  scope.value = props.selectedIds.length ? 'SELECTED' : 'FILTER'
  error.value = ''; loading.value = true; enabled.value = []
  try {
    const result = await artists.artworkPlatforms()
    if (active && epoch === generation) enabled.value = result.enabled
  } catch (e) { if (active && epoch === generation) error.value = e instanceof Error ? e.message : '平台读取失败，请重新打开面板' }
  finally { if (active && epoch === generation) loading.value = false }
})
async function start() {
  if (busy.value || loading.value || !enabled.value.includes(provider.value) || amount.value < 1 || amount.value > 1000) return
  busy.value = true; error.value = ''
  const input = { artistIds: scope.value === 'SELECTED' ? [...props.selectedIds].sort() : [], allMatching: scope.value === 'FILTER', filter: { ...props.filter }, provider: provider.value }
  const key = JSON.stringify(input)
  if (!requests.has(key)) requests.set(key, requestId())
  try {
    const result = await artists.startArtworkBatch({ ...input, requestId: requests.get(key)! })
    if (active) { requests.delete(key); emit('created', result); emit('close') }
  } catch (e) { if (active) error.value = e instanceof Error ? e.message : '提交失败，请保留当前选择后重试' }
  finally { if (active) busy.value = false }
}
onBeforeUnmount(() => { active = false; generation++ })
</script>

<template>
  <ElDialog class="artist-artwork-dialog" :model-value="open" title="获取歌手封面" width="min(660px, calc(100vw - 24px))" :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy" @close="emit('close')">
    <form class="artwork-form" @submit.prevent="start">
      <fieldset v-if="!singleName"><legend>获取哪些歌手的封面</legend><div class="scope-options">
        <label :class="{ chosen: scope === 'SELECTED' }"><input v-model="scope" type="radio" value="SELECTED" :disabled="busy || !selectedIds.length" name="artwork-scope" /><span><strong>已选 {{ selectedIds.length }} 位歌手</strong><small>包含跨页保留的选择</small></span></label>
        <label :class="{ chosen: scope === 'FILTER' }"><input v-model="scope" type="radio" value="FILTER" :disabled="busy || !total" name="artwork-scope" /><span><strong>全部筛选结果 · {{ total }} 位</strong><small>提交时由服务器确定完整范围</small></span></label>
      </div></fieldset>
      <p v-else class="single-artist">为 <strong>{{ singleName }}</strong> 查找封面</p>
      <fieldset><legend>选择图片平台</legend><p v-if="loading" role="status">正在读取平台设置…</p><div class="platform-options">
        <label v-for="(name, code) in artworkPlatforms" :key="code" :class="{ chosen: provider === code, unavailable: !enabled.includes(code) }">
          <input v-model="provider" type="radio" :value="code" name="artwork-platform" :disabled="busy || !enabled.includes(code)" />
          <span><strong>{{ name }}<em v-if="!loading && !enabled.includes(code)">尚未启用</em></strong><small>{{ descriptions[code] }}</small></span>
        </label>
      </div></fieldset>
      <p class="artwork-help">网易云、QQ、酷狗沿用「设置 → 音乐平台」中的启用设置；酷我、汽水直接查询公开歌手图片。<a href="#/system/music-platforms?returnTo=%2Flibrary%2Fartists" :aria-disabled="busy" @click="busy ? $event.preventDefault() : emit('close')">前往平台设置</a>。已有封面和手动移除设置会保留；找到不确定的候选时，等你确认后才使用。</p>
      <ElAlert v-if="amount > 1000" title="每批最多 1000 位歌手，请缩小筛选范围或分批选择。" type="warning" :closable="false" />
      <ElAlert v-if="error" :title="error" type="error" :closable="false" show-icon />
      <p class="artwork-help">任务提交后在 NAS 后台继续，可以离开此页；结果也会出现在「任务」中。</p>
    </form>
    <template #footer><ElButton :disabled="busy" @click="emit('close')">取消</ElButton><ElButton type="primary" :loading="busy" :disabled="loading || !enabled.includes(provider) || amount < 1 || amount > 1000" @click="start">开始获取 · {{ amount }} 位</ElButton></template>
  </ElDialog>
</template>

<style scoped>
:global(.artist-artwork-dialog) {
  display:flex;
  flex-direction:column;
  max-height:90dvh;
}

:global(.artist-artwork-dialog .el-dialog__header),
:global(.artist-artwork-dialog .el-dialog__footer) {
  flex-shrink:0;
}

:global(.artist-artwork-dialog .el-dialog__body) {
  min-height:0;
  overflow-y:auto;
  overscroll-behavior:contain;
  scrollbar-gutter:stable;
}

:global(.artist-artwork-dialog .el-dialog__footer) {
  padding-top:16px;
  border-top:1px solid var(--sv-border-subtle);
}

.artwork-form {
  display:grid;
  gap:24px;
}

.artwork-form fieldset {
  border:0;
  padding:0;
  margin:0;
  min-width:0;
}

.artwork-form legend {
  font-size:14px;
  font-weight:600;
  margin-bottom:12px;
  color:var(--sv-text-primary);
}

.scope-options,.platform-options {
  display:grid;
  grid-template-columns:repeat(2,minmax(0,1fr));
  gap:12px;
}

.artwork-form label {
  display:flex;
  align-items:flex-start;
  gap:12px;
  border:1px solid var(--sv-border-default);
  border-radius:8px;
  padding:16px 16px;
  cursor:pointer;
}

.artwork-form label.chosen {
  border-color:var(--sv-accent-primary);
  background:var(--sv-accent-soft);
}

.artwork-form label.unavailable {
  background:var(--sv-surface-1);
  color:var(--sv-text-secondary);
}

.artwork-form label input {
  width:17px;
  height:17px;
  margin:2px 0 0;
  flex-shrink:0;
  accent-color:var(--sv-accent-primary);
}

.artwork-form label span {
  display:grid;
  gap:8px;
  min-width:0;
}

.artwork-form strong {
  font-size:13px;
  line-height:1.6;
}

.artwork-form small,.artwork-help {
  font-size:12px;
  line-height:1.8;
  color:var(--sv-text-secondary);
}

.artwork-help {
  margin:0;
}

.artwork-form em {
  font-style:normal;
  font-size:12px;
  font-weight:400;
  display:block;
}

.single-artist {
  margin:0;
  font-size:14px;
  overflow-wrap:anywhere;
}

@media(max-width:540px) {
  .scope-options,.platform-options {
    grid-template-columns:1fr;
  }
  .artwork-form {
    gap:20px;
  }
  .artwork-form label {
    padding:12px;
  }
}
</style>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElButton, ElPagination } from 'element-plus'
import { artists } from '../api/artists'
import { artworkItemStates, artworkPlatforms, type ArtworkBatch } from '../types'

const props = defineProps<{ batchId?: string }>()
const emit = defineEmits<{ changed: []; artist: [id: string] }>()
const batch = ref<ArtworkBatch|null>(null), expanded = ref(false), error = ref(''), loading = ref(false), page = ref(1)
const percentage = computed(() => batch.value?.total ? Math.floor(batch.value.completed / batch.value.total * 100) : 0)
let active = true, generation = 0, timer:ReturnType<typeof setTimeout>|undefined
function schedule() { if (timer) clearTimeout(timer); if (active && batch.value?.status === 'RUNNING') timer = setTimeout(() => void load(),4000) }
async function load(reset=false) {
  if (reset) page.value = 1
  const epoch = ++generation
  loading.value = true
  try {
    const next = props.batchId ? await artists.artworkBatch(props.batchId,page.value-1) : batch.value ? await artists.artworkBatch(batch.value.id,page.value-1) : await artists.latestArtworkBatch()
    if (!active || epoch !== generation) return
    if (next && (next.id !== batch.value?.id || next.updatedAt !== batch.value?.updatedAt)) emit('changed')
    batch.value = next; error.value = ''
  } catch (e) { if (active && epoch === generation) error.value = e instanceof Error ? e.message : '获取进度读取失败' }
  finally { if (active && epoch === generation) { loading.value = false; schedule() } }
}
watch(() => props.batchId,() => { batch.value = null; expanded.value = !!props.batchId; void load(true) })
onMounted(() => { expanded.value = !!props.batchId; void load() })
defineExpose({ refresh: () => load() })
onBeforeUnmount(() => { active = false; generation++; if (timer) clearTimeout(timer) })
</script>

<template>
  <section v-if="batch || error" class="artwork-batch" aria-label="封面获取进度">
    <div v-if="batch" class="batch-summary">
      <span class="batch-icon" aria-hidden="true">↗</span><div class="batch-copy"><strong>{{ batch.status === 'RUNNING' ? '正在为歌手收集封面' : '最近一次封面获取' }}</strong><p>{{ artworkPlatforms[batch.provider] }} · 已处理 {{ batch.completed }} / {{ batch.total }} 位<span v-if="batch.status === 'RUNNING'"> · 离开页面后继续</span></p></div>
      <div class="batch-counts"><span><b>{{ batch.counts.APPLIED || 0 }}</b> 已获取</span><span><b>{{ batch.counts.REVIEW || 0 }}</b> 待确认</span><span><b>{{ batch.counts.FAILED || 0 }}</b> 失败</span></div>
      <ElButton text @click="expanded=!expanded" :aria-expanded="expanded">{{ expanded ? '收起结果' : '查看结果' }}</ElButton>
    </div>
    <progress v-if="batch?.status === 'RUNNING'" :value="batch.completed" :max="batch.total" :aria-label="`封面获取进度 ${percentage}%`" />
    <div v-if="batch && expanded" class="batch-results"><p class="batch-note">{{ batch.counts.NO_MATCH || 0 }} 位未找到匹配 · {{ batch.counts.SKIPPED || 0 }} 位保留原有设置。候选图片需在歌手详情中明确确认。</p><ul><li v-for="item in batch.items" :key="item.id"><button @click="emit('artist',item.artistId)">{{ item.name }}</button><span class="item-state" :class="item.status.toLowerCase()">{{ artworkItemStates[item.status] || item.status }}</span><small>{{ item.message }}</small></li></ul><ElPagination v-if="batch.totalElements > 20" :current-page="page" :page-size="20" :total="batch.totalElements" layout="prev, pager, next" @current-change="v=>{page=v;load()}" /></div>
    <div v-if="error" class="batch-error" role="alert"><span>{{ error }}</span><ElButton :loading="loading" text @click="load()">重试读取进度</ElButton></div>
  </section>
</template>

<style scoped>
.artwork-batch {
  border:1px solid var(--sv-border-subtle);
  background:var(--sv-surface-2);
  border-radius:12px;
  overflow:hidden;
}

.batch-summary {
  display:flex;
  align-items:center;
  gap:16px;
  padding:20px 24px;
}

.batch-icon {
  display:grid;
  place-items:center;
  flex:0 0 40px;
  height:40px;
  border-radius:50%;
  background:var(--sv-accent-soft);
  color:var(--sv-accent-primary);
  font-size:23px;
}

.batch-copy {
  flex:1;
  min-width:0;
}

.batch-copy strong {
  font-size:14px;
}

.batch-copy p,.batch-note {
  font-size:12px;
  color:var(--sv-text-secondary);
  line-height:1.7;
  margin:4px 0 0;
}

.batch-counts {
  display:flex;
  gap:20px;
  font-size:12px;
  color:var(--sv-text-secondary);
}

.batch-counts b {
  display:block;
  font-size:18px;
  font-weight:500;
  color:var(--sv-text-primary);
  margin-bottom:4px;
}

.artwork-batch progress {
  appearance:none;
  display:block;
  width:100%;
  height:3px;
  border:0;
  background:var(--sv-border-subtle);
}

progress::-webkit-progress-bar {
  background:var(--sv-border-subtle);
}

progress::-webkit-progress-value {
  background:var(--sv-accent-primary);
}

progress::-moz-progress-bar {
  background:var(--sv-accent-primary);
}

.batch-results {
  padding:0 24px 20px;
}

.batch-results ul {
  padding:0;
  list-style:none;
  margin:16px 0;
}

.batch-results li {
  display:grid;
  grid-template-columns:minmax(100px,1fr) 100px minmax(160px,2fr);
  gap:16px;
  align-items:center;
  padding:12px 0;
  border-top:1px solid var(--sv-border-subtle);
}

.batch-results li button {
  text-align:left;
  justify-self:start;
  border:0;
  background:none;
  padding:4px 0;
  color:var(--sv-accent-primary);
  font-size:13px;
  cursor:pointer;
  overflow-wrap:anywhere;
}

.batch-results small {
  font-size:12px;
  color:var(--sv-text-secondary);
  line-height:1.6;
}

.item-state {
  font-size:12px;
}

.item-state.failed {
  color:var(--sv-semantic-danger);
}

.item-state.review {
  color:var(--sv-semantic-warn);
}

.item-state.applied {
  color:var(--sv-accent-primary);
}

.batch-error {
  display:flex;
  align-items:center;
  justify-content:space-between;
  gap:12px;
  padding:12px 24px;
  font-size:13px;
  color:var(--sv-semantic-danger);
}

@media(max-width:700px) {
  .batch-summary {
    flex-wrap:wrap;
    padding:16px;
    gap:12px;
  }
  .batch-icon {
    display:none;
  }
  .batch-copy {
    flex-basis:70%;
  }
  .batch-counts {
    order:3;
    width:100%;
    gap:24px;
  }
  .batch-counts b {
    display:inline;
    font-size:14px;
    margin-right:4px;
  }
  .batch-summary>.el-button {
    margin-left:auto;
    padding:8px 0;
  }
  .batch-results {
    padding:0 16px 16px;
  }
  .batch-results li {
    grid-template-columns:minmax(0,1fr) auto;
    gap:4px 12px;
  }
  .batch-results li small {
    grid-column:1/-1;
  }
}

@media(max-width:768px), (pointer:coarse) {
  .batch-results li button {
    min-width:44px;
    min-height:44px;
  }
}
</style>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter, type LocationQueryRaw } from 'vue-router'
import { ElAlert, ElButton, ElEmpty, ElInput, ElOption, ElPagination, ElProgress, ElSelect, ElSkeleton, ElTag } from 'element-plus'
import { ArrowLeft, RefreshRight, Search } from '@element-plus/icons-vue'
import PageHeader from '../../shared/ui/PageHeader.vue'
import { backgroundTasks, type BackgroundTask, type ChildPage, type ItemPage, type TaskPage } from './api'
import { activeTask, date, frameProgress, itemStatusNames, progressText, skipReasons, stageName, statistics, statusNames, tone, typeNames } from './presentation'

const route = useRoute(), router = useRouter()
const taskId = computed(() => typeof route.params.id === 'string' ? route.params.id : '')
const value = (key: string) => typeof route.query[key] === 'string' ? route.query[key] as string : ''
const pageNumber = (key: string) => Math.max(0, Math.min(1000000, Number.parseInt(value(key), 10) || 0))
const filter = computed(() => ({ view: ['HISTORY', 'ALL'].includes(value('view')) ? value('view') : 'ACTIVE',
  type: value('type'), status: value('status'), q: value('q'), page: pageNumber('page') }))
const keyword = ref(filter.value.q)
const result = ref<TaskPage>(), task = ref<BackgroundTask>(), items = ref<ItemPage>(), children = ref<ChildPage>()
const error = ref(''), loading = ref(false), observed = ref('')
const detailStats = computed(() => task.value ? statistics(task.value) : [])
const scanProgress = computed(() => task.value ? progressText({ ...task.value, stage: task.value.operation.stage, progress: task.value.operation.progress }) : '')
const skipped = computed(() => task.value?.statistics.skippedByReason as Record<string, number> | undefined)
const countNames: Record<string, string> = { completed: '完成', queued: '排队', running: '运行', failed: '失败', review: '待审核', uncertain: '识别不确定' }
const parentBatch = computed(() => value('fromBatch') || (!task.value?.isBatch ? task.value?.batches[0]?.id : '') || '')
const timingNames: Record<string, string> = { queue: '等待', download: '读取素材', probe: '探测', decode: '解码', inference: '推理', encode: '编码', total: '合计' }
let active = true, generation = 0, timer: ReturnType<typeof setTimeout> | undefined
function valid(turn: number, path: string) { return active && turn === generation && path === route.fullPath && document.visibilityState !== 'hidden' }
function schedule() {
  clearTimeout(timer)
  if (!active || document.visibilityState === 'hidden') return
  if (error.value || (taskId.value ? task.value && activeTask(task.value.status) : filter.value.view !== 'HISTORY'))
    timer = setTimeout(() => { void refresh() }, error.value ? 5000 : 2000)
}
async function refresh() {
  clearTimeout(timer)
  if (!active || document.visibilityState === 'hidden') return
  const turn = ++generation, path = route.fullPath, id = taskId.value
  loading.value = true
  try {
    if (id) {
      const detail = await backgroundTasks.detail(id)
      if (!valid(turn, path)) return
      const native = detail.detailMode === 'ITEMS'
      const entries = native ? await backgroundTasks.items(id, pageNumber('itemPage'), value('itemStatus'))
        : await backgroundTasks.children(id, pageNumber('itemPage'), value('itemStatus'), value('itemType'))
      if (!valid(turn, path)) return
      task.value = detail; items.value = native ? entries as ItemPage : undefined
      children.value = native ? undefined : entries as ChildPage; observed.value = detail.observedAt
      const last = Math.max(0, Math.ceil(entries.totalElements / 20) - 1)
      if (pageNumber('itemPage') > last) { void change({ itemPage: last ? String(last) : undefined }); return }
    } else {
      const data = await backgroundTasks.list(filter.value)
      if (!valid(turn, path)) return
      result.value = data; observed.value = data.observedAt
      const last = Math.max(0, Math.ceil(data.totalElements / 20) - 1)
      if (filter.value.page > last) { void change({ page: last ? String(last) : undefined }); return }
    }
    error.value = ''
  } catch (failure) {
    if (valid(turn, path)) error.value = failure instanceof Error ? failure.message : '读取后台任务失败'
  } finally {
    if (valid(turn, path)) { loading.value = false; schedule() }
  }
}
function change(query: Record<string, string | undefined>) { return router.replace({ query: { ...route.query, ...query } }) }
function search() { void change({ q: keyword.value.trim() || undefined, page: undefined }) }
function open(id: string) { void router.push({ path: '/system/tasks/' + encodeURIComponent(id), query: route.query }) }
function openChild(id: string) {
  if (id === taskId.value) return
  void router.push({ path: '/system/tasks/' + encodeURIComponent(id), query: { ...route.query,
    fromBatch: taskId.value, batchPage: value('itemPage') || undefined, batchStatus: value('itemStatus') || undefined,
    batchType: value('itemType') || undefined, itemPage: undefined, itemStatus: undefined, itemType: undefined } })
}
function back() {
  const query: LocationQueryRaw = { ...route.query }, parent = parentBatch.value
  delete query.itemPage; delete query.itemStatus; delete query.itemType
  if (parent) {
    query.itemPage = value('batchPage') || undefined; query.itemStatus = value('batchStatus') || undefined; query.itemType = value('batchType') || undefined
  }
  for (const key of ['fromBatch', 'batchPage', 'batchStatus', 'batchType']) delete query[key]
  void router.push({ path: parent ? '/system/tasks/' + encodeURIComponent(parent) : '/system/tasks', query })
}
function visibility() { generation++; clearTimeout(timer); loading.value = false; if (document.visibilityState !== 'hidden') void refresh() }
watch(() => route.fullPath, (_next, previous) => {
  generation++; clearTimeout(timer); keyword.value = filter.value.q
  if (previous?.split('?')[0] !== route.path) { task.value = undefined; items.value = undefined; children.value = undefined; result.value = undefined; observed.value = '' }
  error.value = ''; void refresh()
})
onMounted(() => { document.addEventListener('visibilitychange', visibility); void refresh() })
onBeforeUnmount(() => { active = false; generation++; clearTimeout(timer); document.removeEventListener('visibilitychange', visibility) })
</script>

<template>
  <div class="task-center">
    <PageHeader :title="taskId ? task?.isBatch ? '批次详情' : '任务详情' : '后台任务'" :description="taskId ? '查看本次操作的具体任务和进度。' : '每次操作一个批次，运行中的批次优先展示；历史保留 30 天。'">
      <template #actions>
        <ElButton v-if="taskId" :icon="ArrowLeft" @click="back">{{ parentBatch ? '返回批次' : '返回任务列表' }}</ElButton>
        <ElButton :icon="RefreshRight" :loading="loading" @click="refresh">刷新</ElButton>
      </template>
    </PageHeader>
    <ElAlert v-if="error" :title="error" type="error" show-icon :closable="false" class="task-error" role="alert">
      <span v-if="task || result">仍显示上次成功读取的结果。</span><ElButton text @click="refresh">重试</ElButton>
    </ElAlert>

    <template v-if="!taskId">
      <div class="task-tabs" role="group" aria-label="任务记录范围">
        <ElButton v-for="(label, view) in { ACTIVE: '未完成', HISTORY: '历史记录', ALL: '全部' }" :key="view"
          :type="filter.view === view ? 'primary' : 'default'" :aria-pressed="filter.view === view"
          @click="change({ view, page: undefined, status: undefined })">{{ label }}</ElButton>
      </div>
      <form class="task-filters" @submit.prevent="search">
        <ElInput v-model="keyword" aria-label="搜索任务名称或编号" placeholder="搜索任务名称、歌曲或编号" clearable :prefix-icon="Search" maxlength="200" @clear="search" />
        <ElSelect :model-value="filter.type" aria-label="任务类型" @change="change({ type: $event || undefined, page: undefined })">
          <ElOption label="全部类型" value="" /><ElOption v-for="(name, type) in typeNames" :key="type" :label="name" :value="type" />
        </ElSelect>
        <ElSelect :model-value="filter.status" aria-label="任务状态" @change="change({ status: $event || undefined, page: undefined })">
          <ElOption label="全部状态" value="" /><ElOption v-for="(name, status) in statusNames" :key="status" :label="name" :value="status" />
        </ElSelect>
        <ElButton native-type="submit">搜索</ElButton>
      </form>
      <ElSkeleton v-if="loading && !result" :rows="5" animated />
      <div v-else-if="result?.items.length" class="task-table-wrap">
        <table class="task-table">
          <thead><tr><th>批次 / 处理范围</th><th>类型</th><th>状态</th><th>阶段与进度</th><th>子任务统计</th><th>创建时间</th><th><span class="sr-only">操作</span></th></tr></thead>
          <tbody><tr v-for="entry in result.items" :key="entry.id">
            <td><button class="task-name" @click="open(entry.id)">{{ entry.name }}</button><small>{{ entry.subjectName || '全部或选定范围' }}</small><small class="task-number">{{ entry.id }}</small></td>
            <td>{{ typeNames[entry.type] || entry.type }}</td>
            <td><ElTag :type="tone(entry.status)" size="small">{{ statusNames[entry.status] || entry.status }}</ElTag><small v-if="entry.statistics.review">{{ entry.statistics.review }} 首待审核</small><small v-if="entry.statistics.failed" class="task-danger">{{ entry.statistics.failed }} 项失败</small></td>
            <td class="task-progress"><span>{{ stageName(entry.stage) }}</span><ElProgress v-if="entry.progress.percent !== null" :percentage="Math.floor(entry.progress.percent)" :stroke-width="6" :show-text="false" /><small>{{ progressText(entry) }}</small><small v-if="frameProgress(entry.execution)">{{ frameProgress(entry.execution) }}</small></td>
            <td class="task-counts"><small v-for="(label, key) in countNames" :key="key" :class="{ 'task-danger': key === 'failed' && entry.childrenCounts[key] }">{{ label }} {{ entry.childrenCounts[key] || 0 }}</small></td><td>{{ date(entry.createdAt) }}</td><td><ElButton link type="primary" :aria-label="'查看' + entry.name + '的详情'" @click="open(entry.id)">查看详情</ElButton></td>
          </tr></tbody>
        </table>
      </div>
      <ElEmpty v-else-if="!loading && !error" :description="filter.q || filter.type || filter.status ? '没有符合筛选条件的任务' : filter.view === 'ACTIVE' ? '当前没有未完成的任务' : '暂无任务历史'" />
      <ElPagination v-if="result && result.totalElements > 20" :current-page="filter.page + 1" :page-size="20" :total="result.totalElements" layout="total, prev, pager, next" @current-change="change({ page: $event === 1 ? undefined : String($event - 1) })" />
    </template>

    <template v-else-if="task">
      <section class="task-panel" aria-label="任务概览">
        <div class="task-detail-heading"><div><ElTag :type="tone(task.status)">{{ statusNames[task.status] || task.status }}</ElTag><h2>{{ task.name }}</h2></div><ElButton v-if="task.relatedAvailable" @click="router.push(task.relatedPath)">前往相关页面</ElButton></div>
        <p v-if="task.batchKind === 'LEGACY'" class="task-muted">未记录原始批次：这些历史任务按来源和类型归档，未推断扫描归属。</p>
        <p v-if="!task.relatedAvailable" class="task-muted">关联对象或原业务任务已移除或过期，任务记录仍保留。</p>
        <ElAlert v-if="task.error" :title="task.error" :type="['FAILED', 'INTERRUPTED'].includes(task.status) ? 'error' : 'warning'" show-icon :closable="false" />
        <div class="task-detail-progress" aria-live="polite"><strong>{{ stageName(task.stage) }}</strong><span>{{ progressText(task) }}</span><ElProgress v-if="task.progress.percent !== null" :percentage="Math.floor(task.progress.percent)" :stroke-width="8" /><p v-if="frameProgress(task.execution)">{{ frameProgress(task.execution) }}；此为当前阶段进度。</p></div>
        <div v-if="task.isBatch" class="task-count-summary"><span v-for="(label, key) in countNames" :key="key">{{ label }} <strong>{{ task.childrenCounts[key] || 0 }}</strong></span></div>
        <p v-if="task.waitingReason" class="task-muted">{{ task.waitingReason }}</p>
        <p v-if="task.type.endsWith('SCAN')" class="task-muted">扫描自身：{{ statusNames[task.operation.status] || task.operation.businessStatus }} · {{ stageName(task.operation.stage) }} · {{ scanProgress }}<template v-if="task.childrenCounts.pendingDiscovery">；{{ task.childrenCounts.pendingDiscovery }} 首等待后续阶段判断或创建。</template></p>
        <details class="task-extra"><summary>编号、处理范围与时间</summary><dl class="task-metadata"><div><dt>任务编号</dt><dd class="task-number">{{ task.id }}</dd></div><div><dt>业务编号</dt><dd class="task-number">{{ task.businessId }}</dd></div><div><dt>任务类型</dt><dd>{{ typeNames[task.type] || task.type }}</dd></div><div><dt>处理范围</dt><dd>{{ task.subjectName || '全部或选定范围' }}</dd></div><div><dt>创建时间</dt><dd>{{ date(task.createdAt) }}</dd></div><div><dt>开始时间</dt><dd>{{ date(task.startedAt) }}</dd></div><div><dt>结束时间</dt><dd>{{ date(task.finishedAt) }}</dd></div><div><dt>最近变化</dt><dd>{{ date(task.updatedAt) }}</dd></div></dl></details>
        <p v-if="task.timeIncomplete" class="task-muted">这条历史任务的部分时间未记录，无法还原准确耗时。</p>
      </section>
      <section v-if="task.detailMode === 'ITEMS'" class="task-panel" aria-label="结果明细">
        <div class="task-detail-heading"><h2>任务明细</h2><ElSelect :model-value="value('itemStatus')" aria-label="明细状态" @change="change({ itemStatus: $event || undefined, itemPage: undefined })"><ElOption label="全部结果" value="" /><ElOption v-for="(count, status) in items?.counts" :key="status" :label="(itemStatusNames[status] || status) + '（' + count + '）'" :value="status" /></ElSelect></div>
        <div class="task-table-wrap"><table class="task-table"><thead><tr><th>歌曲 / 条目</th><th>状态</th><th>说明</th></tr></thead><tbody><tr v-for="item in items?.items" :key="item.id"><td>{{ item.title || item.songId || item.id }}</td><td>{{ ['PROBING', 'PROCESSING'].includes(item.status) && item.execution?.jobState === 'QUEUED' ? '排队中' : itemStatusNames[item.status] || item.status }}</td><td>{{ item.message || (item.execution?.stage ? stageName(item.execution.stage) : '—') }}<small v-if="frameProgress(item.execution)">{{ frameProgress(item.execution) }}</small></td></tr></tbody></table></div>
        <ElEmpty v-if="items && !items.items.length" description="暂无符合条件的明细" />
        <ElPagination v-if="items && items.totalElements > 20" :current-page="pageNumber('itemPage') + 1" :page-size="20" :total="items.totalElements" layout="total, prev, pager, next" @current-change="change({ itemPage: $event === 1 ? undefined : String($event - 1) })" />
      </section>
      <section v-else class="task-panel" aria-label="任务明细">
        <div class="task-detail-heading"><h2>任务明细</h2><div class="task-child-filters">
          <ElSelect :model-value="value('itemType')" aria-label="明细类型" @change="change({ itemType: $event || undefined, itemPage: undefined })"><ElOption label="全部类型" value="" /><ElOption v-for="(name, type) in typeNames" :key="type" :label="name" :value="type" /></ElSelect>
          <ElSelect :model-value="value('itemStatus')" aria-label="明细状态" @change="change({ itemStatus: $event || undefined, itemPage: undefined })"><ElOption label="全部状态" value="" /><ElOption v-for="(count, status) in children?.counts" :key="status" :label="(statusNames[status] || status) + '（' + count + '）'" :value="status" /></ElSelect>
        </div></div>
        <div v-if="children?.items.length" class="task-table-wrap"><table class="task-table"><thead><tr><th>具体任务 / 歌曲</th><th>状态</th><th>当前阶段与进度</th><th>说明</th></tr></thead><tbody><tr v-for="child in children.items" :key="child.id">
          <td><button v-if="child.id !== taskId" class="task-name" @click="openChild(child.id)">{{ child.name }}</button><span v-else>{{ child.name }}</span><small>{{ child.subjectName }}</small><small class="task-number">{{ child.businessId }}</small></td>
          <td><ElTag :type="tone(child.status)" size="small">{{ statusNames[child.status] || child.status }}</ElTag><small v-if="child.execution.resultState === 'UNKNOWN'">识别不确定</small></td>
          <td><span>{{ stageName(child.stage) }}</span><small>{{ progressText(child) }}</small><small v-if="frameProgress(child.execution)">{{ frameProgress(child.execution) }}</small></td><td>{{ child.error || '—' }}</td>
        </tr></tbody></table></div>
        <ElEmpty v-else :description="value('itemStatus') || value('itemType') ? '暂无符合条件的任务' : activeTask(task.status) ? '等待扫描登记处理范围或创建后续任务' : '本批次未创建后续任务；扫描不记录逐文件日志'" />
        <ElPagination v-if="children && children.totalElements > 20" :current-page="pageNumber('itemPage') + 1" :page-size="20" :total="children.totalElements" layout="total, prev, pager, next" @current-change="change({ itemPage: $event === 1 ? undefined : String($event - 1) })" />
      </section>

      <section v-if="detailStats.length" class="task-panel" aria-label="处理结果">
        <h2>处理结果</h2><dl class="task-stats"><div v-for="entry in detailStats" :key="entry.key"><dt>{{ entry.label }}</dt><dd>{{ entry.value }}</dd></div></dl>
        <p v-if="task.statistics.review" class="task-muted">待审核的结果尚需人工确认，处理完成不代表已全部保存。</p>
        <p v-if="task.statistics.spaceInsufficient" class="task-danger">仍有空间缺口，请查看保护条件和失败原因。</p>
        <p v-if="task.statistics.targetReached === false" class="task-muted">本次清理未达到目标。</p>
        <ul v-if="skipped" class="task-reasons"><li v-for="(count, reason) in skipped" :key="reason">{{ skipReasons[reason] || reason }}：{{ count }}</li></ul>
        <p v-if="task.statistics.errorDetailsLimited" class="task-muted">失败明细保留业务任务提供的前 20 项，失败总数见上方统计。</p>
      </section>
      <section v-if="task.execution?.backend || task.execution?.fallbackReason || task.execution?.reason || Object.keys(task.execution?.timingsMs || {}).length" class="task-panel" aria-label="执行信息">
        <h2>执行信息</h2><p v-if="task.execution.backend">处理设备：{{ task.execution.backend }}<template v-if="task.execution.modelVersion"> · 模型 {{ task.execution.modelVersion }}</template></p><p v-if="task.execution.fallbackReason">{{ task.execution.fallbackReason }}</p><p v-if="task.execution.reason">{{ task.execution.reason }}</p>
        <p v-for="(duration, key) in task.execution.timingsMs" :key="key">{{ timingNames[key] || key }}：{{ (duration / 1000).toFixed(2) }} 秒</p>
      </section>

    </template>
    <ElSkeleton v-else-if="loading" :rows="6" animated />
    <p v-if="observed" class="task-last-read">最近读取：{{ date(observed) }}<template v-if="error"> · 读取失败，等待重试</template></p>
  </div>
</template>

<style scoped>
.task-count-summary{display:flex;gap:20px;flex-wrap:wrap;margin:16px 0}.task-count-summary strong{margin-left:6px}.task-child-filters{display:flex;gap:10px;max-width:420px}.task-child-filters .el-select{width:180px}.task-counts{min-width:150px!important}.task-counts small{display:inline-block;margin-right:12px}.task-extra summary{cursor:pointer;color:var(--sv-text-secondary);font-size:13px}.task-center{max-width:1400px;margin:0 auto;min-width:0}.task-error{margin-bottom:16px}.task-tabs{display:flex;gap:8px;margin:8px 0 18px}.task-tabs .el-button+.el-button{margin-left:0}.task-filters{display:grid;grid-template-columns:minmax(220px,1fr) 180px 160px auto;gap:12px;margin-bottom:20px}.task-table-wrap{overflow-x:auto;border:1px solid var(--sv-border-subtle);border-radius:var(--sv-radius-lg);background:var(--sv-surface-1)}.task-table{width:100%;border-collapse:collapse;text-align:left;font-size:var(--sv-font-body)}.task-table th{background:var(--sv-surface-2);color:var(--sv-text-secondary);font-size:var(--sv-font-meta);font-weight:500;white-space:nowrap}.task-table th,.task-table td{padding:16px 18px;vertical-align:top;border-bottom:1px solid var(--sv-border-subtle)}.task-table tr:last-child td{border-bottom:0}.task-table td{min-width:100px}.task-table td:first-child{min-width:210px;max-width:360px;overflow-wrap:anywhere}.task-table small{display:block;margin-top:6px;color:var(--sv-text-tertiary);font-size:12px}.task-name{padding:0;border:0;background:none;color:var(--sv-accent-primary);text-align:left;font:inherit;font-weight:600;cursor:pointer}.task-name:hover{text-decoration:underline}.task-number{font-family:ui-monospace,monospace;overflow-wrap:anywhere;font-size:12px}.task-progress{min-width:220px!important}.task-progress .el-progress{margin:8px 0}.task-panel{padding:24px;margin-bottom:20px;border:1px solid var(--sv-border-subtle);border-radius:var(--sv-radius-lg);background:var(--sv-surface-1)}.task-panel h2{font-size:18px;margin:0 0 16px}.task-detail-heading{display:flex;align-items:flex-start;justify-content:space-between;gap:16px;margin-bottom:18px}.task-detail-heading h2{margin:12px 0 0}.task-detail-heading>.el-select{width:190px;flex-shrink:0}.task-detail-progress{display:grid;gap:10px;margin:22px 0}.task-detail-progress p{margin:0;color:var(--sv-text-secondary);font-size:13px}.task-metadata,.task-stats{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:22px;margin:20px 0 0}.task-metadata div,.task-stats div{min-width:0}.task-metadata dt,.task-stats dt{color:var(--sv-text-tertiary);font-size:12px;margin-bottom:7px}.task-metadata dd,.task-stats dd{margin:0;overflow-wrap:anywhere}.task-stats dd{font-size:22px;font-weight:600}.task-muted,.task-last-read,.task-reasons{color:var(--sv-text-secondary);font-size:13px;line-height:1.7}.task-danger{color:var(--sv-semantic-danger)!important}.task-last-read{text-align:right;margin:18px 0}.el-pagination{margin-top:18px;justify-content:flex-end}.sr-only{position:absolute;width:1px;height:1px;overflow:hidden;clip-path:inset(50%)}@media(max-width:800px){.task-filters{grid-template-columns:1fr 1fr}.task-filters>.el-input{grid-column:1/-1}.task-metadata,.task-stats{grid-template-columns:repeat(2,minmax(0,1fr))}.task-panel{padding:18px}.task-detail-heading{flex-wrap:wrap}}@media(max-width:480px){.task-table th,.task-table td{padding:12px}.task-tabs{gap:4px}.task-tabs .el-button{padding:8px 12px}.task-filters{gap:8px}.task-metadata,.task-stats{gap:18px}}
</style>

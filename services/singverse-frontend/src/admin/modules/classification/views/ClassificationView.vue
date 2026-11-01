<script setup lang="ts">
import PageHeader from '../../../shared/ui/PageHeader.vue'
import { computed, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElAlert, ElButton, ElCard, ElCheckbox, ElDialog, ElForm, ElIcon, ElInput, ElMessage, ElMessageBox, ElPagination, ElProgress, ElSelect, ElOption, ElTable, ElTableColumn, ElTag } from 'element-plus'
import { classification } from '../api/classification'
import { CollectionTag, MagicStick, ArrowRight, CircleCheck, Clock, Headset } from '@element-plus/icons-vue'
import ClassificationResultCard from '../components/ClassificationResultCard.vue'
import ClassificationSongPicker from '../components/ClassificationSongPicker.vue'
import TagCoverEditor from '../components/TagCoverEditor.vue'
import ClassificationEditor from '../components/ClassificationEditor.vue'
import { dimensionNames, originNames, statusNames, type Dimension, type Item, type Preview, type Settings, type Start, type Tag, type Task } from '../types'
function toggleList(list: Dimension[], dim: Dimension, checked: boolean) { const at = list.indexOf(dim); if (checked && at < 0) list.push(dim); else if (!checked && at >= 0) list.splice(at, 1) }

const route = useRoute(), router = useRouter()
const tab = ref(route.query.task ? 'history' : 'start'), error = ref(''), busy = ref(false), loading = ref(false), tags = ref<Tag[]>([])
const config = ref<Settings>({ enabled: false, endpoint: '', model: '', hasKey: false, timeoutSeconds: 60, intervalSeconds: 3, revision: 0 }), settingsLoaded = ref(false)
const tasks = ref<Task[]>([]), task = ref<Task | null>(null), taskPage = ref(0), page = ref(1), filter = ref(''), loadedAt = ref('')
const dimensions = ref<Dimension[]>(['LANGUAGE', 'DECADE', 'GENRE', 'MOOD', 'SCENE']), automatic = ref<Dimension[]>([]), auto = ref(false), all = ref(false)
const dimensionHints: Record<string,string> = { LANGUAGE: '国语、粤语、英语…', DECADE: '按已有发行年份归类', GENRE: '流行、摇滚、民谣…', MOOD: '轻快、治愈、伤感…', SCENE: '聚会、通勤、独处…' }
const reviewCount = computed(() => task.value?.counts.REVIEW ?? 0)
const startDisabled = computed(() => busy.value || !settingsLoaded.value || !config.value.enabled || !dimensions.value.length || (!all.value && !selectedIds.value.length) || (auto.value && !automatic.value.some(d => dimensions.value.includes(d))))
function formatDate(value: string) { return new Date(value).toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }) }
function selectFilter(value: string) { filter.value = value; page.value = 1; if (task.value) task.value = { ...task.value, items: [], totalElements: 0 }; void refresh() }
function changeResultPage(value: number) { page.value = value; if (task.value) task.value = { ...task.value, items: [], totalElements: 0 }; void refresh() }
async function showHistory(id?: string) { tab.value = 'history'; reviewing.value = null; task.value = null; await router.replace({ query: { ...route.query, task: id || undefined } }); if (!id) { task.value = null; await refresh() } }
const songPickerOpen = ref(false)
function openSongPicker() { if (!busy.value) songPickerOpen.value = true }
async function chooseSongs(ids: string[]) { await router.replace({ query: { ...route.query, songIds: ids.length ? ids.join(',') : undefined } }); all.value = false; songPickerOpen.value = false }
const selectedIds = computed(() => typeof route.query.songIds === 'string' ? route.query.songIds.split(',').filter(Boolean) : [])
const aiDimensions = (Object.keys(dimensionNames) as Dimension[]).filter(d => d !== 'CUSTOM')
const pendingPreview = ref<Preview | null>(null), pendingStart = ref<Start | null>(null)
const editing = ref<string[]>([]), reviewing = ref<Item | null>(null), reviewDims = ref<Dimension[]>([])
const coverTag = ref<Tag | null>(null)
function coverChanged(tag: Tag) { tags.value = tags.value.map(t => t.id === tag.id ? tag : t); coverTag.value = tag }
const tagEdit = ref<{ id?: string; dimension: Dimension; name: string; aliases: string; active: boolean; revision: number } | null>(null), tagSearch = ref(''), tagDimension = ref('')
const visibleTags = computed(() => tags.value.filter(t => (!tagDimension.value || t.dimension === tagDimension.value) && (!tagSearch.value || [t.name, ...t.aliases].some(n => n.includes(tagSearch.value)))))
const matchingTags = computed(() => tags.value.filter(t => !tagSearch.value || [t.name, ...t.aliases].some(n => n.includes(tagSearch.value))))
function tagCount(dimension: string) { return matchingTags.value.filter(t => !dimension || t.dimension === dimension).length }
const progress = computed(() => task.value?.total ? Math.round(task.value.completed * 100 / task.value.total) : 0)
let active = true, timer: ReturnType<typeof setTimeout> | undefined, generation = 0
const requestIds = new Map<string, string>()
function requestId(key: string) { let value = requestIds.get(key); if (!value) { value = crypto.randomUUID(); requestIds.set(key, value) } return value }
function dimensionLabels(dims: Dimension[]) { return dims.map(d => dimensionNames[d]).join('、') }
function names(ids: string[] = []) { return ids.map(id => tags.value.find(t => t.id === id)?.name ?? id).join('、') || '未知 / 无建议' }
function notifyFailure(e: unknown) { error.value = e instanceof Error ? e.message : '请求失败' }
async function perform(action: () => Promise<void>) { if (busy.value) return; busy.value = true; error.value = ''; try { await action() } catch (e) { notifyFailure(e) } finally { busy.value = false } }
async function readSettings() { const saved = await classification.settings(); if (active) { config.value = saved; settingsLoaded.value = true } }
async function refresh() {
  const token = ++generation; loading.value = true
  try {
    const id = typeof route.query.task === 'string' ? route.query.task : ''
    const [list, current] = await Promise.all([classification.tasks(taskPage.value), id ? classification.task(id, page.value - 1, filter.value) : Promise.resolve(null)])
    if (!active || token !== generation) return
    tasks.value = list; task.value = current; loadedAt.value = new Date().toLocaleTimeString(); error.value = ''
  } catch (e) { if (active && token === generation) notifyFailure(e) } finally { if (active && token === generation) loading.value = false }
}
function poll() { timer = setTimeout(async () => { if (!active) return; if (task.value?.status === 'RUNNING' || task.value?.status === 'PAUSED' && task.value.counts.PROCESSING || tab.value === 'history' && !task.value && tasks.value.some(t => t.status === 'RUNNING')) await refresh(); poll() }, 4000) }
async function preview() {
  if (startDisabled.value) return
  await perform(async () => { const input: Start = { all: all.value, songIds: selectedIds.value, dimensions: [...dimensions.value], autoDimensions: auto.value ? automatic.value.filter(d => dimensions.value.includes(d)) : [] }; pendingPreview.value = await classification.preview(input); pendingStart.value = input })
}
async function start() {
  if (!pendingStart.value) return
  await perform(async () => { const result = await classification.start(pendingStart.value!); pendingStart.value = null; pendingPreview.value = null; page.value = 1; filter.value = ''; await router.replace({ query: { ...route.query, task: result.id } }); await refresh(); tab.value = 'history'; ElMessage.success('已开始整理歌曲，完成后可查看分类建议') })
}
async function control(action: string) {
  if (!task.value) return
  if (action === 'cancel') { try { await ElMessageBox.confirm('停止剩余歌曲的分类，保留已有建议及已应用结果。已经写入的分类不会自动撤销；终止后不能继续此任务。', '终止分类任务', { type: 'warning', confirmButtonText: '终止任务' }) } catch { return } }
  await perform(async () => { await classification.control(task.value!.id, action); await refresh(); ElMessage.success('任务状态已更新') })
}
async function retry(item: Item) {
  if (!task.value) return
  try { await ElMessageBox.confirm('将以当前歌曲信息重新生成，仍使用本任务模型、词表和自动应用规则。已应用及人工确认分类会受保护。词表已变化时请新建任务。', '重新生成分类') } catch { return }
  await perform(async () => { await classification.retry(task.value!.id, item.id); await refresh(); ElMessage.success('已加入重新生成队列') })
}
function openReview(item: Item) { reviewing.value = item; reviewDims.value = (Object.keys(item.suggestion?.dimensions ?? {}) as Dimension[]).filter(d => item.suggestion!.dimensions[d]!.length && !item.current.dimensions[d]?.locked) }
async function applyReview() {
  if (!task.value || !reviewing.value) return
  const item = reviewing.value
  if (reviewDims.value.some(dim => item.current.dimensions[dim]?.confirmed)) { try { await ElMessageBox.confirm('所选维度包含人工确认分类，确认后将替换这些维度。锁定维度不能覆盖。', '确认修改分类', { type: 'warning' }) } catch { return } }
  await perform(async () => { await classification.apply(task.value!.id, item.id, requestId(JSON.stringify([task.value!.id, item.id, item.current.revision, reviewDims.value])), item.current.revision, reviewDims.value); reviewing.value = null; await refresh(); ElMessage.success('所选分类已确认，未选维度保留') })
}
async function saveTag() {
  if (!tagEdit.value) return
  const edit = tagEdit.value
  await perform(async () => { await classification.saveTag({ dimension: edit.dimension, name: edit.name, aliases: edit.aliases.split(/[、,\n]/).map(a => a.trim()).filter(Boolean), active: edit.active, revision: edit.revision }, edit.id); tags.value = await classification.tags(); tagEdit.value = null; ElMessage.success('词表已保存；已有任务采用原词表快照，受影响建议需新建任务') })
}
function openTag(tag?: Tag) { tagEdit.value = tag ? { ...tag, aliases: tag.aliases.join('、') } : { dimension: 'CUSTOM', name: '', aliases: '', active: true, revision: 0 } }
async function changed() { try { tags.value = await classification.tags(); await refresh() } catch (e) { notifyFailure(e) } }
watch(() => route.query.task, id => { page.value = 1; filter.value = ''; reviewing.value = null; task.value = null; if (id) tab.value = 'history'; void refresh() })
async function initialize() { try { await Promise.all([readSettings(), classification.tags().then(v => { if (active) tags.value = v })]); if (active) await refresh() } catch (e) { if (active) notifyFailure(e) } }
onMounted(() => { void initialize(); poll() })
onBeforeUnmount(() => { active = false; generation++; clearTimeout(timer) })
</script>
<template>
  <section class="classification-page management-page">
    <PageHeader class="classification-header" title="歌曲分类" description="按语种、风格、场景等维度给歌曲打标签，电视和点歌页可按分类浏览。"><template #actions><ElButton text @click="router.push('/library/songs')">返回歌曲库 <ElIcon><ArrowRight /></ElIcon></ElButton></template></PageHeader>
    <nav class="classification-tabs" aria-label="歌曲分类分区"><button :aria-current="tab === 'start' ? 'page' : undefined" @click="tab = 'start'">开始分类</button><button :aria-current="tab === 'history' ? 'page' : undefined" @click="tab = 'history'">分类记录</button><button :aria-current="tab === 'tags' ? 'page' : undefined" @click="tab = 'tags'">分类词表</button></nav>
    <ElAlert v-if="error" :title="error" type="error" :closable="false" show-icon><ElButton text :disabled="busy || loading" @click="initialize">重新加载</ElButton></ElAlert>

    <template v-if="tab === 'start'">
      <div v-if="!settingsLoaded" class="setup-banner" role="status">正在检查分类服务…</div>
      <div v-else-if="!config.enabled" class="setup-banner"><ElIcon><MagicStick /></ElIcon><div><strong>先连接一个 AI 模型</strong><p>配置并启用分类服务后，就可以让 AI 帮你整理歌曲。</p></div><RouterLink to="/system/ai/features">前往 AI 设置 <ElIcon><ArrowRight /></ElIcon></RouterLink></div>
      <div class="start-layout">
        <ElCard shadow="never" class="start-card">
          <section class="workflow-section"><div class="workflow-heading"><span>1</span><div><h2>想整理哪些歌曲？</h2><p>先选少量歌曲试试，也可以整理整个曲库。</p></div></div>
            <div class="scope-options" role="group" aria-label="分类歌曲范围"><button :aria-pressed="!all" :disabled="busy" @click="all = false; openSongPicker()"><ElIcon><Headset /></ElIcon><strong>选择歌曲 <span v-if="selectedIds.length">{{ selectedIds.length }} 首</span></strong><small>{{ selectedIds.length ? '已选择，可继续添加或移除' : '搜索并勾选想整理的歌曲' }}</small><span class="choice-check" aria-hidden="true">{{ !all ? '✓' : '' }}</span></button><button :aria-pressed="all" :disabled="busy" @click="all = true"><ElIcon><CollectionTag /></ElIcon><strong>整个曲库</strong><small>一次最多整理 5000 首</small><span class="choice-check" aria-hidden="true">{{ all ? '✓' : '' }}</span></button></div>
            <ElButton v-if="!all && selectedIds.length" class="choose-songs" :disabled="busy" @click="openSongPicker">重新选择歌曲 <ElIcon><ArrowRight /></ElIcon></ElButton>
          </section>
          <section class="workflow-section"><div class="workflow-heading"><span>2</span><div><h2>想按什么来分类？</h2><p>可多选，已有的人工分类会受到保护。</p></div></div><div class="dimension-options"><label v-for="dim in aiDimensions" :key="dim" :class="{ selected: dimensions.includes(dim) }"><ElCheckbox :model-value="dimensions.includes(dim)" :disabled="busy" @change="v => toggleList(dimensions, dim, !!v)">{{ dimensionNames[dim] }}</ElCheckbox><small>{{ dimensionHints[dim] }}</small></label></div></section>
          <section class="save-policy"><div><ElIcon><CircleCheck /></ElIcon><strong>{{ auto ? '部分分类将自动保存' : '先看建议，再决定保存' }}</strong><span v-if="auto" class="auto-warning">已开启部分自动保存</span></div><p>{{ auto ? '已选内容会自动保存 AI 推断，其他建议仍需确认。人工确认和锁定的分类受保护。' : '默认不会直接改动歌曲分类。生成后，你可以逐首确认，也可以手动调整。' }}</p><details><summary>更多保存选项</summary><ElCheckbox v-model="auto" :disabled="busy">生成后自动保存以下分类</ElCheckbox><p>仅在你选中的分类内容上自动保存 AI 推断。AI 可能判断有误；人工确认或锁定的分类不会被自动覆盖。</p><div v-if="auto" class="automatic-options"><ElCheckbox v-for="dim in dimensions" :key="dim" :model-value="automatic.includes(dim)" :disabled="busy" @change="v => toggleList(automatic, dim, !!v)">{{ dimensionNames[dim] }}</ElCheckbox><p v-if="!automatic.some(d => dimensions.includes(d))" class="inline-error">请选择允许自动保存的分类内容。</p></div></details></section>
          <footer class="start-footer"><div><strong>{{ all ? '整个曲库' : selectedIds.length ? `${selectedIds.length} 首歌曲` : '还没有选择歌曲' }}</strong><span>{{ dimensions.length ? dimensionLabels(dimensions) : '请选择至少一种分类内容' }}</span></div><ElButton v-if="!all && !selectedIds.length && config.enabled" type="primary" @click="openSongPicker">选择歌曲 <ElIcon><ArrowRight /></ElIcon></ElButton><ElButton v-else type="primary" :loading="busy" :disabled="startDisabled" @click="preview">下一步，确认歌曲 <ElIcon><ArrowRight /></ElIcon></ElButton></footer>
        </ElCard>
        <aside class="classification-guide"><span class="guide-icon"><ElIcon><MagicStick /></ElIcon></span><h2>整理，让好歌更好找</h2><p>用已有的歌名、歌手和专辑信息，生成适合你的分类建议。</p><ol><li><span>01</span><div><strong>选择歌曲与分类内容</strong><p>从一小组熟悉的歌开始。</p></div></li><li><span>02</span><div><strong>让 AI 整理建议</strong><p>处理进度会保存在分类记录中。</p></div></li><li><span>03</span><div><strong>检查后保存</strong><p>不满意的结果可重试或手动调整。</p></div></li></ol><div class="model-note"><span class="service-dot" :class="{ ready: config.enabled }" /><div><strong>当前分类模型</strong><p>{{ settingsLoaded ? (config.serviceName || config.model || '尚未配置') : '检查中…' }}</p><small v-if="config.enabled">{{ config.model }} · 请求间隔 {{ config.intervalSeconds }} 秒</small><RouterLink to="/system/ai/features">前往 AI 设置 →</RouterLink></div></div><small class="classification-caution">AI 推断可能不准确。发行年代只依据已有年份，未知信息保留为空；不会修改原始媒体文件。</small></aside>
      </div>
    </template>

    <template v-else-if="tab === 'history'">
      <div class="section-heading"><div><h2>{{ task ? '这次分类' : '分类记录' }}</h2><p>{{ task ? `${formatDate(task.createdAt)} · ${dimensionLabels(task.dimensions)}` : '查看进度，确认建议，或继续之前的整理。' }}</p></div><div class="button-row"><ElButton v-if="task" text @click="showHistory()">全部记录</ElButton><ElButton :loading="loading" @click="refresh">刷新</ElButton><ElButton v-if="!task" type="primary" @click="tab = 'start'">开始新的分类</ElButton></div></div>
      <p v-if="loading && !task && !tasks.length" role="status" class="loading-note">正在读取分类记录…</p>
      <template v-if="!task">
        <div v-if="!tasks.length && !loading" class="classification-empty"><span><ElIcon><Clock /></ElIcon></span><h3>{{ taskPage ? '这一页没有更多记录' : '还没有分类记录' }}</h3><p>选几首熟悉的歌，让 AI 先整理一份分类建议。</p><ElButton type="primary" @click="tab = 'start'">去开始分类</ElButton></div>
        <div class="history-list"><button v-for="record in tasks" :key="record.id" class="history-record" @click="showHistory(record.id)"><span class="record-icon"><ElIcon><CollectionTag /></ElIcon></span><div class="record-main"><strong>{{ record.total }} 首歌曲的分类</strong><p>{{ formatDate(record.createdAt) }} · {{ dimensionLabels(record.dimensions) }}</p></div><div class="record-progress"><ElTag size="small" :type="record.status === 'COMPLETED' ? 'success' : 'info'">{{ statusNames[record.status] }}</ElTag><small>{{ record.completed }} / {{ record.total }} 首已处理<span v-if="record.counts.REVIEW"> · {{ record.counts.REVIEW }} 首待确认</span></small></div><ElIcon><ArrowRight /></ElIcon></button></div>
        <div v-if="tasks.length || taskPage" class="record-pagination"><span>当前第 {{ taskPage + 1 }} 页，每页最多 20 条记录</span><ElButton :disabled="taskPage === 0 || loading" @click="taskPage--; refresh()">上一页记录</ElButton><ElButton :disabled="tasks.length < 20 || loading" @click="taskPage++; refresh()">下一页记录</ElButton></div>
      </template>
      <template v-if="task">
        <ElCard shadow="never" class="task-overview"><div class="section-heading"><div><ElTag :type="task.status === 'RUNNING' ? 'success' : 'info'">{{ statusNames[task.status] }}</ElTag><h3>{{ reviewCount ? `${reviewCount} 首歌曲等你确认` : task.status === 'RUNNING' ? '正在整理歌曲，请稍候' : task.status === 'PAUSED' ? '分类已暂停，可以稍后继续' : '这次分类的结果已保留' }}</h3></div><div class="button-row"><ElButton v-if="task.status === 'RUNNING'" :disabled="busy" @click="control('pause')">暂停</ElButton><ElButton v-if="task.status === 'PAUSED'" :disabled="busy || !config.enabled" @click="control('resume')">继续</ElButton><ElButton v-if="['RUNNING','PAUSED'].includes(task.status)" text type="danger" :disabled="busy" @click="control('cancel')">终止</ElButton></div></div><ElProgress :percentage="progress" :stroke-width="8" /><div class="task-caption"><span>已处理 {{ task.completed }} / {{ task.total }} 首</span><span>模型：{{ task.model }} · {{ task.autoDimensions.length ? `自动保存：${dimensionLabels(task.autoDimensions)}` : '建议需确认后保存' }}</span></div><p v-if="task.status === 'PAUSED' && !config.enabled" class="inline-error">分类服务未启用，请先前往 AI 设置检查配置。</p></ElCard>
        <ElCard shadow="never" class="results-card"><div class="result-filters" aria-label="分类结果筛选"><button :aria-pressed="filter === ''" @click="selectFilter('')">全部 <span>{{ task.total }}</span></button><button v-for="state in ['REVIEW','APPLIED','FAILED']" :key="state" :aria-pressed="filter === state" @click="selectFilter(state)">{{ state === 'REVIEW' ? '待确认' : state === 'APPLIED' ? '已保存' : '未成功' }} <span>{{ task.counts[state] || 0 }}</span></button><ElSelect v-model="filter" aria-label="其他明细状态" placeholder="更多状态" @change="value => selectFilter(value)"><ElOption label="全部状态" value="" /><ElOption v-for="state in Object.keys(task.counts)" :key="state" :value="state" :label="`${statusNames[state]} ${task.counts[state]}`" /></ElSelect></div><p class="results-note">待确认是已生成的建议，保存后才会写入歌曲分类。已确认或锁定的内容受保护。</p><p v-if="loading" role="status" class="loading-note">正在读取分类结果…</p><ClassificationResultCard v-for="item in loading ? [] : task.items" :key="item.id" :item="item" :dimensions="task.dimensions" :tags="tags" :busy="busy || loading" :can-generate="config.enabled" :cancelled="task.status === 'CANCELLED'" @review="openReview" @retry="retry" @edit="id => editing = [id]" /><div v-if="!loading && !error && !task.items.length" class="filtered-empty"><h3>{{ filter === 'REVIEW' ? '当前没有待确认的歌曲' : filter === 'FAILED' ? '当前没有失败的歌曲' : '这个条件下没有歌曲' }}</h3><p>可以切换上方筛选查看其他结果。</p><ElButton text @click="selectFilter('')">查看全部</ElButton></div><ElPagination :current-page="page" :total="task.totalElements" :page-size="20" layout="total, prev, pager, next" @current-change="changeResultPage" /><details class="task-details"><summary>处理规则与详细状态</summary><p>各状态互斥；已处理包括待确认、已保存、无建议、失败和已终止项。部分分类已自动保存时仍可能有建议待确认；具体写入结果见审核和历史。最近更新：{{ loadedAt }}。</p></details></ElCard>
      </template>
    </template>

    <ElCard v-else shadow="never" class="vocabulary-card"><div class="section-heading"><div><h2>分类词表</h2><p>管理分类时可用的标签，AI 不会自行新增或发布标签。</p></div><div class="button-row"><ElButton @click="changed">刷新词表</ElButton><ElButton type="primary" @click="openTag()">新增标签</ElButton></div></div><div class="vocabulary-filters"><ElInput v-model="tagSearch" placeholder="搜索标签或别名" aria-label="搜索分类标签" clearable /><ElSelect v-model="tagDimension" aria-label="分类维度筛选"><ElOption label="全部分类内容" value="" /><ElOption v-for="(name, dim) in dimensionNames" :key="dim" :value="dim" :label="name" /></ElSelect></div><div class="vocabulary-quick-filters" role="group" aria-label="按分类内容快速筛选"><button type="button" :aria-pressed="tagDimension === ''" @click="tagDimension = ''">全部 <span>{{ tagCount('') }}</span></button><button v-for="(name, dim) in dimensionNames" :key="dim" type="button" :aria-pressed="tagDimension === dim" @click="tagDimension = dim">{{ name }} <span>{{ tagCount(dim) }}</span></button></div><div class="tag-list"><article v-for="label in visibleTags" :key="label.id" class="vocabulary-row"><button class="tag-cover-thumb" :aria-label="`设置${label.name}封面`" @click="coverTag = label"><img v-if="label.coverPath" :src="`${label.coverPath}?v=${label.coverVersion}`" :alt="`${label.name}封面`" /><span v-else>{{ label.name.slice(0, 2) }}</span></button><span class="tag-dimension">{{ dimensionNames[label.dimension] }}</span><div><strong>{{ label.name }}</strong><p>{{ label.aliases.length ? `别名：${label.aliases.join('、')}` : '没有别名' }}</p></div><ElTag v-if="!label.active" size="small" type="info">已停用</ElTag><ElButton text :disabled="!label.active" @click="router.push({ path: '/library/songs', query: { tagIds: label.id } })">{{ label.count }} 首歌曲</ElButton><ElButton text @click="coverTag = label">{{ label.coverPath ? '更换封面' : '上传封面' }}</ElButton><ElButton text type="primary" @click="openTag(label)">编辑</ElButton></article><div v-if="!visibleTags.length" class="filtered-empty">没有找到符合条件的标签，试试其他关键词。</div></div><p class="results-note">停用标签后不再参与正式筛选，已有分类和历史仍会保留。修改词表后，已有任务继续使用原快照。</p></ElCard>

    <ElDialog class="classification-dialog" :model-value="!!pendingPreview" title="确认这次分类" width="min(760px, calc(100vw - 32px))" :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy" @close="pendingPreview = null; pendingStart = null"><template v-if="pendingPreview"><div class="preview-summary"><div><strong>{{ pendingPreview.total }}</strong><span>首歌曲</span></div><div><strong>{{ pendingStart?.dimensions.length }}</strong><span>种分类内容</span></div><div><strong>{{ pendingStart?.autoDimensions.length ? '部分自动保存' : '先看建议' }}</strong><span>{{ pendingStart?.autoDimensions.length ? dimensionLabels(pendingStart.autoDimensions) : '确认后才会写入分类' }}</span></div></div><p class="dialog-copy">{{ dimensionLabels(pendingStart?.dimensions ?? []) }}</p><ElAlert title="将歌曲文字信息发送给已配置模型，AI 判断可能有误。不会发送媒体、路径或修改原文件。" type="info" :closable="false" show-icon /><h3 class="preview-list-heading">将整理的歌曲 <small>预览前 {{ pendingPreview.items.length }} 首，共 {{ pendingPreview.total }} 首</small></h3><div class="preview-songs"><div v-for="song in pendingPreview.items" :key="song.songId"><strong>{{ song.title || '未识别歌名' }}</strong><span>{{ song.artists.join('、') || '未识别歌手' }}</span></div></div><details class="task-details"><summary>查看专辑、年份等补充信息</summary><p>模型：{{ pendingPreview.model.model }}</p><ElTable :data="pendingPreview.items" max-height="250"><ElTableColumn prop="title" label="歌名" /><ElTableColumn prop="album" label="专辑" /><ElTableColumn prop="releaseDate" label="发行日期" /><ElTableColumn prop="language" label="语种" /><ElTableColumn prop="version" label="版本" /><ElTableColumn label="质量标签"><template #default="{ row }">{{ row.qualityTags.join('、') }}</template></ElTableColumn></ElTable></details></template><template #footer><ElButton :disabled="busy" @click="pendingPreview = null; pendingStart = null">返回修改</ElButton><ElButton type="primary" :loading="busy" @click="start">确认开始分类</ElButton></template></ElDialog>
    <ElDialog class="classification-dialog" :model-value="!!reviewing" title="确认歌曲的分类" width="min(760px, calc(100vw - 32px))" :show-close="!busy" :close-on-click-modal="false" :close-on-press-escape="!busy" @close="reviewing = null"><template v-if="reviewing"><div class="review-song"><span class="song-review-icon">♪</span><div><h3>{{ reviewing.input.title || '未识别歌名' }}</h3><p>{{ reviewing.input.artists.join('、') || '未识别歌手' }}</p></div></div><p class="dialog-copy">勾选要保存的分类，未勾选的保持原样。已锁定的分类不能覆盖。</p><section v-for="dim in task?.dimensions" :key="dim" class="review-dimension"><ElCheckbox :model-value="reviewDims.includes(dim)" @change="v => toggleList(reviewDims, dim, !!v)" :disabled="busy || !reviewing.suggestion?.dimensions[dim]?.length || reviewing.current.dimensions[dim]?.locked">{{ dimensionNames[dim] }} <span v-if="reviewing.current.dimensions[dim]?.locked">· 已锁定</span></ElCheckbox><div class="review-comparison"><div><small>当前分类</small><p>{{ names(reviewing.current.dimensions[dim]?.tagIds) }}</p><span v-if="reviewing.current.dimensions[dim]">{{ originNames[reviewing.current.dimensions[dim]!.origin] }}</span></div><ElIcon><ArrowRight /></ElIcon><div><small>AI 建议</small><p>{{ names(reviewing.suggestion?.dimensions[dim]) }}</p></div></div><p class="review-reason">{{ reviewing.suggestion?.reasons[dim] || '暂无补充说明' }}</p></section><ElAlert v-if="error" :title="error" type="error" :closable="false" /><p class="results-note">保存后标记为人工确认。若替换已有人工确认分类，会再次提醒你确认。</p></template><template #footer><ElButton :disabled="busy" @click="reviewing = null">暂不保存</ElButton><ElButton type="primary" :disabled="!reviewDims.length" :loading="busy" @click="applyReview">保存选中的分类</ElButton></template></ElDialog>
    <ElDialog class="classification-dialog" :model-value="!!tagEdit" title="维护分类标签" width="min(520px, calc(100vw - 32px))" :show-close="!busy" @close="tagEdit = null"><ElForm v-if="tagEdit" label-position="top" class="tag-edit-form" @submit.prevent="saveTag"><label>分类内容<ElSelect v-model="tagEdit.dimension" :disabled="!!tagEdit.id || busy"><ElOption v-for="(name, dim) in dimensionNames" :key="dim" :value="dim" :label="name" /></ElSelect></label><label>标签名称<ElInput v-model="tagEdit.name" maxlength="80" :disabled="busy" /></label><label>别名<ElInput v-model="tagEdit.aliases" placeholder="多个别名用顿号分隔" :disabled="busy" /></label><ElCheckbox v-model="tagEdit.active" :disabled="busy">启用这个标签</ElCheckbox><ElAlert v-if="error" :title="error" type="error" :closable="false" /><ElButton type="primary" native-type="submit" :loading="busy">保存标签</ElButton></ElForm></ElDialog>
    <TagCoverEditor v-if="coverTag" :tag="coverTag" @close="coverTag = null" @changed="coverChanged" />
    <ClassificationSongPicker v-if="songPickerOpen" :selected-ids="selectedIds" @close="songPickerOpen = false" @confirm="chooseSongs" />
    <ClassificationEditor v-if="editing.length" :song-ids="editing" @close="editing = []" @changed="changed" />
  </section>
</template>
<style scoped src="../styles/classification.css"></style>

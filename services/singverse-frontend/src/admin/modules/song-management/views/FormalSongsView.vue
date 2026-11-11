<script setup lang="ts">
import { requestId as newRequestId } from '../../../../shared/request-id'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElAlert, ElButton, ElCard, ElDialog, ElDrawer, ElEmpty, ElInput, ElMessage, ElMessageBox, ElOption, ElPagination, ElSelect, ElSkeleton, ElTag } from 'element-plus'
import PageHeader from '../../../shared/ui/PageHeader.vue'
import { formalArtists, formalLibrary, formalStateLabel, formalTitle, type FormalDetail, type FormalDocument } from '../api/formal'
import { songs } from '../api/songs'
import { singleReview } from '../../metadata/api/single-review'
import { mediaVerification } from '../../media-library/api/shareImports'
import type { MediaSong, MediaSource } from '../../media-library/types'
import FormalMetadataEditor from '../components/FormalMetadataEditor.vue'
import SongVideoDialog from '../components/SongVideoDialog.vue'

const route = useRoute(), router = useRouter()
const search = ref(''), sourceId = ref(''), page = ref(1), total = ref(0), items = ref<FormalDocument[]>([]), sources = ref<MediaSource[]>([])
const counts = ref<Record<string, number>>({}), loading = ref(false), error = ref(''), detailError = ref(''), busy = ref(false)
const detail = ref<FormalDetail | null>(null), variant = ref<FormalDetail | null>(null), editing = ref<FormalDocument | null>(null), playing = ref<MediaSong | null>(null)
const difference = ref<{ field_name: string; source_key: string; incoming_json: string }[] | null>(null)
const binding = ref<FormalDocument | null>(null), bindSource = ref(''), bindPath = ref('')
const moving = ref<FormalDocument | null>(null), destinations = ref<FormalDocument[]>([]), destinationSong = ref(''), destinationVariants = ref<FormalDocument[]>([]), destinationVariant = ref('')
const adding = ref(false), versionTitle = ref(''), versionKind = ref('UNKNOWN')
const media = computed(() => variant.value?.children.filter(d => d.type === 'MEDIA') ?? [])
const variants = computed(() => detail.value?.children.filter(d => d.type === 'VARIANT') ?? [])
const sourceName = (id: string | null) => sources.value.find(s => s.id === id)?.name ?? '位置已解绑'
const location = (id: string) => variant.value?.locations?.find(l => l.media_id === id)
const stateLabel = (id: string) => mediaVerification(location(id)?.state) || ({ UNBOUND: '位置已解绑', REPLACED: '文件已替换' }[location(id)?.state ?? ''] || '待核验')
const attempts = new Map<string, { payload: string; id: string }>()
function attempt(key: string, payload: unknown) { const value = JSON.stringify(payload), previous = attempts.get(key); if (previous?.payload === value) return previous.id; const id = newRequestId(); attempts.set(key, { payload: value, id }); return id }
async function refreshCounts() { try { const value = await formalLibrary.statistics(); if (active) counts.value = value } catch {} }
let active = true, generation = 0, detailGeneration = 0, variantGeneration = 0
async function load() {
  const token = ++generation; loading.value = true; error.value = ''
  try { const result = await formalLibrary.list(search.value, page.value - 1, sourceId.value); if (active && token === generation) { items.value = result.items; total.value = result.totalElements } }
  catch (failure) { if (active && token === generation) error.value = failure instanceof Error ? failure.message : '曲库读取失败' }
  finally { if (active && token === generation) loading.value = false }
}
async function apply(nextPage = 1) {
  const query: Record<string, string> = { lens: 'formal' }
  if (search.value.trim()) query.search = search.value.trim()
  if (sourceId.value) query.sourceId = sourceId.value
  if (nextPage > 1) query.page = String(nextPage)
  const target = router.resolve({ path: '/library/songs', query })
  if (target.fullPath === route.fullPath) { page.value = nextPage; await load() } else await router.replace(target.fullPath)
}
function openFilesLens() {
  const query: Record<string, string> = { lens: 'songs' }
  if (search.value.trim()) query.search = search.value.trim()
  if (sourceId.value) query.sourceId = sourceId.value
  if (page.value > 1) query.page = String(page.value)
  void router.replace({ path: '/library/songs', query })
}
watch(() => route.query, query => { search.value = typeof query.search === 'string' ? query.search : ''; sourceId.value = typeof query.sourceId === 'string' ? query.sourceId : ''; page.value = Math.max(1, Number(query.page) || 1); void load() }, { immediate: true })
void songs.sources().then(value => { if (active) sources.value = value }).catch(() => { if (active) error.value = '媒体来源读取失败，请刷新重试' })
void refreshCounts()
async function openSong(id: string, childPage = 0) {
  const token = ++detailGeneration; detail.value = null; variant.value = null; detailError.value = ''
  try { const result = await formalLibrary.detail(id,childPage); if (!active || token !== detailGeneration) return; detail.value = result; if (result.children[0]) await openVariant(result.children[0].id) }
  catch (failure) { if (active && token === detailGeneration) detailError.value = failure instanceof Error ? failure.message : '歌曲资料读取失败' }
}
async function openVariant(id: string,childPage = 0) {
  const token = ++variantGeneration; variant.value = null; detailError.value = ''
  try { const result = await formalLibrary.detail(id,childPage); if (active && token === variantGeneration) variant.value = result }
  catch (failure) { if (active && token === variantGeneration) detailError.value = failure instanceof Error ? failure.message : '版本读取失败' }
}
async function saved() { editing.value = null; await refreshCounts(); await load(); if (detail.value) await openSong(detail.value.document.id) }
async function inspectDifferences(document: FormalDocument) { try { difference.value = (await formalLibrary.differences(document.id)).items } catch (failure) { ElMessage.error(failure instanceof Error ? failure.message : '差异读取失败') } }
async function preview(document: FormalDocument) { try { playing.value = (await singleReview.load(document.id)).song } catch (failure) { ElMessage.error(failure instanceof Error ? failure.message : '媒体读取失败') } }
async function split(document: FormalDocument) { if (!variant.value || busy.value) return; busy.value = true; try { await formalLibrary.split(variant.value.document, document.id, attempt('split', [variant.value.document, document.id])); attempts.delete('split'); await refreshCounts(); if (detail.value) await openSong(detail.value.document.id); ElMessage.success('已建立新版本，请单独确认歌词时间轴和音轨') } catch (failure) { ElMessage.error(failure instanceof Error ? failure.message : '拆分失败') } finally { busy.value = false } }
async function bind(document: FormalDocument) { binding.value = document; const old = location(document.id); bindSource.value = old?.source_id ?? ''; bindPath.value = old?.relative_path ?? ''; detailError.value = '' }
async function saveBinding() { if (!binding.value || !bindSource.value || !bindPath.value || busy.value) return; busy.value = true; try { await formalLibrary.rebind(binding.value, bindSource.value, bindPath.value, attempt('binding', [binding.value, bindSource.value, bindPath.value])); attempts.delete('binding'); binding.value = null; if (variant.value) await openVariant(variant.value.document.id); ElMessage.success('文件位置已保存，后台正在核验；已有资料已保留') } catch (failure) { detailError.value = failure instanceof Error ? failure.message : '位置保存失败' } finally { busy.value = false } }
async function beginMove(document: FormalDocument) { moving.value = document; destinationSong.value = ''; destinationVariant.value = ''; destinationVariants.value = []; await findDestination('') }
async function findDestination(keyword: string) { try { destinations.value = (await formalLibrary.list(keyword)).items } catch (failure) { ElMessage.error(failure instanceof Error ? failure.message : '读取目标歌曲失败') } }
async function chooseDestination() { destinationVariant.value = ''; destinationVariants.value = destinationSong.value ? (await formalLibrary.detail(destinationSong.value)).children : [] }
async function move() { if (!moving.value || !destinationVariant.value || busy.value) return; busy.value = true; try { await formalLibrary.update(moving.value, [], destinationVariant.value, attempt('move', [moving.value, destinationVariant.value])); attempts.delete('move'); moving.value = null; if (detail.value) await openSong(detail.value.document.id); ElMessage.success('已调整媒体版本关联') } catch (failure) { ElMessage.error(failure instanceof Error ? failure.message : '关联失败') } finally { busy.value = false } }
async function createVersion() { if (!detail.value || !versionTitle.value.trim() || busy.value) return; busy.value = true; try { await formalLibrary.createVariant(detail.value.document.id, versionTitle.value.trim(), versionKind.value, attempt('create-version', [detail.value.document.id, versionTitle.value.trim(), versionKind.value])); attempts.delete('create-version'); await refreshCounts(); adding.value = false; versionTitle.value = ''; await openSong(detail.value.document.id) } catch (failure) { detailError.value = failure instanceof Error ? failure.message : '新版本创建失败' } finally { busy.value = false } }
async function replaceMedia(document: FormalDocument) {
  if (busy.value) return
  try { await ElMessageBox.confirm('确认此路径的文件内容已经被替换？系统将登记新媒体，保留原媒体记录与歌曲资料；新文件的时长、音轨需要重新确认。', '确认文件内容变更', { confirmButtonText: '登记新媒体', cancelButtonText: '取消', type: 'warning' }) } catch { return }
  busy.value = true
  try { await formalLibrary.replaceMedia(document, attempt('replace', document)); attempts.delete('replace'); await refreshCounts(); if (detail.value) await openSong(detail.value.document.id); ElMessage.success('新媒体已登记，后台正在核验') } catch (failure) { ElMessage.error(failure instanceof Error ? failure.message : '新媒体登记失败') } finally { busy.value = false }
}
onBeforeUnmount(() => { active = false; generation++; detailGeneration++; variantGeneration++ })
</script>

<template>
  <section class="formal-library">
    <PageHeader title="歌曲与版本" description="歌曲资料保存在本机。每次演绎与剪辑单独管理版本，同一版本可以关联多个编码文件。">
      <template #actions><ElButton @click="openFilesLens">按文件管理与筛选</ElButton><ElButton type="primary" @click="router.push('/library/media')">添加或导入歌曲</ElButton></template>
    </PageHeader>
    <p class="counts" aria-live="polite">全库 {{ counts.SONG ?? '—' }} 首歌曲 · {{ counts.VARIANT ?? '—' }} 个版本 · {{ counts.MEDIA ?? '—' }} 个媒体文件</p>
    <ElCard shadow="never"><div class="filters"><ElInput v-model="search" aria-label="搜索正式歌曲资料" placeholder="搜索歌名、演唱者或别名" maxlength="200" clearable @keyup.enter="apply()" /><ElSelect v-model="sourceId" aria-label="媒体来源" clearable placeholder="全部来源" @change="apply()"><ElOption v-for="source in sources" :key="source.id" :value="source.id" :label="source.name" /></ElSelect><ElButton type="primary" :loading="loading" @click="apply()">搜索</ElButton></div></ElCard>
    <ElAlert v-if="error" type="error" :title="error" :closable="false" role="alert" />
    <ElSkeleton v-if="loading" :rows="5" animated />
    <ElEmpty v-else-if="!items.length && !error" description="还没有符合条件的歌曲资料"><ElButton @click="router.push('/library/media')">添加媒体来源或导入资料包</ElButton></ElEmpty>
    <div v-else class="song-list"><ElCard v-for="song in items" :key="song.id" shadow="never"><div class="song-row"><div><h3>{{ formalTitle(song) }}</h3><p>{{ formalArtists(song) }}</p><small>{{ formalStateLabel(song.states.title) }} · 资料已保存</small></div><div class="actions"><ElButton @click="editing = song">订正资料</ElButton><ElButton @click="inspectDifferences(song)">查看差异</ElButton><ElButton type="primary" plain @click="openSong(song.id)">查看版本与文件</ElButton></div></div></ElCard></div>
    <ElPagination :current-page="page" :page-size="20" :total="total" layout="total, prev, pager, next" @current-change="apply" />
    <ElAlert v-if="detailError && !detail" type="error" :title="detailError" :closable="false" />

    <ElDrawer :model-value="!!detail" :title="detail ? formalTitle(detail.document) : '歌曲资料'" size="min(900px, 100vw)" @close="detail = null; variant = null; detailGeneration++; variantGeneration++">
      <template v-if="detail">
        <p>{{ formalArtists(detail.document) }} · 文字资料已保存</p>
        <p v-if="detail.saveStatus?.attachmentsPending">{{ detail.saveStatus.attachmentsPending }} 项封面正在保存；已有原图继续可用。</p><p v-if="detail.saveStatus?.derivativesPending">{{ detail.saveStatus.derivativesPending }} 项衍生文件等待生成，不影响已保存资料。</p>
        <ElAlert v-if="detailError" type="error" :title="detailError" :closable="false" />
        <div class="version-toolbar"><ElSelect :model-value="variant?.document.id ?? ''" aria-label="选择媒体版本" placeholder="选择媒体版本" class="version-select" @change="id => openVariant(id)"><ElOption v-for="item in variants" :key="item.id" :value="item.id" :label="formalTitle(item) + ' · ' + (item.fields.kind || '待确认类型')" /></ElSelect><ElButton @click="adding = true">新增版本</ElButton><ElButton v-if="variant" @click="editing = variant.document">订正版本资料</ElButton></div>
        <ElPagination v-if="(detail.childrenPage?.totalElements ?? 0)>20" :current-page="(detail.childrenPage?.page ?? 0)+1" :page-size="20" :total="detail.childrenPage?.totalElements ?? 0" layout="prev, pager, next" @current-change="page => openSong(detail!.document.id,page-1)" />
        <p>歌词和时间轴属于当前版本；实际时长与音轨属于具体媒体文件。关联前请确认演绎与剪辑一致。</p>
        <ElEmpty v-if="variant && !media.length" description="这个版本尚未关联媒体文件" />
        <ElCard v-for="file in media" :key="file.id" shadow="never" class="media-card">
          <strong class="file-path">{{ location(file.id)?.relative_path || '媒体位置待绑定' }}</strong><p>{{ sourceName(location(file.id)?.source_id ?? null) }} · <ElTag>{{ stateLabel(file.id) }}</ElTag></p>
          <p v-if="file.fields.durationMs">实际媒体时长 {{ Number(file.fields.durationMs) / 1000 }} 秒<span v-if="detail.document.fields.referenceDurationMs"> · 曲目参考时长 {{ Number(detail.document.fields.referenceDurationMs) / 1000 }} 秒</span></p>
          <div class="actions"><ElButton :disabled="location(file.id)?.state !== 'PRESENT'" @click="preview(file)">试听检查</ElButton><ElButton @click="router.push({ path: '/library/songs', query: { lens: 'songs', reviewSongId: file.id } })">完善信息与封面</ElButton><ElButton @click="bind(file)">重新绑定位置</ElButton><ElButton :disabled="busy" @click="beginMove(file)">关联其他版本</ElButton><ElButton :disabled="busy" @click="replaceMedia(file)">文件内容已替换</ElButton><ElButton :disabled="busy" @click="split(file)">拆为新版本</ElButton></div>
        </ElCard>
        <ElPagination v-if="(variant?.childrenPage?.totalElements ?? 0)>20" :current-page="(variant?.childrenPage?.page ?? 0)+1" :page-size="20" :total="variant?.childrenPage?.totalElements ?? 0" layout="prev, pager, next" @current-change="page => openVariant(variant!.document.id,page-1)" />
      </template>
    </ElDrawer>
    <FormalMetadataEditor :document="editing" @close="editing = null" @saved="saved" />
    <ElDialog :model-value="difference !== null" title="待核对的资料差异" width="min(700px, calc(100vw - 24px))" @close="difference = null"><ElEmpty v-if="!difference?.length" description="没有待处理的字段冲突" /><div v-for="item in difference" :key="item.field_name + item.source_key"><strong>{{ item.field_name }}</strong><pre>{{ item.incoming_json }}</pre><small>{{ item.source_key.startsWith('SHARED:') ? '共享资料提供的新值' : '其他来源提供的新值' }}；本机当前值已保留，可在订正资料中决定。</small></div></ElDialog>
    <ElDialog :model-value="!!binding" title="重新绑定媒体文件位置" width="min(600px, calc(100vw - 24px))" :close-on-click-modal="false" @close="binding = null"><p>选择自己的来源和相对路径，歌曲资料、封面与本机订正会保留。保存后先核验路径。</p><ElAlert v-if="detailError" type="error" :title="detailError" :closable="false" /><ElSelect v-model="bindSource" aria-label="新的媒体来源"><ElOption v-for="source in sources" :key="source.id" :label="source.name" :value="source.id" /></ElSelect><ElInput v-model="bindPath" aria-label="新的媒体相对路径" placeholder="华语/歌手/歌曲.mkv" maxlength="2048" /><template #footer><ElButton :disabled="busy" @click="binding = null">取消</ElButton><ElButton type="primary" :loading="busy" @click="saveBinding">保存位置</ElButton></template></ElDialog>
    <ElDialog :model-value="!!moving" title="关联媒体版本" width="min(600px, calc(100vw - 24px))" @close="moving = null"><p>选择相同演绎、剪辑与时间轴的版本。不同演唱者的翻唱请保留为不同歌曲。</p><ElSelect v-model="destinationSong" aria-label="目标歌曲" filterable remote :remote-method="findDestination" @change="chooseDestination"><ElOption v-for="item in destinations" :key="item.id" :label="formalTitle(item) + ' · ' + formalArtists(item)" :value="item.id" /></ElSelect><ElSelect v-model="destinationVariant" aria-label="目标版本"><ElOption v-for="item in destinationVariants" :key="item.id" :label="formalTitle(item) + ' · ' + (item.fields.kind || '待确认类型')" :value="item.id" /></ElSelect><template #footer><ElButton type="primary" :loading="busy" :disabled="!destinationVariant" @click="move">保存关联</ElButton></template></ElDialog>
    <ElDialog v-model="adding" title="新增媒体版本" width="min(500px, calc(100vw - 24px))"><ElInput v-model="versionTitle" aria-label="版本名称" placeholder="例如：2024 现场完整版" maxlength="500" /><ElSelect v-model="versionKind" aria-label="版本类型"><ElOption v-for="kind in ['UNKNOWN', 'MV', 'KTV', 'LIVE', 'AUDIO', 'OTHER']" :key="kind" :label="({UNKNOWN: '待确认', MV: '音乐视频', KTV: 'KTV', LIVE: '现场', AUDIO: '音频', OTHER: '其他'}[kind])" :value="kind" /></ElSelect><template #footer><ElButton type="primary" :loading="busy" @click="createVersion">创建版本</ElButton></template></ElDialog>
    <SongVideoDialog :song="playing" @close="playing = null" />
  </section>
</template>

<style scoped>
.formal-library { display: grid; gap: 20px; }.counts { margin: 0; color: var(--sv-text-secondary); }.filters,.song-row,.actions,.version-toolbar { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; }.filters .el-input { flex: 1; min-width: 180px; }.filters .el-select { width: 220px; }.song-list { display: grid; gap: 12px; }.song-row { justify-content: space-between; }.song-row h3 { margin: 0 0 8px; }.song-row p { margin: 0 0 8px; }.song-row small,p { color: var(--sv-text-secondary); }.version-toolbar { margin: 20px 0; }.version-toolbar .version-select { min-width: 260px; flex: 1; }.media-card { margin: 16px 0; }.file-path { overflow-wrap: anywhere; }pre { white-space: pre-wrap; overflow-wrap: anywhere; }.el-dialog .el-input,.el-dialog .el-select { margin: 8px 0; width: 100%; }@media(max-width: 650px) { .song-row { align-items: start; }.filters .el-select { width: 100%; }.actions { gap: 8px; }.actions .el-button { margin: 0; } }
</style>

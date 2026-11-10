<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElAlert, ElButton, ElDialog, ElInput, ElPagination, ElTable, ElTableColumn, ElMessage, ElMessageBox } from 'element-plus'
import { ApiError } from '../../../core/auth/api'
import PageHeader from '../../../shared/ui/PageHeader.vue'
import { artists } from '../api/artists'
import { artistTypes, type Artist, type ArtistFilter, type ArtworkBatch, type ImageKind } from '../types'
import type { MediaSong } from '../../media-library/types'
import ArtistImageEditor from '../components/ArtistImageEditor.vue'
import ArtistArtworkDialog from '../components/ArtistArtworkDialog.vue'
import ArtistArtworkBatch from '../components/ArtistArtworkBatch.vue'
import SingleSongScrapeDialog from '../../metadata/components/SingleSongScrapeDialog.vue'

const route = useRoute(), router = useRouter()
const pageSize = 20
const query = ref(''), artistType = ref(''), artworkFilter = ref(''), countLessThan = ref<number|null>(null)
const page = ref(1), total = ref(0), profiles = ref<Artist[]>([]), loading = ref(false), error = ref('')
const profile = ref<Artist|null>(null), songs = ref<MediaSong[]>([]), songPage = ref(1), songTotal = ref(0), songsLoading = ref(false)
const editing = ref<MediaSong|null>(null), imageKind = ref<ImageKind|null>(null), busy = ref(false)
const selected = ref(new Map<string,Artist>()), failedImages = ref(new Set<string>())
const appliedFilter = ref<ArtistFilter>({ q:'', type:'', countLessThan:null, artwork:'' })
const selectedCount = computed(() => selected.value.size)
const allPageSelected = computed(() => !!profiles.value.length && profiles.value.every(a => selected.value.has(a.id)))
const categories = [{ value:'', label:'全部歌手' },{ value:'MALE', label:'男歌手' },{ value:'FEMALE', label:'女歌手' },{ value:'GROUP', label:'乐队 / 组合' },{ value:'UNKNOWN', label:'待分类' }]
const hasFilters = computed(() => !!(appliedFilter.value.q || appliedFilter.value.type || appliedFilter.value.artwork || appliedFilter.value.countLessThan))
const batchPanel = ref<InstanceType<typeof ArtistArtworkBatch>|null>(null)
const batchId = computed(() => typeof route?.query.batch === 'string' ? route.query.batch : undefined)
const artworkOpen = ref(false), artworkIds = ref<string[]>([]), singleArtworkName = ref('')
const categoryOpen = ref(false), bulkType = ref(''), categoryError = ref('')
const categoryTargets = ref<Array<{id:string;revision:number}>>([])
const settingsOpen = ref(false), fanartKey = ref(''), fanartConfigured = ref(false), settingsBusy = ref(false)
const candidateKind = ref<ImageKind|null>(null), candidates = ref<Array<{provider:string;externalId:string;attribution:string;portrait:string;background:string}>>([])
const states:Record<string,string> = { PENDING:'等待获取封面', PROCESSING:'正在获取封面', BLOCKED:'已暂停自动补图，可恢复', RETRY:'暂未获取成功，稍后自动重试', FAILED:'获取失败，可以重试或上传', NO_MATCH:'没有可靠匹配，可自行上传', NEEDS_REVIEW:'有候选图片，等待确认', READY:'封面已就绪', DORMANT:'暂无关联歌曲' }
let active = true, generation = 0, detailGeneration = 0, candidateGeneration = 0, settingsGeneration = 0
let timer:ReturnType<typeof setInterval>|undefined, polling = false
const imageURL = (artist:Artist,kind:ImageKind='portrait') => artist[`${kind}Path`] ? `${artist[`${kind}Path`]}?v=${artist[`${kind}Version`] || ''}` : ''
const usableImage = (artist:Artist,kind:ImageKind='portrait') => !!imageURL(artist,kind) && !failedImages.value.has(imageURL(artist,kind))
function coverCaption(artist:Artist) {
  if (artist.portraitPath && failedImages.value.has(imageURL(artist))) return '封面读取失败，打开详情重试'
  return artist.portraitPath ? (artist.portraitOrigin==='UPLOAD'?'已上传封面':artist.portraitAttribution ? `${artist.portraitAttribution} · 已获取`:'封面已就绪') : states[artist.portraitFetchState || 'PENDING']
}
function failure(e:unknown) { if (active) error.value = e instanceof Error ? e.message : '读取失败，请重试' }
function filterFromForm():ArtistFilter { return { q:query.value.trim(), type:artistType.value, artwork:artworkFilter.value, countLessThan:typeof countLessThan.value === 'number' && Number.isInteger(countLessThan.value) && countLessThan.value > 0 ? countLessThan.value : null } }
function fetchList(filter=appliedFilter.value) { return artists.list(filter.q,page.value-1,pageSize,filter.type,filter.countLessThan,filter.artwork) }
async function load(reset=false) {
  if (reset) { page.value = 1; appliedFilter.value = filterFromForm() }
  const epoch = ++generation
  loading.value = true; error.value = ''
  try { const result = await fetchList(); if (active && epoch === generation) { profiles.value=result.items; total.value=result.totalElements } }
  catch (e) { if (epoch === generation) failure(e) }
  finally { if (active && epoch === generation) loading.value=false }
}
function clearFilters() { query.value=''; artistType.value=''; artworkFilter.value=''; countLessThan.value=null; void load(true) }
function chooseType(value:string) { artistType.value=value; void load(true) }
function toggleArtist(artist:Artist) {
  if (selected.value.has(artist.id)) selected.value.delete(artist.id)
  else if (selected.value.size < 1000) selected.value.set(artist.id,{...artist})
  else ElMessage.warning('每批最多选择 1000 位歌手')
}
function togglePage() {
  if (allPageSelected.value) for (const artist of profiles.value) selected.value.delete(artist.id)
  else for (const artist of profiles.value) if (selected.value.size < 1000) selected.value.set(artist.id,{...artist})
}
function openArtwork(single?:Artist) {
  artworkIds.value = single ? [single.id] : [...selected.value.keys()]
  singleArtworkName.value = single?.name || ''; artworkOpen.value=true
}
async function batchCreated(batch:ArtworkBatch) {
  if (!active) return
  await router.replace({ query:{ ...route.query, batch:batch.id } })
  await refreshAfterArtwork()
}
async function refreshAfterArtwork() {
  if (!active || busy.value || imageKind.value || editing.value) return
  await load()
  const id=profile.value?.id, epoch=detailGeneration
  if (id) try { const next=await artists.get(id); if (active && epoch===detailGeneration && profile.value?.id===id) profile.value={...profile.value,...next} } catch(e) { if (epoch===detailGeneration) failure(e) }
}
function openCategories() { categoryTargets.value=[...selected.value.values()].map(a=>({id:a.id,revision:a.revision})); bulkType.value=''; categoryError.value=''; categoryOpen.value=true }
async function saveCategories() {
  if (busy.value || !bulkType.value) return
  busy.value=true; categoryError.value=''
  try { await artists.categories(categoryTargets.value,bulkType.value as Artist['type']); if (active) { categoryOpen.value=false; selected.value.clear(); ElMessage.success('已更新所选歌手类型'); await load() } }
  catch(e) { if (active) categoryError.value=e instanceof ApiError && e.status===409 ? `${e.message}。本次未更新，请取消后刷新并重新选择。` : '未能确认更新结果，已保留选择。请刷新核对后再试。' }
  finally { if (active) busy.value=false }
}
async function loadSongs() {
  if (!profile.value) return
  const id=profile.value.id, epoch=++detailGeneration
  songsLoading.value=true
  try { const result=await artists.songs(id,songPage.value-1); if (active && epoch===detailGeneration && profile.value?.id===id) { songs.value=result.items; songTotal.value=result.totalElements } }
  catch(e) { if (epoch===detailGeneration) failure(e) }
  finally { if (active && epoch===detailGeneration) songsLoading.value=false }
}
async function openArtist(artist:Artist) { failedImages.value.delete(imageURL(artist)); failedImages.value.delete(imageURL(artist,'background')); profile.value=artist; songs.value=[]; songTotal.value=0; songPage.value=1; candidateKind.value=null; candidateGeneration++; await loadSongs() }
async function openArtistById(id:string) {
  const local=profiles.value.find(a=>a.id===id)
  if (local) { await openArtist(local); return }
  const epoch=++detailGeneration
  try { const artist=await artists.get(id); if (active && epoch===detailGeneration) await openArtist(artist) } catch(e) { if (epoch===detailGeneration) failure(e) }
}
function close() { profile.value=null; imageKind.value=null; candidateKind.value=null; candidateGeneration++; detailGeneration++ }
async function changed() {
  editing.value=null; await load()
  if (!profile.value) return
  const id=profile.value.id, epoch=detailGeneration
  try {
    const next=await artists.get(id)
    if (!active || epoch!==detailGeneration || profile.value?.id!==id) return
    if (!next.count) { close(); return }
    profile.value=next
    if ((songPage.value-1)*12 >= (next.count||0)) songPage.value=Math.max(1,Math.ceil((next.count||0)/12))
    await loadSongs()
  } catch(e) { if (epoch===detailGeneration) failure(e) }
}
async function imageChanged(next:Artist) { if (profile.value?.id===next.id) profile.value={...profile.value,...next}; await load(); await batchPanel.value?.refresh() }
async function changeCategory(type:Artist['type']) {
  if (!profile.value || busy.value) return
  const id=profile.value.id, revision=profile.value.revision, epoch=detailGeneration
  busy.value=true
  try { const next=await artists.category(id,type,revision); if (active && epoch===detailGeneration && profile.value?.id===id) profile.value={...profile.value,...next}; await load() }
  catch(e) { failure(e) } finally { if (active) busy.value=false }
}
async function retry() {
  if (!profile.value || busy.value) return
  const id=profile.value.id
  busy.value=true
  try { await artists.retry(id); if (active) { ElMessage.success('已恢复自动补图，已有图片会保留'); await load(); await batchPanel.value?.refresh(); const next=await artists.get(id); if (active && profile.value?.id===id) profile.value={...profile.value,...next} } }
  catch(e) { failure(e) } finally { if (active) busy.value=false }
}
async function reset(song:MediaSong) {
  if (busy.value) return
  try {
    const state=await artists.override(song.id)
    if (!state.overridden) { error.value='这首歌没有 NAS 歌手订正'; return }
    await ElMessageBox.confirm('恢复扫描的歌手信息？原文件不会修改。','恢复扫描值',{confirmButtonText:'恢复',cancelButtonText:'保留订正',autofocus:false})
    if (!active) return
    busy.value=true; await artists.reset(song.id,state.revision); await changed()
  } catch(e) { if (e!=='cancel' && e!=='close') failure(e) } finally { if (active) busy.value=false }
}
async function openCandidates(kind:ImageKind) {
  if (!profile.value) return
  const id=profile.value.id, epoch=++candidateGeneration
  try { const result=await artists.candidates(id,kind); if (active && epoch===candidateGeneration && profile.value?.id===id) { candidates.value=result; candidateKind.value=kind } }
  catch(e) { if (epoch===candidateGeneration) failure(e) }
}
async function selectCandidate(provider:string,externalId:string) {
  if (!profile.value || !candidateKind.value || busy.value) return
  const id=profile.value.id,kind=candidateKind.value,epoch=candidateGeneration
  busy.value=true
  try { const next=await artists.selectCandidate(id,kind,provider,externalId); if (active && epoch===candidateGeneration && profile.value?.id===id) { candidateKind.value=null; await imageChanged(next) } }
  catch(e) { failure(e) } finally { if (active) busy.value=false }
}
async function openSettings() {
  const epoch=++settingsGeneration
  try { const result=await artists.artworkSettings(); if (active && epoch===settingsGeneration) { fanartConfigured.value=result.fanartConfigured; fanartKey.value=''; settingsOpen.value=true } } catch(e) { failure(e) }
}
async function closeSettings(done:()=>void) {
  if (settingsBusy.value) return
  if (fanartKey.value) try { await ElMessageBox.confirm('新密钥尚未保存，确定离开？','保留未保存内容',{confirmButtonText:'放弃修改',cancelButtonText:'继续编辑',autofocus:false}) } catch { return }
  fanartKey.value=''; done()
}
async function saveSettings(clear=false) {
  if (settingsBusy.value) return
  settingsBusy.value=true
  try { const next=await artists.saveArtworkSettings(clear?null:fanartKey.value,clear); if (active) { fanartConfigured.value=next.fanartConfigured; fanartKey.value=''; settingsOpen.value=false } }
  catch(e) { failure(e) } finally { if (active) settingsBusy.value=false }
}
async function refreshImages() {
  if (polling || loading.value || busy.value || editing.value || imageKind.value || artworkOpen.value || !profiles.value.some(p=>['PENDING','RETRY','PROCESSING'].includes(p.fetchState||'') || p.portraitFetchState==='PROCESSING')) return
  polling=true
  const epoch=generation
  try { const result=await fetchList(); if (active && epoch===generation) { profiles.value=result.items; total.value=result.totalElements } }
  catch(e) { if (epoch===generation) failure(e) } finally { polling=false }
}
onMounted(()=>{ void load(); timer=setInterval(()=>void refreshImages(),8000) })
onBeforeUnmount(()=>{ active=false; generation++; detailGeneration++; candidateGeneration++; settingsGeneration++; if(timer)clearInterval(timer) })
</script>

<template>
<section class="artists-page">
  <PageHeader title="歌手" description="熟悉的声音，值得一张好封面。歌手随曲库自动收录，在这里整理图片与分类。">
    <template #actions><ElButton @click="openSettings">背景图来源</ElButton><ElButton type="primary" :disabled="!total && !selectedCount" @click="openArtwork()">获取歌手封面</ElButton></template>
  </PageHeader>
  <ElAlert v-if="error" :title="error" type="error" :closable="false" show-icon />
  <ArtistArtworkBatch ref="batchPanel" :batch-id="batchId" @changed="refreshAfterArtwork" @artist="openArtistById" />
  <div class="artists-browser">
    <div class="artists-types" role="group" aria-label="按歌手类型筛选"><button v-for="item in categories" :key="item.value" :class="{ active:artistType===item.value }" :aria-pressed="artistType===item.value" @click="chooseType(item.value)">{{ item.label }}</button></div>
    <form class="artists-toolbar" @submit.prevent="load(true)">
      <ElInput v-model="query" placeholder="搜索歌手、别名或拼音" aria-label="搜索歌手库" clearable />
      <select v-model="artworkFilter" class="native-field" aria-label="按封面状态筛选" @change="load(true)"><option value="">全部封面状态</option><option value="MISSING">还没有封面</option><option value="REVIEW">有候选待确认</option></select>
      <label class="song-count-filter">歌曲少于<input v-model.number="countLessThan" aria-label="歌曲数量小于" type="number" min="1" max="1000000" placeholder="不限" @change="load(true)" />首</label>
      <ElButton native-type="submit" :loading="loading">搜索</ElButton><ElButton v-if="hasFilters" text @click="clearFilters">重置</ElButton>
    </form>
    <div class="artists-selection" :class="{ 'has-selection':selectedCount }">
      <label class="page-selection"><input type="checkbox" :checked="allPageSelected" :disabled="!profiles.length || loading" @change="togglePage" />本页全选</label>
      <span v-if="selectedCount" class="selection-count">已选 <strong>{{ selectedCount }}</strong> 位<span class="selection-explanation"> · 跨页保留</span></span><span v-else class="collection-count">{{ loading ? '正在读取…' : `${hasFilters?'符合筛选':'曲库中共有'} ${total} 位歌手` }}</span>
      <div v-if="selectedCount" class="selection-actions"><ElButton size="small" @click="openArtwork()">批量获取封面</ElButton><ElButton size="small" @click="openCategories">标注歌手类型</ElButton><ElButton size="small" text @click="selected.clear()">取消选择</ElButton></div>
      <span v-else class="selection-tip">选择歌手，批量整理封面与分类</span>
    </div>
    <p v-if="loading && !profiles.length" class="artists-note" role="status">正在读取歌手…</p>
    <div v-else-if="!profiles.length" class="artists-empty"><span aria-hidden="true">♪</span><h2>{{ hasFilters?'没有找到符合条件的歌手':'扫描歌曲后，这里会自动出现歌手' }}</h2><p>{{ hasFilters?'试试调整类型、封面状态或搜索词。':'歌手来自歌曲信息，无需手动建档。先到歌曲来源添加目录并扫描。' }}</p><ElButton v-if="hasFilters" @click="clearFilters">查看全部歌手</ElButton><RouterLink v-else class="empty-source-link" to="/library/media">添加歌曲来源 →</RouterLink></div>
    <div v-else class="artists-grid" :aria-busy="loading">
      <article v-for="artist in profiles" :key="artist.id" class="artist-card" :class="{ selected:selected.has(artist.id) }">
        <div class="artist-banner">
          <button class="artist-open" :aria-label="`查看歌手 ${artist.name}`" @click="openArtist(artist)"><img v-if="usableImage(artist)" :src="imageURL(artist)" :alt="`${artist.name}封面`" loading="lazy" @error="failedImages.add(imageURL(artist))" /><img v-else-if="usableImage(artist,'background')" :src="imageURL(artist,'background')" alt="" loading="lazy" @error="failedImages.add(imageURL(artist,'background'))" /><span v-else class="artist-monogram" aria-hidden="true">{{ artist.name.slice(0,2) }}</span></button>
          <label class="artist-select" :class="{ checked:selected.has(artist.id) }"><input type="checkbox" :aria-label="`选择歌手 ${artist.name}`" :checked="selected.has(artist.id)" @change="toggleArtist(artist)" /></label>
          <span class="artist-type" :class="artist.type.toLowerCase()">{{ artistTypes[artist.type] }}</span>
        </div>
        <div class="artist-card-info"><button class="artist-name" @click="openArtist(artist)" :title="artist.name">{{ artist.name }}</button><p>{{ artist.count || 0 }} 首歌曲</p><small :class="{ attention:['NEEDS_REVIEW','FAILED'].includes(artist.portraitFetchState||'') }"><span class="state-dot" aria-hidden="true" />{{ coverCaption(artist) }}</small></div>
      </article>
    </div>
    <ElPagination v-if="total" class="artists-pagination" :current-page="page" :page-size="pageSize" :total="total" layout="total, prev, pager, next" @current-change="v=>{page=v;load()}" />
  </div>
  <ElDialog :model-value="!!profile" :title="profile?.name" width="min(940px, calc(100vw - 24px))" :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy" @close="close">
    <template v-if="profile">
      <ElAlert v-if="error" :title="error" type="error" :closable="false" show-icon />
      <div class="artist-detail-heading"><label>歌手类型<select :value="profile.type==='PERSON'?'UNKNOWN':profile.type" class="native-field" aria-label="设置歌手类型" :disabled="busy" @change="changeCategory(($event.target as HTMLSelectElement).value as Artist['type'])"><option value="UNKNOWN">待分类</option><option value="MALE">男歌手</option><option value="FEMALE">女歌手</option><option value="GROUP">乐队 / 组合</option></select></label><p>由你确认类型，未标注时保留为待分类。</p></div>
      <div class="artist-images"><button @click="imageKind='portrait'"><img v-if="usableImage(profile)" :src="imageURL(profile)" alt="歌手头像" @error="failedImages.add(imageURL(profile))" /><span v-else>{{ profile.name.slice(0,1) }}</span><small>上传 / 替换头像</small></button><button class="background" @click="imageKind='background'"><img v-if="usableImage(profile,'background')" :src="imageURL(profile,'background')" alt="歌手背景图" @error="failedImages.add(imageURL(profile,'background'))" /><img v-else-if="usableImage(profile)" class="derived-backdrop" :src="imageURL(profile)" alt="由歌手头像生成的背景预览" /><span v-else>{{ profile.name.slice(0,2) }}</span><em v-if="!profile.backgroundPath&&profile.portraitPath" class="derived-label">头像生成预览</em><small>上传 / 替换背景图</small></button></div>
      <div class="detail-artwork-actions"><ElButton :disabled="busy" type="primary" @click="openArtwork(profile)">选择平台获取封面</ElButton><ElButton :loading="busy" @click="retry">重试自动补图</ElButton><span>补齐缺少的图片，保留已有图片</span></div>
      <div class="artwork-status"><p><strong>头像</strong><span>{{ states[profile.portraitFetchState||'PENDING'] }}</span><ElButton v-if="profile.portraitFetchState==='NEEDS_REVIEW'" text type="primary" @click="openCandidates('portrait')">查看头像候选</ElButton></p><p><strong>背景</strong><span>{{ states[profile.backgroundFetchState||'PENDING'] }}</span><ElButton v-if="profile.backgroundFetchState==='NEEDS_REVIEW'" text type="primary" @click="openCandidates('background')">查看背景候选</ElButton></p></div>
      <p v-if="profile.portraitAttribution||profile.backgroundAttribution" class="artists-note">头像来源：{{ profile.portraitAttribution||'未获取' }}；背景来源：{{ profile.backgroundAttribution||'未获取' }}。<a v-if="profile.portraitSource" :href="profile.portraitSource" target="_blank" rel="noopener noreferrer">头像原图</a> <a v-if="profile.backgroundSource" :href="profile.backgroundSource" target="_blank" rel="noopener noreferrer">背景原图</a></p>
      <div class="artist-songs-heading"><h3>曲库中的歌曲 <span>{{ songTotal }}</span></h3><p>歌手署名有误时，订正歌曲信息后会自动同步。</p></div>
      <p v-if="songsLoading" class="artists-note" role="status">正在读取歌曲…</p>
      <ElTable v-else :data="songs" row-key="id"><ElTableColumn label="歌曲" min-width="150"><template #default="{row}">{{ row.metadata&&!row.metadata.stale&&row.metadata.fields.includes('title')?row.metadata.track.title:row.result.title }}</template></ElTableColumn><ElTableColumn prop="relativePath" label="文件" min-width="150" show-overflow-tooltip /><ElTableColumn label="操作" width="205"><template #default="{row}"><ElButton text type="primary" @click="editing=row as MediaSong">订正歌曲</ElButton><ElButton text :disabled="busy" @click="reset(row as MediaSong)">恢复歌手</ElButton></template></ElTableColumn></ElTable>
      <ElPagination :current-page="songPage" :page-size="12" :total="songTotal" layout="total, prev, pager, next" @current-change="v=>{songPage=v;loadSongs()}" />
    </template>
  </ElDialog>
  <ArtistArtworkDialog :open="artworkOpen" :selected-ids="artworkIds" :single-name="singleArtworkName" :filter="appliedFilter" :total="total" @close="artworkOpen=false" @created="batchCreated" />
  <ElDialog v-model="categoryOpen" title="标注歌手类型" width="min(480px, calc(100vw - 24px))" :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy"><div class="category-form"><p>将为已选的 <strong>{{ categoryTargets.length }} 位歌手</strong> 设置相同类型，包含跨页选择。请选择你已确认的类型。</p><select v-model="bulkType" class="native-field" aria-label="批量标注歌手类型" :disabled="busy"><option value="" disabled>选择歌手类型</option><option value="MALE">男歌手</option><option value="FEMALE">女歌手</option><option value="GROUP">乐队 / 组合</option><option value="UNKNOWN">待分类</option></select><ElAlert v-if="categoryError" :title="categoryError" type="error" :closable="false" /></div><template #footer><ElButton :disabled="busy" @click="categoryOpen=false">取消</ElButton><ElButton type="primary" :loading="busy" :disabled="!bulkType" @click="saveCategories">确认标注</ElButton></template></ElDialog>
  <ElDialog :model-value="!!candidateKind" title="确认歌手图片" width="min(660px, calc(100vw - 24px))" :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy" @close="candidateKind=null;candidateGeneration++"><p class="artists-note">请对照歌手及歌曲确认图片。选择后将缓存到 NAS。</p><div v-if="profile&&candidateKind" class="candidate-grid"><div v-for="candidate in candidates" :key="candidate.provider+candidate.externalId"><img v-if="candidateKind==='portrait'?candidate.portrait:candidate.background" :src="artists.candidatePreview(profile.id,candidateKind,candidate.provider,candidate.externalId)" alt="候选歌手图片" /><p>{{ candidate.attribution }} · {{ candidate.externalId }}</p><ElButton :disabled="busy" @click="selectCandidate(candidate.provider,candidate.externalId)">使用这张图片</ElButton></div></div><p v-if="!candidates.length">暂无候选图片</p></ElDialog>
  <ElDialog v-model="settingsOpen" title="背景图补充来源" width="min(500px, calc(100vw - 24px))" :close-on-click-modal="false" :before-close="closeSettings"><p class="artists-note">歌手封面可在「获取歌手封面」中按批次选择平台。自动背景图使用 TheAudioDB，Fanart.tv 可作为补充来源。</p><p class="artists-note">Fanart.tv 仅在找到可靠的 MusicBrainz 歌手身份时补充背景图，不影响头像获取。</p><p class="artists-note">Fanart.tv 个人密钥：{{ fanartConfigured?'已配置':'未配置' }}</p><ElInput v-model="fanartKey" type="password" autocomplete="new-password" placeholder="输入新密钥；留空则保持原设置" aria-label="Fanart.tv 个人 API 密钥" /><template #footer><ElButton v-if="fanartConfigured" :loading="settingsBusy" @click="saveSettings(true)">清除密钥</ElButton><ElButton :loading="settingsBusy" type="primary" @click="saveSettings()">保存</ElButton></template></ElDialog>
  <ArtistImageEditor v-if="profile&&imageKind" :key="profile.id+imageKind" :artist="profile" :kind="imageKind" @close="imageKind=null" @changed="imageChanged" />
  <SingleSongScrapeDialog :song="editing" @close="editing=null" @applied="changed" />
</section>
</template>

<style scoped>
.artists-page {
  display:grid;
  gap:24px;
  align-content:start;
  min-width:0;
}

.artists-browser {
  min-width:0;
}

.artists-types {
  display:flex;
  gap:28px;
  border-bottom:1px solid var(--sv-border-subtle);
  margin-bottom:20px;
  overflow-x:auto;
}

.artists-types button {
  min-height:44px;
  position:relative;
  flex:0 0 auto;
  background:none;
  border:0;
  padding:4px 0 16px;
  font-size:15px;
  color:var(--sv-text-secondary);
  cursor:pointer;
  white-space:nowrap;
}

.artists-types button.active {
  color:var(--sv-accent-primary);
  font-weight:650;
}

.artists-types button.active::after {
  content:'';
  position:absolute;
  bottom:0;
  left:0;
  right:0;
  height:3px;
  background:var(--sv-accent-primary);
}

.artists-toolbar {
  display:flex;
  align-items:center;
  gap:12px;
  flex-wrap:wrap;
}

.artists-toolbar>.el-input {
  flex:1 1 240px;
  max-width:420px;
}

.artists-toolbar>.native-field {
  width:auto;
  min-width:150px;
  height:40px;
}

.song-count-filter {
  display:flex;
  align-items:center;
  gap:7px;
  white-space:nowrap;
  font-size:12px;
  color:var(--sv-text-secondary);
}

.song-count-filter input {
  width:74px;
  height:40px;
  padding:0 10px;
  background:var(--sv-surface-2);
  border:1px solid var(--sv-border-default);
  border-radius:8px;
  color:var(--sv-text-primary);
}

.artists-selection {
  display:flex;
  align-items:center;
  gap:18px;
  min-height:64px;
  padding:12px 0;
  margin:8px 0 10px;
  font-size:12px;
  color:var(--sv-text-secondary);
  flex-wrap:wrap;
}

.artists-selection.has-selection {
  padding:12px 14px;
  background:var(--sv-accent-soft);
  border-radius:8px;
}

.page-selection {
  display:flex;
  align-items:center;
  gap:8px;
  cursor:pointer;
  white-space:nowrap;
}

.page-selection input,.artist-select input {
  width:18px;
  height:18px;
  margin:0;
  accent-color:var(--sv-accent-primary);
  cursor:pointer;
}

.selection-count strong {
  color:var(--sv-accent-primary);
}

.selection-actions {
  display:flex;
  gap:6px;
  flex-wrap:wrap;
  margin-left:auto;
}

.selection-actions .el-button+.el-button {
  margin-left:0;
}

.selection-tip {
  margin-left:auto;
}

.artists-grid {
  display:grid;
  grid-template-columns:repeat(5,minmax(0,1fr));
  gap:24px 20px;
}

.artist-card {
  min-width:0;
  border-radius:12px;
  transition:background .16s;
}

.artist-card.selected {
  background:var(--sv-accent-soft);
  box-shadow:0 0 0 8px var(--sv-accent-soft);
}

.artist-banner {
  position:relative;
  aspect-ratio:1;
  border-radius:10px;
  overflow:hidden;
  background:#d4dccb;
}

.artist-card:nth-child(4n+2) .artist-banner {
  background:#dfcdba;
}

.artist-card:nth-child(4n+3) .artist-banner {
  background:#cbd8db;
}

.artist-card:nth-child(4n+4) .artist-banner {
  background:#dfd6cf;
}

.artist-open {
  width:100%;
  height:100%;
  display:grid;
  place-items:center;
  padding:0;
  border:0;
  background:none;
  color:#456251;
  cursor:pointer;
  overflow:hidden;
}

.artist-open img {
  width:100%;
  height:100%;
  object-fit:cover;
  transition:transform .2s;
}

.artist-open:hover img {
  transform:scale(1.025);
}

.artist-monogram {
  font-family:var(--sv-font-display,Georgia,serif);
  font-size:clamp(40px,5vw,76px);
  letter-spacing:.04em;
  font-weight:400;
  opacity:.72;
}

.artist-select {
  width:44px;
  height:44px;
  box-sizing:border-box;
  position:absolute;
  left:10px;
  top:10px;
  padding:8px;
  background:#fffef3ed;
  display:grid;
  place-items:center;
  border:1px solid #ffffffbb;
  border-radius:7px;
  cursor:pointer;
}

.artist-select.checked {
  background:#f1f5ea;
}

.artist-type {
  position:absolute;
  right:10px;
  bottom:10px;
  padding:5px 9px;
  border-radius:5px;
  background:#fafaf3f2;
  color:#475e50;
  font-size:11px;
  line-height:1.5;
  pointer-events:none;
  font-weight:550;
}

.artist-type.female {
  color:#804d3d;
  background:#fff6eef5;
}

.artist-type.group {
  color:#375b70;
  background:#eff7fcf5;
}

.artist-type.unknown,.artist-type.person {
  color:#626961;
  background:#f4f3edf2;
}

.artist-card-info {
  padding:15px 2px 6px;
  min-width:0;
}

.artist-name {
  text-align:left;
  font-size:17px;
  font-weight:620;
  line-height:1.5;
  display:-webkit-box;
  -webkit-line-clamp:2;
  -webkit-box-orient:vertical;
  overflow:hidden;
  overflow-wrap:anywhere;
  margin:0;
  padding:0;
  border:0;
  background:none;
  color:var(--sv-text-primary);
  cursor:pointer;
}

.artist-name:hover {
  color:var(--sv-accent-primary);
}

.artist-card-info>p {
  font-size:12px;
  margin:5px 0 9px;
  color:var(--sv-text-secondary);
}

.artist-card-info>small {
  display:flex;
  gap:6px;
  align-items:flex-start;
  color:var(--sv-text-secondary);
  font-size:11px;
  line-height:1.7;
  overflow-wrap:anywhere;
}

.state-dot {
  width:5px;
  height:5px;
  flex:0 0 5px;
  margin-top:6px;
  border-radius:50%;
  background:#7f917d;
}

.artist-card-info>.attention {
  color:#92632b;
}

.attention .state-dot {
  background:#a5732d;
}

.artists-pagination {
  margin-top:30px;
  justify-content:center;
}

.artists-empty {
  display:grid;
  justify-items:center;
  text-align:center;
  padding:65px 20px 80px;
  border:1px dashed var(--sv-border-default);
  border-radius:12px;
  background:var(--sv-surface-1);
}

.artists-empty>span {
  font-size:52px;
  color:var(--sv-accent-primary);
  opacity:.65;
}

.artists-empty h2 {
  font-size:19px;
  font-weight:550;
  margin:18px 0 10px;
  line-height:1.6;
}

.artists-empty p {
  font-size:13px;
  color:var(--sv-text-secondary);
  line-height:1.8;
  margin:0 0 20px;
}

.empty-source-link {
  color:var(--sv-accent-primary);
  font-size:13px;
  text-decoration:none;
}

.artists-note {
  font-size:12px;
  color:var(--sv-text-secondary);
  line-height:1.8;
  overflow-wrap:anywhere;
}

.artist-detail-heading {
  display:flex;
  gap:20px;
  align-items:center;
  flex-wrap:wrap;
  margin-bottom:20px;
}

.artist-detail-heading label {
  display:flex;
  align-items:center;
  gap:12px;
  font-size:13px;
}

.artist-detail-heading .native-field {
  min-width:140px;
}

.artist-detail-heading p {
  font-size:12px;
  color:var(--sv-text-secondary);
  margin:0;
}

.artist-images {
  display:grid;
  grid-template-columns:1fr 1.8fr;
  gap:18px;
}

.artist-images button {
  position:relative;
  aspect-ratio:1;
  border:0;
  border-radius:12px;
  overflow:hidden;
  display:grid;
  place-items:center;
  background:#d4dccb;
  color:#60755b;
  padding:0;
  cursor:pointer;
  font-size:48px;
}

.artist-images button.background {
  aspect-ratio:auto;
  background:#ccd7d5;
}

.artist-images img {
  position:absolute;
  width:100%;
  height:100%;
  object-fit:cover;
}

.artist-images .derived-backdrop {
  filter:blur(15px);
  transform:scale(1.1);
  opacity:.7;
}

.artist-images small {
  position:absolute;
  inset:auto 0 0;
  padding:16px 8px;
  background:linear-gradient(transparent,#15271dd9);
  color:#fff;
  text-align:center;
  font-size:12px;
}

.derived-label {
  position:absolute;
  top:12px;
  right:12px;
  padding:5px 8px;
  font-size:10px;
  color:#fff;
  background:#284332b0;
  border-radius:4px;
  font-style:normal;
}

.detail-artwork-actions {
  display:flex;
  align-items:center;
  gap:10px;
  flex-wrap:wrap;
  margin:20px 0 14px;
}

.detail-artwork-actions .el-button+.el-button {
  margin-left:0;
}

.detail-artwork-actions>span {
  font-size:12px;
  color:var(--sv-text-secondary);
}

.artwork-status {
  display:grid;
  gap:0;
  background:var(--sv-surface-1);
  border-radius:8px;
  padding:8px 14px;
}

.artwork-status p {
  display:flex;
  align-items:center;
  gap:14px;
  margin:0;
  min-height:36px;
  font-size:12px;
  flex-wrap:wrap;
}

.artwork-status strong {
  font-weight:550;
}

.artwork-status span {
  color:var(--sv-text-secondary);
}

.artist-songs-heading {
  display:flex;
  justify-content:space-between;
  align-items:center;
  gap:16px;
  flex-wrap:wrap;
  margin:28px 0 12px;
}

.artist-songs-heading h3 {
  font-size:15px;
  margin:0;
}

.artist-songs-heading h3 span {
  font-size:12px;
  font-weight:400;
  margin-left:8px;
  color:var(--sv-text-secondary);
}

.artist-songs-heading p {
  font-size:12px;
  color:var(--sv-text-secondary);
  margin:0;
}

.candidate-grid {
  display:grid;
  grid-template-columns:repeat(2,minmax(0,1fr));
  gap:18px;
  margin-top:16px;
}

.candidate-grid>div {
  min-width:0;
  display:grid;
  align-content:start;
  gap:10px;
}

.candidate-grid img {
  width:100%;
  aspect-ratio:1;
  object-fit:cover;
  border-radius:8px;
  background:var(--sv-surface-1);
}

.candidate-grid p {
  font-size:12px;
  overflow-wrap:anywhere;
  margin:0;
}

.category-form {
  display:grid;
  gap:20px;
}

.category-form p {
  margin:0;
  font-size:13px;
  line-height:1.9;
  color:var(--sv-text-secondary);
}

.category-form strong {
  color:var(--sv-text-primary);
}

@media(min-width:1600px) {
  .artists-grid {
    grid-template-columns:repeat(6,minmax(0,1fr));
    gap:28px 22px;
  }
}

@media(max-width:1100px) {
  .artists-grid {
    grid-template-columns:repeat(4,minmax(0,1fr));
  }
  .selection-tip {
    display:none;
  }
}

@media(max-width:820px) {
  .artists-grid {
    grid-template-columns:repeat(3,minmax(0,1fr));
    gap:24px 16px;
  }
  .artists-toolbar>.el-input {
    max-width:none;
  }
  .selection-actions {
    width:100%;
    margin-left:0;
  }
  .artists-types {
    gap:24px;
  }
  .artist-songs-heading {
    display:block;
  }
  .artist-songs-heading p {
    margin-top:8px;
  }
}

@media(max-width:540px) {
  .artists-page {
    gap:20px;
  }
  .artists-types {
    gap:22px;
    margin-bottom:16px;
  }
  .artists-types button {
    font-size:13px;
    padding-bottom:13px;
  }
  .artists-grid {
    grid-template-columns:repeat(2,minmax(0,1fr));
    gap:22px 14px;
  }
  .artists-toolbar {
    gap:10px;
  }
  .artists-toolbar>.el-input {
    flex-basis:100%;
  }
  .artists-toolbar>.native-field {
    min-width:0;
    width:calc(50% - 5px);
  }
  .song-count-filter {
    width:calc(50% - 5px);
    font-size:11px;
    gap:5px;
  }
  .song-count-filter input {
    width:58px;
  }
  .artists-selection {
    gap:12px;
    font-size:11px;
    min-height:60px;
    margin-bottom:6px;
  }
  .selection-explanation {
    display:none;
  }
  .selection-actions {
    gap:5px;
  }
  .selection-actions>.el-button {
    font-size:11px;
    padding-left:9px;
    padding-right:9px;
  }
  .artist-name {
    font-size:15px;
  }
  .artist-card-info {
    padding-top:12px;
  }
  .artist-select {
    left:8px;
    top:8px;
    padding:6px;
  }
  .artist-type {
    right:8px;
    bottom:8px;
    font-size:10px;
    padding:4px 7px;
  }
  .artist-monogram {
    font-size:48px;
  }
  .artist-card-info>small {
    font-size:10px;
  }
  .artist-images {
    gap:10px;
    grid-template-columns:1fr 1.3fr;
  }
  .artist-images button {
    border-radius:8px;
  }
  .artist-detail-heading {
    gap:10px;
  }
  .detail-artwork-actions>span {
    width:100%;
  }
  .artist-images small {
    font-size:11px;
    padding:14px 4px 10px;
  }
  .candidate-grid {
    gap:12px;
  }
  .candidate-grid .el-button {
    padding-left:10px;
    padding-right:10px;
  }
}

@media(prefers-reduced-motion:reduce) {
  .artist-card,.artist-open img {
    transition:none;
  }
  .artist-open:hover img {
    transform:none;
  }
}
</style>

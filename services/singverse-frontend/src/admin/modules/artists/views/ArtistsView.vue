<script setup lang="ts">
import PageHeader from '../../../shared/ui/PageHeader.vue'
import { onBeforeUnmount,onMounted,ref } from 'vue'
import { ElAlert,ElButton,ElDialog,ElInput,ElPagination,ElTable,ElTableColumn,ElMessageBox } from 'element-plus'
import { artists } from '../api/artists'
import { artistTypes, type Artist, type ImageKind } from '../types'
import type { MediaSong } from '../../media-library/types'
import ArtistImageEditor from '../components/ArtistImageEditor.vue'
import SingleSongScrapeDialog from '../../metadata/components/SingleSongScrapeDialog.vue'
const failedImages=ref(new Set<string>())
const query=ref(''),artistType=ref(''),countLessThan=ref<number|null>(null),page=ref(1),total=ref(0),profiles=ref<Artist[]>([]),profile=ref<Artist|null>(null),songs=ref<MediaSong[]>([]),songPage=ref(1),songTotal=ref(0),editing=ref<MediaSong|null>(null),imageKind=ref<ImageKind|null>(null),loading=ref(false),busy=ref(false),error=ref('')
const settingsOpen=ref(false),fanartKey=ref(''),fanartConfigured=ref(false),settingsBusy=ref(false)
const candidateKind=ref<ImageKind|null>(null),candidates=ref<Array<{provider:string;externalId:string;attribution:string;portrait:string;background:string}>>([])
async function openCandidates(kind:ImageKind){if(!profile.value)return;try{candidates.value=await artists.candidates(profile.value.id,kind);candidateKind.value=kind}catch(e){failure(e)}}
async function selectCandidate(provider:string,externalId:string){if(!profile.value||!candidateKind.value)return;busy.value=true;try{const next=await artists.selectCandidate(profile.value.id,candidateKind.value,provider,externalId);candidateKind.value=null;await imageChanged(next)}catch(e){failure(e)}finally{busy.value=false}}
async function openSettings(){try{fanartConfigured.value=(await artists.artworkSettings()).fanartConfigured;fanartKey.value='';settingsOpen.value=true}catch(e){failure(e)}}
async function saveSettings(clear=false){settingsBusy.value=true;try{fanartConfigured.value=(await artists.saveArtworkSettings(clear?null:fanartKey.value,clear)).fanartConfigured;fanartKey.value='';settingsOpen.value=false}catch(e){failure(e)}finally{settingsBusy.value=false}}
const states:Record<string,string>={PENDING:'正在等待获取图片',RETRY:'暂未获取成功，稍后自动重试',FAILED:'图片获取失败，可重新获取或上传',NO_MATCH:'没有可靠匹配，可自行上传',NEEDS_REVIEW:'找到多个候选，请确认',READY:'图片已就绪',DORMANT:'暂无关联歌曲'}
let active=true,generation=0,detailGeneration=0
function failure(e:unknown){if(active)error.value=e instanceof Error?e.message:'读取失败，请重试'}
async function load(reset=false){if(reset)page.value=1;const epoch=++generation;loading.value=true;error.value='';try{const result=await artists.list(query.value,page.value-1,12,artistType.value,typeof countLessThan.value === 'number' && Number.isInteger(countLessThan.value) && countLessThan.value > 0 ? countLessThan.value : null);if(active&&epoch===generation){profiles.value=result.items;total.value=result.totalElements}}catch(e){failure(e)}finally{if(active&&epoch===generation)loading.value=false}}
async function loadSongs(){if(!profile.value)return;const id=profile.value.id,epoch=++detailGeneration;try{const result=await artists.songs(id,songPage.value-1);if(active&&epoch===detailGeneration&&profile.value?.id===id){songs.value=result.items;songTotal.value=result.totalElements}}catch(e){failure(e)}}
async function openArtist(artist:Artist){profile.value=artist;songPage.value=1;await loadSongs()}
function close(){profile.value=null;imageKind.value=null;detailGeneration++}
async function changed(){editing.value=null;await load();if(!profile.value)return;try{const next=await artists.get(profile.value.id);if(!active)return;if(!next.count){close();return}profile.value=next;if((songPage.value-1)*12>=(next.count||0))songPage.value=Math.max(1,Math.ceil((next.count||0)/12));await loadSongs()}catch(e){failure(e)}}
async function imageChanged(next:Artist){if(profile.value?.id===next.id)profile.value={...profile.value,...next};await load()}
async function changeCategory(type:Artist['type']){if(!profile.value||busy.value)return;busy.value=true;try{const next=await artists.category(profile.value.id,type,profile.value.revision);profile.value=next;await load(true)}catch(e){failure(e)}finally{busy.value=false}}
async function retry(){if(!profile.value||busy.value)return;busy.value=true;try{await artists.retry(profile.value.id);if(active){profile.value={...profile.value,fetchState:'PENDING',portraitFetchState:'PENDING',backgroundFetchState:'PENDING'};await load()}}catch(e){failure(e)}finally{if(active)busy.value=false}}
async function reset(song:MediaSong){if(busy.value)return;try{const state=await artists.override(song.id);if(!state.overridden){error.value='这首歌没有 NAS 歌手订正';return}await ElMessageBox.confirm('恢复扫描的歌手信息？原文件不会修改。','恢复扫描值',{confirmButtonText:'恢复',cancelButtonText:'保留订正',autofocus:false});if(!active)return;busy.value=true;await artists.reset(song.id,state.revision);await changed()}catch(e){if(e!=='cancel'&&e!=='close')failure(e)}finally{if(active)busy.value=false}}
let timer:ReturnType<typeof setInterval>|undefined,polling=false
async function refreshImages(){if(polling||loading.value||busy.value||editing.value||imageKind.value||!profiles.value.some(p=>p.fetchState==='PENDING'||p.fetchState==='RETRY'))return;polling=true;const epoch=generation,id=profile.value?.id;try{const result=await artists.list(query.value,page.value-1,12,artistType.value,typeof countLessThan.value === 'number' && Number.isInteger(countLessThan.value) && countLessThan.value > 0 ? countLessThan.value : null);if(active&&epoch===generation){profiles.value=result.items;total.value=result.totalElements;if(id&&profile.value?.id===id){const next=result.items.find(p=>p.id===id);if(next)profile.value=next}}}catch(e){failure(e)}finally{polling=false}}
onMounted(()=>{void load();timer=setInterval(()=>void refreshImages(),8000)});onBeforeUnmount(()=>{active=false;generation++;detailGeneration++;if(timer)clearInterval(timer)})
</script>
<template>
<section class="artists-page">
<PageHeader class="artists-heading" title="熟悉的声音" description="根据歌曲署名自动生成，首次扫描后自动获取图片。歌手有误时在歌曲中订正，歌手库会同步更新。"><template #actions><ElButton @click="openSettings">图片来源设置</ElButton></template></PageHeader>
<ElAlert v-if="error" :title="error" type="error" :closable="false" />
<div class="artists-toolbar"><ElInput v-model="query" placeholder="搜索歌手或拼音" aria-label="搜索歌手库" clearable @keyup.enter="load(true)"/><label>歌手类型<select v-model="artistType" aria-label="按歌手类型筛选" @change="load(true)"><option value="">全部类型</option><option value="MALE">男歌手</option><option value="FEMALE">女歌手</option><option value="GROUP">乐队 / 组合</option><option value="UNKNOWN">未分类</option></select></label><label>歌曲少于<input v-model.number="countLessThan" aria-label="歌曲数量小于" type="number" min="1" max="1000000" placeholder="不限" @change="load(true)"/>首</label><ElButton :loading="loading" @click="load(true)">搜索 / 刷新</ElButton></div>
<p v-if="loading" class="artists-note" role="status">正在读取歌手…</p>
<div v-else-if="!profiles.length" class="artists-empty"><span>♪</span><h2>{{query||artistType||countLessThan!==null?'没有找到符合条件的歌手':'扫描歌曲后，这里会自动出现歌手'}}</h2><p>歌手来自歌曲信息，无需手动建档或关联。</p></div>
<div v-else class="artists-grid"><button v-for="artist in profiles" :key="artist.id" class="artist-card" @click="openArtist(artist)"><div class="artist-banner" :class="{'has-derived-backdrop':!artist.backgroundPath&&!!artist.portraitPath}"><img v-if="artist.backgroundPath&&!failedImages.has(artist.backgroundPath+artist.backgroundVersion)" @error="failedImages.add(artist.backgroundPath+artist.backgroundVersion)" :src="`${artist.backgroundPath}?v=${artist.backgroundVersion}`" alt=""/><img v-else-if="artist.portraitPath&&!failedImages.has(artist.portraitPath+artist.portraitVersion)" @error="failedImages.add(artist.portraitPath+artist.portraitVersion)" class="derived-backdrop" :src="`${artist.portraitPath}?v=${artist.portraitVersion}`" alt=""/><span v-else class="artist-monogram">{{artist.name.slice(0,2)}}</span></div><div class="artist-card-info"><div class="artist-avatar"><img v-if="artist.portraitPath&&!failedImages.has(artist.portraitPath+artist.portraitVersion)" @error="failedImages.add(artist.portraitPath+artist.portraitVersion)" :src="`${artist.portraitPath}?v=${artist.portraitVersion}`" :alt="`${artist.name}头像`"/><span v-else>{{artist.name.slice(0,1)}}</span></div><h2>{{artist.name}}</h2><p>{{artist.count||0}} 首歌曲 · {{artistTypes[artist.type]}}</p><small>{{artist.portraitPath?(artist.portraitOrigin==='UPLOAD'?'已上传头像':'自动获取头像'):states[artist.portraitFetchState||'PENDING']}}</small></div></button></div>
<ElPagination v-if="total" :current-page="page" :page-size="12" :total="total" layout="total, prev, pager, next" @current-change="v=>{page=v;load()}"/>
<ElDialog :model-value="!!profile" :title="profile?.name" width="min(900px, calc(100vw - 32px))" :close-on-click-modal="false" @close="close"><template v-if="profile"><div class="artist-category"><label>歌手类型<select :value="profile.type === 'PERSON' ? 'UNKNOWN' : profile.type" aria-label="设置歌手类型" :disabled="busy" @change="changeCategory(($event.target as HTMLSelectElement).value as Artist['type'])"><option value="UNKNOWN">未分类</option><option value="MALE">男歌手</option><option value="FEMALE">女歌手</option><option value="GROUP">乐队 / 组合</option></select></label><span>按已标注类型筛选，未标注歌手不会自动猜测性别。</span></div><p class="artists-note">歌手及关联歌曲自动同步。发现署名错误，请点“订正歌曲”，无需维护歌手档案。</p><div class="artist-images"><button @click="imageKind='portrait'"><img v-if="profile.portraitPath" :src="`${profile.portraitPath}?v=${profile.portraitVersion}`" alt="歌手头像"/><span v-else>{{profile.name.slice(0,1)}}</span><small>上传 / 替换头像</small></button><button class="background" @click="imageKind='background'"><img v-if="profile.backgroundPath" :src="`${profile.backgroundPath}?v=${profile.backgroundVersion}`" alt="歌手背景图"/><img v-else-if="profile.portraitPath" class="derived-backdrop" :src="`${profile.portraitPath}?v=${profile.portraitVersion}`" alt="由歌手头像生成的背景预览"/><span v-else>{{profile.name.slice(0,2)}}</span><em v-if="!profile.backgroundPath&&profile.portraitPath" class="derived-label">头像生成预览</em><small>上传 / 替换背景图</small></button></div><p v-if="profile.portraitAttribution||profile.backgroundAttribution" class="artists-note">头像来源：{{profile.portraitAttribution||'未获取'}}；背景来源：{{profile.backgroundAttribution||'未获取'}}。<a v-if="profile.portraitSource" :href="profile.portraitSource" target="_blank" rel="noopener noreferrer">头像原图</a> <a v-if="profile.backgroundSource" :href="profile.backgroundSource" target="_blank" rel="noopener noreferrer">背景原图</a></p><div class="artists-toolbar"><p class="artists-note" role="status">头像：{{states[profile.portraitFetchState||'PENDING']}}；背景：{{states[profile.backgroundFetchState||'PENDING']}}</p><ElButton v-if="profile.portraitFetchState==='NEEDS_REVIEW'" @click="openCandidates('portrait')">查看头像候选</ElButton><ElButton v-if="profile.backgroundFetchState==='NEEDS_REVIEW'" @click="openCandidates('background')">查看背景候选</ElButton><ElButton :loading="busy" @click="retry">重新获取图片</ElButton></div><ElTable :data="songs" row-key="id"><ElTableColumn label="歌曲" min-width="160"><template #default="{row}">{{row.metadata&&!row.metadata.stale&&row.metadata.fields.includes('title')?row.metadata.track.title:row.result.title}}</template></ElTableColumn><ElTableColumn prop="relativePath" label="文件" min-width="180" show-overflow-tooltip/><ElTableColumn label="操作" width="210"><template #default="{row}"><ElButton text type="primary" @click="editing=row as MediaSong">订正歌曲</ElButton><ElButton text :disabled="busy" @click="reset(row as MediaSong)">恢复歌手</ElButton></template></ElTableColumn></ElTable><ElPagination :current-page="songPage" :page-size="12" :total="songTotal" layout="total, prev, pager, next" @current-change="v=>{songPage=v;loadSongs()}"/></template></ElDialog>
<ElDialog :model-value="!!candidateKind" @close="candidateKind=null" title="确认歌手图片" width="min(600px, calc(100vw - 32px))"><p class="artists-note">请对照歌手及歌曲确认图片。选择后将缓存到 NAS。</p><div v-if="profile&&candidateKind" class="candidate-grid"><div v-for="candidate in candidates" :key="candidate.provider+candidate.externalId"><img v-if="(candidateKind==='portrait'?candidate.portrait:candidate.background)" :src="artists.candidatePreview(profile.id,candidateKind,candidate.provider,candidate.externalId)" alt="候选歌手图片"/><p>{{candidate.attribution}} · {{candidate.externalId}}</p><ElButton :disabled="busy" @click="selectCandidate(candidate.provider,candidate.externalId)">使用这张图片</ElButton></div></div><p v-if="!candidates.length">暂无候选图片</p></ElDialog>
<ElDialog v-model="settingsOpen" title="歌手图片来源" width="min(480px, calc(100vw - 32px))"><p class="artists-note">头像优先使用已启用的网易云和 QQ 音乐，可在「添加与整理 → 歌曲信息」中选择平台。TheAudioDB 自动作为补充来源。</p><p class="artists-note">Fanart.tv 仅在找到可靠的 MusicBrainz 歌手身份时补充背景图，不影响头像获取。</p><p class="artists-note">Fanart.tv 个人密钥：{{fanartConfigured?'已配置':'未配置'}}</p><ElInput v-model="fanartKey" type="password" show-password autocomplete="new-password" placeholder="输入新密钥；留空则保持原设置" aria-label="Fanart.tv 个人 API 密钥"/><template #footer><ElButton v-if="fanartConfigured" :loading="settingsBusy" @click="saveSettings(true)">清除密钥</ElButton><ElButton :loading="settingsBusy" type="primary" @click="saveSettings()">保存</ElButton></template></ElDialog>
<ArtistImageEditor v-if="profile&&imageKind" :key="profile.id+imageKind" :artist="profile" :kind="imageKind" @close="imageKind=null" @changed="imageChanged"/>
<SingleSongScrapeDialog :song="editing" @close="editing=null" @applied="changed"/>
</section>
</template>
<style scoped>
.artists-page {
  display: grid;
  gap: 24px;
  align-content: start;
  min-width: 0
}

.artists-heading {
  margin-bottom: 6px
}

.artists-note {
  font-size: 12px;
  color: var(--sv-text-secondary);
  line-height: 1.8
}

.artists-toolbar {
  display: flex;
  gap: 14px;
  align-items: center;
  flex-wrap: wrap;
  padding: 18px;
  background: var(--sv-surface-1);
  border: 1px solid var(--sv-border-subtle);
  border-radius: 12px
}

.artists-toolbar>.el-input {
  max-width: 360px;
  flex: 1 1 240px
}

.artists-toolbar label,
.artist-category label {
  display: flex;
  gap: 8px;
  align-items: center;
  font-size: 12px;
  color: var(--sv-text-secondary)
}

.artists-toolbar input[type=number] {
  width: 88px;
  height: 38px;
  padding: 0 10px;
  border: 1px solid var(--sv-border-default);
  border-radius: 7px;
  background: white;
  color: var(--sv-text-primary)
}

.artists-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 22px
}

.artist-card {
  padding: 0;
  text-align: left;
  background: var(--sv-surface-2);
  color: var(--sv-text-primary);
  border: 1px solid var(--sv-border-subtle);
  border-radius: 12px;
  overflow: hidden;
  cursor: pointer;
  transition: box-shadow .18s, border-color .18s
}

.artist-card:hover {
  box-shadow: var(--sv-shadow-md);
  border-color: var(--sv-border-default)
}

.artist-banner {
  position: relative;
  aspect-ratio: 16/10;
  background: #ced7c4;
  overflow: hidden
}

.artist-card:nth-child(3n+2) .artist-banner {
  background: #d8c1ac
}

.artist-card:nth-child(3n+3) .artist-banner {
  background: #c0cdd1
}

.artist-banner>img {
  width: 100%;
  height: 100%;
  object-fit: cover
}

.artist-monogram {
  position: absolute;
  right: 14px;
  bottom: -15px;
  font: 94px var(--sv-font-editorial);
  letter-spacing: 8px;
  color: #304b3b28
}

.artist-card-info {
  padding: 0 20px 22px;
  position: relative
}

.artist-avatar {
  width: 64px;
  height: 64px;
  margin-top: -32px;
  margin-bottom: 14px;
  border-radius: 50%;
  border: 4px solid white;
  background: #e1e6d7;
  color: var(--sv-accent-primary);
  display: grid;
  place-items: center;
  overflow: hidden;
  position: relative;
  font: 24px var(--sv-font-editorial)
}

.artist-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover
}

.artist-card h2 {
  font-size: 18px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap
}

.artist-card p {
  font-size: 12px;
  color: var(--sv-text-secondary);
  margin: 8px 0
}

.artist-card small {
  display: block;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  font-size: 11px;
  color: var(--sv-text-tertiary)
}

.artists-empty {
  text-align: center;
  padding: 60px 20px;
  border: 1px dashed var(--sv-border-default);
  border-radius: 12px
}

.artists-empty>span {
  font-size: 40px;
  color: var(--sv-border-strong)
}

.artists-empty h2 {
  font: 24px var(--sv-font-editorial);
  margin: 18px 0 12px
}

.artists-empty p {
  font-size: 13px;
  color: var(--sv-text-secondary)
}

.artist-category {
  display: flex;
  gap: 14px;
  align-items: center;
  flex-wrap: wrap;
  margin-bottom: 18px
}

.artist-category>span {
  font-size: 11px;
  color: var(--sv-text-secondary)
}

.artist-images {
  display: flex;
  gap: 20px;
  margin: 24px 0
}

.artist-images button {
  position: relative;
  border: 0;
  border-radius: 12px;
  overflow: hidden;
  width: 150px;
  aspect-ratio: 1;
  background: #ced7c4;
  color: var(--sv-accent-primary);
  cursor: pointer;
  font: 40px var(--sv-font-editorial)
}

.artist-images button.background {
  width: 267px;
  aspect-ratio: 16/9
}

.artist-images img {
  width: 100%;
  height: 100%;
  object-fit: cover
}

.artist-banner .derived-backdrop,
.artist-images .derived-backdrop {
  filter: blur(16px) brightness(.6) saturate(.7);
  transform: scale(1.18)
}

.artist-images .derived-label {
  position: absolute;
  top: 9px;
  right: 9px;
  padding: 4px 7px;
  border-radius: 5px;
  background: #203227b8;
  color: white;
  font-size: 10px;
  font-style: normal
}

.artist-images small {
  position: absolute;
  inset: auto 0 0;
  background: #203227d9;
  padding: 10px;
  color: white;
  font: 12px var(--sv-font-sans)
}

.candidate-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 18px
}

.candidate-grid img {
  width: 100%;
  aspect-ratio: 16/9;
  object-fit: contain;
  background: var(--sv-surface-3);
  border-radius: 8px
}

.candidate-grid p {
  font-size: 12px;
  overflow-wrap: anywhere
}

.artists-page>.el-pagination {
  justify-content: flex-end
}

@media(max-width:1100px) {
  .artists-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr))
  }
}

@media(max-width:800px) {
  .artists-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 18px
  }
}

@media(max-width:480px) {
  .artists-grid {
    gap: 12px
  }

  .artist-card-info {
    padding: 0 12px 16px
  }

  .artist-avatar {
    width: 46px;
    height: 46px;
    border-width: 3px;
    margin-top: -23px;
    margin-bottom: 9px;
    font-size: 18px
  }

  .artist-card h2 {
    font-size: 15px
  }

  .artist-card p {
    font-size: 10px;
    line-height: 1.8
  }

  .artist-card small {
    font-size: 9px
  }

  .artist-monogram {
    font-size: 54px
  }

  .artists-toolbar {
    padding: 12px;
    gap: 10px
  }

  .artists-toolbar>.el-input {
    max-width: none;
    flex-basis: 100%
  }

  .artists-toolbar label {
    font-size: 11px
  }

  .artists-toolbar select {
    max-width: 120px
  }

  .artist-images {
    gap: 10px
  }

  .artist-images button {
    width: 32%
  }

  .artist-images button.background {
    width: 62%
  }

  .artist-category select {
    max-width: 130px
  }
}
</style>

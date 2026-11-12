<script setup lang="ts">
import { Headset as SongPlaceholderIcon } from '@element-plus/icons-vue'
import { requestId as newRequestId } from '../../../shared/request-id'
import PageHeader from '../../shared/ui/PageHeader.vue'
import { computed,onBeforeUnmount,onMounted,reactive,ref } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { ElIcon, ElAlert,ElButton,ElDialog,ElInput,ElMessageBox,ElPagination } from 'element-plus'
import { playlists,type Playlist,type Song,type Draft } from './api'
import { ai } from '../ai-settings/api/ai'
import type { AiService } from '../ai-settings/types'
import SongPicker from '../classification/components/ClassificationSongPicker.vue'
const coverFailures=ref(new Set<string>())
const items=ref<Playlist[]>([]),query=ref(''),sort=ref('UPDATED_DESC'),page=ref(1),total=ref(0),loading=ref(false),busy=ref(false),error=ref(''),open=ref(false),picker=ref(false),current=ref<Playlist|null>(null),songs=ref<Record<string,Song>>({}),models=ref<AiService[]>([]),proposal=ref<Draft|null>(null),generating=ref(false)
const form=reactive({requestId:'',name:'',description:'',songIds:[] as string[],revision:0}),generation=reactive({prompt:'',q:'',limit:20,modelServiceId:''})
const originalForm=ref('')
const formValue=()=>JSON.stringify({name:form.name,description:form.description,songIds:form.songIds})
const dirty=computed(()=>open.value&&formValue()!==originalForm.value)
function beforeUnload(event:BeforeUnloadEvent){if(dirty.value||busy.value||generating.value){event.preventDefault();event.returnValue=''}}
async function leave(){
  if(busy.value||generating.value){error.value='正在处理歌单，请稍候再离开。';return false}
  if(!dirty.value)return true
  try{await ElMessageBox.confirm('离开将放弃尚未保存的歌单编辑。','有未保存的歌单',{confirmButtonText:'放弃编辑',cancelButtonText:'继续编辑',autofocus:false});return true}catch{return false}
}
onBeforeRouteLeave(leave)
let active=true,epoch=0
function failure(e:unknown){if(active)error.value=e instanceof Error?e.message:'请求失败，请重试'}
async function load(reset=false){if(reset)page.value=1;const seq=++epoch;loading.value=true;try{const result=await playlists.list(query.value,page.value-1,sort.value);if(active&&seq===epoch){items.value=result.items;total.value=result.totalElements;const last=Math.max(1,Math.ceil(result.totalElements/12));if(page.value>last){page.value=last;await load()}}}catch(e){if(active&&seq===epoch)failure(e)}finally{if(active&&seq===epoch)loading.value=false}}
function create(){current.value=null;songs.value={};Object.assign(form,{requestId:newRequestId(),name:'',description:'',songIds:[],revision:0});originalForm.value=formValue();proposal.value=null;error.value='';open.value=true}
async function edit(id:string){if(busy.value)return;busy.value=true;try{const result=await playlists.get(id);if(!active)return;current.value=result;songs.value=Object.fromEntries(result.songs.map(s=>[s.id,s]));Object.assign(form,{requestId:'',name:result.name,description:result.description,songIds:[...result.songIds],revision:result.revision});originalForm.value=formValue();proposal.value=null;error.value='';open.value=true}catch(e){failure(e)}finally{if(active)busy.value=false}}
async function close(){if(busy.value||generating.value)return;try{await ElMessageBox.confirm('关闭将放弃未保存的歌单编辑。','关闭编辑',{confirmButtonText:'放弃编辑',cancelButtonText:'继续编辑',autofocus:false})}catch{return}open.value=false;proposal.value=null}
async function save(){if(busy.value||generating.value)return;busy.value=true;error.value='';try{const result=await playlists.save({...form,songIds:[...form.songIds]},current.value?.id);if(!active)return;current.value=result;form.revision=result.revision;open.value=false;await load()}catch(e){failure(e)}finally{if(active)busy.value=false}}
async function remove(p:Playlist){try{await ElMessageBox.confirm(`删除歌单“${p.name}”？歌曲文件和聚会队列会保留。`,'删除歌单',{confirmButtonText:'删除歌单',cancelButtonText:'保留',autofocus:false,type:'warning'})}catch{return}if(busy.value)return;busy.value=true;try{await playlists.remove(p.id,p.revision);if(active)await load()}catch(e){failure(e)}finally{if(active)busy.value=false}}
function choose(ids:string[]){form.songIds=ids;picker.value=false}
function move(index:number,direction:number){const target=index+direction;if(target<0||target>=form.songIds.length)return;const list=[...form.songIds];[list[index],list[target]]=[list[target]!,list[index]!];form.songIds=list}
async function generate(){if(generating.value||busy.value)return;generating.value=true;error.value='';try{const result=await playlists.generate({...generation,limit:Number(generation.limit)});if(active)proposal.value=result}catch(e){failure(e)}finally{if(active)generating.value=false}}
function useProposal(){if(!proposal.value)return;const p=proposal.value;Object.assign(form,{name:p.name,description:p.description,songIds:[...p.songIds]});p.songs.forEach(s=>songs.value[s.id]=s);proposal.value=null}
onMounted(()=>{window.addEventListener('beforeunload',beforeUnload);void load();void ai.services('MODEL').then(v=>{if(active){models.value=v.filter(m=>m.enabled);generation.modelServiceId=models.value[0]?.id||''}}).catch(failure)})
onBeforeUnmount(()=>{active=false;epoch++;window.removeEventListener('beforeunload',beforeUnload)})
</script>
<template>
<section class="playlist-page"><PageHeader title="歌单" description="共享歌单会出现在电视和亲友点歌页中。可手动选歌，也可让 AI 从曲库中推荐。"><template #meta><span class="playlist-total" aria-live="polite">{{ total }} 张</span></template><template #actions><ElButton type="primary" @click="create">创建歌单</ElButton></template></PageHeader>
<ElAlert v-if="error" :title="error" type="error" :closable="false"/>
<form class="toolbar playlist-filters" @submit.prevent="load(true)"><ElInput v-model="query" placeholder="搜索歌单名称或简介" aria-label="搜索歌单" clearable @clear="load(true)"/><label>排序<select v-model="sort" aria-label="歌单排序" @change="load(true)"><option value="UPDATED_DESC">最近更新</option><option value="NAME_ASC">名称排序</option><option value="SONGS_DESC">歌曲最多</option></select></label><ElButton native-type="submit" :loading="loading">搜索 / 刷新</ElButton></form>
<p v-if="loading" role="status">正在读取歌单…</p><div v-else-if="!items.length" class="empty"><span><ElIcon><SongPlaceholderIcon /></ElIcon></span><h2>{{query?'没有找到歌单':'为下一次欢聚准备一张歌单'}}</h2><p>手动选歌，或让 AI 根据你的想法从曲库中推荐。</p><ElButton @click="create">创建第一张歌单</ElButton></div>
<div v-else class="grid"><article v-for="p in items" :key="p.id" class="card"><button class="card-open" @click="edit(p.id)"><div class="cover"><img v-if="p.coverPath&&!coverFailures.has(p.id+(p.coverVersion||''))" @error="coverFailures.add(p.id+(p.coverVersion||''))" :src="`${p.coverPath}?v=${encodeURIComponent(p.coverVersion||'')}`" alt=""/><span v-else role="img" aria-label="暂无歌单封面"><ElIcon><SongPlaceholderIcon /></ElIcon></span></div><div class="card-copy"><h2 :title="p.name">{{p.name}}</h2><p>{{p.count}} 首可用歌曲<span v-if="p.missingCount"> · {{p.missingCount}} 首已移除</span></p><small :title="p.description">{{p.description||'打开歌单，选一首想唱的歌'}}</small></div></button><div class="card-actions"><ElButton text @click="edit(p.id)">编辑歌单</ElButton><ElButton text type="danger" :disabled="busy" @click="remove(p)">删除</ElButton></div></article></div>
<ElPagination v-if="total" :current-page="page" :total="total" :page-size="12" layout="total, prev, pager, next" @current-change="v=>{page=v;load()}"/>
<ElDialog :model-value="open" :title="current?'编辑歌单':'创建歌单'" width="min(960px, calc(100vw - 32px))" :close-on-click-modal="false" :close-on-press-escape="!busy&&!generating" :show-close="!busy&&!generating" :before-close="close"><ElAlert v-if="error" :title="error" type="error" :closable="false"/><div class="editor"><section class="details"><label>歌单名称<ElInput v-model="form.name" maxlength="120" aria-label="歌单名称" :disabled="busy||generating"/></label><label>简介<ElInput v-model="form.description" type="textarea" :rows="2" maxlength="1000" :disabled="busy||generating"/></label><div class="toolbar"><strong>已选 {{form.songIds.length}} / 500 首 <small v-if="dirty" class="draft-hint">尚未保存</small></strong><ElButton :disabled="busy||generating" @click="picker=true">选择歌曲</ElButton></div><p v-if="!form.songIds.length" class="hint">还没有歌曲。手动选择或使用右侧 AI 辅助。</p><ol class="song-list"><li v-for="(id,index) in form.songIds" :key="id"><span class="number">{{index+1}}</span><div><strong>{{songs[id]?.title||'已选择歌曲'}}</strong><small>{{songs[id]?.artists.join(' / ')||id}}</small></div><button :disabled="index===0||busy||generating" :aria-label="`上移第${index+1}首`" @click="move(index,-1)">↑</button><button :disabled="index===form.songIds.length-1||busy||generating" :aria-label="`下移第${index+1}首`" @click="move(index,1)">↓</button><ElButton text :disabled="busy||generating" @click="form.songIds=form.songIds.filter(v=>v!==id)">移除</ElButton></li></ol></section><aside class="ai-panel"><h2>AI 帮我选歌</h2><p>描述场景、歌手或氛围，只推荐当前 NAS 曲库内歌曲。使用已配置的模型，生成后预览确认。</p><label>模型<select v-model="generation.modelServiceId" aria-label="歌单 AI 模型" :disabled="generating"><option v-for="m in models" :key="m.id" :value="m.id">{{m.name}}</option></select></label><p v-if="!models.length" class="hint">尚无可用模型，请先到 AI 设置添加并启用。</p><label>你的想法<ElInput v-model="generation.prompt" type="textarea" :rows="3" maxlength="1000" placeholder="例如：适合朋友聚会的华语合唱，节奏轻快" :disabled="generating"/></label><label>候选关键词（可选）<ElInput v-model="generation.q" maxlength="200" placeholder="歌手、歌名或已有搜索词" :disabled="generating"/></label><label>最多推荐首数<input v-model="generation.limit" type="number" min="1" max="50" :disabled="generating"/></label><small>每次从最多 500 首候选中选择，可用关键词缩小范围。只发送歌曲标题、歌手和分类，不发送文件路径或音视频。</small><ElButton type="primary" :loading="generating" :disabled="!generation.prompt.trim()||!generation.modelServiceId||busy" @click="generate">生成歌单建议</ElButton></aside></div><template #footer><ElButton :disabled="busy||generating" @click="close">取消</ElButton><ElButton type="primary" :loading="busy" :disabled="!form.name.trim()||generating||form.songIds.length>500" @click="save">保存歌单</ElButton></template></ElDialog>
<SongPicker v-if="picker" purpose="playlist" :selected-ids="form.songIds" @close="picker=false" @confirm="choose" @selected-songs="v=>v.forEach(s=>{songs[s.id]={id:s.id,title:(s.metadata&&!s.metadata.stale&&s.metadata.fields.includes('title')?s.metadata.track.title||s.result.title:s.result.title)||'未知歌曲',artists:s.metadata&&!s.metadata.stale&&s.metadata.fields.includes('artists')?s.metadata.track.artists:s.result.artists}})"/>
<ElDialog :model-value="!!proposal" title="预览 AI 歌单建议" width="min(620px, calc(100vw - 32px))" :close-on-click-modal="false" @close="proposal=null"><template v-if="proposal"><h2>{{proposal.name}}</h2><p class="hint">{{proposal.description}}</p><p class="hint">已检查 {{proposal.candidateCount}} / {{proposal.libraryCount}} 首候选，推荐 {{proposal.songIds.length}} 首。尚未保存。</p><ol><li v-for="s in proposal.songs" :key="s.id">{{s.title}} · {{s.artists.join(' / ')}}</li></ol><p v-if="!proposal.songIds.length" class="hint">没有选到合适的歌曲，可调整要求或手动选歌。</p></template><template #footer><ElButton @click="proposal=null">放弃建议</ElButton><ElButton type="primary" :disabled="!proposal?.songIds.length" @click="useProposal">使用建议替换编辑内容</ElButton></template></ElDialog>
</section>
</template>
<style scoped>
.playlist-page {
  display: grid;
  gap: 12px;
  min-width: 0
}

.playlist-page>.page-heading {
  margin-bottom: 0
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap
}

.playlist-filters {
  padding: 0;
  margin: 0 0 4px
}

.playlist-filters>.el-input {
  max-width: 360px
}

.playlist-filters label {
  display: flex;
  gap: 8px;
  align-items: center;
  font-size: 12px;
  color: var(--sv-text-secondary)
}

.playlist-total { font-size: 13px; color: var(--sv-text-secondary); }

.playlist-list-heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px
}

.playlist-list-heading>strong {
  font-size: 18px;
  font-weight: 500
}

.playlist-list-heading>span {
  font-size: 12px;
  color: var(--sv-text-secondary)
}

.playlist-page>.el-pagination {
  justify-content: flex-end
}

.grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 20px
}

.card {
  background: transparent;
  border: 1px solid transparent;
  border-radius: 12px;
  overflow: hidden
}

.card:hover {
  box-shadow: var(--sv-shadow-sm)
}

.card-open {
  padding: 0;
  border: 0;
  background: none;
  text-align: left;
  color: inherit;
  width: 100%;
  cursor: pointer
}

.cover {
  --sleeve-paper: var(--sv-surface-3);
  --sleeve-ink: var(--sv-text-tertiary);
  position: relative;
  isolation: isolate;
  aspect-ratio: 1.2;
  background: var(--sleeve-paper);
  display: grid;
  place-items: center;
  overflow: hidden
}

.cover::before {
  content: '';
  position: absolute;
  width: 105%;
  aspect-ratio: 1;
  right: -25%;
  bottom: -48%;
  border-radius: 50%;
  background: repeating-radial-gradient(circle, var(--sleeve-ink) 0 1px, transparent 2px 5px);
  opacity: .4;
  z-index: -1
}



.cover img {
  width: 100%;
  height: 100%;
  object-fit: cover
}

.cover>span {
  display: grid;
  place-items: center;
  width: 84px;
  height: 84px;
  border-radius: 50%;
  background: var(--sv-surface-1);
  color: var(--sv-text-secondary);
  font-size: 28px
}

.card-copy {
  padding: 16px 0 12px
}

.card-copy h2 {
  font-size: 17px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap
}

.card-copy p {
  font-size: 12px;
  color: var(--sv-text-secondary);
  margin: 8px 0
}

.card-copy small {
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  overflow: hidden;
  line-height: 1.6;
  color: var(--sv-text-secondary);
  font-size: 12px
}

.card-actions {
  display: flex;
  justify-content: space-between;
  border-top: 1px solid var(--sv-border-subtle);
  margin: 0;
  padding: 4px 0
}

.card-actions :deep(.el-button) {
  margin: 0;
  font-size: 12px;
  min-height: 40px
}

.empty {
  text-align: center;
  padding: 60px 20px;
  border: 1px dashed var(--sv-border-default);
  border-radius: 16px
}

.empty>span {
  font-size: 44px;
  color: var(--sv-accent-primary)
}

.empty h2 {
  font-size: 20px;
  margin: 16px
}

.empty p,
.hint,
.ai-panel p {
  font-size: 13px;
  line-height: 1.8;
  color: var(--sv-text-secondary)
}

.empty p {
  margin-bottom: 20px
}

.editor {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 290px;
  gap: 28px
}

.details,
.ai-panel {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-width: 0
}

.details label,
.ai-panel label {
  display: grid;
  gap: 8px;
  font-size: 13px
}

.draft-hint {
  font-size: 12px;
  font-weight: 400;
  color: var(--sv-semantic-warn);
  margin-left: 8px
}

.ai-panel {
  padding: 20px;
  border-radius: 12px;
  background: var(--sv-surface-3);
  align-self: start
}

.ai-panel h2 {
  font-size: 17px
}

.ai-panel select,
.ai-panel input[type=number] {
  width: 100%;
  height: 40px;
  padding: 0 8px
}

.ai-panel small {
  font-size: 12px;
  line-height: 1.7;
  color: var(--sv-text-secondary)
}

.song-list {
  list-style: none;
  padding: 0;
  max-height: 420px;
  overflow: auto
}

.song-list li {
  display: flex;
  gap: 8px;
  align-items: center;
  padding: 12px 0;
  border-bottom: 1px solid var(--sv-border-subtle)
}

.song-list li>div {
  flex: 1;
  min-width: 0
}

.song-list strong,
.song-list small {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap
}

.song-list strong {
  font-size: 13px
}

.song-list small,
.number {
  font-size: 12px;
  color: var(--sv-text-secondary)
}

.song-list li>button:not(.el-button) {
  background: var(--sv-surface-2);
  color: var(--sv-text-primary);
  border: 1px solid var(--sv-border-subtle);
  border-radius: 8px;
  cursor: pointer;
  padding: 4px;
  min-width: 34px;
  min-height: 32px
}

.song-list button:disabled {
  opacity: .35
}

@media(max-width:1200px) {
  .grid {
    grid-template-columns: repeat(3, minmax(0, 1fr))
  }
}

@media(max-width:900px) {
  .grid {
    grid-template-columns: repeat(2, minmax(0, 1fr))
  }

  .editor {
    grid-template-columns: 1fr
  }
}

@media(max-width:580px) {
  .grid {
    gap: 12px
  }

  .card-copy {
    padding: 12px 0 8px
  }

  .card-copy h2 {
    font-size: 14px
  }

  .card-copy p,
  .card-copy small {
    font-size: 12px
  }

  .card-actions {
    margin: 0
  }

  .card-actions :deep(.el-button) {
    font-size: 12px;
    padding: 4px 2px;
    min-width: 44px;
    min-height: 44px
  }

  .cover>span {
    width: 55px;
    height: 55px;
    font-size: 24px
  }

  .playlist-list-heading>span {
    font-size: 12px
  }

  .playlist-filters {
    gap: 8px;
    padding: 0
  }

  .playlist-filters>.el-input {
    max-width: none;
    flex-basis: 100%
  }

  .playlist-filters select {
    max-width: 150px
  }

  .editor {
    gap: 20px
  }

  .song-list li {
    flex-wrap: wrap
  }

  .song-list li>div {
    flex-basis: calc(100% - 40px)
  }

  .song-list li>.number {
    min-width: 22px
  }

  .song-list li>button {
    min-height: 44px;
    min-width: 44px
  }

  .song-list li>.el-button {
    margin-left: auto
  }
}
@media(max-width:768px) {
  .playlist-filters :deep(.el-input__inner) { font-size: 16px; }
  .playlist-filters select { min-height: 44px; }
}
</style>

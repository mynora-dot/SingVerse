// @vitest-environment jsdom
import { flushPromises,mount,type VueWrapper } from '@vue/test-utils'
import { afterEach,beforeEach,expect,it,vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import { ElMessageBox, ElPagination } from 'element-plus'
import ArtistsView from '../src/admin/modules/artists/views/ArtistsView.vue'
import ArtistImageEditor from '../src/admin/modules/artists/components/ArtistImageEditor.vue'
import ArtistArtworkBatch from '../src/admin/modules/artists/components/ArtistArtworkBatch.vue'
import ArtistArtworkDialog from '../src/admin/modules/artists/components/ArtistArtworkDialog.vue'
import { artists } from '../src/admin/modules/artists/api/artists'
import type { Artist } from '../src/admin/modules/artists/types'
import type { ArtworkBatch } from '../src/admin/modules/artists/types'
import { ApiError } from '../src/admin/core/auth/api'
vi.mock('../src/admin/modules/artists/api/artists',()=>({artists:Object.fromEntries(['list','get','songs','retry','override','reset','upload','removeImage','artworkSettings','saveArtworkSettings','candidates','candidatePreview','selectCandidate','category','categories','artworkPlatforms','startArtworkBatch','latestArtworkBatch','artworkBatch'].map(k=>[k,vi.fn()]))}))
const profile:Artist={id:'artist-1',name:'歌手甲',aliases:[],type:'UNKNOWN',biography:'',active:true,revision:1,managed:true,automatic:true,count:1,fetchState:'NO_MATCH',portraitFetchState:'NO_MATCH',backgroundFetchState:'NO_MATCH'}
const song={id:'song-1',sourceId:'source',sourceName:'本地',relativePath:'歌曲.mp4',result:{title:'曲库歌曲',artists:['歌手甲']}}
let wrapper:VueWrapper|undefined
beforeEach(()=>{vi.mocked(artists.latestArtworkBatch).mockResolvedValue(null);vi.mocked(artists.artworkPlatforms).mockResolvedValue({enabled:['AUTO','NETEASE','THEAUDIODB']});vi.mocked(artists.list).mockResolvedValue({items:[profile],page:0,size:12,totalElements:1});vi.mocked(artists.get).mockResolvedValue(profile);vi.mocked(artists.songs).mockResolvedValue({items:[song as never],page:0,size:12,totalElements:1});vi.mocked(artists.override).mockResolvedValue({overridden:true,revision:3});vi.mocked(artists.reset).mockResolvedValue(undefined);vi.mocked(artists.retry).mockResolvedValue(undefined);Object.defineProperty(URL,'createObjectURL',{configurable:true,value:vi.fn(()=>'blob:artist-test')});Object.defineProperty(URL,'revokeObjectURL',{configurable:true,value:vi.fn()})})
afterEach(()=>{wrapper?.unmount();wrapper=undefined;vi.clearAllMocks();vi.restoreAllMocks();document.body.innerHTML=''})
async function page(){const router=createRouter({history:createMemoryHistory(),routes:[{path:'/:pathMatch(.*)*',component:{template:'<div />'}}]});await router.push('/library/artists');await router.isReady();wrapper=mount(ArtistsView,{global:{plugins:[router],stubs:{teleport:true,SingleSongScrapeDialog:{name:'SingleSongScrapeDialog',props:['song'],emits:['close','applied'],template:'<div data-editor="true" />'}}}});await flushPromises();return wrapper}
function button(text:string){return wrapper!.findAll('button').find(b=>b.text()===text)!}
it('扫描空库提示自动生成，不提供建档关联操作',async()=>{vi.mocked(artists.list).mockResolvedValue({items:[],page:0,size:12,totalElements:0});await page();expect(wrapper!.text()).toContain('扫描歌曲后，这里会自动出现歌手');expect(wrapper!.text()).not.toContain('新建歌手');expect(wrapper!.text()).not.toContain('曲库署名关联')})
it('歌手类型与歌曲数筛选提交到分页查询，类型可在详情标注',async()=>{await page();await button('乐队 / 组合').trigger('click');await wrapper!.get('input[aria-label="歌曲数量小于"]').setValue('25');expect(artists.list).toHaveBeenLastCalledWith('',0,20,'GROUP',25,'');await wrapper!.get('.artist-open').trigger('click');await flushPromises();vi.mocked(artists.category).mockResolvedValue({...profile,type:'GROUP',revision:2});await wrapper!.get('select[aria-label="设置歌手类型"]').setValue('GROUP');await flushPromises();expect(artists.category).toHaveBeenCalledWith('artist-1','GROUP',1)})
it('歌手来自歌曲，打开后可订正歌曲而非维护人物资料',async()=>{await page();await wrapper!.get('.artist-open').trigger('click');await flushPromises();expect(artists.songs).toHaveBeenCalledWith('artist-1',0);expect(wrapper!.text()).toContain('曲库歌曲');expect(wrapper!.text()).toContain('没有可靠匹配');await button('订正歌曲').trigger('click');expect(wrapper!.findComponent({name:'SingleSongScrapeDialog'}).props('song')).toEqual(song)})
it('订正完成后自动刷新，原歌手为空则退出详情',async()=>{await page();await wrapper!.get('.artist-open').trigger('click');await flushPromises();vi.mocked(artists.get).mockResolvedValue({...profile,count:0});wrapper!.findComponent({name:'SingleSongScrapeDialog'}).vm.$emit('applied');await flushPromises();expect(artists.list).toHaveBeenCalledTimes(2);expect(wrapper!.findAll('button').some(b=>b.text()==='订正歌曲')).toBe(false)})
it('重新取图阻止重复提交，保留图片上传入口',async()=>{await page();await wrapper!.get('.artist-open').trigger('click');await flushPromises();let finish!:()=>void;vi.mocked(artists.retry).mockReturnValue(new Promise(r=>{finish=r}));await button('重试自动补图').trigger('click');await button('重试自动补图').trigger('click');expect(artists.retry).toHaveBeenCalledTimes(1);expect(wrapper!.text()).toContain('上传 / 替换头像');vi.mocked(artists.get).mockResolvedValue({...profile,portraitFetchState:'PENDING'});finish();await flushPromises();expect(wrapper!.text()).toContain('等待获取封面')})
it('恢复扫描值先确认，取消不写入并保留版本保护',async()=>{await page();await wrapper!.get('.artist-open').trigger('click');await flushPromises();const confirm=vi.spyOn(ElMessageBox,'confirm').mockRejectedValueOnce('cancel');await button('恢复歌手').trigger('click');await flushPromises();expect(artists.reset).not.toHaveBeenCalled();confirm.mockResolvedValueOnce('confirm' as Awaited<ReturnType<typeof ElMessageBox.confirm>>);await button('恢复歌手').trigger('click');await flushPromises();expect(artists.reset).toHaveBeenCalledWith('song-1',3)})
it('图片上传维持用途与独立版本，卸载取消请求',async()=>{wrapper=mount(ArtistImageEditor,{props:{artist:{...profile,portraitVersion:'p1',backgroundVersion:'b1'},kind:'background'},global:{stubs:{teleport:true}}});await flushPromises();let finish!:(a:Artist)=>void;vi.mocked(artists.upload).mockReturnValue(new Promise(r=>{finish=r}));const file=new File(['image'],'artist.png',{type:'image/png'}),input=wrapper.get('input[type=file]');Object.defineProperty(input.element,'files',{configurable:true,value:[file]});await input.trigger('change');await button('保存图片').trigger('click');expect(artists.upload).toHaveBeenCalledWith('artist-1','background','b1',file,expect.any(AbortSignal));const signal=vi.mocked(artists.upload).mock.calls[0]![4];wrapper.unmount();expect(signal.aborted).toBe(true);finish(profile);await flushPromises();expect(wrapper.emitted('changed')).toBeUndefined();wrapper=undefined})
it('管理页保存可选 Fanart.tv 密钥且不显示明文',async()=>{vi.mocked(artists.artworkSettings).mockResolvedValue({fanartConfigured:false});vi.mocked(artists.saveArtworkSettings).mockResolvedValue({fanartConfigured:true});await page();await button('背景图来源').trigger('click');await flushPromises();await wrapper!.get('input[aria-label="Fanart.tv 个人 API 密钥"]').setValue('test-key');await button('保存').trigger('click');await flushPromises();expect(artists.saveArtworkSettings).toHaveBeenCalledWith('test-key',false);expect(wrapper!.text()).not.toContain('test-key')})
it('不可靠的头像候选需要管理员选择',async()=>{vi.mocked(artists.list).mockResolvedValue({items:[{...profile,portraitFetchState:'NEEDS_REVIEW'}],page:0,size:12,totalElements:1});vi.mocked(artists.candidates).mockResolvedValue([{provider:'THEAUDIODB',externalId:'123',attribution:'TheAudioDB',portrait:'https://r2.theaudiodb.com/a.jpg',background:''}]);vi.mocked(artists.candidatePreview).mockReturnValue('/api/v1/artists/artist-1/images/portrait/candidates/THEAUDIODB/123/preview');vi.mocked(artists.selectCandidate).mockResolvedValue({...profile,portraitPath:'/api/portrait'});await page();await wrapper!.get('.artist-open').trigger('click');await flushPromises();await button('查看头像候选').trigger('click');await flushPromises();expect(wrapper!.text()).toContain('TheAudioDB');await button('使用这张图片').trigger('click');await flushPromises();expect(artists.selectCandidate).toHaveBeenCalledWith('artist-1','portrait','THEAUDIODB','123')})
it('列表封面突出头像，详情仍保留独立背景与头像生成预览',async()=>{const portrait={...profile,portraitPath:'/api/portrait',portraitVersion:'p1'};vi.mocked(artists.list).mockResolvedValue({items:[portrait],page:0,size:12,totalElements:1});await page();expect(wrapper!.get('.artist-banner img').attributes('src')).toBe('/api/portrait?v=p1');await wrapper!.get('.artist-open').trigger('click');await flushPromises();expect(wrapper!.text()).toContain('头像生成预览');wrapper!.unmount();wrapper=undefined;vi.mocked(artists.list).mockResolvedValue({items:[{...portrait,backgroundPath:'/api/background',backgroundVersion:'b1'}],page:0,size:12,totalElements:1});await page();expect(wrapper!.get('.artist-banner img').attributes('src')).toBe('/api/portrait?v=p1');await wrapper!.get('.artist-open').trigger('click');await flushPromises();expect(wrapper!.get('.artist-images .background img').attributes('src')).toBe('/api/background?v=b1')})

it('图片重试提示不把所有原因误报为网络故障',async()=>{vi.mocked(artists.list).mockResolvedValue({items:[{...profile,portraitFetchState:'RETRY'}],page:0,size:12,totalElements:1});await page();expect(wrapper!.text()).toContain('暂未获取成功，稍后自动重试');expect(wrapper!.text()).not.toContain('网络暂不可用')})

const completedBatch:ArtworkBatch={id:'batch-1',provider:'NETEASE',status:'COMPLETED',createdAt:'2026-11-10T01:00:00Z',updatedAt:'2026-11-10T01:01:00Z',total:1,completed:1,counts:{APPLIED:1},items:[{id:'item-1',artistId:'artist-1',name:'歌手甲',status:'APPLIED',message:'已保存'}],page:0,size:20,totalElements:1}
it('男歌手、女歌手与待分类常驻筛选，卡片明确显示类型',async()=>{
  vi.mocked(artists.list).mockResolvedValue({items:[{...profile,type:'FEMALE'}],page:0,size:20,totalElements:1})
  await page()
  expect(wrapper!.get('.artist-type').text()).toBe('女歌手')
  await button('女歌手').trigger('click');await flushPromises()
  expect(artists.list).toHaveBeenLastCalledWith('',0,20,'FEMALE',null,'')
  expect(button('女歌手').attributes('aria-pressed')).toBe('true')
  await wrapper!.get('select[aria-label="按封面状态筛选"]').setValue('MISSING');await flushPromises()
  expect(artists.list).toHaveBeenLastCalledWith('',0,20,'FEMALE',null,'MISSING')
})
it('跨页保留选择，批量类型确认携带每位歌手的版本',async()=>{
  await page()
  await wrapper!.get('input[aria-label="选择歌手 歌手甲"]').setValue(true)
  const second={...profile,id:'artist-2',name:'歌手乙',revision:7}
  vi.mocked(artists.list).mockResolvedValue({items:[second],page:1,size:20,totalElements:21})
  wrapper!.findComponent(ElPagination).vm.$emit('current-change',2);await flushPromises()
  await wrapper!.get('input[aria-label="选择歌手 歌手乙"]').setValue(true)
  expect(wrapper!.get('.selection-count').text()).toContain('2')
  expect(wrapper!.get('.selection-count').text()).toContain('跨页保留')
  await button('标注歌手类型').trigger('click');await flushPromises()
  await wrapper!.get('select[aria-label="批量标注歌手类型"]').setValue('FEMALE')
  vi.mocked(artists.categories).mockResolvedValue({updated:2})
  await button('确认标注').trigger('click');await flushPromises()
  expect(artists.categories).toHaveBeenCalledWith([{id:'artist-1',revision:1},{id:'artist-2',revision:7}],'FEMALE')
  expect(wrapper!.find('.selection-count').exists()).toBe(false)
})
it('批量分类版本冲突保留选择与表单，不能显示成功',async()=>{
  await page();await wrapper!.get('input[aria-label="选择歌手 歌手甲"]').setValue(true)
  await button('标注歌手类型').trigger('click');await flushPromises()
  await wrapper!.get('select[aria-label="批量标注歌手类型"]').setValue('MALE')
  vi.mocked(artists.categories).mockRejectedValue(new ApiError(409,'歌手类型已变化'))
  await button('确认标注').trigger('click');await flushPromises()
  expect(wrapper!.text()).toContain('本次未更新')
  expect((wrapper!.get('select[aria-label="批量标注歌手类型"]').element as HTMLSelectElement).value).toBe('MALE')
  expect((wrapper!.get('input[aria-label="选择歌手 歌手甲"]').element as HTMLInputElement).checked).toBe(true)
})
it('获取封面需要明确选择范围与平台，停用平台不能提交',async()=>{
  vi.mocked(artists.list).mockResolvedValue({items:[profile],page:0,size:20,totalElements:48})
  vi.mocked(artists.startArtworkBatch).mockResolvedValue(completedBatch)
  vi.mocked(artists.artworkBatch).mockResolvedValue(completedBatch)
  await page();await button('获取歌手封面').trigger('click');await flushPromises()
  expect(artists.startArtworkBatch).not.toHaveBeenCalled()
  expect(wrapper!.text()).toContain('全部筛选结果 · 48 位')
  expect(wrapper!.get('input[name="artwork-platform"][value="QQ"]').attributes('disabled')).toBeDefined()
  await wrapper!.get('input[name="artwork-platform"][value="NETEASE"]').setValue(true)
  await button('开始获取 · 48 位').trigger('click');await flushPromises()
  expect(artists.startArtworkBatch).toHaveBeenCalledWith(expect.objectContaining({allMatching:true,artistIds:[],provider:'NETEASE',filter:{q:'',type:'',countLessThan:null,artwork:''},requestId:expect.any(String)}))
})
it('提交结果不明时保留输入和幂等标识，重复点击不会建立重复批次',async()=>{
  wrapper=mount(ArtistArtworkDialog,{props:{open:false,selectedIds:['artist-1'],filter:{q:'',type:'',countLessThan:null,artwork:''},total:48},global:{stubs:{teleport:true}}})
  await wrapper.setProps({open:true});await flushPromises()
  await wrapper.get('input[name="artwork-platform"][value="NETEASE"]').setValue(true)
  vi.mocked(artists.startArtworkBatch).mockRejectedValue(new Error('连接失败'))
  await button('开始获取 · 1 位').trigger('click');await flushPromises()
  const first=vi.mocked(artists.startArtworkBatch).mock.calls[0]![0]
  expect(wrapper.text()).toContain('连接失败')
  await button('开始获取 · 1 位').trigger('click');await flushPromises()
  expect(vi.mocked(artists.startArtworkBatch).mock.calls[1]![0]).toEqual(first)
})
it.each(['KUGOU','KUWO','QISHUI'] as const)('批量获取封面实际提交所选图片平台 %s',async provider=>{
  vi.mocked(artists.artworkPlatforms).mockResolvedValue({enabled:['AUTO',provider,'THEAUDIODB']})
  vi.mocked(artists.startArtworkBatch).mockResolvedValue({...completedBatch,provider})
  wrapper=mount(ArtistArtworkDialog,{props:{open:false,selectedIds:['artist-1'],filter:{q:'',type:'',countLessThan:null,artwork:''},total:1},global:{stubs:{teleport:true}}})
  await wrapper.setProps({open:true});await flushPromises()
  await wrapper.get(`input[name="artwork-platform"][value="${provider}"]`).setValue(true)
  await button('开始获取 · 1 位').trigger('click');await flushPromises()
  expect(artists.startArtworkBatch).toHaveBeenCalledWith(expect.objectContaining({provider,artistIds:['artist-1'],allMatching:false}))
  expect(wrapper.emitted('created')?.[0]?.[0]).toEqual(expect.objectContaining({provider}))
})
it('酷狗沿用启用状态，酷我和汽水可直接选择',async()=>{
  vi.mocked(artists.artworkPlatforms).mockResolvedValue({enabled:['AUTO','KUWO','QISHUI','THEAUDIODB']})
  wrapper=mount(ArtistArtworkDialog,{props:{open:false,selectedIds:['artist-1'],filter:{q:'',type:'',countLessThan:null,artwork:''},total:1},global:{stubs:{teleport:true}}})
  await wrapper.setProps({open:true});await flushPromises()
  expect(wrapper.get('input[name="artwork-platform"][value="KUGOU"]').attributes('disabled')).toBeDefined()
  for(const provider of ['KUWO','QISHUI']) expect(wrapper.get(`input[name="artwork-platform"][value="${provider}"]`).attributes('disabled')).toBeUndefined()
  expect(wrapper.text()).toContain('酷我、汽水直接查询公开歌手图片')
})
it('取图批次重新进入后读取真实进度，离页停止轮询且迟到结果无效',async()=>{
  vi.useFakeTimers()
  try {
    vi.mocked(artists.latestArtworkBatch).mockResolvedValue({...completedBatch,status:'RUNNING',completed:0,counts:{PENDING:1}})
    let finish!:(batch:ArtworkBatch)=>void
    vi.mocked(artists.artworkBatch).mockReturnValue(new Promise(resolve=>{finish=resolve}))
    wrapper=mount(ArtistArtworkBatch);await flushPromises()
    expect(wrapper.text()).toContain('已处理 0 / 1 位')
    await vi.advanceTimersByTimeAsync(4000)
    expect(artists.artworkBatch).toHaveBeenCalledWith('batch-1',0)
    wrapper.unmount();wrapper=undefined
    finish(completedBatch);await flushPromises()
    await vi.advanceTimersByTimeAsync(20000)
    expect(artists.artworkBatch).toHaveBeenCalledTimes(1)
  } finally { vi.useRealTimers() }
})
it('切换歌手后旧歌曲请求不能覆盖新详情',async()=>{
  const second={...profile,id:'artist-2',name:'歌手乙'}
  vi.mocked(artists.list).mockResolvedValue({items:[profile,second],page:0,size:20,totalElements:2})
  let finish!:(v:Awaited<ReturnType<typeof artists.songs>>)=>void
  vi.mocked(artists.songs).mockReturnValueOnce(new Promise(resolve=>{finish=resolve})).mockResolvedValueOnce({items:[{...song,result:{title:'第二位的歌',artists:['歌手乙']}} as never],page:0,size:12,totalElements:1})
  await page();await wrapper!.findAll('.artist-open')[0]!.trigger('click');await flushPromises()
  await wrapper!.findAll('.artist-open')[1]!.trigger('click');await flushPromises()
  finish({items:[song as never],page:0,size:12,totalElements:1});await flushPromises()
  expect(wrapper!.text()).toContain('第二位的歌')
  expect(wrapper!.text()).not.toContain('曲库歌曲')
})

it('手机筛选展开不提交草稿或清空跨页选择',async()=>{
  await page()
  await wrapper!.get('input[aria-label="选择歌手 歌手甲"]').setValue(true)
  await wrapper!.get('input[aria-label="搜索歌手库"]').setValue('未提交关键词')
  const before=vi.mocked(artists.list).mock.calls.length,toggle=wrapper!.get('.artists-filter-toggle')
  expect(toggle.attributes('aria-expanded')).toBe('false')
  await toggle.trigger('click')
  expect(toggle.attributes('aria-expanded')).toBe('true')
  expect(wrapper!.get('#artist-extra-filters').classes()).toContain('expanded')
  await toggle.trigger('click')
  expect(artists.list).toHaveBeenCalledTimes(before)
  expect(wrapper!.get('.selection-count').text()).toContain('1')
  expect((wrapper!.get('input[aria-label="搜索歌手库"]').element as HTMLInputElement).value).toBe('未提交关键词')
})

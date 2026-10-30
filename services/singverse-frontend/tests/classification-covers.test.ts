// @vitest-environment jsdom
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { beforeEach, afterEach, expect, it, vi } from 'vitest'
import { ElMessageBox } from 'element-plus'
import TagCoverEditor from '../src/admin/modules/classification/components/TagCoverEditor.vue'
import { classification } from '../src/admin/modules/classification/api/classification'
import type { Tag } from '../src/admin/modules/classification/types'
vi.mock('../src/admin/modules/classification/api/classification', () => ({ classification: { uploadCover: vi.fn(), removeCover: vi.fn() } }))
const tag: Tag = { id: 'genre-0', dimension: 'GENRE', name: '流行', aliases: [], active: true, revision: 2, count: 12 }
let wrapper: VueWrapper
const revoke = vi.fn()
beforeEach(() => { Object.defineProperty(URL,'createObjectURL',{configurable:true,value:vi.fn(()=>'blob:cover-preview')});Object.defineProperty(URL,'revokeObjectURL',{configurable:true,value:revoke});wrapper=mount(TagCoverEditor,{props:{tag},global:{stubs:{teleport:true}}}) })
afterEach(() => {wrapper.unmount();vi.clearAllMocks();vi.restoreAllMocks();document.body.innerHTML=''})
async function choose(file: File) {const input=wrapper.get('input[type=file]');Object.defineProperty(input.element,'files',{configurable:true,value:[file]});await input.trigger('change');await flushPromises()}
function button(text: string) {return wrapper.findAll('button').find(b=>b.text()===text)!}
it('选择后预览，显式保存并使用服务端封面版本，不改标签修订',async()=>{
 const result={...tag,coverPath:'/api/v1/classification/tags/genre-0/cover',coverVersion:'actual-version'}
 vi.mocked(classification.uploadCover).mockResolvedValue(result)
 const file=new File(['image'],'landscape.png',{type:'image/png'});await choose(file)
 expect(wrapper.get('img').attributes('src')).toBe('blob:cover-preview');expect(classification.uploadCover).not.toHaveBeenCalled()
 await button('保存封面').trigger('click');await flushPromises()
 expect(classification.uploadCover).toHaveBeenCalledWith('genre-0','',file,expect.any(AbortSignal));expect(wrapper.emitted('changed')?.[0]).toEqual([result]);expect(revoke).toHaveBeenCalledWith('blob:cover-preview')
})
it('拒绝伪类型和超限文件，不发送请求',async()=>{
 await choose(new File(['<svg/>'],'art.svg',{type:'image/svg+xml'}));expect(wrapper.text()).toContain('2MB 以内');expect(classification.uploadCover).not.toHaveBeenCalled()
 await choose(new File([new Uint8Array(2_000_001)],'large.png',{type:'image/png'}));expect(button('保存封面').attributes('disabled')).toBeDefined()
})
it('上传期间禁止重复提交，冲突保留本地预览供重新选择',async()=>{
 let reject!: (e:Error)=>void;vi.mocked(classification.uploadCover).mockReturnValue(new Promise((_,r)=>{reject=r}))
 await choose(new File(['image'],'cover.jpg',{type:'image/jpeg'}));await button('保存封面').trigger('click');await button('保存封面').trigger('click');expect(classification.uploadCover).toHaveBeenCalledTimes(1)
 reject(new Error('封面已被修改，请刷新分类后重试'));await flushPromises();expect(wrapper.text()).toContain('封面已被修改');expect(wrapper.get('img').attributes('src')).toBe('blob:cover-preview');expect(wrapper.emitted('changed')).toBeUndefined()
})
it('移除先确认并提交封面版本，取消不删除',async()=>{
 await wrapper.setProps({tag:{...tag,coverVersion:'v1',coverPath:'/api/v1/classification/tags/genre-0/cover'}})
 const confirm=vi.spyOn(ElMessageBox,'confirm').mockRejectedValueOnce('cancel')
 await button('移除封面').trigger('click');await flushPromises();expect(classification.removeCover).not.toHaveBeenCalled()
 confirm.mockResolvedValueOnce('confirm' as Awaited<ReturnType<typeof ElMessageBox.confirm>>);vi.mocked(classification.removeCover).mockResolvedValue({...tag,coverVersion:null,coverPath:null})
 await button('移除封面').trigger('click');await flushPromises();expect(classification.removeCover).toHaveBeenCalledWith('genre-0','v1');expect(wrapper.emitted('changed')).toHaveLength(1)
})
it('卸载取消上传并忽略迟到成功结果',async()=>{
 let resolve!: (t:Tag)=>void;vi.mocked(classification.uploadCover).mockReturnValue(new Promise(r=>{resolve=r}));await choose(new File(['image'],'cover.png',{type:'image/png'}));await button('保存封面').trigger('click');const signal=vi.mocked(classification.uploadCover).mock.calls[0]![3]!
 wrapper.unmount();expect(signal.aborted).toBe(true);resolve({...tag,coverVersion:'late'});await flushPromises();expect(wrapper.emitted('changed')).toBeUndefined()
})

// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { DOMWrapper, flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import ElementPlus from 'element-plus'
import FormalMetadataEditor from '../src/admin/modules/song-management/components/FormalMetadataEditor.vue'
import FormalSongsView from '../src/admin/modules/song-management/views/FormalSongsView.vue'
import { formalLibrary, type FormalDocument } from '../src/admin/modules/song-management/api/formal'
import { songs } from '../src/admin/modules/song-management/api/songs'
vi.mock('../src/admin/modules/song-management/api/formal', async importOriginal => {
 const original = await importOriginal<typeof import('../src/admin/modules/song-management/api/formal')>()
 return { ...original, formalLibrary: Object.fromEntries(Object.keys(original.formalLibrary).map(key => [key, vi.fn()])) }
})
vi.mock('../src/admin/modules/song-management/api/songs')
const doc: FormalDocument = { schemaVersion: '1.0', type: 'SONG', id: '11111111-1111-4111-8111-111111111111', revision: '7', fields: { title: '正式歌名', artists: ['演唱者'] }, states: { title: { decision: 'AUTO', status: 'PRESENT', revision: '7', origin: { kind: 'SHARED' } } } }
let wrapper: VueWrapper | undefined
const button = (text: string) => new DOMWrapper([...document.querySelectorAll('button')].find(node => node.textContent?.trim() === text)!)
async function choice(label: string, value: string) { const select = new DOMWrapper(document.querySelector(`select[aria-label="${label}"]`)!); expect(select.exists()).toBe(true); await select.setValue(value) }
beforeEach(() => { vi.resetAllMocks(); vi.mocked(formalLibrary.update).mockResolvedValue({ ...doc, revision: '8' }); vi.mocked(songs.sources).mockResolvedValue([]) })
afterEach(() => { wrapper?.unmount(); wrapper = undefined; document.body.innerHTML = '' })
const editor = () => mount(FormalMetadataEditor, { attachTo: document.body, props: { document: doc }, global: { plugins: [ElementPlus] } })
it('相同值也可确认，失败重试复用提交标识', async () => {
 vi.mocked(formalLibrary.update).mockRejectedValueOnce(new Error('连接中断'))
 wrapper = editor(); await flushPromises(); await choice('名称保存方式', 'SET'); await button('保存正式资料').trigger('click'); await flushPromises()
 expect(document.body.textContent).toContain('连接中断')
 expect(formalLibrary.update).toHaveBeenLastCalledWith(doc, [{ field: 'title', op: 'SET', value: '正式歌名' }], undefined, expect.any(String))
 const firstId = vi.mocked(formalLibrary.update).mock.calls[0]![3]
 await button('保存正式资料').trigger('click'); await flushPromises()
 expect(vi.mocked(formalLibrary.update).mock.calls[1]![3]).toBe(firstId); expect(wrapper.emitted('saved')).toHaveLength(1)
})
it('清空与解除本地决定分别提交，均不携带旧值', async () => {
 wrapper = editor(); await flushPromises(); await choice('名称保存方式', 'CLEAR'); await choice('演唱署名保存方式', 'RESET'); await button('保存正式资料').trigger('click'); await flushPromises()
 expect(formalLibrary.update).toHaveBeenLastCalledWith(doc, [{ field: 'title', op: 'CLEAR' }, { field: 'artists', op: 'RESET' }], undefined, expect.any(String))
})
it('3 MiB 原图先保存，确认后才更新正式引用', async () => {
 wrapper = editor(); await flushPromises(); const id = 'a'.repeat(64); vi.mocked(formalLibrary.uploadAsset).mockResolvedValue({ id, mime: 'image/png' })
 const input = new DOMWrapper(document.querySelector('input[type=file]')!); const file = new File([new Uint8Array(3 * 1024 * 1024)], '原图.png', { type: 'image/png' })
 Object.defineProperty(input.element, 'files', { value: [file] }); await input.trigger('change'); await flushPromises()
 expect(formalLibrary.uploadAsset).toHaveBeenCalledWith(file); expect(formalLibrary.update).not.toHaveBeenCalled()
 await button('保存正式资料').trigger('click'); await flushPromises()
 expect(formalLibrary.update).toHaveBeenLastCalledWith(doc, [{ field: 'cover', op: 'SET', value: { assetId: id } }], undefined, expect.any(String))
})
it('歌曲、版本、文件分别计数，待核验文件不能试听', async () => {
 const variant: FormalDocument = { ...doc, id: '22222222-2222-4222-8222-222222222222', type: 'VARIANT', parentId: doc.id, fields: { title: '现场完整版', kind: 'LIVE' }, states: {} }
 const media: FormalDocument = { ...doc, id: '33333333-3333-4333-8333-333333333333', type: 'MEDIA', parentId: variant.id, fields: { durationMs: 123000 }, states: {} }
 vi.mocked(formalLibrary.statistics).mockResolvedValue({ SONG: 1, VARIANT: 2, MEDIA: 3 }); vi.mocked(formalLibrary.list).mockResolvedValue({ items: [doc], totalElements: 1 })
 vi.mocked(formalLibrary.detail).mockImplementation(async id => id === doc.id ? { document: doc, children: [variant], childrenPage: { page: 0, size: 20, totalElements: 21 } } : { document: variant, children: [media], locations: [{ id: 'loc', media_id: media.id, source_id: null, relative_path: '中文 空格.mkv', state: 'PENDING' }] })
 const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/library/songs', component: FormalSongsView }] }); await router.push('/library/songs'); await router.isReady()
 wrapper = mount(FormalSongsView, { attachTo: document.body, global: { plugins: [ElementPlus, router], stubs: { SongVideoDialog: true } } }); await flushPromises()
 expect(document.body.textContent).toContain('1 首歌曲 · 2 个版本 · 3 个媒体文件'); await button('查看版本与文件').trigger('click'); await flushPromises()
 expect(formalLibrary.detail).toHaveBeenCalledWith(doc.id, 0); expect(formalLibrary.detail).toHaveBeenCalledWith(variant.id, 0)
 expect(document.body.textContent).toContain('中文 空格.mkv'); expect(document.body.textContent).toContain('实际媒体时长 123 秒'); expect(button('试听检查').attributes('disabled')).toBeDefined()
})

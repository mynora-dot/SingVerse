// @vitest-environment jsdom
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { ElCheckbox, ElDialog, ElMessageBox } from 'element-plus'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import DownloadsView from '../src/admin/modules/lx-downloads/DownloadsView.vue'
import RecordsPanel from '../src/admin/modules/lx-downloads/RecordsPanel.vue'
import SourcesDialog from '../src/admin/modules/lx-downloads/SourcesDialog.vue'
import TrackPicker from '../src/admin/modules/lx-downloads/TrackPicker.vue'
import { lx, type Batch, type LxSource, type Status, type Track } from '../src/admin/modules/lx-downloads/api'
import { media } from '../src/admin/modules/media-library/api/media'

vi.mock('../src/admin/modules/lx-downloads/api', async importOriginal => {
  const actual = await importOriginal<typeof import('../src/admin/modules/lx-downloads/api')>()
  return { ...actual, lx: Object.fromEntries(Object.keys(actual.lx).map(key => [key, vi.fn()])) }
})
vi.mock('../src/admin/modules/media-library/api/media', () => ({ media: { list: vi.fn(), save: vi.fn() } }))
const source: LxSource = { id: 'script', name: '我的音源', version: '1', environment: 'mobile', createdAt: '', active: true, capabilities: { wy: ['flac', '128k'], tx: ['128k'] } }
const status = (): Status => ({ ready: true, target: { id: 'local', name: '下载曲库', path: '/downloads', ready: true, reason: '' }, activeSource: source, platforms: {}, activeBatches: 0 })
const track = (id = 'a', extra: Partial<Track> = {}): Track => ({ id, platform: 'wy', title: `歌曲${id}`, artists: ['歌手'], album: '专辑', durationMs: 180000, coverUrl: '', inLibrary: false, downloading: false, songId: null, ...extra })
const batch = (state = 'RUNNING'): Batch => ({ id: 'batch', name: '测试批次', sourceId: 'local', scriptId: 'script', path: '/downloads', quality: 'auto', status: state, counts: state === 'RUNNING' ? { DOWNLOADING: 1 } : state === 'FAILED' ? { FAILED: 1 } : { SUCCESS: 1 }, total: 1, createdAt: '2026-11-09T01:00:00Z', updatedAt: '' })
let wrapper: VueWrapper | undefined, router: Router
beforeEach(async () => {
  router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/library/downloads', component: { template: '<div />' } }, { path: '/library/media', component: { template: '<div />' } }] })
  await router.push('/library/downloads')
  vi.mocked(lx.status).mockResolvedValue(status())
  vi.mocked(lx.search).mockResolvedValue({ tracks: [track()], errors: [], warnings: [], page: 1, hasMore: false })
  vi.mocked(lx.batches).mockResolvedValue({ items: [], total: 0, page: 0, size: 20 })
  vi.mocked(lx.items).mockResolvedValue({ items: [], total: 0, page: 0, size: 20 })
  vi.mocked(lx.batch).mockResolvedValue(batch())
  vi.mocked(lx.sources).mockResolvedValue([source])
  vi.mocked(lx.create).mockResolvedValue({ batch: batch(), merged: 0, relatedBatchIds: [] })
  vi.mocked(lx.trackStates).mockResolvedValue([track('a', { inLibrary: true, songId: 'song' })])
  vi.spyOn(document, 'hidden', 'get').mockReturnValue(false)
})
afterEach(() => { wrapper?.unmount(); wrapper = undefined; document.body.innerHTML = ''; vi.clearAllMocks(); vi.restoreAllMocks(); vi.useRealTimers() })
async function page() { wrapper = mount(DownloadsView, { global: { plugins: [router], stubs: { teleport: true, SourcesDialog: true, RecordsPanel: { template: '<div />', methods: { refresh: vi.fn() } } } } }); await flushPromises(); return wrapper }
const button = (text: string) => wrapper!.findAll('button').find(b => b.text() === text)!
async function search() { await wrapper!.get('input[aria-label="歌名或歌手"]').setValue('测试'); await wrapper!.get('.search-form').trigger('submit'); await flushPromises() }

it('目录和音源缺失时禁用找歌，配置入口保持可用', async () => {
  vi.mocked(lx.status).mockResolvedValue({ ...status(), ready: false, target: { id: null, name: null, path: null, ready: false, reason: '尚未设置' }, activeSource: null })
  await page()
  expect(button('搜索').attributes('disabled')).toBeDefined()
  expect(button('设置下载目录').attributes('disabled')).toBeUndefined()
  expect(button('导入并启用音源').attributes('disabled')).toBeUndefined()
  expect(wrapper!.text()).toContain('已有下载记录仍可查看')
  expect(lx.search).not.toHaveBeenCalled()
})
it('目录失效显示检查挂载提示，选择新目录显示原位置并保留写入错误', async () => {
  vi.mocked(lx.status).mockResolvedValue({ ...status(), ready: false, target: { ...status().target, ready: false, reason: '下载目录不可写，请检查 NAS 挂载' } })
  vi.mocked(media.list).mockResolvedValue({ items: [{ id: 'local', name: '新目录', type: 'LOCAL', location: '/new-downloads', parserProfileId: 'default' }], page: 0, size: 100, totalElements: 1 } as never)
  vi.mocked(media.save).mockRejectedValue(new Error('原保存位置仍有下载任务'))
  await page(); expect(wrapper!.text()).toContain('检查 NAS 挂载')
  await button('更改保存位置').trigger('click'); await flushPromises()
  expect(wrapper!.text()).toContain('原保存位置：/downloads'); expect(wrapper!.text()).toContain('新保存位置：/new-downloads')
  await button('保存位置').trigger('click'); await flushPromises()
  expect(media.save).toHaveBeenCalledWith(expect.objectContaining({ useForLxDownloads: true, location: '/new-downloads' }), 'local')
  expect(wrapper!.text()).toContain('原保存位置仍有下载任务')
})
it('保留部分平台结果、版本和失败提示，批量确认显示数量位置和可用音质', async () => {
  vi.mocked(lx.search).mockResolvedValue({ tracks: [track('a'), track('b', { platform: 'tx' })], errors: [{ platform: 'kg', message: '平台暂不可用' }], warnings: [], page: 1, hasMore: false })
  await page(); await search()
  expect(wrapper!.text()).toContain('平台暂不可用'); expect(wrapper!.findAll('.track')).toHaveLength(2)
  await wrapper!.findAllComponents(ElCheckbox)[0]!.get('input').setValue(true)
  await button('下载所选').trigger('click'); await flushPromises()
  const confirm = wrapper!.findAllComponents(ElDialog).find(c => c.props('title') === '确认下载')!
  expect(confirm.text()).toContain('2 首歌曲'); expect(confirm.text()).toContain('/downloads')
  const select = confirm.get('select[aria-label="下载音质"]'); expect(select.element).toHaveProperty('value', 'auto')
  expect(select.findAll('option').map(o => o.attributes('value'))).toEqual(['auto', '128k'])
  await select.setValue('128k'); await button('开始下载').trigger('click'); await flushPromises()
  expect(lx.create).toHaveBeenCalledWith(['a', 'b'], '128k', undefined)
  expect(wrapper!.findAll('.track')).toHaveLength(2); expect(wrapper!.text()).toContain('已提交 2 首歌曲')
})
it('指定音质失败保留确认框和歌曲选择', async () => {
  vi.mocked(lx.create).mockRejectedValueOnce(new Error('该音质不可用，请更换音源'))
  await page(); await search(); await button('下载').trigger('click'); await flushPromises()
  await button('开始下载').trigger('click'); await flushPromises()
  expect(wrapper!.text()).toContain('该音质不可用'); expect(button('开始下载').exists()).toBe(true)
})
it('链接预览逐页读取，重复页停止并保留已读歌曲', async () => {
  const preview = { platform: 'wy', kind: 'album' as const, name: '我的专辑', coverUrl: '', tracks: [track('a')], page: 1, total: 30, hasMore: true, warning: '平台仅公开部分歌曲' }
  vi.mocked(lx.preview).mockResolvedValueOnce(preview).mockResolvedValueOnce({ ...preview, page: 2 })
  await page(); await wrapper!.get('#tab-preview').trigger('click')
  await wrapper!.get('input[aria-label="歌单或专辑分享链接"]').setValue('https://music.163.com/album?id=123')
  await wrapper!.get('.link-form').trigger('submit'); await flushPromises()
  expect(wrapper!.text()).toContain('已读取 1 / 30 首'); expect(wrapper!.text()).toContain('平台仅公开部分歌曲')
  await button('继续读取歌曲').trigger('click'); await flushPromises()
  expect(wrapper!.text()).toContain('平台未返回新的歌曲'); expect(wrapper!.findAll('.track')).toHaveLength(1)
  expect(lx.preview).toHaveBeenLastCalledWith('https://music.163.com/album?id=123', 2)
})
it('配置改变后忽略迟到的搜索响应', async () => {
  let resolve!: (value: Awaited<ReturnType<typeof lx.search>>) => void
  vi.mocked(lx.search).mockReturnValue(new Promise(r => { resolve = r }))
  await page(); await search()
  vi.mocked(lx.status).mockResolvedValue({ ...status(), activeSource: { ...source, id: 'replacement' } })
  await button('刷新状态').trigger('click'); await flushPromises()
  resolve({ tracks: [track('late')], errors: [], warnings: [], page: 1, hasMore: false }); await flushPromises()
  expect(wrapper!.text()).not.toContain('歌曲late')
})
it('选择已读取歌曲跳过正在下载和已入库项目', async () => {
  wrapper = mount(TrackPicker, { props: { tracks: [track('a'), track('b', { downloading: true }), track('c', { inLibrary: true, songId: 'song' })], selected: [] } })
  await wrapper.findAllComponents(ElCheckbox)[0]!.get('input').setValue(true)
  expect(wrapper.emitted('update:selected')?.[0]).toEqual([['a']])
  expect(wrapper.get('a').attributes('href')).toContain('detailSongId=song')
})
it('任务完成后停止轮询，隐藏和卸载时忽略迟到响应', async () => {
  vi.useFakeTimers()
  vi.mocked(lx.batches).mockResolvedValueOnce({ items: [batch()], total: 1, page: 0, size: 20 }).mockResolvedValue({ items: [batch('COMPLETED')], total: 1, page: 0, size: 20 })
  wrapper = mount(RecordsPanel, { props: { visible: true, status: status() }, global: { plugins: [router] } }); await flushPromises()
  await vi.advanceTimersByTimeAsync(2600); await flushPromises(); expect(lx.batches).toHaveBeenCalledTimes(2)
  await vi.advanceTimersByTimeAsync(10000); expect(lx.batches).toHaveBeenCalledTimes(2)
  let resolve!: (value: Awaited<ReturnType<typeof lx.batches>>) => void
  vi.mocked(lx.batches).mockReturnValue(new Promise(r => { resolve = r })); await button('刷新记录').trigger('click')
  vi.spyOn(document, 'hidden', 'get').mockReturnValue(true); document.dispatchEvent(new Event('visibilitychange'))
  resolve({ items: [batch('FAILED')], total: 1, page: 0, size: 20 }); await flushPromises(); expect(wrapper.text()).not.toContain('失败 / 中断 1')
  wrapper.unmount(); wrapper = undefined; await vi.advanceTimersByTimeAsync(10000); expect(lx.batches).toHaveBeenCalledTimes(3)
})
it('重试和取消使用业务接口，并显示歌曲记录及深链接', async () => {
  vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
  vi.mocked(lx.batches).mockResolvedValue({ items: [batch('FAILED')], total: 1, page: 0, size: 20 })
  vi.mocked(lx.retry).mockResolvedValue({ batch: batch(), merged: 0, relatedBatchIds: [] })
  vi.mocked(lx.items).mockResolvedValue({ items: [{ id: 'item', track: track(), status: 'SUCCESS', requestedQuality: 'auto', resolvedQuality: '128k', receivedBytes: 1000, totalBytes: 1000, songId: 'saved-song', message: '封面获取失败，可稍后补充' }], total: 1, page: 0, size: 20 })
  wrapper = mount(RecordsPanel, { props: { visible: true, status: status() }, global: { plugins: [router] } }); await flushPromises()
  vi.mocked(lx.batches).mockResolvedValue({ items: [batch()], total: 1, page: 0, size: 20 })
  await button('重试未完成歌曲').trigger('click'); await flushPromises()
  expect(lx.retry).toHaveBeenCalledWith('batch'); expect(ElMessageBox.confirm).toHaveBeenCalledWith(expect.stringContaining('/downloads'), '重新尝试下载', expect.anything())
  expect(wrapper.text()).toContain('封面获取失败'); expect(wrapper.get('a').attributes('href')).toContain('detailSongId=saved-song')
  await button('取消未完成项目').trigger('click'); await flushPromises(); expect(lx.cancel).toHaveBeenCalledWith('batch')
})
it('HTTPS 导入默认 mobile，导入并启用后清除地址', async () => {
  vi.mocked(lx.importSource).mockResolvedValue(source)
  wrapper = mount(SourcesDialog, { props: { modelValue: false }, global: { stubs: { teleport: true } } }); await wrapper.setProps({ modelValue: true }); await flushPromises()
  await button('HTTPS 链接').trigger('click'); await wrapper.get('input[type="url"]').setValue('https://example.com/source.js?token=fixture')
  await wrapper.get('form').trigger('submit'); await flushPromises()
  expect(lx.importSource).toHaveBeenCalledWith({ name: '', url: 'https://example.com/source.js?token=fixture', environment: 'mobile' }); expect(lx.activate).toHaveBeenCalledWith('script')
  expect(wrapper.get('input[type="url"]').element).toHaveProperty('value', '')
})
it('拒绝非 js 文件，关闭音源窗口后忽略迟到的文件读取', async () => {
  wrapper = mount(SourcesDialog, { props: { modelValue: false }, global: { stubs: { teleport: true } } }); await wrapper.setProps({ modelValue: true }); await flushPromises()
  const input = wrapper.get('input[type="file"]'); Object.defineProperty(input.element, 'files', { configurable: true, value: [{ name: 'bad.txt', size: 3 }] }); await input.trigger('change'); expect(wrapper.text()).toContain('不超过 9 MB 的 .js')
  let resolve!: (value: ArrayBuffer) => void
  Object.defineProperty(input.element, 'files', { configurable: true, value: [{ name: 'source.js', size: 3, arrayBuffer: () => new Promise(r => { resolve = r }) }] }); await input.trigger('change'); await wrapper.setProps({ modelValue: false }); resolve(new TextEncoder().encode('lx;').buffer); await flushPromises(); await wrapper.setProps({ modelValue: true }); await flushPromises()
  expect(wrapper.text()).not.toContain('source.js'); expect(lx.importSource).not.toHaveBeenCalled()
})

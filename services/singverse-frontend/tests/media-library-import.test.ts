// @vitest-environment jsdom
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import MediaLibraryView from '../src/admin/modules/media-library/views/MediaLibraryView.vue'
import WebDavMetadataDialog from '../src/admin/modules/media-library/components/WebDavMetadataDialog.vue'
import { media } from '../src/admin/modules/media-library/api/media'
import { embeddedMetadata, type EmbeddedSummary } from '../src/admin/modules/media-library/api/embeddedMetadata'
import { shareImports, type ShareImportJob } from '../src/admin/modules/media-library/api/shareImports'
import { parserProfiles } from '../src/admin/modules/filename-parser/api/rules'
import type { MediaSource } from '../src/admin/modules/media-library/types'
import { workspace } from './fixtures/filename-workspace'

vi.mock('../src/admin/modules/media-library/api/media', () => ({ media: { list: vi.fn(), save: vi.fn(), get: vi.fn(), latestTask: vi.fn(), latestIngest: vi.fn(), scan: vi.fn(), createTask: vi.fn() } }))
vi.mock('../src/admin/modules/media-library/api/embeddedMetadata', async original => ({
  ...await original<typeof import('../src/admin/modules/media-library/api/embeddedMetadata')>(),
  embeddedMetadata: { summary: vi.fn(), configure: vi.fn(), refresh: vi.fn() },
}))
vi.mock('../src/admin/modules/media-library/api/shareImports', async original => ({
  ...await original<typeof import('../src/admin/modules/media-library/api/shareImports')>(),
  shareImports: { upload: vi.fn(), latest: vi.fn(), get: vi.fn(), action: vi.fn() },
}))
vi.mock('../src/admin/modules/filename-parser/api/rules', () => ({ parserProfiles: { load: vi.fn() } }))
vi.mock('../src/admin/core/auth/api', () => ({ authRequest: vi.fn(async () => ({})) }))

const source: MediaSource = { id: 'new-library', name: '我的曲库', type: 'WEBDAV', location: 'https://mine.example.invalid/dav/我的 KTV/', username: '', passwordConfigured: false, parserProfileId: 'default', scanEnabled: false, createdAt: '', updatedAt: '' }
const job = (state: string): ShareImportJob => ({ id: 'import', state, phase: state, totalCount: 2, processedCount: 0, addedCount: 0, updatedCount: 0, preservedCount: 0, unchangedCount: 0, verifiedCount: 0, presentCount: 0, missingCount: 0, mismatchCount: 0, unreachableCount: 0, removePrefix: '', addPrefix: '', preview: { total: 2, newSongs: 2, existingSongs: 0 }, samples: [{ originalPath: '华语/歌 曲.mkv', relativePath: '华语/歌 曲.mkv', mediaURL: source.location + '华语/歌 曲.mkv' }] })
const summary: EmbeddedSummary = { enabled: false, paused: false, accessGroup: '', privateOrigins: [], emptyScans: 0, canConfirmEmpty: false, counts: {}, presence: {}, budget: { files_started: 0, bytes_read: 0 } }
let wrapper: VueWrapper, router: Router
beforeEach(async () => {
  vi.resetAllMocks()
  vi.mocked(media.list).mockResolvedValue({ items: [], page: 0, size: 100, totalElements: 0 })
  vi.mocked(media.save).mockResolvedValue(source)
  vi.mocked(media.get).mockResolvedValue(source)
  vi.mocked(embeddedMetadata.summary).mockResolvedValue(summary)
  vi.mocked(parserProfiles.load).mockResolvedValue(workspace())
  vi.mocked(shareImports.upload).mockResolvedValue(job('PARSING'))
  vi.mocked(shareImports.get).mockResolvedValue(job('PREVIEW'))
  router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/library/media', component: MediaLibraryView },
    { path: '/library/songs', component: { template: '<p>歌曲</p>' } },
  ] })
  await router.push('/library/media')
  wrapper = mount({ template: '<RouterView />' }, { global: { plugins: [router], stubs: { teleport: true } } })
  await flushPromises()
})
afterEach(() => { wrapper?.unmount(); document.body.innerHTML = ''; vi.restoreAllMocks() })
const button = (text: string) => wrapper.findAll('button').find(item => item.text() === text)!
const checkbox = (text: string) => wrapper.findAll('label.el-checkbox').find(item => item.text() === text)!.get('input')
async function openWebDAV(fromPackage = false) {
  await button(fromPackage ? '导入资料包' : '添加来源').trigger('click'); await flushPromises()
  if (!fromPackage) { await button('WebDAV').trigger('click'); await flushPromises() }
  await wrapper.get('input[placeholder="https://nas.example/dav/歌曲目录/"]').setValue(source.location)
}
async function selectPackage(file = new File(['zip'], '曲库资料.zip')) {
  const input = wrapper.get('input[aria-label="新媒体库资料包"]')
  Object.defineProperty(input.element, 'files', { configurable: true, value: [file] })
  await input.trigger('change'); await flushPromises()
  return file
}
async function submit() { await wrapper.get('.media-dialog form').trigger('submit'); await flushPromises() }
function noMediaParsing() {
  expect(media.scan).not.toHaveBeenCalled()
  expect(media.createTask).not.toHaveBeenCalled()
  expect(embeddedMetadata.configure).not.toHaveBeenCalled()
  expect(embeddedMetadata.refresh).not.toHaveBeenCalled()
}

it('新建 WebDAV 选择资料包后自动上传，只预览不自动确认，并关闭所有自动解析入口', async () => {
  await openWebDAV()
  await checkbox('自动读取文件内的歌曲信息').setValue(true)
  await checkbox('启用定时扫描').setValue(true); await flushPromises()
  await checkbox('从已导出的资料包入库').setValue(true); await flushPromises()
  expect(checkbox('启用定时扫描').element).toHaveProperty('disabled', true)
  expect(checkbox('启用定时扫描').element).toHaveProperty('checked', false)
  const file = await selectPackage()
  expect(wrapper.get('.source-package').text()).toContain(file.name)
  expect(button('保存并导入资料包')).toBeDefined()
  expect(button('仅保存')).toBeUndefined()
  await submit()
  expect(media.save).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({ type: 'WEBDAV', location: source.location, startScan: false, autoExtractEmbedded: false, scanEnabled: false }), undefined)
  expect(shareImports.upload).toHaveBeenCalledExactlyOnceWith(source.id, file, expect.any(AbortSignal))
  expect(wrapper.text()).toContain('自己的地址：' + source.location)
  expect(button('确认导入资料')).toBeDefined()
  expect(shareImports.action).not.toHaveBeenCalled()
  expect(button('扫描歌曲').attributes('disabled')).toBeDefined()
  expect(button('开始自动提取').attributes('disabled')).toBeDefined()
  noMediaParsing()
  expect(wrapper.findComponent(WebDavMetadataDialog).props('initialImportFile')).toBeUndefined()

  vi.mocked(shareImports.action).mockResolvedValue(job('VERIFYING'))
  vi.mocked(shareImports.get).mockResolvedValue(job('VERIFYING'))
  await button('确认导入资料').trigger('click'); await flushPromises()
  expect(shareImports.action).toHaveBeenCalledExactlyOnceWith(source.id, 'import', 'apply')
  expect(media.get).toHaveBeenCalledWith(source.id)
  expect(shareImports.upload).toHaveBeenCalledTimes(1)
  noMediaParsing()
})

it('上传中阻止关闭窗口和站内离页，上传完成后可以离页', async () => {
  let finish!: (value: ShareImportJob) => void
  vi.mocked(shareImports.upload).mockReturnValue(new Promise(resolve => { finish = resolve }))
  await openWebDAV(true); await selectPackage(); await submit()
  expect(wrapper.text()).toContain('请保持页面打开')
  expect(button('关闭').attributes('disabled')).toBeDefined()
  const during = new Event('beforeunload', { cancelable: true }); window.dispatchEvent(during)
  expect(during.defaultPrevented).toBe(true)
  await router.push('/library/songs'); await flushPromises()
  expect(router.currentRoute.value.path).toBe('/library/media')
  finish(job('PARSING')); await flushPromises()
  expect(button('关闭').attributes('disabled')).toBeUndefined()
  const after = new Event('beforeunload', { cancelable: true }); window.dispatchEvent(after)
  expect(after.defaultPrevented).toBe(false)
  await router.push('/library/songs'); await flushPromises()
  expect(router.currentRoute.value.path).toBe('/library/songs')
  noMediaParsing()
})

it('上传失败可复用已保存的媒体库和已选文件重试，不重复新建来源', async () => {
  vi.mocked(shareImports.upload).mockRejectedValueOnce(new Error('网络断开'))
  await openWebDAV(true)
  const file = await selectPackage(); await submit()
  expect(wrapper.text()).toContain('网络断开')
  expect(wrapper.text()).toContain('媒体库已保存，无需重新新建')
  await button('重试上传资料包').trigger('click'); await flushPromises()
  expect(shareImports.upload).toHaveBeenCalledTimes(2)
  expect(shareImports.upload).toHaveBeenLastCalledWith(source.id, file, expect.any(AbortSignal))
  expect(media.save).toHaveBeenCalledTimes(1)
  expect(button('确认导入资料')).toBeDefined()
  noMediaParsing()
})

it('来源列表刷新缓慢时仍立即开始上传，不会丢失选择的资料包', async () => {
  await openWebDAV(true)
  const file = await selectPackage()
  let finish!: (value: Awaited<ReturnType<typeof media.list>>) => void
  vi.mocked(media.list).mockReturnValueOnce(new Promise(resolve => { finish = resolve }))
  await submit()
  expect(shareImports.upload).toHaveBeenCalledExactlyOnceWith(source.id, file, expect.any(AbortSignal))
  expect(button('确认导入资料')).toBeDefined()
  finish({ items: [source], page: 0, size: 100, totalElements: 1 }); await flushPromises()
  expect(shareImports.upload).toHaveBeenCalledTimes(1)
  noMediaParsing()
})

it('保存来源失败时保留选中的资料包，再次保存成功后才上传', async () => {
  vi.mocked(media.save).mockRejectedValueOnce(new Error('地址无效'))
  await openWebDAV(true)
  const file = await selectPackage(); await submit()
  expect(wrapper.text()).toContain('地址无效')
  expect(wrapper.get('.source-package').text()).toContain(file.name)
  expect(shareImports.upload).not.toHaveBeenCalled()
  await submit()
  expect(shareImports.upload).toHaveBeenCalledExactlyOnceWith(source.id, file, expect.any(AbortSignal))
  noMediaParsing()
})

it('未选包、空文件、非 ZIP 及超限资料包在新建来源前给出错误', async () => {
  await openWebDAV(true); await submit()
  expect(wrapper.text()).toContain('请先选择导出的 ZIP 资料包')
  await selectPackage(new File(['json'], 'metadata.json'))
  expect(wrapper.text()).toContain('请选择 ZIP 格式的资料包')
  await selectPackage(new File([], 'empty.zip'))
  expect(wrapper.text()).toContain('资料包为空')
  const large = new File(['zip'], 'large.zip'); Object.defineProperty(large, 'size', { value: 8 * 1024 ** 3 + 1 })
  await selectPackage(large)
  expect(wrapper.text()).toContain('分享包不能超过 8 GiB')
  await submit()
  expect(media.save).not.toHaveBeenCalled()
  expect(shareImports.upload).not.toHaveBeenCalled()
})

it('切换来源类型会清除资料包选择，普通 WebDAV 保留默认保存并扫描', async () => {
  await openWebDAV(true); await selectPackage()
  await button('本地目录').trigger('click'); await flushPromises()
  expect(button('保存并扫描')).toBeDefined()
  await button('WebDAV').trigger('click'); await flushPromises()
  expect(wrapper.find('input[type="file"]').exists()).toBe(false)
  await wrapper.get('input[placeholder="https://nas.example/dav/歌曲目录/"]').setValue(source.location)
  await submit()
  expect(media.save).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({ startScan: true, autoExtractEmbedded: false }), undefined)
  expect(shareImports.upload).not.toHaveBeenCalled()
})

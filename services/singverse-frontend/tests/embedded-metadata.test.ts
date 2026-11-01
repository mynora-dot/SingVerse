// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { ElButton, ElMessageBox } from 'element-plus'
import WebDavMetadataDialog from '../src/admin/modules/media-library/components/WebDavMetadataDialog.vue'
import EmbeddedMetadataPanel from '../src/admin/modules/media-library/components/EmbeddedMetadataPanel.vue'
import { embeddedMetadata, type EmbeddedSummary } from '../src/admin/modules/media-library/api/embeddedMetadata'
import type { MediaSource } from '../src/admin/modules/media-library/types'
import { media } from '../src/admin/modules/media-library/api/media'
import { authRequest } from '../src/admin/core/auth/api'
vi.mock('../src/admin/modules/media-library/api/embeddedMetadata', async importOriginal => {
  const original = await importOriginal<typeof import('../src/admin/modules/media-library/api/embeddedMetadata')>()
  return { ...original, embeddedMetadata: { summary: vi.fn(), configure: vi.fn(), confirmEmpty: vi.fn(), detail: vi.fn(), refresh: vi.fn() } }
})
vi.mock('../src/admin/modules/media-library/api/media', () => ({ media: { latestTask: vi.fn().mockResolvedValue(undefined), createTask: vi.fn(), songs: vi.fn().mockResolvedValue({ items: [{ id: 'trial', relativePath: 'sample.mp3' }] }) } }))
vi.mock('../src/admin/core/auth/api', () => ({ authRequest: vi.fn(async (path: string) => path.endsWith('/csrf') ? { token: 'test-csrf' } : {}) }))
const source: MediaSource = { id: 'source', name: '网盘曲库', type: 'WEBDAV', location: 'https://example.invalid/dav/', username: '', passwordConfigured: false, parserProfileId: 'default', createdAt: '', updatedAt: '' }
const summary: EmbeddedSummary = { enabled: false, paused: false, accessGroup: 'shared-cloud', privateOrigins: [], emptyScans: 0, canConfirmEmpty: false, counts: { COMPLETE: 12, PARTIAL: 3 }, presence: { missing: 1, changed: 2 }, budget: { files_started: 15, bytes_read: 1048576 } }
let wrapper: ReturnType<typeof mount>
async function openAdvanced() { const details=wrapper.get('details.advanced-options'); (details.element as HTMLDetailsElement).open=true; await details.trigger('toggle'); await flushPromises() }
beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(embeddedMetadata.summary).mockResolvedValue({ ...summary })
  vi.mocked(embeddedMetadata.configure).mockResolvedValue({ ...summary, enabled: true })
  vi.mocked(embeddedMetadata.detail).mockResolvedValue({ state: 'PARTIAL', changed: 1, result: { state: 'PARTIAL', fields: { lyrics: 'UNKNOWN' }, technical: { container: 'mp3' }, lyrics: { text: '内嵌歌词', timing: 'none' } } })
})
afterEach(() => { wrapper?.unmount(); vi.restoreAllMocks() })
it('主操作直接开启和暂停完善，无需勾选和额外保存', async () => {
  wrapper = mount(WebDavMetadataDialog, { props: { source }, global: { stubs: { teleport: true } } })
  await flushPromises()
  expect(wrapper.text()).not.toContain('MiB')
  expect(wrapper.find('.el-tabs').exists()).toBe(false)
  await wrapper.findAllComponents(ElButton).find(button => button.text() === '完善歌曲信息')!.trigger('click')
  await flushPromises()
  expect(embeddedMetadata.configure).toHaveBeenLastCalledWith('source', { enabled: true, paused: false, accessGroup: 'shared-cloud', privateOrigins: [] })
  vi.mocked(embeddedMetadata.configure).mockResolvedValue({ ...summary, enabled: true, paused: true })
  await wrapper.findAllComponents(ElButton).find(button => button.text() === '暂停完善')!.trigger('click')
  await flushPromises()
  expect(embeddedMetadata.configure).toHaveBeenLastCalledWith('source', { enabled: true, paused: true, accessGroup: 'shared-cloud', privateOrigins: [] })
  expect(wrapper.text()).toContain('继续完善')
})
it('仅在满足空库确认条件后提供全部下架操作', async () => {
  vi.mocked(embeddedMetadata.summary).mockResolvedValue({ ...summary, emptyScans: 2, canConfirmEmpty: true })
  vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as Awaited<ReturnType<typeof ElMessageBox.confirm>>)
  wrapper = mount(WebDavMetadataDialog, { props: { source }, global: { stubs: { teleport: true } } })
  await flushPromises(); await openAdvanced()
  await wrapper.findAllComponents(ElButton).find(button => button.text() === '确认空库同步')!.trigger('click')
  await flushPromises()
  expect(embeddedMetadata.confirmEmpty).toHaveBeenCalledWith('source')
})
it('展示缓存变化提示、歌词及普通和深度手动提取', async () => {
  vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as Awaited<ReturnType<typeof ElMessageBox.confirm>>)
  wrapper = mount(EmbeddedMetadataPanel, { props: { songId: 'song' } })
  await flushPromises()
  expect(wrapper.text()).toContain('文件可能已变化')
  expect(wrapper.text()).toContain('内嵌歌词')
  await wrapper.findAllComponents(ElButton).find(button => button.text() === '刷新内嵌元数据')!.trigger('click')
  await flushPromises()
  expect(embeddedMetadata.refresh).toHaveBeenLastCalledWith(['song'], false)
  await wrapper.findAllComponents(ElButton).find(button => button.text() === '深度提取')!.trigger('click')
  await flushPromises()
  expect(embeddedMetadata.refresh).toHaveBeenLastCalledWith(['song'], true)
})
it('切换歌曲后忽略旧请求结果', async () => {
  let finish: (value: { state: string }) => void = () => undefined
  vi.mocked(embeddedMetadata.detail).mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  wrapper = mount(EmbeddedMetadataPanel, { props: { songId: 'old-song' } })
  await wrapper.setProps({ songId: 'new-song' }); await flushPromises()
  finish({ state: 'EMPTY' }); await flushPromises()
  expect(wrapper.text()).toContain('部分完成')
  expect(wrapper.text()).not.toContain('没有内嵌标签')
})

it('ZIP 导入使用当前来源、会话与 CSRF，并刷新状态', async () => {
  vi.spyOn(ElMessageBox, 'alert').mockResolvedValue('confirm' as Awaited<ReturnType<typeof ElMessageBox.alert>>)
  const fetcher = vi.fn(async (path: string) => Response.json(path.endsWith('/latest') ? {} : path.endsWith('/csrf') ? { token: 'test-csrf' } : { imported: 2, skipped: 1 }))
  vi.stubGlobal('fetch', fetcher)
  try {
    wrapper = mount(WebDavMetadataDialog, { props: { source }, global: { stubs: { teleport: true } } })
    await flushPromises()
    const file = new File(['zip-test'], 'index.zip', { type: 'application/zip' })
    const input = wrapper.get('input[type="file"]')
    Object.defineProperty(input.element, 'files', { value: [file] })
    await input.trigger('change'); await flushPromises()
    expect(fetcher).toHaveBeenCalledWith('/api/v1/media-sources/source/embedded-metadata/share', expect.objectContaining({
      method: 'POST', credentials: 'same-origin', body: file,
      headers: { 'Content-Type': 'application/zip', 'X-CSRF-Token': 'test-csrf' },
    }))
    expect(wrapper.text()).not.toContain('开始导入')
    expect(wrapper.text()).toContain('已导入 2 首，已有 1 首自动跳过')
  } finally { vi.unstubAllGlobals() }
})

it('试提取一首不会开启整库自动提取，分享不再暴露旧版入口', async () => {
  wrapper = mount(WebDavMetadataDialog, { props: { source }, global: { stubs: { teleport: true } } })
  await flushPromises()
  await openAdvanced()
  vi.mocked(embeddedMetadata.detail).mockResolvedValueOnce({ state: 'PENDING' })
  await wrapper.findAllComponents(ElButton).find(button => button.text() === '检查一首歌曲')!.trigger('click')
  await flushPromises()
  expect(embeddedMetadata.refresh).toHaveBeenCalledWith(['trial'])
  expect(embeddedMetadata.configure).not.toHaveBeenCalled()
  expect(wrapper.text()).not.toContain('旧版')
  expect(wrapper.text()).not.toContain('SUCCEEDED')
})

it('已完成样本复用缓存，未知字段不显示为确认没有', async () => {
  wrapper = mount(WebDavMetadataDialog, { props: { source }, global: { stubs: { teleport: true } } })
  await flushPromises(); await openAdvanced()
  await wrapper.findAllComponents(ElButton).find(button => button.text() === '检查一首歌曲')!.trigger('click')
  await flushPromises()
  expect(embeddedMetadata.refresh).not.toHaveBeenCalled()
  expect(wrapper.text()).toContain('尚未读取到内嵌歌名')
  expect(wrapper.text()).not.toContain('文件未提供内嵌歌名')
})

it('默认隐藏同步和分包细节，分享可直接提交给后台准备', async () => {
  vi.mocked(embeddedMetadata.summary).mockResolvedValue({ ...summary, files: { pending: 2, failed: 1, directory: 'library/source' } })
  wrapper = mount(WebDavMetadataDialog, { props: { source: { ...source, type: 'LOCAL' } }, global: { stubs: { teleport: true } } })
  await flushPromises()
  expect(wrapper.text()).not.toContain('等待处理')
  expect(wrapper.text()).not.toContain('暂未保存')
  expect(wrapper.text()).not.toContain('5000')
  expect(wrapper.text()).not.toContain('启用自动补齐')
  expect(wrapper.findAllComponents(ElButton).find(button => button.text() === '导出分享包')!.props('disabled')).toBe(false)
  expect(media.latestTask).not.toHaveBeenCalled()
  await openAdvanced()
  expect(wrapper.text()).toContain('等待处理 2 首')
  expect(wrapper.text()).toContain('1 首暂未保存')
  expect(wrapper.text()).toContain('5000 首')
})

it('后台准备只显示可理解的进度，不暴露任务状态和分包细节', async () => {
  vi.mocked(authRequest).mockResolvedValueOnce({ id: 'job', state: 'WAITING', total_count: 15, processed_count: 0, part_count: 0 })
  wrapper = mount(WebDavMetadataDialog, { props: { source }, global: { stubs: { teleport: true } } })
  await flushPromises()
  expect(wrapper.text()).toContain('正在准备')
  expect(wrapper.text()).toContain('关闭窗口也会继续')
  expect(wrapper.text()).not.toContain('WAITING')
  expect(wrapper.text()).not.toContain('分包')
})

// @vitest-environment jsdom
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { beforeEach, afterEach, expect, it, vi } from 'vitest'
import ShareImportPanel from '../src/admin/modules/media-library/components/ShareImportPanel.vue'
import { shareImports, type ShareImportJob } from '../src/admin/modules/media-library/api/shareImports'
import type { MediaSource } from '../src/admin/modules/media-library/types'
vi.mock('../src/admin/modules/media-library/api/shareImports', async original => ({
  ...await original<typeof import('../src/admin/modules/media-library/api/shareImports')>(),
  shareImports: { latest: vi.fn(), get: vi.fn(), upload: vi.fn(), mapping: vi.fn(), action: vi.fn(), differences: vi.fn() },
}))
const source: MediaSource = { id: 'mine', name: '自己的曲库', type: 'WEBDAV', location: 'https://my.example.invalid/dav/我的曲库/', username: '', passwordConfigured: false, parserProfileId: 'default', createdAt: '', updatedAt: '' }
const job = (state: string): ShareImportJob => ({ id: 'job', state, phase: state, totalCount: 2, processedCount: 0, addedCount: 0, updatedCount: 0, preservedCount: 0, unchangedCount: 0, verifiedCount: 0, presentCount: 0, missingCount: 0, mismatchCount: 0, unreachableCount: 0, removePrefix: '', addPrefix: '', preview: { total: 2, newSongs: 1, existingSongs: 1 }, samples: [{ originalPath: '旧目录/歌 曲.mkv', relativePath: '旧目录/歌 曲.mkv', mediaURL: 'https://my.example.invalid/dav/我的曲库/旧目录/歌 曲.mkv' }] })
let wrapper: VueWrapper
beforeEach(() => { vi.resetAllMocks(); vi.mocked(shareImports.latest).mockResolvedValue({} as ShareImportJob) })
afterEach(() => { wrapper?.unmount(); vi.useRealTimers() })
const button = (text: string) => wrapper.findAll('button').find(item => item.text() === text)!
it('上传完成后台解析，预览自己的地址后显式确认，不自动应用资料', async () => {
  vi.mocked(shareImports.upload).mockResolvedValue(job('PARSING'))
  vi.mocked(shareImports.get).mockResolvedValue(job('PREVIEW'))
  wrapper = mount(ShareImportPanel, { props: { source } }); await flushPromises()
  const file = new File(['zip'], '资料.zip'), input = wrapper.get('input[type="file"]')
  Object.defineProperty(input.element, 'files', { value: [file] })
  await input.trigger('change'); await flushPromises()
  expect(shareImports.upload).toHaveBeenCalledWith('mine', file, expect.any(AbortSignal))
  expect(wrapper.text()).toContain('自己的地址：https://my.example.invalid/')
  expect(wrapper.text()).toContain('为已有 1 首合并资料')
  expect(shareImports.action).not.toHaveBeenCalled()
  vi.mocked(shareImports.action).mockResolvedValue(job('VERIFYING'))
  vi.mocked(shareImports.get).mockResolvedValue(job('VERIFYING'))
  await button('确认导入资料').trigger('click'); await flushPromises()
  expect(shareImports.action).toHaveBeenCalledWith('mine', 'job', 'apply')
  expect(wrapper.text()).toContain('上传已完成，可以关闭页面')
  expect(wrapper.emitted('updated')).toHaveLength(1)
})
it('目录映射修改后必须刷新预览再确认，并保留根目录', async () => {
  vi.mocked(shareImports.latest).mockResolvedValue(job('PREVIEW'))
  wrapper = mount(ShareImportPanel, { props: { source } }); await flushPromises()
  const inputs = wrapper.findAll('input[type="text"]')
  await inputs[0]!.setValue('旧目录')
  expect(button('确认导入资料').attributes('disabled')).toBeDefined()
  vi.mocked(shareImports.mapping).mockResolvedValue({ ...job('PREVIEW'), removePrefix: '旧目录', samples: [{ originalPath: '旧目录/歌 曲.mkv', relativePath: '歌 曲.mkv', mediaURL: source.location + '歌 曲.mkv' }] })
  await button('更新路径预览').trigger('click'); await flushPromises()
  expect(shareImports.mapping).toHaveBeenCalledWith('mine', 'job', '旧目录', '')
  expect(button('确认导入资料').attributes('disabled')).toBeUndefined()
  expect(wrapper.text()).toContain('自己的地址：' + source.location + '歌 曲.mkv')
})
it('从任务中心恢复指定任务，差异显示保留的本地字段与连接原因', async () => {
  const result = { ...job('SUCCEEDED'), phase: 'VERIFYING', preservedCount: 1, unreachableCount: 1, processedCount: 2, verifiedCount: 2 }
  vi.mocked(shareImports.get).mockResolvedValue(result)
  vi.mocked(shareImports.differences).mockResolvedValue({ items: [{ ordinal: 1, relativePath: '歌曲.mkv', outcome: 'PRESERVED', fields: ['title'], local: { title: '我的订正' }, incoming: { title: '共享修订' }, verification: 'UNREACHABLE', verificationError: '来源权限错误' }], nextCursor: 1, hasMore: false })
  wrapper = mount(ShareImportPanel, { props: { source, jobId: 'earlier-task' } }); await flushPromises()
  expect(shareImports.get).toHaveBeenCalledWith('mine', 'earlier-task')
  await button('查看差异与待处理项').trigger('click'); await flushPromises()
  expect(wrapper.text()).toContain('保留本地「我的订正」；共享「共享修订」')
  expect(wrapper.text()).toContain('来源权限错误')
  vi.mocked(shareImports.action).mockResolvedValue(job('VERIFYING'))
  await button('重试未通过的核验').trigger('click'); await flushPromises()
  expect(shareImports.action).toHaveBeenCalledWith('mine', 'job', 'retry')
})

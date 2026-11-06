// @vitest-environment jsdom
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import MissingFeedbackView from '../src/admin/modules/missing-feedback/MissingFeedbackView.vue'
import { missingFeedback, type MissingFeedback } from '../src/admin/modules/missing-feedback/api'
import { songs } from '../src/admin/modules/song-management/api/songs'

vi.mock('../src/admin/modules/missing-feedback/api', () => ({ missingFeedback: { list: vi.fn(), get: vi.fn(), create: vi.fn(), update: vi.fn() } }))
vi.mock('../src/admin/modules/song-management/api/songs', () => ({ songs: { list: vi.fn() } }))
const feedback: MissingFeedback = {
  id: 'feedback-1', title: '后来', artist: '刘若英', versionLabel: '原版 MV', description: '朋友想唱这首歌', submittedBy: '小林',
  source: 'ADMIN', status: 'PENDING', handlingNote: '', resolvedSongId: null, resolvedSongTitle: null,
  resolvedSongArtists: [], resolvedSongAvailable: false, revision: 1, createdAt: '2026-11-06T02:00:00.000Z', updatedAt: '2026-11-06T02:00:00.000Z', resolvedAt: null,
}
const song = { id: 'song-1', sourceName: '家庭曲库', relativePath: '刘若英 - 后来.mp4', result: { title: '后来', artists: ['刘若英'] } }
let wrapper: VueWrapper | undefined
beforeEach(() => {
  vi.mocked(missingFeedback.list).mockResolvedValue({ items: [feedback], totalElements: 1, page: 0, size: 20 })
  vi.mocked(missingFeedback.get).mockResolvedValue({ ...feedback })
  vi.mocked(missingFeedback.create).mockResolvedValue({ ...feedback })
  vi.mocked(missingFeedback.update).mockResolvedValue({ ...feedback, revision: 2 })
  vi.mocked(songs.list).mockResolvedValue({ items: [song], totalElements: 1 } as never)
})
afterEach(() => { wrapper?.unmount(); wrapper = undefined; document.body.innerHTML = ''; vi.resetAllMocks() })
async function page() { wrapper = mount(MissingFeedbackView, { global: { stubs: { teleport: true } } }); await flushPromises() }
function button(text: string) { return wrapper!.findAll('button').find(b => b.text() === text)! }

it('真实空态提供管理员登记入口，读取失败显示重试', async () => {
  vi.mocked(missingFeedback.list).mockResolvedValueOnce({ items: [], totalElements: 0, page: 0, size: 20 })
  await page()
  expect(wrapper!.text()).toContain('还没有缺歌反馈')
  expect(button('登记第一首缺歌').exists()).toBe(true)
  vi.mocked(missingFeedback.list).mockRejectedValueOnce(new Error('反馈读取失败'))
  await wrapper!.get('form.feedback-filters').trigger('submit'); await flushPromises()
  expect(wrapper!.text()).toContain('反馈读取失败')
  expect(wrapper!.text()).not.toContain('还没有缺歌反馈')
  await button('重新加载').trigger('click'); await flushPromises()
  expect(wrapper!.text()).toContain('朋友想唱这首歌')
})

it('搜索和状态由后端分页前筛选，切换条件重置页码', async () => {
  await page()
  await wrapper!.get('input[aria-label="搜索缺歌反馈"]').setValue('刘若英')
  await wrapper!.get('form.feedback-filters').trigger('submit'); await flushPromises()
  expect(missingFeedback.list).toHaveBeenLastCalledWith('刘若英', 'ALL', 0)
  await wrapper!.get('select[aria-label="缺歌处理状态"]').setValue('PROCESSING'); await flushPromises()
  expect(missingFeedback.list).toHaveBeenLastCalledWith('刘若英', 'PROCESSING', 0)
})

it('登记失败保留原内容和幂等标识，保存期间阻止重复请求', async () => {
  await page(); await button('登记缺歌').trigger('click'); await flushPromises()
  expect(button('保存登记').attributes('disabled')).toBeDefined()
  await wrapper!.get('input[aria-label="缺歌名称"]').setValue('后来')
  await wrapper!.get('input[aria-label="缺歌歌手"]').setValue('刘若英')
  let reject!: (e: Error) => void
  vi.mocked(missingFeedback.create).mockReturnValueOnce(new Promise((_, r) => { reject = r }))
  await button('保存登记').trigger('click'); await button('保存登记').trigger('click')
  expect(missingFeedback.create).toHaveBeenCalledTimes(1)
  const original = vi.mocked(missingFeedback.create).mock.calls[0]![0]
  reject(new Error('连接中断，稍后重试')); await flushPromises()
  expect(wrapper!.text()).toContain('连接中断，稍后重试')
  expect(wrapper!.get('input[aria-label="缺歌名称"]').element).toHaveProperty('value', '后来')
  await button('保存登记').trigger('click'); await flushPromises()
  expect(vi.mocked(missingFeedback.create).mock.calls[1]![0].requestId).toBe(original.requestId)
})

it('已补齐必须选择具体曲库歌曲，并携带当前修订号保存', async () => {
  await page(); await button('查看 / 处理').trigger('click'); await flushPromises()
  expect(wrapper!.text()).toContain('朋友想唱这首歌')
  await wrapper!.get('select[aria-label="设置缺歌处理状态"]').setValue('RESOLVED')
  expect(button('保存处理结果').attributes('disabled')).toBeDefined()
  await button('选择已补齐的歌曲').trigger('click'); await flushPromises()
  expect(songs.list).toHaveBeenCalledWith({ q: '后来', sourceId: '', status: 'ALL', page: 0, size: 20 })
  await wrapper!.get('button[aria-label="关联歌曲：后来"]').trigger('click'); await flushPromises()
  await wrapper!.get('textarea[aria-label="缺歌处理备注"]').setValue('原版 MV 已扫描入库')
  await button('保存处理结果').trigger('click'); await flushPromises()
  expect(missingFeedback.update).toHaveBeenCalledWith('feedback-1', { revision: 1, status: 'RESOLVED', handlingNote: '原版 MV 已扫描入库', resolvedSongId: 'song-1' })
})

it('关联歌曲已下架时保留快照并要求重新选择，重开时清除关联', async () => {
  vi.mocked(missingFeedback.get).mockResolvedValueOnce({ ...feedback, status: 'RESOLVED', resolvedSongId: 'removed-song', resolvedSongTitle: '旧版后来', resolvedSongArtists: ['刘若英'], resolvedSongAvailable: false, revision: 4 })
  await page(); await button('查看 / 处理').trigger('click'); await flushPromises()
  expect(wrapper!.text()).toContain('旧版后来')
  expect(wrapper!.text()).toContain('这首歌已下架')
  expect(button('保存处理结果').attributes('disabled')).toBeDefined()
  await wrapper!.get('select[aria-label="设置缺歌处理状态"]').setValue('PENDING')
  await button('保存处理结果').trigger('click'); await flushPromises()
  expect(missingFeedback.update).toHaveBeenCalledWith('feedback-1', { revision: 4, status: 'PENDING', handlingNote: '', resolvedSongId: null })
})

it('并发冲突保留处理备注，显式重新读取后使用新修订号', async () => {
  await page(); await button('查看 / 处理').trigger('click'); await flushPromises()
  await wrapper!.get('select[aria-label="设置缺歌处理状态"]').setValue('PROCESSING')
  await wrapper!.get('textarea[aria-label="缺歌处理备注"]').setValue('整理中')
  vi.mocked(missingFeedback.update).mockRejectedValueOnce(new Error('反馈已被更新，请重新打开后处理'))
  await button('保存处理结果').trigger('click'); await flushPromises()
  expect(wrapper!.get('textarea[aria-label="缺歌处理备注"]').element).toHaveProperty('value', '整理中')
  vi.mocked(missingFeedback.get).mockResolvedValueOnce({ ...feedback, revision: 5, status: 'CLOSED', handlingNote: '另一窗口已处理' })
  await button('重新读取反馈').trigger('click'); await flushPromises()
  await button('保存处理结果').trigger('click'); await flushPromises()
  expect(missingFeedback.update).toHaveBeenLastCalledWith('feedback-1', { revision: 5, status: 'CLOSED', handlingNote: '另一窗口已处理', resolvedSongId: null })
})

it('迟到的列表响应不会覆盖新筛选结果', async () => {
  let resolve!: (value: Awaited<ReturnType<typeof missingFeedback.list>>) => void
  vi.mocked(missingFeedback.list).mockReturnValueOnce(new Promise(r => { resolve = r }))
  wrapper = mount(MissingFeedbackView, { global: { stubs: { teleport: true } } })
  await wrapper.get('select[aria-label="缺歌处理状态"]').setValue('CLOSED'); await flushPromises()
  resolve({ items: [{ ...feedback, title: '过期结果' }], totalElements: 1, page: 0, size: 20 }); await flushPromises()
  expect(wrapper.text()).not.toContain('过期结果')
  expect(wrapper.text()).toContain('后来')
})

it('重读详情途中关闭会取消加载状态，迟到响应不会阻止再次登记', async () => {
  await page(); await button('查看 / 处理').trigger('click'); await flushPromises()
  vi.mocked(missingFeedback.update).mockRejectedValueOnce(new Error('修订号冲突'))
  await button('保存处理结果').trigger('click'); await flushPromises()
  let resolve!: (value: MissingFeedback) => void
  vi.mocked(missingFeedback.get).mockReturnValueOnce(new Promise(r => { resolve = r }))
  await button('重新读取反馈').trigger('click'); await flushPromises()
  expect(button('登记缺歌').attributes('disabled')).toBeDefined()
  await wrapper!.get('.el-dialog__headerbtn').trigger('click'); await flushPromises()
  expect(button('登记缺歌').attributes('disabled')).toBeUndefined()
  expect(button('查看 / 处理').attributes('disabled')).toBeUndefined()
  await button('登记缺歌').trigger('click'); await flushPromises()
  resolve({ ...feedback, title: '迟到详情' }); await flushPromises()
  expect(wrapper!.text()).not.toContain('迟到详情')
  expect(wrapper!.get('input[aria-label="缺歌名称"]').element).toHaveProperty('value', '')
})

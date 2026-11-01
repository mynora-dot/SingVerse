// @vitest-environment jsdom
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import MetadataScrapeView from '../src/admin/modules/metadata/views/MetadataScrapeView.vue'
import MetadataMatchDialog from '../src/admin/modules/metadata/components/MetadataMatchDialog.vue'
import SingleSongScrapeDialog from '../src/admin/modules/metadata/components/SingleSongScrapeDialog.vue'
import { metadata } from '../src/admin/modules/metadata/api/metadata'
import { media } from '../src/admin/modules/media-library/api/media'
import { singleReview } from '../src/admin/modules/metadata/api/single-review'
import type { MetadataBatch, MetadataCandidate } from '../src/admin/modules/metadata/types'
vi.mock('../src/admin/modules/metadata/api/metadata', () => ({ metadata: Object.fromEntries(['settings', 'saveSettings', 'latest', 'batch', 'start', 'pause', 'resume', 'cancel', 'retry', 'search', 'apply', 'applyItem', 'candidateCover'].map(name => [name, vi.fn()])) }))
vi.mock('../src/admin/modules/media-library/api/media', () => ({ media: { list: vi.fn() } }))
vi.mock('../src/admin/modules/metadata/api/single-review', () => ({ singleReview: { load: vi.fn(), apply: vi.fn(), applyBatch: vi.fn() } }))
let wrapper: VueWrapper
const config = { enabled: true, providers: ['NETEASE' as const], autoApply: false, threshold: .95, minIntervalSeconds: 3, maxIntervalSeconds: 8 }
const candidate: MetadataCandidate = { id: 'candidate-1', score: .95, track: { provider: 'NETEASE', externalId: '1', title: '晴天', artists: ['周杰伦'], album: '叶惠美', aliases: [], releaseDate: '2003-07-31', durationMs: 270000, coverUrl: null, availability: 'UNKNOWN', fetchedAt: '2026-10-28T00:00:00Z' } }
function batch(status = 'RUNNING'): MetadataBatch { return { id: 'batch-1', mode: 'SELECTED', status, total: 1, completed: 0, pending: 1, processing: 0, review: 0, applied: 0, failed: 0, skippedExisting: 0, autoApply: false, threshold: .95, createdAt: '', updatedAt: '', items: [], page: 0, size: 20, totalElements: 1 } }
function button(text: string) { return wrapper.findAll('button').find(item => item.text() === text)! }
async function page(path = '/library/songs/metadata') {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/library/songs/metadata', component: MetadataScrapeView }, { path: '/library/songs', component: { template: '<div>歌曲管理</div>' } }] })
  await router.push(path)
  wrapper = mount({ template: '<RouterView />' }, { global: { plugins: [router], stubs: { teleport: true } } })
  await flushPromises(); return router
}
beforeEach(() => {
  vi.mocked(media.list).mockResolvedValue({ items: [{ id: 'source-1', name: '家庭 NAS', type: 'WEBDAV', location: 'https://nas.example/dav/', username: '', passwordConfigured: false, parserProfileId: 'default', createdAt: '', updatedAt: '' }], page: 0, size: 100, totalElements: 1 })
  vi.mocked(metadata.settings).mockResolvedValue({ ...config })
  vi.mocked(metadata.latest).mockResolvedValue(undefined)
  vi.mocked(metadata.batch).mockResolvedValue(batch())
  vi.mocked(metadata.search).mockResolvedValue({ candidates: [candidate], errors: [] })
})
it('可选择指定媒体来源创建批次，确认文案与请求范围一致', async () => {
  vi.mocked(metadata.start).mockResolvedValue(batch())
  const confirm = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as Awaited<ReturnType<typeof ElMessageBox.confirm>>)
  await page()
  await wrapper.get('select[aria-label="刮削媒体来源"]').setValue('source-1')
  await button('刮削所选媒体源').trigger('click'); await flushPromises()
  expect(confirm).toHaveBeenCalledWith(expect.stringContaining('仅检查媒体来源「家庭 NAS」'), '开始元数据刮削', expect.any(Object))
  expect(metadata.start).toHaveBeenCalledWith(true, [], 'source-1')
})
afterEach(() => { wrapper?.unmount(); document.body.innerHTML = ''; vi.restoreAllMocks(); vi.clearAllMocks(); vi.useRealTimers() })
it('关闭状态阻止启动，修改设置需先保存，自动写入保持关闭', async () => {
  vi.mocked(metadata.settings).mockResolvedValue({ ...config, enabled: false })
  vi.mocked(metadata.saveSettings).mockResolvedValue(config)
  await page()
  expect((button('刮削全部歌曲').element as HTMLButtonElement).disabled).toBe(true)
  await wrapper.findAll('input[type=checkbox]')[0]!.setValue(true)
  expect((button('刮削全部歌曲').element as HTMLButtonElement).disabled).toBe(true)
  await wrapper.get('form').trigger('submit'); await flushPromises()
  expect(metadata.saveSettings).toHaveBeenCalledWith(config)
  expect((button('刮削全部歌曲').element as HTMLButtonElement).disabled).toBe(false)
})
it('选中批次确认后提交所选歌曲，并显示持久化进度', async () => {
  vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as Awaited<ReturnType<typeof ElMessageBox.confirm>>)
  vi.mocked(metadata.start).mockResolvedValue(batch())
  const router = await page('/library/songs/metadata?songIds=a,b')
  await button('刮削已选（2）').trigger('click'); await flushPromises()
  expect(metadata.start).toHaveBeenCalledWith(false, ['a', 'b'])
  expect(router.currentRoute.value.query.batch).toBe('batch-1')
  expect(wrapper.text()).toContain('正在匹配')
  expect(wrapper.text()).toContain('本批次自动写入：关闭')
})
it('暂停、继续和离开页面停止轮询', async () => {
  vi.useFakeTimers()
  vi.mocked(metadata.latest).mockResolvedValue(batch())
  vi.mocked(metadata.pause).mockResolvedValue(batch('PAUSED'))
  vi.mocked(metadata.batch).mockResolvedValueOnce(batch('PAUSED')).mockResolvedValue(batch())
  vi.mocked(metadata.resume).mockResolvedValue(batch())
  await page(); await button('暂停').trigger('click'); await flushPromises()
  expect(metadata.pause).toHaveBeenCalledWith('batch-1')
  await button('继续').trigger('click'); await flushPromises()
  expect(metadata.resume).toHaveBeenCalledWith('batch-1')
  wrapper.unmount(); const calls = vi.mocked(metadata.batch).mock.calls.length
  await vi.advanceTimersByTimeAsync(6000)
  expect(metadata.batch).toHaveBeenCalledTimes(calls)
})
it('高相似度候选自动选中，但必须确认才应用', async () => {
  wrapper = mount(MetadataMatchDialog, { props: { songId: 'song-1', title: '晴天', artists: ['周杰伦'] }, global: { stubs: { teleport: true } } })
  await flushPromises()
  expect(metadata.search).toHaveBeenCalledWith('song-1', '晴天 周杰伦', ['NETEASE'])
  expect(metadata.apply).not.toHaveBeenCalled()
  expect((button('确认应用元数据').element as HTMLButtonElement).disabled).toBe(false)
  expect((wrapper.get('input[type=radio]').element as HTMLInputElement).checked).toBe(true)
  await button('确认应用元数据').trigger('click'); await flushPromises()
  expect(metadata.apply).toHaveBeenCalledWith('song-1', 'candidate-1', expect.arrayContaining(['title', 'artists', 'album']))
  expect(wrapper.emitted('applied')).toHaveLength(1)
})
it('批次审核复用单曲编辑工作区及批次候选', async () => {
  const item = { id: 'item-1', songId: 'song-1', title: '晴天', artists: ['周杰伦'], status: 'REVIEW' as const, candidates: [candidate], error: null, updatedAt: '', confidence: 'HIGH' as const }
  vi.mocked(metadata.latest).mockResolvedValue({ ...batch('COMPLETED'), completed: 1, pending: 0, review: 1, items: [item] })
  vi.mocked(singleReview.load).mockResolvedValue({ song: { id: 'song-1', sourceName: 'nas', relativePath: '晴天.mkv', fileSize: 1024, metadata: null } as never, fingerprint: 'fingerprint', version: 'version', values: { title: '晴天', artists: ['周杰伦'], album: null, releaseDate: null, aliases: [], durationMs: null, cover: null } })
  await page()
  await button('审核候选').trigger('click'); await flushPromises()
  expect(wrapper.findComponent(SingleSongScrapeDialog).exists()).toBe(true)
  expect(wrapper.text()).toContain('当前歌曲 · 入库信息')
  expect(wrapper.text()).toContain('选中候选与应用预览')
  expect(wrapper.text()).toContain('编辑元数据')
  expect(wrapper.text()).toContain('选择封面')
  expect(metadata.search).not.toHaveBeenCalled()
  vi.mocked(singleReview.applyBatch).mockResolvedValue({ metadata: null, updated: ['album'], skipped: [], retained: [], before: { title: '晴天', artists: ['周杰伦'], album: null, releaseDate: null, aliases: [], durationMs: null, cover: null }, after: { title: '晴天', artists: ['周杰伦'], album: '叶惠美', releaseDate: null, aliases: [], durationMs: null, cover: null } })
  await wrapper.findAll('button').find(item => item.text().includes('确认应用（'))!.trigger('click'); await flushPromises()
  expect(singleReview.applyBatch).toHaveBeenCalledWith('batch-1', 'item-1', expect.objectContaining({ candidateId: 'candidate-1', fields: expect.arrayContaining(['album']) }))
})

it('批量随机间隔显示服务端配置并随设置保存', async () => {
  vi.mocked(metadata.saveSettings).mockResolvedValue({ ...config, minIntervalSeconds: 10, maxIntervalSeconds: 20 })
  await page()
  expect((wrapper.get('input[aria-label="批量最小间隔秒"]').element as HTMLInputElement).value).toBe('3')
  await wrapper.get('input[aria-label="批量最小间隔秒"]').setValue('10')
  await wrapper.get('input[aria-label="批量最大间隔秒"]').setValue('20')
  await wrapper.get('form').trigger('submit'); await flushPromises()
  expect(metadata.saveSettings).toHaveBeenCalledWith({ ...config, minIntervalSeconds: 10, maxIntervalSeconds: 20 })
})
it('批量请求间隔允许设置为零秒', async () => {
  vi.mocked(metadata.saveSettings).mockResolvedValue({ ...config, minIntervalSeconds: 0, maxIntervalSeconds: 0 })
  await page()
  expect(wrapper.get('input[aria-label="批量最小间隔秒"]').attributes('min')).toBe('0')
  await wrapper.get('input[aria-label="批量最小间隔秒"]').setValue('0')
  await wrapper.get('input[aria-label="批量最大间隔秒"]').setValue('0')
  await wrapper.get('form').trigger('submit'); await flushPromises()
  expect(metadata.saveSettings).toHaveBeenCalledWith({ ...config, minIntervalSeconds: 0, maxIntervalSeconds: 0 })
})
it('运行批次优先显示统一整体进度且设置默认折叠，可重新展开', async () => {
  vi.mocked(metadata.latest).mockResolvedValue({ ...batch(), total: 12, completed: 5, pending: 6, processing: 1, review: 2, skippedExisting: 3, processableTotal: 9, legacySkipped: 3, observedAt: '2026-10-28T00:00:00Z' })
  await page()
  expect(wrapper.get('.progress-caption').text()).toContain('已处理 5 / 12 首')
  expect(wrapper.get('[role=progressbar]').attributes('aria-valuenow')).toBe('42')
  expect(wrapper.get('#batch-settings-form').isVisible()).toBe(false)
  await button('展开配置').trigger('click'); await flushPromises()
  expect(button('收起配置').attributes('aria-expanded')).toBe('true')
  expect((wrapper.get('#batch-settings-form').element as HTMLElement).style.display).not.toBe('none')
  expect(wrapper.text()).toContain('仅保存汇总')
  expect(wrapper.text()).toContain('最近读取')
})
it('5% 候选明确显示无可信匹配和核对入口，不能误呈现为高可信推荐', async () => {
  vi.mocked(metadata.latest).mockResolvedValue({ ...batch('COMPLETED'), total: 1, completed: 1, pending: 0, review: 1, items: [{ id: 'item-1', songId: 'song-1', title: '原歌名', artists: ['原歌手'], status: 'REVIEW', candidates: [{ ...candidate, score: .05 }], error: null, updatedAt: '', confidence: 'NONE', sourceId: 'source-1' }] })
  await page()
  expect(wrapper.get('.batch-song-table').text()).toContain('5%')
  expect(wrapper.get('.batch-song-table').text()).toContain('无可信候选')
  expect(wrapper.get('.batch-song-table').text()).toContain('低分候选仅供核对')
  expect(button('核对候选')).toBeDefined()
  expect(button('重新匹配')).toBeDefined()
  expect(wrapper.get('.batch-song-table').text()).not.toContain('审核候选')
  expect(metadata.apply).not.toHaveBeenCalled()
})
it('状态快捷筛选、歌手搜索和排序提交给后端，而不是只过滤当前页', async () => {
  vi.mocked(metadata.latest).mockResolvedValue(batch('COMPLETED'))
  await page()
  await wrapper.get('.batch-stats button:last-child').trigger('click'); await flushPromises()
  expect(metadata.batch).toHaveBeenLastCalledWith('batch-1', 0, 'SKIPPED_EXISTING', '', 'DEFAULT')
  await wrapper.get('input[aria-label="搜索批次歌名或歌手"]').setValue('歌手%')
  await wrapper.get('form[aria-label="批次歌曲筛选"]').trigger('submit'); await flushPromises()
  await wrapper.get('select[aria-label="批次排序"]').setValue('SCORE_DESC'); await flushPromises()
  expect(metadata.batch).toHaveBeenLastCalledWith('batch-1', 0, 'SKIPPED_EXISTING', '歌手%', 'SCORE_DESC')
})
it('保存运行中设置前告知暂停，后端返回暂停状态后提供继续', async () => {
  vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as Awaited<ReturnType<typeof ElMessageBox.confirm>>)
  vi.mocked(metadata.latest).mockResolvedValue(batch())
  vi.mocked(metadata.batch).mockResolvedValue(batch('PAUSED'))
  vi.mocked(metadata.saveSettings).mockResolvedValue({ ...config, threshold: .96 })
  await page(); await button('展开配置').trigger('click')
  await wrapper.get('input[aria-label="自动写入阈值"]').setValue('0.96')
  await wrapper.get('form').trigger('submit'); await flushPromises()
  expect(ElMessageBox.confirm).toHaveBeenCalledWith(expect.stringContaining('暂停'), '保存并暂停批次', expect.anything())
  expect(metadata.saveSettings).toHaveBeenCalledWith(expect.objectContaining({ threshold: .96 }))
  expect(button('继续')).toBeDefined()
  expect(wrapper.text()).toContain('批次已暂停')
})
it('终止确认说明未处理和已写入的真实语义，终止后不再显示续跑', async () => {
  vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as Awaited<ReturnType<typeof ElMessageBox.confirm>>)
  vi.mocked(metadata.latest).mockResolvedValue(batch())
  vi.mocked(metadata.cancel).mockResolvedValue(batch('CANCELLED'))
  vi.mocked(metadata.batch).mockResolvedValue(batch('CANCELLED'))
  await page(); await button('终止').trigger('click'); await flushPromises()
  expect(ElMessageBox.confirm).toHaveBeenCalledWith(expect.stringContaining('不会自动撤销'), '终止批次', expect.anything())
  expect(metadata.cancel).toHaveBeenCalledWith('batch-1')
  expect(wrapper.text()).toContain('已终止')
  expect(button('继续')).toBeUndefined()
})

it('重新进入指定批次恢复后端状态与筛选结果', async () => {
  vi.mocked(metadata.batch).mockResolvedValue({ ...batch('PAUSED'), total: 9, completed: 5, pending: 4, processing: 0, review: 2, applied: 0, failed: 0, skippedExisting: 3 })
  await page('/library/songs/metadata?batch=batch-1')
  expect(metadata.batch).toHaveBeenCalledWith('batch-1')
  expect(wrapper.get('.progress-caption').text()).toContain('已处理 5 / 9 首')
  expect(wrapper.get('[role=progressbar]').attributes('aria-valuenow')).toBe('56')
  expect(button('继续')).toBeDefined()
  expect(metadata.start).not.toHaveBeenCalled()
})

it('批次使用自身阈值，未达到阈值不会自动选择', async () => {
  wrapper = mount(MetadataMatchDialog, { props: { songId: 'song-1', title: '晴天', artists: [], initialCandidates: [candidate], autoSelectThreshold: .96 }, global: { stubs: { teleport: true } } })
  await flushPromises()
  expect((wrapper.get('input[type=radio]').element as HTMLInputElement).checked).toBe(false)
  expect((button('确认应用元数据').element as HTMLButtonElement).disabled).toBe(true)
  expect(metadata.apply).not.toHaveBeenCalled()
})
it('同分高相似度自动选中首项并提示接近候选，允许手工改选', async () => {
  wrapper = mount(MetadataMatchDialog, { props: { songId: 'song-1', title: '晴天', artists: [], initialCandidates: [candidate, { ...candidate, id: 'second' }] }, global: { stubs: { teleport: true } } })
  await flushPromises()
  expect((wrapper.findAll('input[type=radio]')[0]!.element as HTMLInputElement).checked).toBe(true)
  expect(wrapper.text()).toContain('还有接近候选')
  await wrapper.findAll('input[type=radio]')[1]!.setValue(true)
  expect((wrapper.findAll('input[type=radio]')[1]!.element as HTMLInputElement).checked).toBe(true)
  expect(metadata.apply).not.toHaveBeenCalled()
  expect(metadata.applyItem).not.toHaveBeenCalled()
})

it('95% 同分候选展示高相似度及接近候选提示，不误标为低可信', async () => {
  vi.mocked(metadata.latest).mockResolvedValue({ ...batch('COMPLETED'), completed: 1, pending: 0, review: 1, items: [{ id: 'item-1', songId: 'song-1', title: '晴天', artists: ['周杰伦'], status: 'REVIEW', candidates: [candidate, { ...candidate, id: 'second' }], error: null, updatedAt: '', confidence: 'LOW' }] })
  await page()
  expect(wrapper.get('.batch-song-table').text()).toContain('高相似度 · 待确认')
  expect(wrapper.get('.batch-song-table').text()).toContain('多个接近候选')
  expect(wrapper.get('.batch-song-table').text()).not.toContain('未达到高相似度阈值')
  expect(metadata.applyItem).not.toHaveBeenCalled()
})

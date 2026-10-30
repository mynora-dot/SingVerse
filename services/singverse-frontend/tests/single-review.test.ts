// @vitest-environment jsdom
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { beforeEach, afterEach, expect, it, vi } from 'vitest'
import { ElMessageBox } from 'element-plus'
import SingleSongScrapeDialog from '../src/admin/modules/metadata/components/SingleSongScrapeDialog.vue'
import { useSingleSongScrape } from '../src/admin/modules/metadata/composables/useSingleSongScrape'
import { metadata } from '../src/admin/modules/metadata/api/metadata'
import { singleReview } from '../src/admin/modules/metadata/api/single-review'
import { preview } from './fixtures/filename-workspace'
import type { SingleReview, ReviewResult } from '../src/admin/modules/metadata/single-types'
import type { MetadataCandidate } from '../src/admin/modules/metadata/types'
vi.mock('../src/admin/modules/metadata/api/single-review', () => ({ singleReview: { load: vi.fn(), apply: vi.fn(), covers: vi.fn(), upload: vi.fn(), assetUrl: (id: string) => `/api/v1/metadata/cover-assets/${id}` } }))
vi.mock('../src/admin/modules/metadata/api/metadata', () => ({ metadata: { settings: vi.fn(), search: vi.fn(), candidateCover: (id: string) => `/api/v1/metadata/candidates/${id}/cover`, songCover: (id: string) => `/api/v1/metadata/songs/${id}/cover` } }))
function context(): SingleReview { return { song: { id: 'song-1', sourceId: 'source', sourceName: '家庭曲库', relativePath: '歌手《歌名》.mkv', fileSize: 2000, modifiedAt: null, scannedAt: '', parserProfileId: 'default', parserRevision: 0, selectedRuleId: null, result: preview().result }, fingerprint: 'fingerprint', version: 'version', values: { title: '歌名', artists: ['歌手'], album: null, releaseDate: null, aliases: [], durationMs: null, cover: null } } }
function result(): ReviewResult { return { metadata: null, before: context().values, after: { ...context().values, title: '手工歌名' }, updated: ['title'], skipped: [], retained: ['artists', 'album', 'releaseDate', 'aliases', 'durationMs', 'cover'] } }
const candidate: MetadataCandidate = { id: 'candidate', score: .95, track: { provider: 'QQ', externalId: 'id', title: '歌名', artists: ['歌手'], album: '新专辑', releaseDate: null, aliases: [], durationMs: null, coverUrl: null, availability: 'UNKNOWN', fetchedAt: '' } }
let wrapper: VueWrapper | undefined
beforeEach(() => {
  vi.mocked(singleReview.load).mockResolvedValue(context())
  vi.mocked(metadata.settings).mockResolvedValue({ enabled: false, providers: ['QQ'], autoApply: false, threshold: .95 })
  vi.mocked(metadata.search).mockResolvedValue({ candidates: [candidate], errors: [] })
  vi.mocked(singleReview.apply).mockResolvedValue(result())
})
afterEach(() => { wrapper?.unmount(); wrapper = undefined; document.body.innerHTML = ''; vi.clearAllMocks(); vi.restoreAllMocks() })
it('没有平台也能编辑并真实提交，成功使用服务端反馈', async () => {
  wrapper = mount(SingleSongScrapeDialog, { props: { song: context().song }, global: { stubs: { teleport: true } } })
  await flushPromises()
  expect(metadata.search).not.toHaveBeenCalled()
  const edit = wrapper.findAll('button').find(b => b.text() === '编辑元数据')!
  await edit.trigger('click'); await flushPromises()
  await wrapper.get('input[aria-label="编辑歌名"]').setValue('手工歌名')
  await wrapper.findAll('button').find(b => b.text() === '保存编辑到草稿')!.trigger('click'); await flushPromises()
  expect(singleReview.apply).not.toHaveBeenCalled()
  const apply = wrapper.findAll('button').find(b => b.text() === '确认应用（1 项）')!
  await apply.trigger('click'); await flushPromises()
  expect(singleReview.apply).toHaveBeenCalledWith('song-1', expect.objectContaining({ candidateId: null, fields: ['title'], edits: { title: '手工歌名' }, fingerprint: 'fingerprint', version: 'version' }))
  expect(wrapper.text()).toContain('元数据已应用成功')
  expect(wrapper.text()).toContain('本次更新 1 项')
  expect(wrapper.emitted('applied')).toHaveLength(1)
})
it('候选高分仍需确认，计数排除未变化和缺失字段', async () => {
  const state = useSingleSongScrape(); await state.open('song-1'); state.choose(candidate)
  expect(state.changed.value).toEqual(['album'])
  expect(singleReview.apply).not.toHaveBeenCalled()
  state.fields.value = []
  await state.apply(); expect(singleReview.apply).not.toHaveBeenCalled()
})
it('请求失败保留草稿，安全重试复用提交标识', async () => {
  const state = useSingleSongScrape(); await state.open('song-1'); state.saveEdits({ ...context().values, title: '手工歌名' })
  vi.mocked(singleReview.apply).mockRejectedValueOnce(new Error('连接失败'))
  await state.apply(); const first = vi.mocked(singleReview.apply).mock.calls[0]![1]
  expect(state.draft.value?.title).toBe('手工歌名'); expect(state.result.value).toBeNull()
  await state.apply(); expect(vi.mocked(singleReview.apply).mock.calls[1]![1].requestId).toBe(first.requestId)
})
it('关闭或换曲后的迟到请求不会覆盖当前歌曲', async () => {
  let resolve!: (value: SingleReview) => void
  vi.mocked(singleReview.load).mockImplementationOnce(() => new Promise(done => { resolve = done }))
  const state = useSingleSongScrape(); const pending = state.open('old')
  await state.open('new'); resolve({ ...context(), version: 'old' }); await pending
  expect(state.review.value?.version).toBe('version')
})
it('未应用草稿关闭时确认，取消确认后保留编辑', async () => {
  vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue('cancel')
  wrapper = mount(SingleSongScrapeDialog, { props: { song: context().song }, global: { stubs: { teleport: true } } }); await flushPromises()
  await wrapper.findAll('button').find(b => b.text() === '编辑元数据')!.trigger('click'); await flushPromises()
  await wrapper.get('input[aria-label="编辑专辑"]').setValue('草稿专辑')
  await wrapper.findAll('button').find(b => b.text() === '保存编辑到草稿')!.trigger('click'); await flushPromises()
  await wrapper.findAll('button').find(b => b.text() === '取消')!.trigger('click'); await flushPromises()
  expect(ElMessageBox.confirm).toHaveBeenCalledWith(expect.any(String), '有尚未应用的修改', expect.objectContaining({ customClass: 'discard-message-box', closeOnClickModal: false }))
  expect(wrapper.emitted('close')).toBeUndefined()
  expect(wrapper.text()).toContain('草稿专辑')
  vi.mocked(ElMessageBox.confirm).mockResolvedValueOnce('confirm' as Awaited<ReturnType<typeof ElMessageBox.confirm>>)
  await wrapper.findAll('button').find(b => b.text() === '取消')!.trigger('click'); await flushPromises()
  expect(wrapper.emitted('close')).toHaveLength(1)
  expect(singleReview.apply).not.toHaveBeenCalled()
})
it('未选字段不提交，上传封面加入真实变更摘要', async () => {
  const state = useSingleSongScrape(); await state.open('song-1')
  state.chooseCover({ kind: 'asset', id: 'asset', value: 'asset:asset', url: '/api/v1/metadata/cover-assets/asset', label: '已上传' })
  expect(state.changed.value).toEqual(['cover'])
  await state.apply()
  expect(singleReview.apply).toHaveBeenCalledWith('song-1', expect.objectContaining({ coverAssetId: 'asset', coverCandidateId: null, fields: ['cover'] }))
})
it('总开关关闭时配置中的平台不会显示为可选或已勾选，未搜索不显示结果数', async () => {
  wrapper = mount(SingleSongScrapeDialog, { props: { song: context().song }, global: { stubs: { teleport: true } } }); await flushPromises()
  const platforms = wrapper.find('.platform-select').findAll('input[type=checkbox]')
  expect(platforms).toHaveLength(3)
  for (const input of platforms) { expect((input.element as HTMLInputElement).checked).toBe(false); expect((input.element as HTMLInputElement).disabled).toBe(true) }
  expect(wrapper.text()).toContain('尚未搜索')
  expect(wrapper.text()).not.toContain('0 条结果')
  expect(wrapper.text()).toContain('打开刮削设置')
  expect(wrapper.text()).toContain('手工编辑元数据')
  expect(wrapper.text()).toContain('选择或上传封面')
  expect(wrapper.findAll('button').find(b => b.text() === '重新搜索')!.attributes('disabled')).toBeDefined()
})
it('平台已启用时真实空结果和请求失败有不同状态，部分失败保留成功候选', async () => {
  vi.mocked(metadata.settings).mockResolvedValue({ enabled: true, providers: ['QQ'], autoApply: false, threshold: .95 })
  vi.mocked(metadata.search).mockResolvedValueOnce({ candidates: [], errors: [] })
  wrapper = mount(SingleSongScrapeDialog, { props: { song: context().song }, global: { stubs: { teleport: true } } }); await flushPromises()
  expect(wrapper.text()).toContain('未找到匹配结果'); expect(wrapper.text()).toContain('0 条结果')
  vi.mocked(metadata.search).mockRejectedValueOnce(new Error('平台连接失败'))
  await wrapper.find('form.workspace-search').trigger('submit'); await flushPromises()
  expect(wrapper.text()).toContain('平台请求失败'); expect(wrapper.text()).not.toContain('未找到匹配结果')
  vi.mocked(metadata.search).mockResolvedValueOnce({ candidates: [candidate], errors: [{ provider: 'NETEASE', message: '冷却中' }] })
  await wrapper.find('form.workspace-search').trigger('submit'); await flushPromises()
  expect(wrapper.text()).toContain('冷却中'); expect(wrapper.findAll('.review-candidate')).toHaveLength(1)
})
it('重新搜索立即清除旧候选、封面和字段，取消候选恢复当前入库值', async () => {
  vi.mocked(metadata.settings).mockResolvedValue({ enabled: true, providers: ['QQ'], autoApply: false, threshold: .95 })
  const state = useSingleSongScrape(); await state.open('song-1'); state.choose(candidate)
  expect(state.preview.value?.album).toBe('新专辑')
  state.clearSelection(); expect(state.preview.value).toEqual(context().values); expect(state.changed.value).toEqual([])
  state.choose(candidate)
  let finish!: (value: { candidates: MetadataCandidate[]; errors: [] }) => void
  vi.mocked(metadata.search).mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  const pending = state.search()
  expect(state.selected.value).toBeNull(); expect(state.cover.value).toBeNull(); expect(state.fields.value).toEqual([])
  expect(state.preview.value).toEqual(context().values)
  finish({ candidates: [], errors: [] }); await pending
  expect(state.selected.value).toBeNull()
})
it('字段取消应用同步预览和状态，提交只含实际勾选变更', async () => {
  vi.mocked(metadata.settings).mockResolvedValue({ enabled: true, providers: ['QQ'], autoApply: false, threshold: .95 })
  wrapper = mount(SingleSongScrapeDialog, { props: { song: context().song }, global: { stubs: { teleport: true } } }); await flushPromises()
  await wrapper.get('.review-candidate').trigger('click'); await flushPromises()
  expect(wrapper.get('.selected-preview').text()).toContain('新专辑')
  await wrapper.get('[aria-label="应用专辑"] input').setValue(false); await flushPromises()
  expect(wrapper.get('.selected-preview').text()).not.toContain('新专辑')
  expect(wrapper.get('.field-comparison').text()).toContain('不应用')
  expect(wrapper.text()).toContain('确认应用（0 项）')
  expect(wrapper.get('.field-comparison tbody tr:last-child').text()).toContain('封面')
})
it('仅根据服务端已更新的封面显示成功缩略图，失败反馈保留审核上下文', async () => {
  const response = result()
  response.updated = ['cover']; response.after.cover = 'asset:confirmed-id'
  vi.mocked(singleReview.apply).mockRejectedValueOnce(new Error('保存失败')).mockResolvedValueOnce(response)
  wrapper = mount(SingleSongScrapeDialog, { props: { song: context().song }, global: { stubs: { teleport: true } } }); await flushPromises()
  await wrapper.findAll('button').find(b => b.text() === '编辑元数据')!.trigger('click'); await flushPromises()
  await wrapper.get('input[aria-label="编辑歌名"]').setValue('手工歌名')
  await wrapper.findAll('button').find(b => b.text() === '保存编辑到草稿')!.trigger('click'); await flushPromises()
  await wrapper.findAll('button').find(b => b.text() === '确认应用（1 项）')!.trigger('click'); await flushPromises()
  expect(wrapper.text()).toContain('保存失败'); expect(wrapper.text()).not.toContain('元数据已应用成功')
  await wrapper.findAll('button').find(b => b.text() === '确认应用（1 项）')!.trigger('click'); await flushPromises()
  expect(wrapper.get('img[alt="已实际应用的歌曲封面"]').attributes('src')).toBe('/api/v1/metadata/cover-assets/confirmed-id')
  expect(wrapper.text()).toContain('封面已应用')
})

it('单曲搜索自动选择达到阈值的最高候选，预览更新但不自动写入', async () => {
  vi.mocked(metadata.settings).mockResolvedValue({ enabled: true, providers: ['QQ'], autoApply: false, threshold: .95 })
  vi.mocked(metadata.search).mockResolvedValue({ candidates: [{ ...candidate, score: .8, id: 'low' }, candidate, { ...candidate, id: 'equal' }], errors: [] })
  const state = useSingleSongScrape(); await state.open('song-1')
  expect(state.selected.value?.id).toBe('candidate')
  expect(state.autoSelected.value).toBe(true)
  expect(state.preview.value?.album).toBe('新专辑')
  expect(state.changed.value).toEqual(['album'])
  expect(singleReview.apply).not.toHaveBeenCalled()
  state.clearSelection()
  expect(state.selected.value).toBeNull()
  expect(state.autoSelected.value).toBe(false)
  expect(state.changed.value).toEqual([])
  vi.mocked(metadata.search).mockResolvedValue({ candidates: [{ ...candidate, score: .94 }], errors: [] })
  await state.search()
  expect(state.selected.value).toBeNull()
  expect(singleReview.apply).not.toHaveBeenCalled()
})

it('待复核歌曲不再展示顶部说明，保留可操作的手工编辑入口', async () => {
  const review = context()
  review.song.result.needsReview = true
  vi.mocked(singleReview.load).mockResolvedValue(review)
  wrapper = mount(SingleSongScrapeDialog, { props: { song: review.song }, global: { stubs: { teleport: true } } })
  await flushPromises()
  expect(wrapper.text()).not.toContain('文件名解析待复核')
  expect(wrapper.text()).not.toContain('可点击“编辑元数据”补充歌名')
  const edit = wrapper.findAll('button').find(button => button.text() === '手工编辑元数据')!
  expect(edit.exists()).toBe(true)
  await edit.trigger('click'); await flushPromises()
  expect(wrapper.get('input[aria-label="编辑歌名"]').element.tagName).toBe('INPUT')
})

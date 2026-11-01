// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { VueWrapper } from '@vue/test-utils'
import { flushPromises, mount } from '@vue/test-utils'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import ElementPlus from 'element-plus'
import SongLibraryTable from '../src/admin/modules/song-management/components/SongLibraryTable.vue'
import { defaultSongColumns, fileSize, identityReviewed, needsSongReview, songReviewLabel } from '../src/admin/modules/song-management/presentation'
import SongsView from '../src/admin/modules/song-management/views/SongsView.vue'
import type { MediaSong } from '../src/admin/modules/media-library/types'
import { songs } from '../src/admin/modules/song-management/api/songs'
import { classification } from '../src/admin/modules/classification/api/classification'
import type { Tag } from '../src/admin/modules/classification/types'
import { metadata } from '../src/admin/modules/metadata/api/metadata'
import { singleReview } from '../src/admin/modules/metadata/api/single-review'
import type { RulePreview } from '../src/admin/modules/filename-parser/types'

vi.mock('../src/admin/modules/song-management/api/songs')
vi.mock('../src/admin/modules/classification/api/classification')
vi.mock('../src/admin/modules/metadata/api/metadata')
vi.mock('../src/admin/modules/metadata/api/single-review')

const routes = [{ path: '/library/songs', component: SongsView, name: 'Songs', meta: { activeMenu: '/library/songs' } }]

function song(id = 'song-1', opts: { sourceId?: string; needsReview?: boolean } = {}): MediaSong {
  const result: RulePreview['result'] = {
    originalFilename: '周杰伦-晴天.mp4',
    normalizedFilename: '周杰伦-晴天.mp4',
    title: '晴天',
    artists: ['周杰伦'],
    language: 'MANDARIN',
    versionType: 'ORIGINAL',
    songCode: null,
    extension: 'mp4',
    qualityTags: [],
    confidence: 0.95,
    parseStatus: 'SUCCESS',
    parserRule: 'pattern-1',
    needsReview: opts.needsReview ?? false,
    unparsedTokens: [],
    diagnostics: []
  }
  return {
    id,
    sourceId: opts.sourceId ?? 'source-1',
    sourceName: 'nas',
    relativePath: `周杰伦-晴天.mp4`,
    fileSize: 12800000,
    modifiedAt: '2023-10-01T10:00:00Z',
    scannedAt: '2024-01-01T12:00:00Z',
    parserProfileId: 'default',
    parserRevision: 1,
    selectedRuleId: 'pattern-1',
    result,
    metadata: null,
    classification: undefined
  }
}
function pending(title = 'Crazy', unparsed = ['demo']): MediaSong {
  const base = song('pending-1')
  return {
    ...base,
    relativePath: `${title} ${unparsed.join(' ')}.mp4`,
    result: { ...base.result, title, unparsedTokens: unparsed, needsReview: true }
  }
}
function result(items: MediaSong[], page = 0, totalElements = items.length) {
  return { items, page, size: 20, totalElements }
}

describe('歌曲管理集中展示扫描数据及详情', () => {
  let wrapper: VueWrapper, router: Router
  beforeEach(async () => {
    router = createRouter({ history: createMemoryHistory(), routes })
    await router.push('/library/songs')
    vi.mocked(metadata.settings).mockResolvedValue({ enabled: false, providers: [], autoApply: false, threshold: 0.9 })
    vi.mocked(classification.tags).mockResolvedValue([])
    vi.mocked(songs.sources).mockResolvedValue([])
    vi.mocked(songs.list).mockResolvedValue(result([song()]))
    wrapper?.unmount()
    wrapper = mount(SongsView, { global: { plugins: [router, ElementPlus] } })
    await flushPromises()
  })
  afterEach(() => { wrapper.unmount(); vi.clearAllMocks() })

  it('加载后展示第一页歌曲，统计卡片显示数量', async () => {
    const matched = song('song-1'), review = pending('Crazy')
    const unclassified = song('song-3', { needsReview: false })
    unclassified.classification = { songId: 'song-3', revision: 0, dimensions: {} }
    vi.mocked(songs.list).mockResolvedValue(result([matched, review, unclassified]))
    wrapper?.unmount()
    wrapper = mount(SongsView, { global: { plugins: [router, ElementPlus] } })
    await flushPromises()
    expect(wrapper.text()).toContain('3')
    expect(wrapper.text()).toContain('1')
    expect(wrapper.text()).toContain('待复核')
    expect(wrapper.text()).toContain('晴天')
    expect(wrapper.text()).toContain('周杰伦')
  })

  it('总数和当前页范围分开显示，修改显示列不重新请求歌曲', async () => {
    vi.mocked(songs.list).mockResolvedValue(result([song()], 0, 47))
    await router.replace('/library/songs?page=2'); await flushPromises()
    expect(wrapper.find('.page-range').text()).toContain('21–21')
    expect(wrapper.find('.count-badge').text()).toBe('47')
    const before = vi.mocked(songs.list).mock.calls.length
    await wrapper.find('.column-settings-button').trigger('click'); await flushPromises()
    const label = [...document.querySelectorAll('.column-settings label')].find(el => el.textContent?.includes('扫描与修改时间'))
    expect(label).toBeDefined()
    const input = label!.querySelector('input')!; input.checked = true; input.dispatchEvent(new Event('change', { bubbles: true })); await flushPromises()
    expect(wrapper.find('.library-table thead').text()).toContain('扫描 / 修改时间')
    expect(songs.list).toHaveBeenCalledTimes(before)
  })

  it('每页数量决定完整渲染行数，顶部和底部分页数量同步', async () => {
    vi.mocked(songs.list).mockImplementation(async input => result(Array.from({ length: input.size ?? 20 }, (_, i) => song(`row-${i}`)), 0, 137))
    for (const size of [50, 100, 20]) {
      await wrapper.find('.top-page-size select').setValue(size); await flushPromises()
      expect(songs.list).toHaveBeenLastCalledWith(expect.objectContaining({ page: 0, ...(size === 20 ? {} : { size }) }))
      expect(wrapper.findAll('.library-table tbody tr')).toHaveLength(size)
      expect((wrapper.find('.songs-pagination select').element as HTMLSelectElement).value).toBe(String(size))
      expect(wrapper.find('.page-range').text()).toContain(`1–${size}`)
    }
  })

  it('重置筛选同时清除搜索、来源、标签和快捷条件，并返回第一页', async () => {
    await router.replace('/library/songs?q=晴天&sourceId=source-1&status=REVIEW&unclassified=true&tagIds=tag-1&page=2&size=50'); await flushPromises()
    await wrapper.find('.filter-reset').trigger('click'); await flushPromises()
    expect(router.currentRoute.value.query).toEqual({ size: '50' })
    expect(songs.list).toHaveBeenLastCalledWith({ sourceId: '', q: '', status: 'ALL', page: 0, size: 50 })
    expect((wrapper.find('input[aria-label="搜索歌曲文件"]').element as HTMLInputElement).value).toBe('')
    expect(wrapper.find('.filter-reset').exists()).toBe(false)
  })

  it('移除快捷筛选并保留既有复核筛选链接兼容', async () => {
    expect(wrapper.find('.quick-filters').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('快捷筛选')
    await router.replace('/library/songs?status=REVIEW'); await flushPromises()
    expect(songs.list).toHaveBeenLastCalledWith(expect.objectContaining({ status: 'REVIEW' }))
  })

  it('错误可重试，真实空列表引导到媒体库扫描', async () => {
    vi.mocked(songs.list).mockRejectedValue(new Error('读取歌曲失败'))
    wrapper?.unmount()
    wrapper = mount(SongsView, { global: { plugins: [router, ElementPlus] } })
    await flushPromises()
    expect(wrapper.text()).toContain('读取歌曲失败')
    const retry = wrapper.find('.error-alert button')
    expect(retry.exists()).toBe(true)
    vi.mocked(songs.list).mockClear()
    await retry.trigger('click')
    await flushPromises()
    expect(songs.list).toHaveBeenCalledTimes(1)
  })

  it('切换筛选后迟到的列表响应不会覆盖新结果', async () => {
    type Resolver = (value: any) => void
    let resolver: Resolver | null = null
    vi.mocked(songs.list).mockImplementation(() => new Promise<any>(resolve => { resolver = resolve }))
    wrapper?.unmount()
    wrapper = mount(SongsView, { global: { plugins: [router, ElementPlus] } })
    const promise1 = resolver
    const searchInput = wrapper.find('input[aria-label="搜索歌曲文件"]')
    await searchInput.setValue('晴天')
    await wrapper.find('.filters-row button').trigger('click')
    await flushPromises()
    const promise2 = resolver;
    (promise2 as any)(result([pending('Crazy', [])]))
    await flushPromises();
    (promise1 as any)(result([song()]))
    await flushPromises()
    expect(wrapper.text()).toContain('Crazy')
    expect(wrapper.text()).not.toContain('晴天')
  })

  it('WebDAV 未提供文件属性时显示未知，不伪造大小和时间', async () => {
    const songWithoutMeta = song()
    songWithoutMeta.fileSize = null
    songWithoutMeta.modifiedAt = null
    vi.mocked(songs.list).mockResolvedValue(result([songWithoutMeta]))
    wrapper?.unmount()
    wrapper = mount(SongsView, { global: { plugins: [router, ElementPlus] } })
    await flushPromises()
    const card = wrapper.find('.song-card')
    await card.trigger('click')
    await flushPromises()
    const drawer = wrapper.find('.el-drawer__body')
    expect(drawer.text()).toContain('大小未提供')
    expect(drawer.text()).toContain('修改时间未提供')
  })

  it('歌曲页大小可调整并同步路由，重新查询回到第一页', async () => {
    vi.mocked(songs.list).mockResolvedValue(result(Array(25).fill(0).map((_, i) => song(`song-${i}`)), 0, 25))
    wrapper?.unmount()
    wrapper = mount(SongsView, { global: { plugins: [router, ElementPlus] } })
    await flushPromises()
    const select = wrapper.find('.page-size-selector select')
    await select.setValue(50)
    await flushPromises()
    expect(songs.list).toHaveBeenLastCalledWith(expect.objectContaining({ size: 50, page: 0 }))
  })

  it('批次手工处理深链接复用现有单曲弹窗并保留返回批次入口', async () => {
    await router.push('/library/songs?reviewSongId=song-1')
    vi.mocked(singleReview.load).mockResolvedValue({
      song: pending('Crazy'),
      fingerprint: 'fp-1',
      version: '1',
      values: { title: 'Crazy', artists: [], album: null, releaseDate: null, aliases: [], durationMs: null, cover: null }
    })
    wrapper?.unmount()
    wrapper = mount(SongsView, { global: { plugins: [router, ElementPlus] } })
    await flushPromises()
    expect(singleReview.load).toHaveBeenCalledWith('song-1')
    const dialog = wrapper.findComponent({ name: 'SingleSongScrapeDialog' })
    expect(dialog.props('song')).toBeDefined()
  })

  it('待复核行提供明显入口，打开当前歌曲的现有元数据审核工作台', async () => {
    vi.mocked(songs.list).mockResolvedValue(result([pending('Crazy')]))
    vi.mocked(singleReview.load).mockResolvedValue({
      song: pending('Crazy'),
      fingerprint: 'fp-1',
      version: '1',
      values: { title: 'Crazy', artists: [], album: null, releaseDate: null, aliases: [], durationMs: null, cover: null }
    })
    wrapper?.unmount()
    wrapper = mount(SongsView, { global: { plugins: [router, ElementPlus] } })
    await flushPromises()
    const editBtn = wrapper.find('.song-card-actions button:nth-child(2)')
    await editBtn.trigger('click')
    await flushPromises()
    const dialog = wrapper.findComponent({ name: 'SingleSongScrapeDialog' })
    expect(dialog.props('song')).toBeDefined()
  })

  it('详情中的复核入口关闭详情并打开同一歌曲的审核窗口', async () => {
    vi.mocked(songs.list).mockResolvedValue(result([pending('Crazy')]))
    vi.mocked(singleReview.load).mockResolvedValue({
      song: pending('Crazy'),
      fingerprint: 'fp-1',
      version: '1',
      values: { title: 'Crazy', artists: [], album: null, releaseDate: null, aliases: [], durationMs: null, cover: null }
    })
    wrapper?.unmount()
    wrapper = mount(SongsView, { global: { plugins: [router, ElementPlus] } })
    await flushPromises()
    await wrapper.find('.song-card').trigger('click')
    await flushPromises()
    const editBtn = wrapper.find('.song-primary-actions').findAll('button').find(button => button.text() === '核对信息')!
    await editBtn.trigger('click')
    await flushPromises()
    expect(wrapper.findComponent({ name: 'SongDetailDrawer' }).props('visible')).toBe(false)
    expect(wrapper.findComponent({ name: 'SingleSongScrapeDialog' }).props('song')).toBeDefined()
  })

  it('分类筛选链接兼容并可用重置按钮清除', async () => {
    const tag1: Tag = { id: 'tag-1', name: '经典', dimension: 'MOOD', aliases: [], active: true, revision: 0, count: 0 }
    vi.mocked(classification.tags).mockResolvedValue([tag1])
    wrapper?.unmount()
    wrapper = mount(SongsView, { global: { plugins: [router, ElementPlus] } })
    await flushPromises()
    await router.replace('/library/songs?unclassified=true&tagIds=tag-1'); await flushPromises()
    expect(songs.list).toHaveBeenLastCalledWith(expect.objectContaining({ unclassified: true }))
    const clearBtn = wrapper.find('.filter-reset')
    await clearBtn.trigger('click')
    await flushPromises()
    expect(songs.list).toHaveBeenLastCalledWith(expect.objectContaining({ status: 'ALL' }))
    expect(vi.mocked(songs.list).mock.lastCall?.[0]?.unclassified).toBeUndefined()
  })

  it('行内操作全部使用按钮，在线播放打开当前歌曲并在关闭时移除视频', async () => {
    const actions = wrapper.find('.song-card-actions')
    expect(actions.findAll('button').length).toBeGreaterThan(0)
    const playBtn = actions.find('button:nth-child(1)')
    await playBtn.trigger('click')
    await flushPromises()
    const videoDialog = wrapper.findComponent({ name: 'SongVideoDialog' })
    expect(videoDialog.props('song')).toEqual(song())
  })

  it('批量操作显示页内工具栏，未选择时禁用动作，取消后退出', async () => {
    const batchBtn = wrapper.findAll('button').find(b => b.text() === '批量操作')
    await batchBtn!.trigger('click')
    await flushPromises()
    expect(wrapper.find('.selection-checkbox').exists()).toBe(true)
    expect(wrapper.find('.batch-toolbar').exists()).toBe(true)
    expect(wrapper.find('.batch-actions button').attributes('disabled')).toBeDefined()
    await wrapper.find('.selection-checkbox input').setValue(true)
    await flushPromises()
    expect(wrapper.find('.batch-count').text()).toContain('已选 1 首')
    expect(wrapper.find('.batch-actions button').attributes('disabled')).toBeUndefined()
    await wrapper.find('.batch-actions button:last-child').trigger('click')
    expect(wrapper.find('.batch-toolbar').exists()).toBe(false)
    expect(wrapper.find('.selection-checkbox').exists()).toBe(false)
  })
  it('来源筛选保留服务端分页与查询参数', async () => {
    const sourceSelect = wrapper.findAllComponents({ name: 'ElSelect' })[0]!
    sourceSelect.vm.$emit('update:modelValue', 'source-1')
    sourceSelect.vm.$emit('change', 'source-1')
    await flushPromises()
    expect(router.currentRoute.value.query.sourceId).toBe('source-1')
    expect(songs.list).toHaveBeenLastCalledWith(expect.objectContaining({ sourceId: 'source-1', page: 0 }))
  })

  it('清除组合筛选只发起一次新列表查询', async () => {
    await router.replace('/library/songs?status=REVIEW&unclassified=true&tagIds=tag-1')
    await flushPromises()
    vi.mocked(songs.list).mockClear()
    await wrapper.get('.filter-reset').trigger('click')
    await flushPromises()
    expect(songs.list).toHaveBeenCalledTimes(1)
    expect(router.currentRoute.value.query).toEqual({})
  })

})


describe('歌曲明细表信息与交互', () => {
  it('显示确认封面，加载失败显示占位并在封面更新后恢复', async () => {
    const row = song()
    row.metadata = { track: { provider: null, externalId: null, title: null, artists: [], album: null, releaseDate: null, durationMs: null, aliases: [], coverUrl: 'asset:cover-one', availability: 'UNKNOWN', fetchedAt: '' }, fields: ['cover'], appliedAt: '', stale: false, origins: {} }
    const w = mount(SongLibraryTable, { props: { ...props(), items: [row] } })
    expect(w.get('.song-cover img').attributes('src')).toBe('/api/v1/metadata/songs/song-1/cover')
    expect(w.get('.song-cover img').attributes('loading')).toBe('lazy')
    await w.get('.song-cover img').trigger('error')
    expect(w.find('.song-cover img').exists()).toBe(false)
    expect(w.find('[aria-label="暂无歌曲封面"]').exists()).toBe(true)
    await w.setProps({ items: [{ ...row, metadata: { ...row.metadata, track: { ...row.metadata.track, coverUrl: 'asset:cover-two' } } }] })
    expect(w.find('.song-cover img').exists()).toBe(true)
    await w.setProps({ items: [{ ...row, metadata: { ...row.metadata, stale: true } }] })
    expect(w.find('.song-cover img').exists()).toBe(false)
    await w.setProps({ items: [song()] })
    expect(w.find('[aria-label="暂无歌曲封面"]').exists()).toBe(true)
  })

  const props = () => ({ items: [song()], tags: [] as Tag[], columns: [...defaultSongColumns], compact: true, selectionMode: false, selectedIds: [] as string[] })
  it('只展示已确认且未过期的元数据，时长有独立标识', async () => {
    const row = song()
    row.metadata = { track: { provider: null, externalId: null, title: null, artists: [], album: '叶惠美', releaseDate: '2003-07-31', durationMs: 269000, aliases: [], coverUrl: null, availability: 'UNKNOWN', fetchedAt: '' }, fields: ['album', 'releaseDate', 'durationMs'], appliedAt: '', stale: false, origins: {} }
    const w = mount(SongLibraryTable, { props: { ...props(), items: [row] } })
    expect(w.text()).toContain('叶惠美'); expect(w.text()).toContain('2003-07-31'); expect(w.text()).toContain('时长 4:29'); expect(w.text()).toContain('MP4'); expect(w.text()).toContain('12.2 MB')
    await w.setProps({ items: [{ ...row, metadata: { ...row.metadata, fields: ['album'] } }] })
    expect(w.text()).not.toContain('时长 4:29'); expect(w.text()).not.toContain('2003-07-31')
    await w.setProps({ items: [{ ...row, metadata: { ...row.metadata, stale: true } }] })
    expect(w.text()).not.toContain('叶惠美'); expect(w.text()).toContain('元数据需重审'); w.unmount()
  })
  it('共享核心信息须核对，人工确认后才解除提示', () => {
    const row = song()
    row.metadata = { track: { provider: null, externalId: null, title: '共享歌名', artists: ['共享歌手'], album: null, releaseDate: null, durationMs: 1000, aliases: [], coverUrl: null, availability: 'UNKNOWN', fetchedAt: '' }, fields: ['title', 'artists', 'durationMs'], appliedAt: '', stale: false, origins: { title: { provider: null, externalId: null, appliedAt: '', kind: 'SHARED' } } }
    expect(identityReviewed(row)).toBe(false)
    expect(needsSongReview(row)).toBe(true)
    expect(songReviewLabel(row)).toBe('共享信息待核对')
    row.metadata.origins.title!.kind = 'MANUAL'
    expect(identityReviewed(row)).toBe(true)
    expect(needsSongReview(row)).toBe(false)
  })
  it('确认歌名歌手后解除复核提示，部分确认或文件变化后仍需复核', async () => {
    const row = pending()
    row.metadata = { track: { provider: null, externalId: null, title: '已修正歌名', artists: ['已修正歌手'], album: null, releaseDate: null, durationMs: null, aliases: [], coverUrl: null, availability: 'UNKNOWN', fetchedAt: '' }, fields: ['title', 'artists'], appliedAt: '', stale: false, origins: {} }
    const w = mount(SongLibraryTable, { props: { ...props(), items: [row] } })
    expect(w.text()).toContain('歌曲信息已复核'); expect(w.findAll('.table-actions button')[1]!.text()).toBe('编辑')
    await w.setProps({ items: [{ ...row, metadata: { ...row.metadata, origins: { title: { provider: null, externalId: null, appliedAt: '', kind: 'FILE' } } } }] })
    expect(w.text()).toContain('优先使用文件内嵌信息')
    expect(row.result.needsReview).toBe(true)
    await w.setProps({ items: [{ ...row, metadata: { ...row.metadata, fields: ['album'] } }] })
    expect(w.text()).toContain('文件名待复核'); expect(w.findAll('.table-actions button')[1]!.text()).toBe('复核')
    await w.setProps({ items: [{ ...row, metadata: { ...row.metadata, stale: true } }] })
    expect(w.text()).toContain('文件名待复核'); expect(w.text()).toContain('元数据需重审')
    w.unmount()
  })

  it('展示分类名称与来源路径，未知大小不伪装为零', () => {
    const row = song(); row.fileSize = null
    row.classification = { songId: row.id, revision: 1, dimensions: { GENRE: { tagIds: ['pop'], origin: 'MANUAL', locked: true, confirmed: true } } }
    const w = mount(SongLibraryTable, { props: { ...props(), items: [row], tags: [{ id: 'pop', dimension: 'GENRE', name: '流行', aliases: [], active: true, revision: 1, count: 1 }] } })
    expect(w.text()).toContain('流行'); expect(w.text()).not.toContain('尚未分类'); expect(w.text()).toContain(row.relativePath); expect(w.text()).toContain('大小未提供'); expect(fileSize(0)).toBe('0 B'); w.unmount()
  })
  it('勾选本页和单曲通过事件通知父级，部分选择显示半选', async () => {
    const w = mount(SongLibraryTable, { props: { ...props(), items: [song('one'), song('two')], selectionMode: true, selectedIds: ['one'] } })
    const checks = w.findAll('input[type=checkbox]')
    expect((checks[0]!.element as HTMLInputElement).indeterminate).toBe(true)
    await checks[0]!.setValue(true); expect(w.emitted('selectPage')).toEqual([[true]])
    await checks[2]!.setValue(true); expect(w.emitted('select')?.[0]).toEqual([expect.objectContaining({ id: 'two' }), true]); w.unmount()
  })
  it('播放、复核与详情独立，按需显示解析及时间列', async () => {
    const row = pending()
    const w = mount(SongLibraryTable, { props: { ...props(), items: [row], columns: ['parsed', 'timestamps'] } })
    expect(w.text()).toContain('由文件名提取'); expect(w.text()).toContain('扫描 / 修改时间'); expect(w.text()).not.toContain('专辑 / 发行信息')
    for (const button of w.findAll('.table-actions button')) await button.trigger('click')
    expect(w.emitted('play')).toEqual([[row]]); expect(w.emitted('review')).toEqual([[row]]); expect(w.emitted('detail')).toEqual([[row]]); w.unmount()
  })
})

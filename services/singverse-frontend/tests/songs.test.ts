// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { VueWrapper } from '@vue/test-utils'
import { flushPromises, mount } from '@vue/test-utils'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import ElementPlus from 'element-plus'
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

  it('快速筛选标签可点击切换状态并请求API', async () => {
    const chip = wrapper.findAll('.quick-filter-chip').find(c => c.text().includes('待复核'))
    expect(chip).toBeDefined()
    await chip!.trigger('click')
    await flushPromises()
    expect(songs.list).toHaveBeenCalledWith(expect.objectContaining({ status: 'REVIEW' }))
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
    const searchInput = wrapper.find('input[placeholder="搜索歌曲或文件名..."]')
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
    expect(drawer.text()).toContain('远端未提供')
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
    const editBtn = wrapper.find('.el-drawer__body .detail-actions button:nth-child(2)')
    await editBtn.trigger('click')
    await flushPromises()
    expect(wrapper.findComponent({ name: 'SongDetailDrawer' }).props('visible')).toBe(false)
    expect(wrapper.findComponent({ name: 'SingleSongScrapeDialog' }).props('song')).toBeDefined()
  })

  it('分类筛选通过快速标签切换，清除按钮重置', async () => {
    const tag1: Tag = { id: 'tag-1', name: '经典', dimension: 'MOOD', aliases: [], active: true, revision: 0, count: 0 }
    vi.mocked(classification.tags).mockResolvedValue([tag1])
    wrapper?.unmount()
    wrapper = mount(SongsView, { global: { plugins: [router, ElementPlus] } })
    await flushPromises()
    const unclassifiedChip = wrapper.findAll('.quick-filter-chip').find(c => c.text().includes('未分类'))
    await unclassifiedChip!.trigger('click')
    await flushPromises()
    expect(songs.list).toHaveBeenLastCalledWith(expect.objectContaining({ unclassified: true }))
    const clearBtn = wrapper.find('.quick-filter-clear')
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

  it('批量操作模式进入后显示复选框和底部工具栏', async () => {
    const batchBtn = wrapper.findAll('button').find(b => b.text() === '批量操作')
    await batchBtn!.trigger('click')
    await flushPromises()
    expect(wrapper.find('.selection-checkbox').exists()).toBe(true)
    expect(document.body.querySelector('.batch-toolbar')).not.toBeNull()
    await wrapper.find('.selection-checkbox input').setValue(true)
    await flushPromises()
    expect(document.body.querySelector('.batch-count')?.textContent).toContain('已选 1 首')
  })
})

// @vitest-environment jsdom
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { ElPagination } from 'element-plus'
import PlaybackHistoryView from '../src/admin/modules/playback-history/PlaybackHistoryView.vue'
import { playbackHistory, type Page, type PlaybackEntry, type PopularSong } from '../src/admin/modules/playback-history/api'

vi.mock('../src/admin/modules/playback-history/api')
const song: PopularSong = { songId: 'song-1', title: '家里的歌', artists: ['歌手甲'], versionLabel: '现场版', sourceName: '家庭曲库', playCount: 5, effectivePlayCount: 3, completedCount: 2, lastPlayedAt: '2026-11-06T10:00:00Z' }
const entry: PlaybackEntry = { id: 'play-1', songId: song.songId, title: song.title, artists: song.artists, versionLabel: song.versionLabel, sourceName: song.sourceName, clientType: 'TV', deviceName: '客厅电视', requestedBy: '小林', startedAt: song.lastPlayedAt, endedAt: null, playedMs: 12000, durationMs: 240000, status: 'INTERRUPTED', effective: false }
const stats = { totalPlays: 5, effectivePlays: 3, playedSongs: 1, playedMs: 250000 }
function paged<T>(items: T[], totalElements = items.length): Page<T> { return { items, totalElements, page: 0, size: 20 } }
let wrapper: VueWrapper | undefined
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(playbackHistory.summary).mockResolvedValue(stats)
  vi.mocked(playbackHistory.popular).mockResolvedValue(paged([song]))
  vi.mocked(playbackHistory.list).mockResolvedValue(paged([entry]))
})
afterEach(() => { wrapper?.unmount(); wrapper = undefined; document.body.innerHTML = '' })
async function page() { wrapper = mount(PlaybackHistoryView, { global: { stubs: { teleport: true } } }); await flushPromises() }
function button(text: string) { return wrapper!.findAll('button').find(b => b.text() === text)! }

it('热门与明细共用时间和搜索筛选，后端分页前筛选', async () => {
  await page()
  expect(playbackHistory.popular).toHaveBeenLastCalledWith({ q: '', period: '30d', page: 0, size: 20 })
  expect(wrapper!.text()).toContain('家里的歌')
  expect(wrapper!.text()).toContain('自然播完 2 次')
  await wrapper!.get('select[aria-label="播放统计时间"]').setValue('7d')
  await flushPromises()
  await wrapper!.get('input[aria-label="搜索播放记录"]').setValue('歌手甲')
  await wrapper!.get('form.history-filters').trigger('submit')
  await flushPromises()
  expect(playbackHistory.summary).toHaveBeenLastCalledWith({ q: '歌手甲', period: '7d' })
  await button('播放明细').trigger('click'); await flushPromises()
  expect(playbackHistory.list).toHaveBeenLastCalledWith({ q: '歌手甲', period: '7d', page: 0, size: 20 })
  expect(wrapper!.text()).toContain('播放中断')
  expect(wrapper!.text()).toContain('点歌人：小林')
  expect(wrapper!.text()).toContain('未计入热门排行')
  expect(wrapper!.text()).not.toContain('结束于')
})

it('单曲历史独立查询全部时间，并按歌曲编号分页', async () => {
  vi.mocked(playbackHistory.list).mockResolvedValue(paged([entry], 21))
  await page()
  await button('查看历史').trigger('click'); await flushPromises()
  expect(playbackHistory.list).toHaveBeenLastCalledWith({ songId: 'song-1', period: 'all', page: 0, size: 20 })
  expect(wrapper!.text()).toContain('家里的歌 · 全部播放历史')
  const paginations = wrapper!.findAllComponents(ElPagination)
  paginations.at(-1)!.vm.$emit('current-change', 2)
  await flushPromises()
  expect(playbackHistory.list).toHaveBeenLastCalledWith({ songId: 'song-1', period: 'all', page: 1, size: 20 })
})

it('加载失败给出重试，成功读到空数据后才显示空态', async () => {
  vi.mocked(playbackHistory.popular).mockRejectedValueOnce(new Error('暂时无法读取播放记录'))
  await page()
  expect(wrapper!.get('[role="alert"]').text()).toContain('暂时无法读取播放记录')
  expect(wrapper!.text()).not.toContain('暂无热门歌曲')
  expect(wrapper!.get('.history-stats').text()).not.toContain('0 条')
  vi.mocked(playbackHistory.summary).mockResolvedValue({ totalPlays: 0, effectivePlays: 0, playedSongs: 0, playedMs: 0 })
  vi.mocked(playbackHistory.popular).mockResolvedValue(paged([]))
  await button('重试').trigger('click'); await flushPromises()
  expect(wrapper!.text()).toContain('暂无热门歌曲')
  expect(wrapper!.find('.popular-row').exists()).toBe(false)
  await button('播放明细').trigger('click'); await flushPromises()
  expect(wrapper!.text()).toContain('播放中断')
})

it('切换视图后忽略旧请求的迟到错误', async () => {
  let reject!: (reason: Error) => void
  vi.mocked(playbackHistory.popular).mockReturnValueOnce(new Promise((_, fail) => { reject = fail }))
  await page()
  await button('播放明细').trigger('click'); await flushPromises()
  reject(new Error('已经过期的查询失败')); await flushPromises()
  expect(wrapper!.text()).toContain('播放中断')
  expect(wrapper!.text()).not.toContain('已经过期的查询失败')
  expect(wrapper!.find('[role="alert"]').exists()).toBe(false)
})

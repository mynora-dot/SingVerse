// @vitest-environment jsdom
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import ClassificationSongPicker from '../src/admin/modules/classification/components/ClassificationSongPicker.vue'
import { songs } from '../src/admin/modules/song-management/api/songs'
import type { MediaSong, SongPage } from '../src/admin/modules/media-library/types'
import { preview } from './fixtures/filename-workspace'
vi.mock('../src/admin/modules/song-management/api/songs')
let wrapper: VueWrapper
function song(index: number): MediaSong { return { id: `song-${index}`, sourceId: 'source-1', sourceName: '隔离曲库', relativePath: `歌手-歌曲${index}.mp3`, fileSize: 10, modifiedAt: null, scannedAt: '', parserProfileId: 'default', parserRevision: 0, selectedRuleId: null, result: { ...preview().result, title: `歌曲${index}`, artists: ['测试歌手'] } } }
const first = Array.from({ length: 20 }, (_, i) => song(i + 1))
function result(items = first, page = 0, totalElements = 21): SongPage { return { items, page, size: 20, totalElements } }
const button = (name: string) => wrapper.findAll('button').find(b => b.text() === name)!
async function open(ids: string[] = []) { wrapper = mount(ClassificationSongPicker, { props: { selectedIds: ids }, attachTo: document.body }); await flushPromises() }
async function query(q: string) { await wrapper.get('input[aria-label="搜索要分类的歌曲"]').setValue(q); await wrapper.get('form').trigger('submit'); await flushPromises() }
async function page(n: number) { wrapper.findComponent({ name: 'ElPagination' }).vm.$emit('current-change', n); await flushPromises() }
beforeEach(() => { vi.mocked(songs.list).mockImplementation(async filters => filters.q ? result([song(21)], 0, 1) : filters.page ? result([song(21)], 1) : result()) })
afterEach(() => { wrapper?.unmount(); document.body.innerHTML = ''; vi.clearAllMocks() })
it('搜索和跨页保留勾选，确认只返回已选歌曲 ID', async () => {
  await open(['song-1'])
  await wrapper.get('input[aria-label="选择歌曲：歌曲2"]').setValue(true)
  await page(2); await wrapper.get('input[aria-label="选择歌曲：歌曲21"]').setValue(true)
  await query('测试歌手')
  expect(songs.list).toHaveBeenLastCalledWith(expect.objectContaining({ q: '测试歌手', page: 0, size: 20 }))
  expect((wrapper.get('input[aria-label="选择歌曲：歌曲21"]').element as HTMLInputElement).checked).toBe(true)
  await button('确认选择（3 首）').trigger('click')
  expect(wrapper.emitted('confirm')).toEqual([[['song-1', 'song-2', 'song-21']]])
})
it('取消和清空只修改弹窗草稿，不覆盖原有选择', async () => {
  const ids = ['song-1']; await open(ids)
  await button('清空已选').trigger('click'); await button('取消').trigger('click')
  expect(ids).toEqual(['song-1']); expect(wrapper.emitted('confirm')).toBeUndefined(); expect(wrapper.emitted('close')).toHaveLength(1)
})
it('选择本页和取消本页不会影响其他页已选歌曲', async () => {
  await open(); await wrapper.get('input[aria-label="选择本页歌曲"]').setValue(true)
  await page(2); await wrapper.get('input[aria-label="选择歌曲：歌曲21"]').setValue(true)
  await page(1); await wrapper.get('input[aria-label="选择本页歌曲"]').setValue(false)
  await button('确认选择（1 首）').trigger('click'); expect(wrapper.emitted('confirm')).toEqual([[['song-21']]])
})
it('读取失败可重试，搜索无结果保留已选歌曲', async () => {
  vi.mocked(songs.list).mockRejectedValueOnce(new Error('曲库暂时不可用'))
  await open(['song-1']); expect(wrapper.text()).toContain('曲库暂时不可用')
  await button('重新加载').trigger('click'); await flushPromises(); expect(wrapper.text()).toContain('歌曲1')
  vi.mocked(songs.list).mockResolvedValueOnce(result([], 0, 0)); await query('不存在的歌曲')
  expect(wrapper.text()).toContain('没有找到匹配的歌曲'); expect(wrapper.text()).toContain('已选 1 首')
})
it('迟到的搜索结果不会替换新搜索结果', async () => {
  await open(); let resolveOld!: (value: SongPage) => void
  vi.mocked(songs.list).mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve }))
  await query('旧查询'); await query('新查询')
  resolveOld(result([song(99)], 0, 1)); await flushPromises()
  expect(wrapper.text()).toContain('歌曲21'); expect(wrapper.text()).not.toContain('歌曲99')
})
it('选择有 5000 首上限，仍可取消选择并确认清空', async () => {
  await open(Array.from({ length: 5000 }, (_, i) => i === 0 ? 'song-1' : `selected-${i}`))
  expect(wrapper.get('input[aria-label="选择歌曲：歌曲2"]').attributes('disabled')).toBeDefined()
  expect(wrapper.get('input[aria-label="选择歌曲：歌曲1"]').attributes('disabled')).toBeUndefined()
  await button('清空已选').trigger('click'); await button('确认选择').trigger('click')
  expect(wrapper.emitted('confirm')).toEqual([[[]]])
})

// @vitest-environment jsdom
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { ElMessageBox, ElPagination } from 'element-plus'
import { onBeforeRouteLeave } from 'vue-router'
import SongCacheView from '../src/admin/modules/song-cache/SongCacheView.vue'
import { songCache, type CachePage } from '../src/admin/modules/song-cache/api'
import { ApiError } from '../src/admin/core/auth/api'

vi.mock('../src/admin/modules/song-cache/api')
vi.mock('vue-router', async original => ({ ...await original<typeof import('vue-router')>(), onBeforeRouteLeave: vi.fn() }))
const original: CachePage = {
  settings: { maxBytes: 10 * 1024 ** 3, policy: 'LRU', ttlHours: 72, revision: 2 }, usageBytes: 1024 ** 3, entryCount: 2,
  page: 0, size: 20, totalElements: 2,
  items: [
    { id: 'cache-video', songId: 'song1', title: '合唱歌曲', artist: '歌手一 / 歌手二', mediaType: 'VIDEO', bytes: 512 * 1024 ** 2, hits: 7, createdAt: '2026-11-04T02:00:00Z', lastAccessAt: '2026-11-05T02:00:00Z', leased: false, target: { mode: 'TRANSCODE', codec: 'h264', width: 1920, height: 1080 } },
    { id: 'cache-audio', songId: 'song2', title: '音频歌曲', artist: '歌手三', mediaType: 'AUDIO', bytes: 10 * 1024 ** 2, hits: 1, createdAt: '2026-11-04T03:00:00Z', lastAccessAt: '2026-11-05T03:00:00Z', leased: true, target: { mode: 'AUDIO_TRANSCODE', start: 10 } },
  ],
}
let wrapper: VueWrapper | undefined
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(songCache.list).mockResolvedValue(structuredClone(original))
  vi.mocked(songCache.save).mockImplementation(async value => ({ ...value, revision: value.revision + 1 }))
  vi.mocked(songCache.remove).mockResolvedValue(undefined)
  vi.mocked(songCache.clear).mockResolvedValue({ removed: 1, skipped: 1 })
})
afterEach(() => { wrapper?.unmount(); wrapper = undefined; vi.restoreAllMocks() })
async function page() { wrapper = mount(SongCacheView, { global: { stubs: { RouterLink: { template: '<a><slot/></a>' } } } }); await flushPromises() }
function button(text: string) { return wrapper!.findAll('button').find(b => b.text() === text)! }
function confirm() { return vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as Awaited<ReturnType<typeof ElMessageBox.confirm>>) }

it('显示音视频缓存、容量、命中和租约状态，格式标明请求画质上限', async () => {
  await page()
  expect(songCache.list).toHaveBeenCalledWith(0, 20)
  expect(wrapper!.findAll('.cache-row')).toHaveLength(2)
  expect(wrapper!.text()).toContain('1 GiB')
  expect(wrapper!.text()).toContain('2 份已完成缓存')
  expect(wrapper!.text()).toContain('合唱歌曲')
  expect(wrapper!.text()).toContain('画质上限 1920 × 1080')
  expect(wrapper!.text()).toContain('命中 7 次')
  expect(wrapper!.text()).toContain('从 10 秒起')
  expect(wrapper!.text()).toContain('整曲缓存支持跳转复用')
  expect(wrapper!.text()).toContain('离线兼容版本仍在歌曲播放设置中管理')
  expect(wrapper!.get('button[aria-label="删除音频歌曲的缓存"]').attributes('disabled')).toBeDefined()
  expect(wrapper!.get('button[aria-label="删除合唱歌曲的缓存"]').attributes('disabled')).toBeUndefined()
})

it('容量和策略只有显式保存才生效，按GiB转为字节且携带当前revision', async () => {
  await page()
  expect(wrapper!.get('select[aria-label="缓存策略"]').findAll('option').map(o => o.attributes('value'))).toEqual(['LRU', 'LFU', 'TTL', 'MANUAL'])
  await wrapper!.get('input[aria-label="最大缓存容量"]').setValue('3.5')
  await wrapper!.get('select[aria-label="缓存策略"]').setValue('TTL')
  await wrapper!.get('input[aria-label="缓存保留时长"]').setValue('24')
  expect(songCache.save).not.toHaveBeenCalled()
  await wrapper!.get('form.cache-settings').trigger('submit'); await flushPromises()
  expect(songCache.save).toHaveBeenCalledWith({ maxBytes: 3.5 * 1024 ** 3, policy: 'TTL', ttlHours: 24, revision: 2 })
  expect(wrapper!.text()).toContain('缓存设置已保存')
})

it('验证容量与保留时长边界，无效草稿不能提交', async () => {
  await page()
  await wrapper!.get('input[aria-label="最大缓存容量"]').setValue('0')
  expect(wrapper!.text()).toContain('缓存容量应在')
  expect(button('保存设置').attributes('disabled')).toBeDefined()
  await wrapper!.get('input[aria-label="最大缓存容量"]').setValue('1')
  await wrapper!.get('select[aria-label="缓存策略"]').setValue('TTL')
  await wrapper!.get('input[aria-label="缓存保留时长"]').setValue('1.5')
  expect(wrapper!.text()).toContain('保留时长应为')
  await wrapper!.get('form.cache-settings').trigger('submit'); await flushPromises()
  expect(songCache.save).not.toHaveBeenCalled()
})

it('刷新列表保留设置草稿，重新读取设置需确认放弃', async () => {
  await page()
  await wrapper!.get('input[aria-label="最大缓存容量"]').setValue('25')
  await button('刷新列表').trigger('click'); await flushPromises()
  expect(wrapper!.get('input[aria-label="最大缓存容量"]').element).toHaveProperty('value', '25')
  const ask = confirm().mockRejectedValueOnce('cancel')
  await button('重新读取设置').trigger('click'); await flushPromises()
  expect(songCache.list).toHaveBeenCalledTimes(2)
  expect(ask).toHaveBeenCalledTimes(1)
  await button('重新读取设置').trigger('click'); await flushPromises()
  expect(wrapper!.get('input[aria-label="最大缓存容量"]').element).toHaveProperty('value', '10')
})

it('单份删除先确认，批量清理显示跳过的播放中缓存', async () => {
  await page()
  const ask = confirm().mockRejectedValueOnce('cancel')
  await wrapper!.get('button[aria-label="删除合唱歌曲的缓存"]').trigger('click'); await flushPromises()
  expect(songCache.remove).not.toHaveBeenCalled()
  await wrapper!.get('button[aria-label="删除合唱歌曲的缓存"]').trigger('click'); await flushPromises()
  expect(songCache.remove).toHaveBeenCalledWith('cache-video')
  await button('清理缓存').trigger('click'); await flushPromises()
  expect(songCache.clear).toHaveBeenCalledTimes(1)
  expect(ask.mock.calls[2]![0]).toContain('原始歌曲和离线兼容版本会保留')
  expect(wrapper!.text()).toContain('已清理 1 份缓存，跳过 1 份正在使用的缓存')
})

it('设置冲突保留草稿，重新读取后才能再次提交', async () => {
  await page()
  await wrapper!.get('input[aria-label="最大缓存容量"]').setValue('12')
  vi.mocked(songCache.save).mockRejectedValueOnce(new ApiError(409, '配置已变化，请重新读取'))
  await wrapper!.get('form.cache-settings').trigger('submit'); await flushPromises()
  expect(wrapper!.text()).toContain('配置已变化，请重新读取')
  expect(wrapper!.get('input[aria-label="最大缓存容量"]').element).toHaveProperty('value', '12')
  expect(button('保存设置').attributes('disabled')).toBeDefined()
  expect(wrapper!.text()).not.toContain('缓存设置已保存')
  confirm()
  await button('重新读取设置').trigger('click'); await flushPromises()
  await wrapper!.get('input[aria-label="最大缓存容量"]').setValue('12')
  expect(button('保存设置').attributes('disabled')).toBeUndefined()
})

it('未连接和空缓存状态分开，重试失败读取后显示真实空态', async () => {
  vi.mocked(songCache.list).mockRejectedValueOnce(new ApiError(503, '缓存目录不可读取'))
  await page()
  expect(wrapper!.text()).toContain('缓存目录不可读取')
  expect(wrapper!.text()).toContain('缓存管理无需连接计算节点')
  expect(wrapper!.text()).not.toContain('前往性能节点配置')
  expect(wrapper!.text()).not.toContain('暂无歌曲缓存')
  vi.mocked(songCache.list).mockResolvedValueOnce({ ...structuredClone(original), items: [], totalElements: 0, entryCount: 0, usageBytes: 0 })
  await button('重试').trigger('click'); await flushPromises()
  expect(wrapper!.text()).toContain('暂无歌曲缓存')
  expect(button('清理缓存').attributes('disabled')).toBeDefined()
})

it('没有完整可复用条目但仍有磁盘占用时，允许清理旧缓存或损坏产物', async () => {
  vi.mocked(songCache.list).mockResolvedValue({ ...structuredClone(original), settings: { ...original.settings, policy: 'MANUAL' }, items: [], totalElements: 0, entryCount: 0, usageBytes: 128 * 1024 ** 2 })
  await page()
  expect(wrapper!.text()).toContain('暂无歌曲缓存')
  expect(button('清理缓存').attributes('disabled')).toBeUndefined()
  confirm()
  vi.mocked(songCache.clear).mockResolvedValue({ removed: 1, skipped: 0 })
  await button('清理缓存').trigger('click'); await flushPromises()
  expect(songCache.clear).toHaveBeenCalledTimes(1)
  expect(wrapper!.text()).toContain('已清理 1 份缓存')
})

it('分页交给服务端，删除末页最后一份缓存会回退有效页', async () => {
  vi.mocked(songCache.list).mockResolvedValue({ ...structuredClone(original), totalElements: 21 })
  await page()
  wrapper!.findComponent(ElPagination).vm.$emit('current-change', 2); await flushPromises()
  expect(songCache.list).toHaveBeenLastCalledWith(1, 20)
  vi.mocked(songCache.list).mockResolvedValue({ ...structuredClone(original), totalElements: 20 })
  confirm()
  await wrapper!.get('button[aria-label="删除合唱歌曲的缓存"]').trigger('click'); await flushPromises()
  expect(songCache.list).toHaveBeenLastCalledWith(0, 20)
})

it('离页和关闭浏览器时保护设置草稿，取消离页后继续编辑', async () => {
  await page()
  const guard = vi.mocked(onBeforeRouteLeave).mock.calls[0]![0] as () => Promise<boolean>
  expect(await guard()).toBe(true)
  await wrapper!.get('input[aria-label="最大缓存容量"]').setValue('12')
  const event = new Event('beforeunload', { cancelable: true })
  window.dispatchEvent(event)
  expect(event.defaultPrevented).toBe(true)
  confirm().mockRejectedValueOnce('cancel')
  expect(await guard()).toBe(false)
  expect(wrapper!.get('input[aria-label="最大缓存容量"]').element).toHaveProperty('value', '12')
})

// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import SongVideoDialog from '../src/admin/modules/song-management/components/SongVideoDialog.vue'
import type { MediaSong } from '../src/admin/modules/media-library/types'
import { playback } from '../src/admin/modules/transcoding/playback'

const adapter = vi.hoisted(() => ({ load: vi.fn(), attach: vi.fn(), destroy: vi.fn(), on: vi.fn() }))
vi.mock('hls.js', () => ({ default: class {
  static Events = { ERROR: 'error' }
  static isSupported() { return true }
  loadSource = adapter.load
  attachMedia = adapter.attach
  destroy = adapter.destroy
  on = adapter.on
} }))
vi.mock('../src/admin/modules/transcoding/playback')
const song = { id: 'fixture', sourceName: '来源', relativePath: 'fixture.mkv', result: { title: '测试', artists: [] } } as unknown as MediaSong
beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(playback.start).mockResolvedValue({ id: 'execution', mode: 'HLS', status: 'READY', url: '/api/v1/transcoding/sessions/execution/artifacts/master.m3u8', offset: 0 })
  vi.mocked(playback.tasks).mockResolvedValue([])
  vi.mocked(playback.cancel).mockResolvedValue(undefined)
})
afterEach(() => { vi.restoreAllMocks(); document.body.innerHTML = '' })
it('非原生浏览器连接 HLS 适配器，错误时终止请求并取消后台执行', async () => {
  const wrapper = mount(SongVideoDialog, { props: { song }, global: { stubs: { teleport: true } } })
  try {
    for (let i = 0; i < 5; i++) await flushPromises()
    expect(adapter.load).toHaveBeenCalledWith('/api/v1/transcoding/sessions/execution/artifacts/master.m3u8')
    expect(adapter.attach).toHaveBeenCalledWith(wrapper.get('video').element)
    const error = adapter.on.mock.calls.find(([event]) => event === 'error')![1] as (event: string, data: { fatal: boolean }) => void
    error('error', { fatal: true }); await flushPromises()
    expect(adapter.destroy).toHaveBeenCalledOnce()
    expect(playback.cancel).toHaveBeenCalledWith('execution')
    expect(wrapper.find('video').exists()).toBe(false)
    expect(wrapper.text()).toContain('兼容播放中断')
  } finally { wrapper.unmount() }
})
it('原生 HLS 使用同源地址，关闭时取消执行', async () => {
  vi.spyOn(HTMLMediaElement.prototype, 'canPlayType').mockReturnValue('probably')
  const wrapper = mount(SongVideoDialog, { props: { song }, global: { stubs: { teleport: true } } })
  try {
    await flushPromises()
    expect(adapter.load).not.toHaveBeenCalled()
    expect(wrapper.get('video').attributes('src')).toBe('/api/v1/transcoding/sessions/execution/artifacts/master.m3u8')
    await wrapper.setProps({ song: null }); await flushPromises()
    expect(playback.cancel).toHaveBeenCalledWith('execution')
    expect(wrapper.find('video').exists()).toBe(false)
  } finally { wrapper.unmount() }
})

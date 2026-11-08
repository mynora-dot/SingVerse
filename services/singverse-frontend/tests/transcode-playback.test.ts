// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import SongVideoDialog from '../src/admin/modules/song-management/components/SongVideoDialog.vue'
import type { MediaSong } from '../src/admin/modules/media-library/types'
import { playback } from '../src/admin/modules/transcoding/playback'
import { mockMediaElements } from './media-element-mocks'

const adapter = vi.hoisted(() => ({ supported: vi.fn(), load: vi.fn(), attach: vi.fn(), destroy: vi.fn(), on: vi.fn() }))
vi.mock('hls.js', () => ({ default: class {
  static Events = { ERROR: 'error' }
  static isSupported = adapter.supported
  loadSource = adapter.load
  attachMedia = adapter.attach
  destroy = adapter.destroy
  on = adapter.on
} }))
vi.mock('../src/admin/modules/transcoding/playback')
const song = { id: 'fixture', sourceName: '来源', relativePath: 'fixture.mkv', result: { title: '测试', artists: [] } } as unknown as MediaSong
beforeEach(() => {
  vi.resetAllMocks()
  adapter.supported.mockReturnValue(true)
  mockMediaElements()
  vi.mocked(playback.start).mockResolvedValue({ id: 'execution', mode: 'HLS', status: 'READY', url: '/api/v1/transcoding/sessions/execution/artifacts/master.m3u8', offset: 0 })
  vi.mocked(playback.tasks).mockResolvedValue([])
  vi.mocked(playback.cancel).mockResolvedValue(undefined)
  vi.mocked(playback.roles).mockResolvedValue({ state: 'UNKNOWN', jobs: [] })
})

it('生成的伴奏与视频保持原时间轴同步，静音原声且暂停与缓冲同时控制两端', async () => {
  adapter.supported.mockReturnValue(false)
  vi.spyOn(HTMLMediaElement.prototype, 'canPlayType').mockReturnValue('probably')
  const audioRoles = { state: 'CONFIRMED', originalId: 'stream:0', backingResource: { jobId: 'backing' }, jobs: [] }
  vi.mocked(playback.start).mockResolvedValue({ id: 'execution', mode: 'HLS', status: 'READY', url: '/api/v1/master.m3u8', offset: 40, audioRoles, backingUrl: '/api/v1/backing' })
  const wrapper = mount(SongVideoDialog, { props: { song }, global: { stubs: { teleport: true } } })
  try {
    await flushPromises()
    const video = wrapper.get('video').element, backing = wrapper.get('audio').element
    Object.defineProperty(backing, 'readyState', { value: 4, configurable: true })
    video.currentTime = 8; await wrapper.get('video').trigger('timeupdate')
    expect(backing.currentTime).toBe(48)
    expect(video.muted).toBe(true)
    await wrapper.get('.main-play').trigger('click'); await flushPromises()
    expect(backing.paused).toBe(false)
    await wrapper.get('video').trigger('waiting')
    expect(backing.paused).toBe(true)
    await wrapper.get('video').trigger('playing'); await flushPromises()
    expect(backing.paused).toBe(false)
    await wrapper.get('audio').trigger('waiting'); await flushPromises()
    expect(video.paused).toBe(true)
    await wrapper.get('audio').trigger('canplay'); await flushPromises()
    expect(video.paused).toBe(false)
    await wrapper.get('.main-play').trigger('click'); await flushPromises()
    expect(video.paused).toBe(true); expect(backing.paused).toBe(true)
    await wrapper.get('[aria-label="音量"]').setValue('25')
    expect(backing.volume).toBe(.25)
    await wrapper.get('[aria-label="静音"]').trigger('click')
    expect(backing.muted).toBe(true)
  } finally { wrapper.unmount() }
})

it('HLS 准备轮询不丢失首次协商的伴奏资源和角色快照', async () => {
  adapter.supported.mockReturnValue(false)
  vi.useFakeTimers()
  vi.spyOn(HTMLMediaElement.prototype, 'canPlayType').mockReturnValue('probably')
  vi.mocked(playback.start).mockResolvedValueOnce({ id: 'preparing', mode: 'HLS', status: 'PREPARING', url: '', offset: 0,
    audioRoles: { state: 'CONFIRMED', backingResource: { jobId: 'backing' }, jobs: [] }, backingUrl: '/api/v1/initial-backing' })
  vi.mocked(playback.status).mockResolvedValue({ id: 'preparing', mode: 'HLS', status: 'READY', url: '/api/v1/ready.m3u8', offset: 12 })
  const wrapper = mount(SongVideoDialog, { props: { song }, global: { stubs: { teleport: true } } })
  try {
    await flushPromises(); await vi.advanceTimersByTimeAsync(500); await flushPromises()
    expect(wrapper.get('video').attributes('src')).toBe('/api/v1/ready.m3u8')
    expect(wrapper.get('audio').attributes('src')).toBe('/api/v1/initial-backing')
    expect(wrapper.findAll('.role-picker button')[1]!.attributes('disabled')).toBeUndefined()
  } finally { wrapper.unmount(); vi.useRealTimers() }
})

it.each([
  { native: true, offset: 0, mediaTime: 100.21576, displayedTime: '1:40' },
  { native: true, offset: 40, mediaTime: 60.21576, displayedTime: '1:40' },
  { native: true, offset: 100.21576, mediaTime: 0, displayedTime: '1:40' },
  { native: true, offset: 120, mediaTime: 0, displayedTime: '2:00' },
  { native: false, offset: 0, mediaTime: 100.21576, displayedTime: '1:40' },
  { native: false, offset: 40, mediaTime: 60.21576, displayedTime: '1:40' },
])('HLS 协商保留原曲位置（native=$native，offset=$offset）', async ({ native, offset, mediaTime, displayedTime }) => {
  adapter.supported.mockReturnValue(!native)
  vi.spyOn(HTMLMediaElement.prototype, 'canPlayType').mockReturnValue(native ? 'probably' : '')
  const cached = { id: 'cached', mode: 'HLS', status: 'READY', url: '/api/v1/cached.m3u8', offset,
    execution: { cacheHit: true, media: { duration: 240, streams: [] } } }
  vi.mocked(playback.start).mockResolvedValueOnce({ id: 'initial', mode: 'HLS', status: 'READY', url: '/api/v1/initial.m3u8', offset: 0,
    media: { duration: 240, streams: [] } }).mockResolvedValueOnce(cached)
  const wrapper = mount(SongVideoDialog, { props: { song }, global: { stubs: { teleport: true } } })
  try {
    for (let i = 0; i < 5; i++) await flushPromises()
    wrapper.get('video').element.currentTime = 100.21576
    await wrapper.get('video').trigger('timeupdate')
    await wrapper.get('.settings-button').trigger('click')
    await wrapper.get('[aria-label="播放画质"]').setValue('1080p')
    for (let i = 0; i < 5; i++) await flushPromises()
    expect(playback.start).toHaveBeenLastCalledWith(song.id, expect.objectContaining({ start: 100.21576 }))
    const video = wrapper.get('video')
    if (native) expect(video.attributes('src')).toBe('/api/v1/cached.m3u8')
    else expect(adapter.load).toHaveBeenLastCalledWith('/api/v1/cached.m3u8')
    await video.trigger('loadedmetadata')
    expect(video.element.currentTime).toBeCloseTo(mediaTime)
    expect(wrapper.get('.timeline span').text()).toBe(displayedTime)
  } finally { wrapper.unmount() }
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
it('MSE 不可用时原生 HLS 使用同源地址，关闭时取消执行', async () => {
  adapter.supported.mockReturnValue(false)
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

it('HLS 解码失败只自动兼容一次，旧 HLS 回调不能打断新执行', async () => {
  const wrapper = mount(SongVideoDialog, { props: { song }, global: { stubs: { teleport: true } } })
  try {
    for (let i = 0; i < 5; i++) await flushPromises()
    const error = adapter.on.mock.calls[0]![1] as (event: string, data: { fatal: boolean; type: string }) => void
    error('error', { fatal: true, type: 'mediaError' })
    for (let i = 0; i < 5; i++) await flushPromises()
    expect(playback.start).toHaveBeenCalledTimes(2)
    expect(playback.start).toHaveBeenLastCalledWith(song.id, expect.objectContaining({ force: true }))
    expect(wrapper.find('.player-error').exists()).toBe(false)
    expect(adapter.destroy).toHaveBeenCalledOnce()
    error('error', { fatal: true, type: 'mediaError' }); await flushPromises()
    expect(wrapper.find('video').exists()).toBe(true)
    const fallbackError = adapter.on.mock.calls[1]![1] as typeof error
    fallbackError('error', { fatal: true, type: 'mediaError' }); await flushPromises()
    expect(playback.start).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('兼容播放解码失败')
  } finally { wrapper.unmount() }
})

// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import AudioTrackPreview from '../src/admin/modules/song-management/components/AudioTrackPreview.vue'
import { playback } from '../src/admin/modules/transcoding/playback'
import { adminActivity } from '../src/admin/modules/transcoding/activity'
import { mockMediaElements } from './media-element-mocks'
const adapter = vi.hoisted(() => ({ supported: vi.fn(), load: vi.fn(), attach: vi.fn(), destroy: vi.fn(), on: vi.fn() }))
vi.mock('hls.js', () => ({ default: class {
  static Events = { MANIFEST_PARSED: 'manifestParsed', ERROR: 'error' }
  static isSupported = adapter.supported
  loadSource = adapter.load
  attachMedia = adapter.attach
  destroy = adapter.destroy
  on = adapter.on
} }))
vi.mock('../src/admin/modules/transcoding/playback', () => ({ playback: { start: vi.fn(), status: vi.fn(), cancel: vi.fn() } }))
const streams = [{ id: 'stream:1' }, { id: 'stream:2' }]
let wrapper: ReturnType<typeof mount> | undefined
beforeEach(() => {
  vi.useFakeTimers(); vi.clearAllMocks(); mockMediaElements()
  adapter.supported.mockReturnValue(false)
  vi.spyOn(HTMLMediaElement.prototype, 'canPlayType').mockReturnValue('probably')
  vi.mocked(playback.start).mockResolvedValue({ id: 'session1', mode: 'HLS', status: 'READY', offset: 0, url: '/first.m3u8' })
  vi.mocked(playback.cancel).mockResolvedValue(undefined)
  vi.spyOn(adminActivity, 'create').mockResolvedValue({ attemptId: 'attempt', writeToken: 'write-token', eventSeq: -1, leaseSeconds: 120, startedAt: Date.now(), serverTime: Date.now() })
  vi.spyOn(adminActivity, 'event').mockResolvedValue(undefined)
})
afterEach(() => { wrapper?.unmount(); wrapper = undefined; vi.restoreAllMocks(); vi.useRealTimers() })
it('同时支持原生 HLS 和 MSE 时通过 hls.js 试听，并在停止时释放播放器', async () => {
  adapter.supported.mockReturnValue(true)
  wrapper = mount(AudioTrackPreview, { props: { songId: 'song', streams } })
  await wrapper.findAll('button')[0]!.trigger('click')
  for (let i = 0; i < 5; i++) await flushPromises()
  expect(adapter.load).toHaveBeenCalledExactlyOnceWith('/first.m3u8')
  expect(adapter.attach).toHaveBeenCalledExactlyOnceWith(wrapper.get('audio').element)
  expect(wrapper.get('audio').attributes('src')).toBeUndefined()
  const play = adapter.on.mock.calls.find(([event]) => event === 'manifestParsed')![1] as () => void
  play(); await flushPromises()
  expect(HTMLMediaElement.prototype.play).toHaveBeenCalled()
  await wrapper.findAll('button').find(button => button.text() === '停止试听')!.trigger('click')
  expect(adapter.destroy).toHaveBeenCalledOnce()
  expect(playback.cancel).toHaveBeenCalledWith('session1')
})
it('未标记也能指定音轨试听，切换时保留时间且释放旧会话', async () => {
  wrapper = mount(AudioTrackPreview, { props: { songId: 'song', streams } })
  await wrapper.findAll('button')[0]!.trigger('click'); await flushPromises()
  expect(playback.start).toHaveBeenCalledWith('song', { audio_id: 'stream:1', quality: 'auto', start: 0 })
  const audio = wrapper.get('audio'); expect(audio.attributes('src')).toBe('/first.m3u8')
  await audio.trigger('loadedmetadata'); expect(HTMLMediaElement.prototype.play).toHaveBeenCalled()
  ;(audio.element as HTMLAudioElement).currentTime = 42
  vi.mocked(playback.start).mockResolvedValue({ id: 'session2', mode: 'HLS', status: 'READY', offset: 42, url: '/second.m3u8' })
  await wrapper.findAll('button')[1]!.trigger('click'); await flushPromises()
  expect(playback.cancel).toHaveBeenCalledWith('session1')
  expect(playback.start).toHaveBeenLastCalledWith('song', { audio_id: 'stream:2', quality: 'auto', start: 42 })
  expect(audio.attributes('src')).toBe('/second.m3u8')
  wrapper.unmount(); wrapper = undefined
  expect(playback.cancel).toHaveBeenCalledWith('session2')
  expect(vi.getTimerCount()).toBe(0)
})
it.each([
  { native: true, offset: 0, mediaTime: 100.21576 },
  { native: true, offset: 40, mediaTime: 60.21576 },
  { native: false, offset: 0, mediaTime: 100.21576 },
  { native: false, offset: 40, mediaTime: 60.21576 },
])('切轨命中 HLS 缓存时保留原曲位置（native=$native，offset=$offset）', async ({ native, offset, mediaTime }) => {
  vi.mocked(HTMLMediaElement.prototype.canPlayType).mockReturnValue(native ? 'probably' : '')
  adapter.supported.mockReturnValue(!native)
  wrapper = mount(AudioTrackPreview, { props: { songId: 'song', streams } })
  await wrapper.findAll('button')[0]!.trigger('click')
  for (let i = 0; i < 5; i++) await flushPromises()
  const audio = wrapper.get('audio')
  await audio.trigger('loadedmetadata')
  audio.element.currentTime = 100.21576
  const cached = { id: 'cached', mode: 'HLS', status: 'READY', offset, url: '/cached.m3u8', execution: { cacheHit: true, media: { duration: 240, streams: [] } } }
  vi.mocked(playback.start).mockResolvedValue(cached)
  await wrapper.findAll('button')[1]!.trigger('click')
  for (let i = 0; i < 5; i++) await flushPromises()
  expect(playback.start).toHaveBeenLastCalledWith('song', { audio_id: 'stream:2', quality: 'auto', start: 100.21576 })
  audio.element.currentTime = 0 // A new media URL starts at the resource's zero point.
  await audio.trigger('loadedmetadata')
  if (!native) {
    const onParsed = adapter.on.mock.calls.filter(([event]) => event === 'manifestParsed').at(-1)![1] as () => void
    onParsed(); await flushPromises()
    expect(adapter.load).toHaveBeenLastCalledWith('/cached.m3u8')
  }
  expect(audio.element.currentTime).toBeCloseTo(mediaTime)
  expect(audio.element.paused).toBe(false)
})
it('关闭或切换歌曲后迟到的会话会被释放，不会开始播放', async () => {
  let finish!: (value: Awaited<ReturnType<typeof playback.start>>) => void
  vi.mocked(playback.start).mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  wrapper = mount(AudioTrackPreview, { props: { songId: 'old', streams } })
  await wrapper.findAll('button')[0]!.trigger('click')
  await wrapper.setProps({ songId: 'new' })
  finish({ id: 'late', mode: 'HLS', status: 'READY', offset: 0, url: '/late.m3u8' }); await flushPromises()
  expect(playback.cancel).toHaveBeenCalledWith('late')
  expect(wrapper.get('audio').attributes('src')).toBeUndefined()
  expect(HTMLMediaElement.prototype.play).not.toHaveBeenCalled()
})
it('节点不可用时拒绝播放无法保证音轨的原始文件，并显示原因', async () => {
  vi.mocked(playback.start).mockResolvedValue({ mode: 'DIRECT', status: 'READY', offset: 0, url: '/raw.mkv' })
  wrapper = mount(AudioTrackPreview, { props: { songId: 'song', streams } })
  await wrapper.findAll('button')[1]!.trigger('click'); await flushPromises()
  expect(wrapper.get('[role="alert"]').text()).toContain('检查计算节点连接')
  expect(wrapper.get('audio').attributes('src')).toBeUndefined()
})
it('等待中的会话可停止，失败时释放会话', async () => {
  vi.mocked(playback.start).mockResolvedValue({ id: 'pending', mode: 'HLS', status: 'STARTING', offset: 0, url: '' })
  vi.mocked(playback.status).mockResolvedValue({ id: 'pending', mode: 'HLS', status: 'FAILED', state: 'FAILED', error: '源文件无法读取', offset: 0, url: '' })
  wrapper = mount(AudioTrackPreview, { props: { songId: 'song', streams } })
  await wrapper.findAll('button')[0]!.trigger('click'); await flushPromises()
  await vi.advanceTimersByTimeAsync(500); await flushPromises()
  expect(wrapper.get('[role="alert"]').text()).toContain('源文件无法读取')
  expect(playback.cancel).toHaveBeenCalledWith('pending')
})
it('独立原始音轨只在真正播放时登记保护，切轨复用一次试听并在关闭时停止', async () => {
  wrapper = mount(AudioTrackPreview, { props: { songId: 'song', streams } })
  await wrapper.findAll('button')[0]!.trigger('click'); await flushPromises()
  expect(adminActivity.create).not.toHaveBeenCalled()
  const audio = wrapper.get('audio')
  Object.defineProperty(audio.element, 'duration', { configurable: true, value: 180 })
  await audio.trigger('loadedmetadata'); await audio.trigger('playing'); await flushPromises()
  expect(adminActivity.create).toHaveBeenCalledExactlyOnceWith('song', expect.any(Number))
  expect(adminActivity.event).toHaveBeenLastCalledWith('attempt', expect.objectContaining({ state: 'PLAYING', durationMs: 180000, actualPlayedMs: 0 }))
  await vi.advanceTimersByTimeAsync(1200)
  await audio.trigger('waiting'); await flushPromises()
  expect(adminActivity.event).toHaveBeenLastCalledWith('attempt', expect.objectContaining({ state: 'BUFFERING', actualPlayedMs: 1200 }))
  vi.mocked(playback.start).mockResolvedValue({ id: 'session2', mode: 'HLS', status: 'READY', offset: 0, url: '/second.m3u8' })
  await wrapper.findAll('button')[1]!.trigger('click'); await flushPromises()
  await audio.trigger('loadedmetadata'); await audio.trigger('playing'); await flushPromises()
  expect(adminActivity.create).toHaveBeenCalledTimes(1)
  wrapper.unmount(); wrapper = undefined; await flushPromises()
  expect(adminActivity.event).toHaveBeenLastCalledWith('attempt', expect.objectContaining({ state: 'STOPPED' }))
  expect(vi.getTimerCount()).toBe(0)
})

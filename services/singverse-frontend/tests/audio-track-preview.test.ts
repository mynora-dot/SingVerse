// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import AudioTrackPreview from '../src/admin/modules/song-management/components/AudioTrackPreview.vue'
import { playback } from '../src/admin/modules/transcoding/playback'
import { mockMediaElements } from './media-element-mocks'
vi.mock('../src/admin/modules/transcoding/playback', () => ({ playback: { start: vi.fn(), status: vi.fn(), cancel: vi.fn() } }))
const streams = [{ id: 'stream:1' }, { id: 'stream:2' }]
let wrapper: ReturnType<typeof mount> | undefined
beforeEach(() => {
  vi.useFakeTimers(); vi.clearAllMocks(); mockMediaElements()
  vi.spyOn(HTMLMediaElement.prototype, 'canPlayType').mockReturnValue('probably')
  vi.mocked(playback.start).mockResolvedValue({ id: 'session1', mode: 'HLS', status: 'READY', offset: 0, url: '/first.m3u8' })
  vi.mocked(playback.cancel).mockResolvedValue(undefined)
})
afterEach(() => { wrapper?.unmount(); wrapper = undefined; vi.restoreAllMocks(); vi.useRealTimers() })
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

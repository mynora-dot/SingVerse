// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { expect, it, vi, beforeEach } from 'vitest'
import { playback } from '../src/admin/modules/transcoding/playback'
vi.mock('../src/admin/modules/transcoding/playback')
beforeEach(() => {
  vi.mocked(playback.start).mockImplementation(async id => ({ mode: 'DIRECT', status: 'READY', url: `/api/v1/media-songs/${id}/video`, offset: 0 }))
  vi.mocked(playback.tasks).mockResolvedValue([])
})
import SongVideoDialog from '../src/admin/modules/song-management/components/SongVideoDialog.vue'
import type { MediaSong } from '../src/admin/modules/media-library/types'

it('播放器失败时显示原因范围并可重新加载，切换歌曲使用新的同源地址', async () => {
  const song = { id: 'song-1', sourceName: '远端', relativePath: 'video.mp4', result: { title: '歌曲', artists: [] } } as unknown as MediaSong
  const wrapper = mount(SongVideoDialog, { props: { song }, global: { stubs: { teleport: true } } })
  try {
    await flushPromises()
    expect(wrapper.get('video').attributes('controls')).toBeDefined()
    expect(wrapper.get('video').attributes('preload')).toBe('metadata')
    await wrapper.get('video').trigger('error'); await flushPromises()
    expect(wrapper.text()).toContain('媒体暂时无法播放')
    expect(wrapper.find('video').exists()).toBe(false)
    await wrapper.findAll('button').find(button => button.text() === '重新加载')!.trigger('click'); await flushPromises()
    expect(wrapper.get('video').attributes('src')).toBe('/api/v1/media-songs/song-1/video')
    await wrapper.setProps({ song: { ...song, id: 'song-2' } }); await flushPromises()
    expect(wrapper.get('video').attributes('src')).toBe('/api/v1/media-songs/song-2/video')
    await wrapper.setProps({ song: null })
    expect(wrapper.find('video').exists()).toBe(false)
  } finally { wrapper.unmount(); document.body.innerHTML = '' }
})

it('音频使用音频控件，切换视频时移除音频，关闭时停止加载', async () => {
  const song = { id: 'audio', sourceName: '本地音频', relativePath: '歌手《歌名》.FLAC', result: { title: '歌名', artists: [] } } as unknown as MediaSong
  const wrapper = mount(SongVideoDialog, { props: { song }, global: { stubs: { teleport: true } } })
  try {
    await flushPromises()
    expect(wrapper.find('video').exists()).toBe(false)
    expect(wrapper.get('audio').attributes('controls')).toBeDefined()
    expect(wrapper.get('audio').attributes('src')).toBe('/api/v1/media-songs/audio/video')
    expect(wrapper.text()).toContain('音频播放')
    await wrapper.get('audio').trigger('error'); await flushPromises()
    expect(wrapper.text()).toContain('媒体暂时无法播放')
    await wrapper.findAll('button').find(button => button.text() === '重新加载')!.trigger('click'); await flushPromises()
    expect(wrapper.find('audio').exists()).toBe(true)
    await wrapper.setProps({ song: { ...song, id: 'video', relativePath: 'song.mp4' } }); await flushPromises()
    expect(wrapper.find('audio').exists()).toBe(false)
    expect(wrapper.find('video').exists()).toBe(true)
    await wrapper.setProps({ song: null })
    expect(wrapper.find('audio, video').exists()).toBe(false)
  } finally { wrapper.unmount(); document.body.innerHTML = '' }
})

it('切换歌曲时丢弃迟到的准备结果并取消旧执行', async () => {
  let resolveOld!: (value: Awaited<ReturnType<typeof playback.start>>) => void
  vi.mocked(playback.start).mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve }))
  vi.mocked(playback.cancel).mockResolvedValue(undefined)
  const song = { id: 'old', sourceName: '来源', relativePath: 'old.mp4', result: { title: '旧歌曲', artists: [] } } as unknown as MediaSong
  const wrapper = mount(SongVideoDialog, { props: { song }, global: { stubs: { teleport: true } } })
  try {
    await wrapper.setProps({ song: { ...song, id: 'new' } }); await flushPromises()
    resolveOld({ id: 'old-execution', mode: 'HLS', status: 'READY', url: '/api/v1/transcoding/old/master.m3u8', offset: 0 })
    await flushPromises()
    expect(wrapper.get('video').attributes('src')).toBe('/api/v1/media-songs/new/video')
    expect(playback.cancel).toHaveBeenCalledWith('old-execution')
  } finally { wrapper.unmount(); document.body.innerHTML = '' }
})

it('转换失败展示真实错误，生成兼容版本只提交当前歌曲', async () => {
  vi.mocked(playback.start).mockRejectedValueOnce(new Error('计算节点不可用'))
  vi.mocked(playback.offline).mockResolvedValue(['offline-task'])
  const song = { id: 'offline-song', sourceName: '来源', relativePath: 'song.mkv', result: { title: '歌曲', artists: [] } } as unknown as MediaSong
  const wrapper = mount(SongVideoDialog, { props: { song }, global: { stubs: { teleport: true } } })
  try {
    await flushPromises(); expect(wrapper.text()).toContain('计算节点不可用')
    await wrapper.findAll('button').find(button => button.text() === '生成兼容版本')!.trigger('click')
    await flushPromises(); expect(playback.offline).toHaveBeenCalledWith(['offline-song'], 'auto')
  } finally { wrapper.unmount(); document.body.innerHTML = '' }
})

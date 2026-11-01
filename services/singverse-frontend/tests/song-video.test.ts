// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { expect, it, vi, beforeEach, afterEach } from 'vitest'
import { mockMediaElements } from './media-element-mocks'
import { playback } from '../src/admin/modules/transcoding/playback'
vi.mock('../src/admin/modules/transcoding/playback')
beforeEach(() => {
  vi.resetAllMocks()
  mockMediaElements()
  vi.mocked(playback.start).mockImplementation(async id => ({ mode: 'DIRECT', status: 'READY', url: `/api/v1/media-songs/${id}/video`, offset: 0 }))
  vi.mocked(playback.tasks).mockResolvedValue([])
  vi.mocked(playback.roles).mockResolvedValue({ state: 'UNKNOWN', jobs: [] })
  vi.mocked(playback.cancel).mockResolvedValue(undefined)
})
afterEach(() => vi.restoreAllMocks())
import SongVideoDialog from '../src/admin/modules/song-management/components/SongVideoDialog.vue'
import type { MediaSong } from '../src/admin/modules/media-library/types'

it('播放器失败时显示原因范围并可重新加载，切换歌曲使用新的同源地址', async () => {
  const song = { id: 'song-1', sourceName: '远端', relativePath: 'video.mp4', result: { title: '歌曲', artists: [] } } as unknown as MediaSong
  const wrapper = mount(SongVideoDialog, { props: { song }, global: { stubs: { teleport: true } } })
  try {
    await flushPromises()
    expect(wrapper.get('video').attributes('controls')).toBeUndefined()
    expect(wrapper.findAll('[aria-label="播放进度"]')).toHaveLength(1)
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
    expect(wrapper.get('audio').attributes('controls')).toBeUndefined()
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
    await wrapper.get('.settings-button').trigger('click')
    await wrapper.findAll('button').find(button => button.text() === '生成兼容版本')!.trigger('click')
    await flushPromises(); expect(playback.offline).toHaveBeenCalledWith(['offline-song'], 'auto')
  } finally { wrapper.unmount(); document.body.innerHTML = '' }
})

const videoSong = { id: 'roles-song', sourceName: '来源', relativePath: 'song.mkv', result: { title: '歌曲', artists: [] } } as unknown as MediaSong
const confirmed = { state: 'CONFIRMED', originalId: 'stream:1', backingId: 'stream:2', jobs: [], streams: [{ id: 'stream:1' }, { id: 'stream:2' }] }

function mediaError(element: HTMLMediaElement, code: number) {
  Object.defineProperty(element, 'error', { configurable: true, value: { code } })
  element.dispatchEvent(new Event('error'))
}

it('首次自动播放解码失败时内部只重试一次兼容编码，不先展示错误', async () => {
  const wrapper = mount(SongVideoDialog, { props: { song: videoSong }, global: { stubs: { teleport: true } } })
  try {
    await flushPromises()
    const oldVideo = wrapper.get('video').element
    oldVideo.currentTime = 48; await wrapper.get('video').trigger('timeupdate')
    await wrapper.get('.main-play').trigger('click'); await flushPromises()
    await wrapper.get('[aria-label="音量"]').setValue('40')
    mediaError(oldVideo, 3); await flushPromises()
    expect(wrapper.find('.player-error').exists()).toBe(false)
    expect(playback.start).toHaveBeenCalledTimes(2)
    expect(playback.start).toHaveBeenLastCalledWith(videoSong.id, expect.objectContaining({ force: true, start: 48, audioRole: 'original' }))
    const newVideo = wrapper.get('video').element
    expect(newVideo).not.toBe(oldVideo)
    // A queued event from the replaced source must not tear down the retry.
    mediaError(oldVideo, 4); await flushPromises()
    expect(playback.start).toHaveBeenCalledTimes(2)
    await wrapper.get('video').trigger('loadedmetadata'); await flushPromises()
    expect(newVideo.currentTime).toBe(48)
    expect(newVideo.volume).toBe(.4)
    expect(newVideo.paused).toBe(false)
    mediaError(newVideo, 4); await flushPromises()
    expect(playback.start).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('媒体暂时无法播放')
  } finally { wrapper.unmount() }
})

it('不支持的格式也会自动兼容，网络错误和取消事件不触发转码', async () => {
  const wrapper = mount(SongVideoDialog, { props: { song: videoSong }, global: { stubs: { teleport: true } } })
  try {
    await flushPromises()
    mediaError(wrapper.get('video').element, 1); await flushPromises()
    expect(wrapper.find('.player-error').exists()).toBe(false)
    expect(playback.start).toHaveBeenCalledTimes(1)
    mediaError(wrapper.get('video').element, 4); await flushPromises()
    expect(playback.start).toHaveBeenCalledTimes(2)
    await wrapper.setProps({ song: { ...videoSong, id: 'network' } }); await flushPromises()
    const calls = vi.mocked(playback.start).mock.calls.length
    mediaError(wrapper.get('video').element, 2); await flushPromises()
    expect(playback.start).toHaveBeenCalledTimes(calls)
    expect(wrapper.text()).toContain('媒体读取失败，请检查网络或媒体来源后重试')
  } finally { wrapper.unmount() }
})

it('用户明确选择原始编码时，失败后不擅自转码', async () => {
  const wrapper = mount(SongVideoDialog, { props: { song: videoSong }, global: { stubs: { teleport: true } } })
  try {
    await flushPromises()
    await wrapper.get('[aria-label="播放编码"]').setValue('source'); await flushPromises()
    mediaError(wrapper.get('video').element, 4); await flushPromises()
    expect(playback.start).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('播放器无法解码此媒体')
  } finally { wrapper.unmount() }
})

it('自动兼容准备失败保留真实原因，并允许手动重试', async () => {
  vi.mocked(playback.start).mockRejectedValueOnce(new Error('计算节点不可用'))
  const wrapper = mount(SongVideoDialog, { props: { song: videoSong }, global: { stubs: { teleport: true } } })
  try {
    await flushPromises()
    await wrapper.findAll('button').find(b => b.text() === '重新加载')!.trigger('click'); await flushPromises()
    expect(wrapper.find('video').exists()).toBe(true)
    await wrapper.setProps({ song: { ...videoSong, id: 'fallback-failure' } }); await flushPromises()
    vi.mocked(playback.start).mockRejectedValueOnce(new Error('兼容转码设备不可用'))
    mediaError(wrapper.get('video').element, 3); await flushPromises()
    expect(wrapper.text()).toContain('兼容转码设备不可用')
  } finally { wrapper.unmount() }
})

it('原唱伴奏按共享角色选择，切换保留位置、播放状态和音量', async () => {
  vi.mocked(playback.start).mockImplementation(async () => ({ mode: 'DIRECT', status: 'READY', url: '/api/v1/selected', offset: 0, audioRoles: confirmed }))
  const wrapper = mount(SongVideoDialog, { props: { song: videoSong }, global: { stubs: { teleport: true } } })
  try {
    await flushPromises()
    expect(playback.start).toHaveBeenLastCalledWith(videoSong.id, expect.objectContaining({ audioRole: 'original' }))
    const media = wrapper.get('video').element
    media.currentTime = 48; await wrapper.get('video').trigger('timeupdate')
    await wrapper.get('.main-play').trigger('click'); await flushPromises()
    await wrapper.get('[aria-label="音量"]').setValue('35')
    await wrapper.findAll('.role-picker button')[1]!.trigger('click'); await flushPromises()
    expect(playback.start).toHaveBeenLastCalledWith(videoSong.id, expect.objectContaining({ audioRole: 'backing', start: 48 }))
    await wrapper.get('video').trigger('loadedmetadata'); await flushPromises()
    expect(wrapper.get('video').element.currentTime).toBe(48)
    expect(wrapper.get('video').element.paused).toBe(false)
    expect(wrapper.get('video').element.volume).toBe(.35)
    expect(wrapper.get('.transport-state').text()).toContain('正在播放 · 伴奏')
    expect(wrapper.findAll('.role-picker button')[1]!.attributes('aria-pressed')).toBe('true')
  } finally { wrapper.unmount() }
})

it('未确认的音轨不能切换伴奏，设置默认收起且不展示元数据和路径', async () => {
  vi.mocked(playback.roles).mockResolvedValue({ ...confirmed, state: 'NEEDS_REVIEW' })
  const wrapper = mount(SongVideoDialog, { props: { song: videoSong }, global: { stubs: { teleport: true } } })
  try {
    await flushPromises()
    expect(wrapper.findAll('.role-picker button')[1]!.attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('伴奏未就绪')
    expect(wrapper.find('[aria-label="播放画质"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('song.mkv')
    await wrapper.get('.settings-button').trigger('click')
    expect(wrapper.find('[aria-label="播放画质"]').exists()).toBe(true)
  } finally { wrapper.unmount() }
})

it('编码选择真实直放或强制 H264 兼容处理，位置保持在原时间轴', async () => {
  const wrapper = mount(SongVideoDialog, { props: { song: videoSong }, global: { stubs: { teleport: true } } })
  try {
    await flushPromises()
    wrapper.get('video').element.currentTime = 30; await wrapper.get('video').trigger('timeupdate')
    const calls = vi.mocked(playback.start).mock.calls.length
    await wrapper.get('[aria-label="播放编码"]').setValue('source'); await flushPromises()
    expect(playback.start).toHaveBeenCalledTimes(calls)
    expect(wrapper.get('video').attributes('src')).toBe('/api/v1/media-songs/roles-song/video')
    await wrapper.get('video').trigger('loadedmetadata')
    expect(wrapper.get('video').element.currentTime).toBe(30)
    await wrapper.get('[aria-label="播放编码"]').setValue('compatible'); await flushPromises()
    expect(playback.start).toHaveBeenLastCalledWith(videoSong.id, expect.objectContaining({ codec: 'h264', force: true, start: 30 }))
  } finally { wrapper.unmount() }
})

it('多轨原唱不能使用浏览器默认音轨冒充，选择原始编码回到自动处理', async () => {
  vi.mocked(playback.roles).mockResolvedValue(confirmed)
  const wrapper = mount(SongVideoDialog, { props: { song: videoSong }, global: { stubs: { teleport: true } } })
  try {
    await flushPromises()
    await wrapper.get('[aria-label="播放编码"]').setValue('source'); await flushPromises()
    expect((wrapper.get('[aria-label="播放编码"]').element as HTMLSelectElement).value).toBe('auto')
    expect(playback.start).toHaveBeenLastCalledWith(videoSong.id, expect.objectContaining({ audioRole: 'original' }))
    expect(wrapper.text()).toContain('已切换为自动编码')
  } finally { wrapper.unmount() }
})

it('音频的独立伴奏使用授权地址，切回原唱恢复原媒体', async () => {
  vi.mocked(playback.roles).mockResolvedValue({ state: 'CONFIRMED', originalId: 'stream:0', backingResource: { jobId: 'generated' }, jobs: [] })
  const wrapper = mount(SongVideoDialog, { props: { song: { ...videoSong, relativePath: 'song.flac' } }, global: { stubs: { teleport: true } } })
  try {
    await flushPromises()
    wrapper.get('audio').element.currentTime = 20; await wrapper.get('audio').trigger('timeupdate')
    await wrapper.findAll('.role-picker button')[1]!.trigger('click'); await flushPromises()
    expect(wrapper.get('audio').attributes('src')).toBe('/api/v1/media-songs/roles-song/accompaniment/generated')
    expect(wrapper.findAll('audio')).toHaveLength(1)
    await wrapper.get('audio').trigger('loadedmetadata')
    expect(wrapper.get('audio').element.currentTime).toBe(20)
    await wrapper.findAll('.role-picker button')[0]!.trigger('click'); await flushPromises()
    expect(wrapper.get('audio').attributes('src')).toBe('/api/v1/media-songs/roles-song/video')
    expect(wrapper.get('audio').element.muted).toBe(false)
  } finally { wrapper.unmount() }
})

it('关闭会停止并移除媒体源，迟到的音轨查询不会覆盖下一首歌', async () => {
  let resolveRoles!: (value: Awaited<ReturnType<typeof playback.roles>>) => void
  vi.mocked(playback.roles).mockImplementationOnce(() => new Promise(resolve => { resolveRoles = resolve }))
  const wrapper = mount(SongVideoDialog, { props: { song: videoSong }, global: { stubs: { teleport: true } } })
  try {
    await flushPromises()
    const media = wrapper.get('video').element
    await wrapper.setProps({ song: { ...videoSong, id: 'next' } }); await flushPromises()
    resolveRoles(confirmed); await flushPromises()
    expect(wrapper.findAll('.role-picker button')[1]!.attributes('disabled')).toBeDefined()
    expect(media.getAttribute('src')).toBeNull()
    const current = wrapper.get('video').element
    await wrapper.setProps({ song: null }); await flushPromises()
    expect(current.getAttribute('src')).toBeNull()
    expect(current.paused).toBe(true)
  } finally { wrapper.unmount() }
})

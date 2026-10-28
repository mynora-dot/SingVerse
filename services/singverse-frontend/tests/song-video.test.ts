// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { expect, it } from 'vitest'
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
    await wrapper.setProps({ song: { ...song, id: 'song-2' } })
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
    await wrapper.setProps({ song: { ...song, id: 'video', relativePath: 'song.mp4' } })
    expect(wrapper.find('audio').exists()).toBe(false)
    expect(wrapper.find('video').exists()).toBe(true)
    await wrapper.setProps({ song: null })
    expect(wrapper.find('audio, video').exists()).toBe(false)
  } finally { wrapper.unmount(); document.body.innerHTML = '' }
})

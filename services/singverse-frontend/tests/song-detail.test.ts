// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { ElButton } from 'element-plus'
import SongDetailDrawer from '../src/admin/modules/song-management/components/SongDetailDrawer.vue'
import { embeddedMetadata } from '../src/admin/modules/media-library/api/embeddedMetadata'
import type { MediaSong } from '../src/admin/modules/media-library/types'
import { authRequest } from '../src/admin/core/auth/api'
vi.mock('../src/admin/core/auth/api', () => ({ authRequest: vi.fn() }))
vi.mock('../src/admin/modules/media-library/api/embeddedMetadata', async original => ({ ...await original<typeof import('../src/admin/modules/media-library/api/embeddedMetadata')>(), embeddedMetadata: { detail: vi.fn(), refresh: vi.fn() } }))
function song(id = 'song-one'): MediaSong {
  return { id, sourceId: 'nas', sourceName: '家庭曲库', relativePath: 'music/long filename.flac', fileSize: 22544384, modifiedAt: null, scannedAt: '2026-10-31T10:00:00Z', parserProfileId: 'default', parserRevision: 1, selectedRuleId: null,
    result: { originalFilename: 'long filename.flac', normalizedFilename: 'long filename.flac', title: '文件名歌名', artists: ['文件名歌手'], language: 'MANDARIN', versionType: 'ORIGINAL', songCode: null, extension: 'flac', qualityTags: [], confidence: 1, parseStatus: 'SUCCESS', parserRule: '', needsReview: false, unparsedTokens: [], diagnostics: [] },
    metadata: { track: { provider: null, externalId: null, title: '王小六打豆腐', artists: ['黄梅戏'], album: '黄梅戏精选', releaseDate: '2026', durationMs: 352000, aliases: [], coverUrl: 'asset:chosen-cover', availability: 'UNKNOWN', fetchedAt: '' }, fields: ['title', 'artists', 'album', 'releaseDate', 'durationMs', 'cover'], appliedAt: '', stale: false, origins: { cover: { kind: 'UPLOAD', appliedAt: '', provider: null, externalId: null } } }, classification: undefined }
}
let wrapper: ReturnType<typeof mount>
beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(authRequest).mockResolvedValue({ state: 'UNKNOWN', revision: 0, streams: [{ id: 'stream:1' }, { id: 'stream:2' }], jobs: [] })
  vi.mocked(embeddedMetadata.detail).mockResolvedValue({ state: 'PARTIAL', result: { state: 'PARTIAL', cover: 'embedded-cover', technical: { container: 'flac', duration: 352 }, lyrics: { text: '[ti:曲名]\n[00:01.00]第一句歌词\n[00:02.50]第二句歌词', timing: 'lrc' } } })
})
afterEach(() => wrapper?.unmount())
it('封面与播放置顶，歌词直接可读，技术信息默认折叠且不重复请求', async () => {
  wrapper = mount(SongDetailDrawer, { props: { song: song(), visible: true }, global: { stubs: { teleport: true, transition: false } } }); await flushPromises()
  const hero = wrapper.get('.song-hero')
  expect(hero.text()).toContain('王小六打豆腐'); expect(hero.text()).toContain('黄梅戏精选'); expect(hero.text()).toContain('5:52')
  expect(wrapper.findAll('img')).toHaveLength(1)
  expect(hero.get('img').attributes('src')).toBe('/api/v1/metadata/songs/song-one/cover')
  expect(wrapper.get('.lyrics-content').text()).toContain('第一句歌词\n第二句歌词')
  expect(wrapper.get('.lyrics-content').text()).not.toContain('[00:'); expect(wrapper.get('.lyrics-content').text()).not.toContain('[ti:')
  expect(wrapper.get('.file-details').attributes('open')).toBeUndefined(); expect(wrapper.text()).not.toContain('SONG DETAILS')
  for (const label of ['播放', '编辑信息', '编辑标签']) await wrapper.findAllComponents(ElButton).find(b => b.text() === label)!.trigger('click')
  expect(wrapper.emitted('play')).toHaveLength(1); expect(wrapper.emitted('edit')).toHaveLength(1); expect(wrapper.emitted('classify')).toHaveLength(1)
  expect(embeddedMetadata.detail).toHaveBeenCalledTimes(1)
})
it('封面加载失败有占位图，缺失专辑和歌词不堆叠空字段', async () => {
  vi.mocked(embeddedMetadata.detail).mockResolvedValue({ state: 'PENDING' }); const row = song(); row.metadata!.track.album = '未知专辑'
  wrapper = mount(SongDetailDrawer, { props: { song: row, visible: true }, global: { stubs: { teleport: true, transition: false } } }); await flushPromises()
  await wrapper.get('.song-artwork img').trigger('error')
  expect(wrapper.find('.artwork-placeholder').exists()).toBe(true); expect(wrapper.find('.song-artwork img').exists()).toBe(false)
  expect(wrapper.find('.song-album').exists()).toBe(false); expect(wrapper.get('.lyrics-empty').text()).toBe('暂无可显示的歌词')
})
it('切歌后旧歌词不覆盖新歌，封面失败状态重置', async () => {
  let finish!: (v: Awaited<ReturnType<typeof embeddedMetadata.detail>>) => void
  vi.mocked(embeddedMetadata.detail).mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  wrapper = mount(SongDetailDrawer, { props: { song: song('old'), visible: true }, global: { stubs: { teleport: true, transition: false } } }); await flushPromises()
  await wrapper.get('.song-artwork img').trigger('error'); await wrapper.setProps({ song: song('new') }); await flushPromises()
  finish({ state: 'COMPLETE', result: { state: 'COMPLETE', lyrics: { text: '过期歌词', timing: 'none' } } }); await flushPromises()
  expect(wrapper.get('.lyrics-content').text()).toContain('第一句歌词'); expect(wrapper.text()).not.toContain('过期歌词')
  expect(wrapper.get('.song-artwork img').attributes('src')).toContain('/new/cover')
})
it('无已确认信息时可显示缓存封面、媒体时长和结构化歌词', async () => {
  const row = song(); row.metadata = null
  vi.mocked(embeddedMetadata.detail).mockResolvedValue({ state: 'COMPLETE', result: { state: 'COMPLETE', cover: 'cached', technical: { duration: 355 }, lyrics: { text: '原始同步内容', timing: 'milliseconds', lines: [{ time: 0, text: '结构歌词第一句' }, { time: 1000, text: '结构歌词第二句' }] } } })
  wrapper = mount(SongDetailDrawer, { props: { song: row, visible: true }, global: { stubs: { teleport: true, transition: false } } }); await flushPromises()
  expect(wrapper.get('.song-artwork img').attributes('src')).toBe('/api/v1/media-songs/song-one/embedded-metadata/cover')
  expect(wrapper.get('.song-quick-facts').text()).toContain('5:55')
  expect(wrapper.get('.lyrics-content').text()).toContain('结构歌词第一句\n结构歌词第二句')
  expect(wrapper.get('.lyrics-content').text()).not.toContain('原始同步内容')
})
it('原唱与伴奏显示真实音轨状态并直达处理入口', async () => {
  vi.mocked(embeddedMetadata.detail).mockResolvedValue({ state: 'PENDING' })
  wrapper = mount(SongDetailDrawer, { props: { song: song(), visible: true }, global: { stubs: { teleport: true, transition: false, RouterLink: { props: ['to'], template: '<a :data-to="JSON.stringify(to)"><slot /></a>' } } } }); await flushPromises()
  const section = wrapper.get('.audio-summary')
  expect(authRequest).toHaveBeenCalledWith('/api/v1/media-songs/song-one/audio-roles')
  expect(section.text()).toContain('待标记'); expect(section.text()).toContain('需要确认哪条是原唱'); expect(section.text()).toContain('去处理')
  expect(section.get('a').attributes('data-to')).toContain('"song":"song-one"')
  vi.mocked(authRequest).mockRejectedValueOnce(new Error('offline'))
  await wrapper.setProps({ song: song('two') }); await flushPromises()
  expect(wrapper.get('.audio-summary').text()).toContain('暂时无法读取音轨信息')
})

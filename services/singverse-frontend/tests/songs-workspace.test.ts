// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import SongsWorkspaceView from '../src/admin/modules/song-management/views/SongsWorkspaceView.vue'
import { resolveSongsLens, songManagementRoutes } from '../src/admin/modules/song-management/routes'

describe('歌曲工作区透镜选择', () => {
  let wrapper: VueWrapper | undefined, router: Router
  beforeEach(() => { router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/library/songs', component: SongsWorkspaceView }] }) })
  afterEach(() => { wrapper?.unmount(); wrapper = undefined })
  async function at(url: string) {
    await router.push(url); await router.isReady()
    wrapper?.unmount()
    wrapper = mount(SongsWorkspaceView, { global: { plugins: [router], stubs: { SongsView: true, FormalSongsView: true } } })
    await flushPromises()
  }
  const songs = () => wrapper!.findComponent({ name: 'SongsView' }).exists()
  const formal = () => wrapper!.findComponent({ name: 'FormalSongsView' }).exists()

  it('无 query 默认歌曲透镜', async () => { await at('/library/songs'); expect(songs()).toBe(true); expect(formal()).toBe(false) })
  it('lens=songs 与文件专属参数展示歌曲透镜', async () => { await at('/library/songs?lens=songs&status=REVIEW'); expect(songs()).toBe(true); expect(formal()).toBe(false) })
  it('lens=formal 展示歌曲资料透镜', async () => { await at('/library/songs?lens=formal&search=周杰伦'); expect(formal()).toBe(true); expect(songs()).toBe(false) })
  it('detailSongId 深链落在歌曲透镜', async () => { await at('/library/songs?detailSongId=song-1'); expect(songs()).toBe(true) })
})

describe('歌曲透镜深链判定', () => {
  const to = (query: Record<string, string>, hash = '') => ({ query, hash }) as never
  it('空查询默认歌曲透镜', () => { expect(resolveSongsLens(to({}))).toBe(true) })
  it('显式透镜原样放行', () => { expect(resolveSongsLens(to({ lens: 'songs' }))).toBe(true); expect(resolveSongsLens(to({ lens: 'formal' }))).toBe(true) })
  it('旧正式深链补 lens=formal 并保留参数与 hash', () => { expect(resolveSongsLens(to({ search: '周杰伦', page: '2' }, '#x'))).toEqual({ path: '/library/songs', query: { search: '周杰伦', page: '2', lens: 'formal' }, hash: '#x' }) })
  it('仅 sourceId 也视为正式深链', () => { expect(resolveSongsLens(to({ sourceId: 's1' }))).toEqual({ path: '/library/songs', query: { sourceId: 's1', lens: 'formal' }, hash: '' }) })
  it('文件专属参数走歌曲透镜', () => { for (const key of ['q', 'status', 'tagIds', 'unclassified', 'size', 'detailSongId', 'reviewSongId']) expect(resolveSongsLens(to({ [key]: 'x' }))).toBe(true) })
  it('正式参数与文件参数共存时优先歌曲透镜', () => { expect(resolveSongsLens(to({ search: 'a', detailSongId: 'b' }))).toBe(true) })
})

describe('旧文件路径重定向与导航高亮', () => {
  const files = songManagementRoutes.find(route => route.path === '/library/songs/files')!
  const songs = songManagementRoutes.find(route => route.path === '/library/songs')!
  it('保留全部查询与 hash 并补 lens=songs', () => {
    const redirect = files.redirect as (to: { query: Record<string, string>; hash: string }) => unknown
    expect(redirect({ query: { status: 'REVIEW', page: '2' }, hash: '#y' })).toEqual({ path: '/library/songs', query: { status: 'REVIEW', page: '2', lens: 'songs' }, hash: '#y' })
  })
  it('歌曲路由高亮曲库歌曲入口并挂载透镜判定', () => { expect(songs.meta?.activeMenu).toBe('/library/songs'); expect(songs.beforeEnter).toBe(resolveSongsLens) })
})
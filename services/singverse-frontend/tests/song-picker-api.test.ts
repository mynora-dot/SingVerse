// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, expect, it, vi } from 'vitest'
import ClassificationSongPicker from '../src/admin/modules/classification/components/ClassificationSongPicker.vue'
import { preview } from './fixtures/filename-workspace'

afterEach(() => { vi.unstubAllGlobals(); document.body.innerHTML = '' })

it('歌单选歌通过真实请求适配层传递有效状态和服务端身份搜索，保留原勾选', async () => {
  const fetch = vi.fn(async (path: string) => {
    const query = new URL(path, 'http://localhost').searchParams
    if (query.get('status') !== 'ALL') return Response.json({ detail: '请选择有效的解析状态' }, { status: 400 })
    return Response.json({ items: [{ id: 'song-1', sourceName: '家庭曲库', relativePath: 'file.mp4', result: { ...preview().result, title: '家庭合唱', artists: ['周末歌手'] } }], page: 0, size: 20, totalElements: 1 })
  })
  vi.stubGlobal('fetch', fetch)
  const wrapper = mount(ClassificationSongPicker, { props: { purpose: 'playlist', selectedIds: ['song-1'] }, attachTo: document.body })
  try {
    await flushPromises()
    expect(wrapper.text()).toContain('家庭合唱')
    expect(wrapper.text()).not.toContain('请选择有效的解析状态')
    await wrapper.get('input[aria-label="搜索歌名或歌手"]').setValue('周末歌手')
    await wrapper.get('form').trigger('submit'); await flushPromises()
    const [path, options] = fetch.mock.calls.at(-1) as unknown as [string, RequestInit]
    const url = new URL(path, 'http://localhost')
    expect(url.pathname).toBe('/api/v1/media-songs')
    expect(url.searchParams.get('search')).toBe('周末歌手')
    expect(url.searchParams.has('q')).toBe(false)
    expect(options).toMatchObject({ credentials: 'same-origin', method: 'GET', cache: 'no-store' })
    expect((wrapper.get('input[aria-label="选择歌曲：家庭合唱"]').element as HTMLInputElement).checked).toBe(true)
    await wrapper.findAll('button').find(button => button.text() === '确认选择（1 首）')!.trigger('click')
    expect(wrapper.emitted('confirm')).toEqual([[['song-1']]])
  } finally { wrapper.unmount() }
})

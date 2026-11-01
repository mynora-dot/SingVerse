// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import AudioRolesPanel from '../src/admin/modules/song-management/components/AudioRolesPanel.vue'
import { authRequest } from '../src/admin/core/auth/api'
vi.mock('../src/admin/core/auth/api', () => ({ authRequest: vi.fn() }))
const result = { state: 'UNKNOWN', revision: 0, streams: [{ id: 'stream:1' }, { id: 'stream:2' }], jobs: [] }
let wrapper: ReturnType<typeof mount> | undefined
beforeEach(() => { vi.useFakeTimers(); vi.mocked(authRequest).mockReset(); vi.mocked(authRequest).mockResolvedValue(result) })
afterEach(() => { wrapper?.unmount(); wrapper = undefined; vi.useRealTimers() })
it('轮询保留未保存的人工选择，纠正提交源 ID 和修订号', async () => {
  wrapper = mount(AudioRolesPanel, { props: { songId: 'song' } }); await flushPromises()
  await wrapper.findAll('select')[0]!.setValue('stream:2')
  await wrapper.findAll('select')[1]!.setValue('stream:1')
  await vi.advanceTimersByTimeAsync(4000); await flushPromises()
  expect((wrapper.findAll('select')[0]!.element as HTMLSelectElement).value).toBe('stream:2')
  await wrapper.findAll('button').find(button => button.text() === '保存到全曲库')!.trigger('click'); await flushPromises()
  expect(authRequest).toHaveBeenCalledWith('/api/v1/media-songs/song/audio-roles', 'PUT', { originalId: 'stream:2', backingId: 'stream:1', revision: 0 })
  expect(wrapper.findAll('button').find(button => button.text() === '生成独立伴奏')!.attributes('disabled')).toBeDefined()
})
it('关闭详情时，尚未完成的请求不能重新启动轮询', async () => {
  let finish: (value: typeof result) => void = () => undefined
  vi.mocked(authRequest).mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  wrapper = mount(AudioRolesPanel, { props: { songId: 'song' } })
  wrapper.unmount(); wrapper = undefined
  finish(result); await flushPromises(); await vi.advanceTimersByTimeAsync(10000)
  expect(authRequest).toHaveBeenCalledTimes(1)
})
it('切换歌曲忽略旧探测结果，并且只保留一个轮询', async () => {
  let finish: (value: typeof result) => void = () => undefined
  vi.mocked(authRequest).mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  wrapper = mount(AudioRolesPanel, { props: { songId: 'old' } })
  await wrapper.setProps({ songId: 'new' }); await flushPromises()
  finish({ ...result, streams: [{ id: 'stream:9' }] }); await flushPromises()
  expect(wrapper.text()).not.toContain('stream:9')
  const before = vi.mocked(authRequest).mock.calls.length
  await vi.advanceTimersByTimeAsync(4000); await flushPromises()
  expect(authRequest).toHaveBeenCalledTimes(before + 1)
  expect(authRequest).toHaveBeenLastCalledWith('/api/v1/media-songs/new/audio-roles')
})

// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import AudioRolesPanel from '../src/admin/modules/song-management/components/AudioRolesPanel.vue'
import { mockMediaElements } from './media-element-mocks'
import { authRequest } from '../src/admin/core/auth/api'
vi.mock('../src/admin/core/auth/api', () => ({ authRequest: vi.fn() }))
const result = { state: 'UNKNOWN', revision: 0, streams: [{ id: 'stream:1' }, { id: 'stream:2' }], jobs: [] }
let wrapper: ReturnType<typeof mount> | undefined
beforeEach(() => { mockMediaElements(); vi.useFakeTimers(); vi.mocked(authRequest).mockReset(); vi.mocked(authRequest).mockResolvedValue(result) })
afterEach(() => { wrapper?.unmount(); wrapper = undefined; vi.restoreAllMocks(); vi.useRealTimers() })
it('旧版不确定结果可以重新分析，新版显示原因及折叠证据', async () => {
  vi.mocked(authRequest).mockResolvedValue({ ...result, jobs: [{ id: 'a', kind: 'ANALYZE', state: 'SUCCEEDED' }] })
  wrapper = mount(AudioRolesPanel, { props: { songId: 'song' } }); await flushPromises()
  await wrapper.findAll('button').find(button => button.text() === '深度识别')!.trigger('click'); await flushPromises()
  expect(authRequest).toHaveBeenCalledWith('/api/v1/media-songs/song/audio-jobs', 'POST', { kind: 'ANALYZE' })
  wrapper.unmount()
  vi.mocked(authRequest).mockResolvedValue({ ...result, jobs: [{ id: 'b', kind: 'ANALYZE', state: 'SUCCEEDED', resultState: 'UNKNOWN', reason: 'CONFLICTING_WINDOWS', evidence: { analysisVersion: 'vocal-activity-v2', vocalRatio: [[.6, .01], [.01, .6]] } }] })
  wrapper = mount(AudioRolesPanel, { props: { songId: 'song' } }); await flushPromises()
  expect(wrapper.text()).toContain('不同片段对原唱的判断相反')
  expect(wrapper.get('details[aria-label="识别依据"]').attributes('open')).toBeUndefined()
  expect(wrapper.text()).toContain('音轨 1：0.600')
})
it('轮询保留未保存的人工选择，纠正提交源 ID 和修订号', async () => {
  wrapper = mount(AudioRolesPanel, { props: { songId: 'song' } }); await flushPromises()
  expect(wrapper.text()).toContain('哪一条是原唱')
  await wrapper.find('input[type="radio"][value="stream:2"]').setValue(true)
  expect(wrapper.findAll('.track-option')[1]!.text()).toContain('原唱'); expect(wrapper.findAll('.track-option')[0]!.text()).toContain('伴奏')
  await vi.advanceTimersByTimeAsync(4000); await flushPromises()
  expect((wrapper.find('input[value="stream:2"]').element as HTMLInputElement).checked).toBe(true)
  await wrapper.findAll('button').find(button => button.text() === '保存标记')!.trigger('click'); await flushPromises()
  expect(authRequest).toHaveBeenCalledWith('/api/v1/media-songs/song/audio-roles', 'PUT', { originalId: 'stream:2', backingId: 'stream:1', revision: 0 })
  expect(wrapper.emitted('changed')).toHaveLength(1)
  expect(wrapper.findAll('button').some(button => button.text() === '生成独立伴奏')).toBe(false)
})
it('单音轨已生成伴奏时提供试听，未生成时可提交分离', async () => {
  vi.mocked(authRequest).mockResolvedValue({ state: 'CONFIRMED', revision: 0, originalId: 'stream:1', streams: [{ id: 'stream:1' }], backingResource: { jobId: 'job 1' }, jobs: [] })
  wrapper = mount(AudioRolesPanel, { props: { songId: 'song' } }); await flushPromises()
  expect(wrapper.text()).toContain('已生成独立伴奏')
  expect(wrapper.get('audio').attributes('src')).toBe('/api/v1/media-songs/song/accompaniment/job%201')
  wrapper.unmount()
  vi.mocked(authRequest).mockResolvedValue({ state: 'CONFIRMED', revision: 0, originalId: 'stream:1', streams: [{ id: 'stream:1' }], jobs: [] })
  wrapper = mount(AudioRolesPanel, { props: { songId: 'song', canProcess: false } }); await flushPromises()
  expect(wrapper.findAll('button').find(button => button.text() === '生成独立伴奏')!.attributes('disabled')).toBeDefined()
  await wrapper.findAll('button').find(button => button.text() === '去准备环境')!.trigger('click')
  expect(wrapper.emitted('setup')).toHaveLength(1)
})
it('AI 自动标记需要人工确认时可一键确认，人工标记无改动时不可重复保存', async () => {
  vi.mocked(authRequest).mockResolvedValue({ ...result, state: 'CONFIRMED', origin: 'AUTO', revision: 2, originalId: 'stream:1', backingId: 'stream:2', jobs: [{ id: 'a', kind: 'ANALYZE', state: 'SUCCEEDED' }] })
  wrapper = mount(AudioRolesPanel, { props: { songId: 'song' } }); await flushPromises()
  expect(wrapper.text()).toContain('自动识别')
  expect(wrapper.findAll('button').some(button => button.text() === '让 AI 识别')).toBe(false)
  const confirm = wrapper.findAll('button').find(button => button.text() === '确认自动标记')!
  expect(confirm.attributes('disabled')).toBeUndefined()
  wrapper.unmount()
  vi.mocked(authRequest).mockResolvedValue({ ...result, state: 'CONFIRMED', origin: 'MANUAL', revision: 3, originalId: 'stream:1', backingId: 'stream:2' })
  wrapper = mount(AudioRolesPanel, { props: { songId: 'song' } }); await flushPromises()
  expect(wrapper.findAll('button').find(button => button.text() === '保存标记')!.attributes('disabled')).toBeDefined()
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

it('未准备深度模型也能快速识别，不隐式启动深度分离', async () => {
  wrapper = mount(AudioRolesPanel, { props: { songId: 'song', canProcess: false } }); await flushPromises()
  await wrapper.findAll('button').find(button => button.text() === '快速识别')!.trigger('click'); await flushPromises()
  expect(authRequest).toHaveBeenCalledWith('/api/v1/media-songs/song/audio-jobs', 'POST', { kind: 'FAST' })
  expect(wrapper.findAll('button').find(button => button.text() === '深度识别')!.attributes('disabled')).toBeDefined()
})

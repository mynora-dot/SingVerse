// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, expect, it, vi } from 'vitest'
import AudioRolesPanel from '../src/admin/modules/song-management/components/AudioRolesPanel.vue'
import { authRequest } from '../src/admin/core/auth/api'
import type { SeparationCapabilities } from '../src/admin/modules/audio-processing/api'
vi.mock('../src/admin/core/auth/api', () => ({ authRequest: vi.fn() }))
const capabilities: SeparationCapabilities = { version: 1, profiles: [{ id: 'DSP_FAST', ready: true, modelId: 'center-cancel-v1' }, { id: 'AI_CLEAN', ready: true, modelId: 'uvr-mdxnet-9482' }], backends: [{ id: 'cpu', state: 'AVAILABLE', reason: '' }] }
let wrapper: ReturnType<typeof mount> | undefined
afterEach(() => { wrapper?.unmount(); wrapper = undefined; vi.clearAllMocks() })
const roles = { state: 'UNKNOWN', jobs: [], streams: [{ id: 'stream:0', channels: 2 }], versions: [
  { jobId: 'preview', preview: true, separationProfile: 'DSP_FAST', outputFormat: 'aac' },
  { jobId: 'flac', preview: false, separationProfile: 'AI_CLEAN', outputFormat: 'flac' },
  { jobId: 'full', preview: false, separationProfile: 'DSP_FAST', outputFormat: 'aac' },
] }
async function open() {
  vi.mocked(authRequest).mockImplementation(async () => roles as never)
  wrapper = mount(AudioRolesPanel, { props: { songId: 'song', canProcess: false, separation: capabilities } })
  await flushPromises()
}
function button(label: string) { return wrapper!.findAll('button').find(item => item.text() === label)! }
it('无 Demucs 环境时仍可提交 DSP 试听，并保留试听与 FLAC 的独立版本', async () => {
  await open()
  expect(button('生成 12 秒试听').attributes('disabled')).toBeUndefined()
  await button('生成 12 秒试听').trigger('click'); await flushPromises()
  expect(authRequest).toHaveBeenCalledWith('/api/v1/media-songs/song/audio-jobs', 'POST', expect.objectContaining({ separationProfile: 'DSP_FAST', preview: true, outputFormat: 'aac' }))
  expect(wrapper!.findAll('button').filter(item => item.text() === '设为当前伴奏')).toHaveLength(1)
  expect(wrapper!.findAll('audio')).toHaveLength(3)
})
it('干净档允许显式选择 CPU，FLAC 从所选方案重新生成', async () => {
  await open()
  await wrapper!.get('select[aria-label="伴奏方案"]').setValue('AI_CLEAN')
  await wrapper!.get('select[aria-label="生成伴奏使用的设备"]').setValue('cpu')
  await button('生成 FLAC 导出').trigger('click'); await flushPromises()
  expect(authRequest).toHaveBeenCalledWith('/api/v1/media-songs/song/audio-jobs', 'POST', expect.objectContaining({ separationProfile: 'AI_CLEAN', backend: 'cpu', outputFormat: 'flac', preview: false }))
})
it('手工确认左声道后只提取该声道，不改动全曲库原唱标记', async () => {
  await open()
  await wrapper!.get('select[aria-label="已确认的源伴奏"]').setValue('stream:0:LEFT')
  await button('生成整曲伴奏').trigger('click'); await flushPromises()
  expect(authRequest).toHaveBeenCalledWith('/api/v1/media-songs/song/audio-jobs', 'POST', expect.objectContaining({ sourceAudioId: 'stream:0:LEFT', sourceConfirmed: true }))
  expect(vi.mocked(authRequest).mock.calls.some(([path, method]) => path.endsWith('/audio-roles') && method === 'PUT')).toBe(false)
})

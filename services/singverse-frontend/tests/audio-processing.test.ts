// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import View from '../src/admin/modules/audio-processing/AudioProcessingView.vue'
import { authRequest } from '../src/admin/core/auth/api'
import type { Node } from '../src/admin/modules/audio-processing/api'

vi.mock('../src/admin/core/auth/api', () => ({ authRequest: vi.fn() }))
vi.mock('vue-router', () => ({ useRoute: () => ({ query: {} }) }))
let wrapper: ReturnType<typeof mount> | undefined
let ready = true
let resourceReady = true
const preview = { id: 'batch', state: 'PREVIEW', total: 2, counts: { PENDING: 2 }, categories: { UNPROBED: 1, ELIGIBLE: 1 } }
const node = () => ({ selected: ready ? 'cpu' : null, selection: 'auto', environment: { system: 'Linux', architecture: 'aarch64', processor: 'RK3566', versions: {} }, model: { installed: false, writable: true, filename: 'model.th', sha256: 'digest', maxBytes: 100 * 1024 ** 2 }, operation: { state: 'IDLE', bytes: 0 }, backends: [{ id: 'cpu', state: ready ? 'AVAILABLE' : 'UNVERIFIED' }, { id: 'rknn', state: 'MODEL_UNSUPPORTED', reason: '无通过验收的转换模型' }], instructions: {} } as Node)
beforeEach(() => {
  vi.useFakeTimers(); ready = true; resourceReady = true
  vi.mocked(authRequest).mockReset().mockImplementation(async (path) => {
    if (path.includes('/media-sources')) return { items: [] } as never
    if (path.includes('/node')) return { ...node(), resourceReady } as never
    if (path.includes('/songs?')) { const page = new URL('http://test' + path).searchParams.get('page'); const ids = page === '1' ? ['c', 'd'] : ['a', 'b']; return { totalElements: 60, items: ids.map(id => ({ id, title: id, artists: ['歌手'], sourceName: '媒体库', category: 'ELIGIBLE', audio: { state: 'UNKNOWN', streams: [{}], jobs: [] } })) } as never }
    if (path.endsWith('/batches/preview') || path.endsWith('/retry')) return preview as never
    if (path.endsWith('/submit')) return { ...preview, state: 'QUEUED' } as never
    if (path.endsWith('/batches/batch?page=0')) return { ...preview, state: 'QUEUED', items: [{ song_id: 'a', title: 'a', state: 'PROCESSING', job_state: 'RUNNING', execution: { backend: 'cpu' }, error: '' }] } as never
    if (path.includes('/batches?page')) return { items: [{ ...preview, state: 'QUEUED' }], totalElements: 1 } as never
    return {} as never
  })
})
afterEach(() => { wrapper?.unmount(); wrapper = undefined; vi.useRealTimers() })
async function open() { wrapper = mount(View, { global: { stubs: { RouterLink: { template: '<a><slot /></a>' }, ElDialog: { props: ['modelValue'], template: '<div v-if="modelValue"><slot /></div>' } } } }); await flushPromises() }
function button(text: string) { return wrapper!.findAll('button').find(item => item.text() === text)! }

it('跨页勾选后预览保留所选ID，确认后进入持久批次详情', async () => {
  await open(); await wrapper!.find('input[aria-label="选择a"]').setValue(true)
  await button('下一页').trigger('click'); await flushPromises()
  await wrapper!.find('input[aria-label="选择c"]').setValue(true)
  await button('批量生成伴奏').trigger('click'); await flushPromises()
  expect(authRequest).toHaveBeenCalledWith('/api/v1/audio-processing/batches/preview', 'POST', expect.objectContaining({ ids: ['a', 'c'], all: false }), 120000)
  expect(wrapper!.text()).toContain('待探测 1')
  await button('确认生成').trigger('click'); await flushPromises()
  expect(authRequest).toHaveBeenCalledWith('/api/v1/audio-processing/batches/batch/submit', 'POST')
  expect(wrapper!.text()).toContain('批次详情')
  await vi.advanceTimersByTimeAsync(4000); await flushPromises()
  expect(wrapper!.text()).toContain('CPU')
})
it('全部筛选结果绑定已应用的筛选，编辑未提交条件不会改变快照范围', async () => {
  await open(); await button('选中全部筛选结果（60）').trigger('click')
  await wrapper!.find('input[placeholder="搜索歌曲名称"]').setValue('尚未应用')
  await button('批量生成伴奏').trigger('click'); await flushPromises()
  expect(authRequest).toHaveBeenCalledWith('/api/v1/audio-processing/batches/preview', 'POST', expect.objectContaining({ all: true, filter: expect.objectContaining({ title: '', streams: null }) }), 120000)
})
it('应用新筛选会清除旧选择', async () => {
  await open(); await wrapper!.find('input[aria-label="选择a"]').setValue(true)
  await wrapper!.find('input[placeholder="搜索歌手"]').setValue('新歌手')
  await wrapper!.find('form').trigger('submit'); await flushPromises()
  expect(wrapper!.text()).toContain('已选 0 首')
  expect(button('批量生成伴奏').attributes('disabled')).toBeDefined()
  expect(vi.mocked(authRequest).mock.calls.some(([path]) => path.includes('artist=%E6%96%B0%E6%AD%8C%E6%89%8B'))).toBe(true)
})
it('没有合格后端时禁止生成，并提供模型下载和未适配硬件提示', async () => {
  ready = false; await open()
  expect(button('批量生成伴奏').attributes('disabled')).toBeDefined()
  await button('节点与模型').trigger('click'); await flushPromises()
  expect(wrapper!.text()).toContain('下载模型到节点')
  expect(wrapper!.text()).toContain('当前模型未适配')
  expect(wrapper!.text()).toContain('RK3566')
  await button('下载模型到节点').trigger('click'); await flushPromises()
  expect(authRequest).toHaveBeenCalledWith('/api/v1/audio-processing/model/download', 'POST', undefined, 60000)
})
it('离开页面后不再轮询', async () => {
  await open(); wrapper!.unmount(); wrapper = undefined
  const count = vi.mocked(authRequest).mock.calls.length
  await vi.advanceTimersByTimeAsync(12000); await flushPromises()
  expect(authRequest).toHaveBeenCalledTimes(count)
})
it('模型试跑通过但当前资源不足时阻止生成并显示预算入口', async () => {
  resourceReady = false; await open()
  expect(button('批量生成伴奏').attributes('disabled')).toBeDefined()
  expect(wrapper!.text()).toContain('内存或磁盘不足')
})

it('展示镜像部署信息，OpenVINO模型下载使用独立清单', async () => {
  const previous = vi.mocked(authRequest).getMockImplementation()!
  vi.mocked(authRequest).mockImplementation(async (...args) => {
    if (args[0].includes('/node')) return { ...node(),
      build: { version: '0.1.0', revision: 'abc123', variant: 'cpu', runtimeLock: 'runtime-digest' },
      deployment: { note: '镜像架构由Docker选择', profiles: [{ backend: 'openvino', image: 'singverse-compute:0.1.0-openvino', command: 'docker compose example', included: false }] },
      models: [{ ...node().model, id: 'htdemucs', version: 'baseline' }, { id: 'htdemucs-openvino', version: 'fixed-revision', installed: false, writable: true, maxBytes: 128*1024**2, files: [{ name: 'htdemucs_fwd.xml', url: 'https://example.test/model.xml', sha256: 'expected', size: 1 }] }],
      backends: [{ id: 'openvino', state: 'IMAGE_MISSING', reason: '当前镜像未包含此推理后端' }]
    } as never
    return previous(...args)
  })
  await open(); await button('节点与模型').trigger('click'); await flushPromises()
  expect(wrapper!.text()).toContain('0.1.0')
  expect(wrapper!.text()).toContain('singverse-compute:0.1.0-openvino')
  expect(wrapper!.text()).toContain('当前镜像未包含')
  const select = wrapper!.findAll('select').find(item => item.find('option[value="htdemucs-openvino"]').exists())!
  await select.setValue('htdemucs-openvino')
  expect(wrapper!.find('input[type="file"]').attributes('accept')).toBe('.zip')
  expect(wrapper!.text()).toContain('htdemucs_fwd.xml')
  await button('下载模型到节点').trigger('click'); await flushPromises()
  expect(authRequest).toHaveBeenCalledWith('/api/v1/audio-processing/model/download?model=htdemucs-openvino', 'POST', undefined, 60000)
})

// @vitest-environment jsdom
import { type DOMWrapper, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import AudioModelSettings from '../src/admin/modules/compute-settings/AudioModelSettings.vue'
import View from '../src/admin/modules/audio-processing/AudioProcessingView.vue'
import { authRequest } from '../src/admin/core/auth/api'
import { confirmDanger } from '../src/admin/shared/ui/confirm'
import { readiness } from '../src/admin/modules/audio-processing/presentation'
import type { Node } from '../src/admin/modules/audio-processing/api'

const router = vi.hoisted(() => ({ route: undefined as unknown as { path: string; query: Record<string, string> }, replace: vi.fn(), push: vi.fn() }))
vi.mock('vue-router', async () => {
  const { reactive } = await import('vue')
  router.route = reactive({ path: '/library/audio-processing', query: {} })
  router.replace.mockImplementation(async (to: { query: Record<string, string> }) => { router.route.query = typeof to === 'string' ? {} : to.query })
  return { useRoute: () => router.route, useRouter: () => ({ replace: router.replace, push: router.push }) }
})
vi.mock('../src/admin/core/auth/api', () => ({ authRequest: vi.fn() }))
vi.mock('../src/admin/shared/ui/confirm', () => ({ confirmDanger: vi.fn(async () => true) }))
let wrapper: ReturnType<typeof mount> | undefined
let ready = true
let resourceReady = true
const preview = { id: 'batch', state: 'PREVIEW', backend: '', total: 2, counts: { PENDING: 2 }, categories: { UNPROBED: 1, ELIGIBLE: 1 } }
const roles = { state: 'UNKNOWN', revision: 0, streams: [{ id: 'stream:1', codec: 'aac', channels: 2 }, { id: 'stream:2', codec: 'aac', channels: 2 }], jobs: [] }
const node = () => ({ selected: ready ? 'cpu' : null, selection: 'auto', environment: { system: 'Linux', architecture: 'aarch64', processor: 'RK3566', versions: {} }, model: { installed: false, writable: true, filename: 'model.th', sha256: 'digest', maxBytes: 100 * 1024 ** 2 }, operation: { state: 'IDLE', bytes: 0 }, backends: [{ id: 'cpu', state: ready ? 'AVAILABLE' : 'UNVERIFIED' }, { id: 'rknn', state: 'MODEL_UNSUPPORTED', reason: '无通过验收的转换模型' }], instructions: {} } as unknown as Node)
const row = (id: string, extra: Record<string, unknown> = {}) => ({ id, title: id, artists: ['歌手'], sourceName: '媒体库', relativePath: id + '.mkv', category: 'ELIGIBLE', audio: { state: 'UNKNOWN', streams: [{ id: 'stream:1' }], jobs: [] }, ...extra })
beforeEach(() => {
  vi.useFakeTimers(); ready = true; resourceReady = true; router.route.query = {}; router.replace.mockClear(); router.push.mockClear(); vi.mocked(confirmDanger).mockClear().mockResolvedValue(true)
  vi.mocked(authRequest).mockReset().mockImplementation(async (path) => {
    if (path.includes('/media-sources')) return { items: [] } as never
    if (path.includes('/node')) return { ...node(), resourceReady } as never
    if (path.includes('/songs?')) { const page = new URL('http://test' + path).searchParams.get('page'); const ids = page === '1' ? ['c', 'd'] : ['a', 'b']; return { totalElements: 60, items: ids.map(id => row(id)) } as never }
    if (path.endsWith('/batches/preview') || path.endsWith('/retry')) return preview as never
    if (path.endsWith('/submit')) return { ...preview, state: 'QUEUED' } as never
    if (path.endsWith('/batches/batch?page=0')) return { ...preview, state: 'QUEUED', items: [{ song_id: 'a', title: 'a', state: 'PROCESSING', job_state: 'RUNNING', execution: { backend: 'cpu' }, error: '' }] } as never
    if (path.includes('/batches?page')) return { items: [{ ...preview, state: 'QUEUED' }], totalElements: 1 } as never
    if (path.endsWith('/audio-roles')) return roles as never
    return {} as never
  })
})
afterEach(() => { wrapper?.unmount(); wrapper = undefined; vi.useRealTimers() })
const stubs = {
  RouterLink: { template: '<a><slot /></a>' },
  ElDialog: { props: ['modelValue'], template: '<div v-if="modelValue" role="dialog"><slot /><slot name="footer" /></div>' },
  ElDrawer: { props: ['modelValue', 'title'], template: '<aside v-if="modelValue" class="drawer"><h2>{{ title }}</h2><slot /></aside>' },
}
async function open() { wrapper = mount(View, { global: { stubs } }); await flushPromises() }
function button(text: string, scope: Pick<DOMWrapper<Element>, 'findAll'> = wrapper!) { return scope.findAll('button').find(item => item.text() === text)! }
function requested(fragment: string) { return vi.mocked(authRequest).mock.calls.some(([path]) => path.includes(fragment)) }

it('跨页勾选后确认生成，提交后进入生成任务并显示执行设备', async () => {
  await open(); await wrapper!.find('input[aria-label="选择a"]').setValue(true)
  await button('下一页').trigger('click'); await flushPromises()
  await wrapper!.find('input[aria-label="选择c"]').setValue(true)
  expect(wrapper!.text()).toContain('已选 2 首')
  await button('为所选歌曲生成伴奏').trigger('click'); await flushPromises()
  expect(authRequest).toHaveBeenCalledWith('/api/v1/audio-processing/batches/preview', 'POST', expect.objectContaining({ ids: ['a', 'c'], all: false }), 120000)
  const dialog = wrapper!.get('[role="dialog"]')
  expect(dialog.text()).toContain('将为 2 首歌曲生成独立伴奏'); expect(dialog.text()).toContain('先读取音轨 1 首')
  await button('开始生成', dialog).trigger('click'); await flushPromises()
  expect(authRequest).toHaveBeenCalledWith('/api/v1/audio-processing/batches/batch/submit', 'POST')
  expect(router.route.query.tab).toBe('tasks'); await flushPromises()
  expect(wrapper!.get('[role="tab"][aria-selected="true"]').text()).toContain('生成任务')
  expect(wrapper!.get('.drawer').text()).toContain('任务详情')
  await vi.advanceTimersByTimeAsync(4000); await flushPromises()
  const item = wrapper!.get('.item-row'); expect(item.text()).toContain('CPU'); expect(item.text()).toContain('生成中')
})
it('全部筛选结果绑定已应用的筛选，编辑未提交条件不会改变快照范围', async () => {
  await open(); await button('选择全部 60 首').trigger('click')
  expect(wrapper!.text()).toContain('已选 60 首')
  await wrapper!.find('input[placeholder="搜索歌曲名称"]').setValue('尚未应用')
  await button('为所选歌曲生成伴奏').trigger('click'); await flushPromises()
  expect(authRequest).toHaveBeenCalledWith('/api/v1/audio-processing/batches/preview', 'POST', expect.objectContaining({ all: true, filter: expect.objectContaining({ title: '', streams: null }) }), 120000)
})
it('应用新筛选会清除旧选择', async () => {
  await open(); await wrapper!.find('input[aria-label="选择a"]').setValue(true)
  expect(wrapper!.text()).toContain('已选 1 首')
  await wrapper!.find('input[placeholder="搜索歌手"]').setValue('新歌手')
  await wrapper!.find('form').trigger('submit'); await flushPromises()
  expect(wrapper!.text()).not.toContain('已选')
  expect(button('为所选歌曲生成伴奏').attributes('disabled')).toBeDefined()
  expect(requested('artist=%E6%96%B0%E6%AD%8C%E6%89%8B')).toBe(true)
})
it('状态快捷筛选立即生效，并按状态给出对应的单曲操作', async () => {
  const previous = vi.mocked(authRequest).getMockImplementation()!
  vi.mocked(authRequest).mockImplementation(async (...args) => {
    if (args[0].includes('/songs?')) return { totalElements: 3, items: [
      row('双轨', { category: 'INAPPLICABLE', audio: { state: 'UNKNOWN', streams: [{ id: 'stream:1' }, { id: 'stream:2' }], jobs: [] } }),
      row('已完成', { category: 'READY', audio: { state: 'CONFIRMED', streams: [{ id: 'stream:1' }], backingResource: { jobId: 'job' }, jobs: [] } }),
      row('失败', { audio: { state: 'CONFIRMED', streams: [{ id: 'stream:1' }], jobs: [{ id: 'j', kind: 'SEPARATE', state: 'FAILED', error: '素材或模型不支持此任务' }] } }),
    ] } as never
    return previous(...args)
  })
  await open()
  const chip = wrapper!.findAll('.chip input').find(input => input.element.parentElement!.textContent === '待标记原唱')!
  await chip.setValue(true); await flushPromises()
  expect(requested('state=UNCONFIRMED')).toBe(true)
  expect(wrapper!.text()).toContain('待标记'); expect(wrapper!.text()).toContain('已有伴奏'); expect(wrapper!.text()).toContain('素材或模型不支持此任务')
  expect(button('试听伴奏')).toBeTruthy(); expect(button('生成伴奏')).toBeTruthy()
  await button('标记原唱').trigger('click'); await flushPromises()
  const drawer = wrapper!.get('.drawer')
  expect(drawer.text()).toContain('哪一条是原唱'); expect(drawer.text()).toContain('音轨 1 · AAC 立体声')
  expect(authRequest).toHaveBeenCalledWith('/api/v1/media-songs/%E5%8F%8C%E8%BD%A8/audio-roles')
})
it('从歌曲详情带入的歌曲会自动打开音轨面板，并可返回全部歌曲', async () => {
  router.route.query = { song: 'a' }; await open()
  expect(requested('songId=a')).toBe(true)
  expect(wrapper!.text()).toContain('正在查看单首歌曲：a')
  expect(wrapper!.get('.drawer').text()).toContain('哪一条是原唱')
  await button('查看全部歌曲').trigger('click'); await flushPromises()
  expect(router.route.query.song).toBeUndefined()
})
it('页签与地址同步，支持方向键切换', async () => {
  router.route.query = { tab: 'tasks' }; await open()
  const tabs = wrapper!.findAll('[role="tab"]')
  expect(tabs[1]!.attributes('aria-selected')).toBe('true'); expect(tabs[0]!.attributes('tabindex')).toBe('-1')
  expect(wrapper!.text()).toContain('2 首歌曲')
  await tabs[1]!.trigger('keydown', { key: 'ArrowRight' }); await flushPromises()
  expect(router.replace).toHaveBeenLastCalledWith({ path: '/library/audio-processing', query: {} })
  expect(wrapper!.findAll('[role="tab"]')).toHaveLength(2)
})
it('停止进行中的任务需要确认，取消确认则不发送请求', async () => {
  router.route.query = { tab: 'tasks' }; await open()
  vi.mocked(confirmDanger).mockResolvedValueOnce(false)
  await button('停止').trigger('click'); await flushPromises()
  expect(confirmDanger).toHaveBeenCalledTimes(1); expect(requested('/batches/batch/cancel')).toBe(false)
  await button('停止').trigger('click'); await flushPromises()
  expect(authRequest).toHaveBeenCalledWith('/api/v1/audio-processing/batches/batch/cancel', 'POST')
})
it('没有合格后端时禁止生成，并引导到节点与模型下载模型', async () => {
  ready = false; await open()
  expect(button('为所选歌曲生成伴奏').attributes('disabled')).toBeDefined()
  expect(wrapper!.text()).toContain('还没有安装分离模型')
  await button('去准备环境').trigger('click'); await flushPromises()
  expect(router.push).toHaveBeenCalledWith('/system/compute#audio-models')
  wrapper!.unmount(); wrapper = mount(AudioModelSettings, { global: { stubs } }); await flushPromises()
  expect(wrapper!.text()).toContain('下载到节点')
  expect(wrapper!.text()).toContain('RK3566')
  await button('下载到节点').trigger('click'); await flushPromises()
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
  expect(button('为所选歌曲生成伴奏').attributes('disabled')).toBeDefined()
  expect(wrapper!.text()).toContain('内存或磁盘不足')
})
it('隐藏部署信息，OpenVINO模型下载使用独立清单', async () => {
  const previous = vi.mocked(authRequest).getMockImplementation()!
  vi.mocked(authRequest).mockImplementation(async (...args) => {
    if (args[0].includes('/node')) return { ...node(),
      build: { version: '0.1.0', revision: 'abc123', variant: 'cpu', runtimeLock: 'runtime-digest' },
      deployment: { note: '镜像架构由Docker选择', profiles: [{ backend: 'openvino', image: 'singverse-compute:0.1.0-openvino', command: 'docker compose example', included: false }] },
      models: [{ ...node().model, id: 'htdemucs', version: 'baseline' }, { id: 'htdemucs-openvino', version: 'fixed-revision', installed: false, writable: true, maxBytes: 128*1024**2, files: [{ name: 'htdemucs_fwd.xml', url: 'https://example.test/model.xml', sha256: 'expected', size: 1 }] }],
      backends: [{ id: 'openvino', detected: true, state: 'IMAGE_MISSING', reason: '当前镜像未包含此推理后端' }]
    } as never
    return previous(...args)
  })
  wrapper = mount(AudioModelSettings, { global: { stubs } }); await flushPromises()
  expect(wrapper!.text()).not.toContain('运行环境、镜像与安装命令')
  expect(wrapper!.text()).not.toContain('singverse-compute:0.1.0-openvino')
  expect(wrapper!.text()).toContain('当前镜像未包含')
  const openvino = wrapper!.get('[data-model="htdemucs-openvino"]')
  expect(openvino.text()).toContain('Intel 优化模型')
  expect(openvino.get('input[type="file"]').attributes('accept')).toBe('.zip')
  expect(wrapper!.get('[data-model="htdemucs"] input[type="file"]').attributes('accept')).toBe('.th')
  expect(openvino.text()).toContain('htdemucs_fwd.xml')
  await button('下载到节点', openvino).trigger('click'); await flushPromises()
  expect(authRequest).toHaveBeenCalledWith('/api/v1/audio-processing/model/download?model=htdemucs-openvino', 'POST', undefined, 60000)
})

it('旧 setup 链接跳转性能节点配置', async () => {
  router.route.query = { tab: 'setup' }; await open()
  expect(router.replace).toHaveBeenCalledWith('/system/compute#audio-models')
})
it('表格展示封面音轨与任务信息，调整分页后回到首页', async () => {
  const previous = vi.mocked(authRequest).getMockImplementation()!
  vi.mocked(authRequest).mockImplementation(async (...args) => args[0].includes('/songs?') ? { totalElements: 60, items: [row('a', { coverAvailable: true, audio: { ...roles, jobs: [{ id: 'job', kind: 'SEPARATE', state: 'FAILED', backend: 'cuda', error: '执行失败' }] } })] } as never : previous(...args))
  await open()
  expect(wrapper!.findAll('thead th')).toHaveLength(7)
  expect(wrapper!.text()).toContain('AAC · 2 声道')
  expect(wrapper!.text()).toContain('执行失败')
  expect(wrapper!.get('.row-cover img').attributes('src')).toBe('/api/v1/metadata/songs/a/cover')
  await wrapper!.get('.row-cover img').trigger('error')
  expect(wrapper!.find('[aria-label="暂无歌曲封面"]').exists()).toBe(true)
  const pager = wrapper!.findComponent({ name: 'ElPagination' })
  pager.vm.$emit('current-change', 2); await flushPromises()
  pager.vm.$emit('size-change', 50); await flushPromises()
  expect(requested('page=0&size=50')).toBe(true)
})
it('硬件存在时歌曲和任务入口均不提供手选 CPU', async () => {
  const previous = vi.mocked(authRequest).getMockImplementation()!
  vi.mocked(authRequest).mockImplementation(async (...args) => args[0].includes('/node') ? { ...node(), backends: [{ id: 'cpu', state: 'AVAILABLE' }, { id: 'cuda', state: 'AVAILABLE', detected: true }, { id: 'mps', state: 'AVAILABLE', detected: true }] } as never : previous(...args))
  await open()
  const choices = wrapper!.get('[aria-label="生成伴奏使用的设备"]')
  expect(choices.find('option[value="cpu"]').exists()).toBe(false)
  expect(choices.find('option[value="cuda"]').exists()).toBe(true)
})
it('刷新后总量缩减，自动回到最后有效页', async () => {
  await open()
  const previous = vi.mocked(authRequest).getMockImplementation()!
  vi.mocked(authRequest).mockImplementation(async (...args) => args[0].includes('/songs?') ? { totalElements: 1, items: args[0].includes('page=0') ? [row('a')] : [] } as never : previous(...args))
  wrapper!.findComponent({ name: 'ElPagination' }).vm.$emit('current-change', 2)
  await flushPromises()
  expect(wrapper!.findComponent({ name: 'ElPagination' }).props('currentPage')).toBe(1)
  expect(wrapper!.findAll('.song-row')).toHaveLength(1)
})

it('伴奏页可保存 CPU 自动降级策略，并保留视频加速方式和修订号', async () => {
  const previous = vi.mocked(authRequest).getMockImplementation()!
  vi.mocked(authRequest).mockImplementation(async (...args) => {
    if (args[0] === '/api/v1/transcoding/hardware-settings') return { backend: 'deployment', revision: 9 } as never
    return previous(...args)
  })
  wrapper = mount(AudioModelSettings, { global: { stubs } }); await flushPromises()
  await wrapper.get('.fallback-toggle input').setValue(false); await flushPromises()
  expect(authRequest).toHaveBeenCalledWith('/api/v1/transcoding/hardware-settings', 'POST', { backend: 'deployment', revision: 9, cpuFallback: false }, 45000)
  expect(wrapper!.text()).not.toContain('固定使用')
})
it('没有加速设备时也不提供手选 CPU 或 CPU 重试', async () => {
  await open()
  expect(wrapper!.find('option[value="cpu"]').exists()).toBe(false)
  expect(wrapper!.text()).not.toContain('改用 CPU 重试')
})

it('快速分类模型独立展示，GPU 节点也可下载且不提供设备选择', async () => {
  const previous = vi.mocked(authRequest).getMockImplementation()!
  vi.mocked(authRequest).mockImplementation(async (...args) => {
    if (args[0].includes('/node')) return { ...node(), cpuFallback: false,
      fastClassifier: { id: 'panns-cnn6', installed: false, ready: false, writable: true, maxBytes: 32*1024**2, files: [{ name: 'Cnn6_mAP=0.343.pth', size: 23698321, sha256: 'pinned' }] },
      backends: [{ id: 'mps', detected: true, state: 'UNVERIFIED' }]
    } as never
    return previous(...args)
  })
  wrapper = mount(AudioModelSettings, { global: { stubs } }); await flushPromises()
  const fast = wrapper!.get('[data-model="panns-cnn6"]')
  expect(fast.text()).toContain('快速人声分类模型')
  expect(fast.find('select').exists()).toBe(false)
  expect(fast.get('input[type="file"]').attributes('accept')).toBe('.zip')
  await button('下载到节点', fast).trigger('click'); await flushPromises()
  expect(authRequest).toHaveBeenCalledWith('/api/v1/audio-processing/model/download?model=panns-cnn6', 'POST', undefined, 60000)
})

it('启动自动试跑显示进度和播放优先提示，验证完成后恢复就绪', () => {
  const current = { ...node(), selected: null, startupCheck: { state: 'TESTING', backend: 'mps' } }
  expect(readiness(current, '').title).toContain('正在自动检测')
  expect(readiness(current, '').ready).toBe(false)
  current.startupCheck.state = 'WAITING'
  expect(readiness(current, '').title).toContain('等待空闲')
  expect(readiness(current, '').hint).toContain('播放任务优先')
  expect(readiness({ ...current, selected: 'mps', startupCheck: { state: 'COMPLETED' } }, '').ready).toBe(true)
})

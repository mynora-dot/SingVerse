// @vitest-environment jsdom
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { ElMessageBox } from 'element-plus'
import ComputeSettingsView from '../src/admin/modules/compute-settings/views/ComputeSettingsView.vue'
import { computeNode } from '../src/admin/modules/compute-settings/api'
import type { NodeSettings, NodeCheck } from '../src/admin/modules/compute-settings/types'
vi.mock('../src/admin/modules/compute-settings/api')
vi.mock('../src/admin/modules/compute-settings/HardwareSettings.vue', () => ({ default: { template: '<div data-test="hardware-settings">硬件设置内容</div>' } }))
vi.mock('../src/admin/modules/compute-settings/AudioModelSettings.vue', () => ({ default: { template: '<div data-test="audio-model-settings">模型设置内容</div>' } }))
const settings: NodeSettings = { mode: 'AUTO', endpoint: '', hasToken: false, mappings: [], automaticEndpoint: 'http://singverse-compute:8090', automaticHasToken: true, revision: 0 }
const ready: NodeCheck = { status: 'AVAILABLE', message: '连接与认证成功', capabilities: ['media-tags'], elapsedMs: 12 }
let wrapper: VueWrapper, router: Router
const button = (text: string) => wrapper.findAll('button').find(b => b.text() === text)!
async function page(path = '/system/compute') {
  router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/system/compute', component: ComputeSettingsView }, { path: '/other', component: { template: '<p>其他页面</p>' } }] })
  await router.push(path)
  wrapper = mount({ template: '<RouterView />' }, { attachTo: document.body, global: { plugins: [router] } })
  await flushPromises()
}
async function edit(mode = 'MANUAL') {
  await button(wrapper.text().includes('计算服务已就绪') ? '编辑连接' : wrapper.text().includes('暂未启用') ? '启用计算服务' : '检查连接设置').trigger('click')
  await flushPromises(); if (mode === 'DISABLED') await wrapper.get('[role="switch"]').trigger('click')

}
async function manual() {
  await edit()
  await wrapper.get('input[aria-label="计算服务地址"]').setValue('http://192.168.1.20:18090')
  await wrapper.get('input[aria-label="计算节点认证凭据"]').setValue('isolated-fixture-12345678901234567890')
}
beforeEach(() => {
  vi.mocked(computeNode.get).mockResolvedValue({ ...settings, mappings: [] })
  vi.mocked(computeNode.test).mockResolvedValue(ready)
  vi.mocked(computeNode.save).mockImplementation(async input => ({ ...settings, mode: input.mode, endpoint: input.endpoint, hasToken: !input.clearToken && (!!input.token || input.endpoint === settings.endpoint && settings.hasToken), mappings: input.mappings, revision: input.revision + 1 }))
})
afterEach(() => { wrapper?.unmount(); vi.restoreAllMocks(); vi.resetAllMocks(); document.body.innerHTML = '' })
it('默认显示已就绪状态和用途，已自动连接的用户无需再填写表单', async () => {
  await page(); expect(wrapper.text()).toContain('计算服务已就绪'); expect(wrapper.find('.connection-error').exists()).toBe(false)
  expect(wrapper.text()).toContain('http://singverse-compute:8090'); expect(wrapper.text()).toContain('12 ms')
  expect(wrapper.find('form').exists()).toBe(false); expect(wrapper.find('input[type="password"]').exists()).toBe(false)
  expect(computeNode.save).not.toHaveBeenCalled()
})
it('更换连接才展示表单，密钥帮助和高级映射默认折叠，支持添加删除映射', async () => {
  await page(); await manual()
  expect(wrapper.get('.connection-drawer').element.contains(document.activeElement)).toBe(true)
  expect(document.querySelector('.el-dialog')).toBeNull(); expect(wrapper.get('.node-paths').attributes('open')).toBeUndefined()
  await button('添加目录映射').trigger('click')
  await wrapper.get('input[aria-label="本机媒体路径 1"]').setValue('/media')
  await wrapper.get('input[aria-label="节点媒体路径 1"]').setValue('/mnt/music')
  await wrapper.get('form').trigger('submit'); await flushPromises()
  expect(computeNode.save).toHaveBeenCalledWith(expect.objectContaining({ mode: 'MANUAL', endpoint: 'http://192.168.1.20:18090', mappings: [{ localRoot: '/media', remoteRoot: '/mnt/music' }] }))
  expect(wrapper.find('form').exists()).toBe(false); expect(wrapper.text()).toContain('配置已保存')
  await edit(); expect((wrapper.get('input[type="password"]').element as HTMLInputElement).value).toBe('')
  await wrapper.get('button[aria-label="移除路径映射 1"]').trigger('click'); expect(wrapper.find('.node-mapping').exists()).toBe(false)
})
it('已保存密钥不回填，留空保留；改变地址时提示重新填写', async () => {
  vi.mocked(computeNode.get).mockResolvedValue({ ...settings, mode: 'MANUAL', endpoint: 'http://192.168.1.20:18090', hasToken: true })
  await page(); await edit()
  expect(wrapper.get('input[type="password"]').attributes('placeholder')).toContain('留空保留')
  await wrapper.get('input[aria-label="计算服务地址"]').setValue('http://192.168.1.21:18090'); expect(wrapper.text()).toContain('需重新填写密钥')
  expect(wrapper.find('.node-clear-token').exists()).toBe(false)
  await wrapper.get('form').trigger('submit'); await flushPromises(); expect(computeNode.save).not.toHaveBeenCalled(); expect(wrapper.text()).toContain('请填写连接密钥')
})
it('新连接失败保留原连接，明确选择后可以只保存离线配置', async () => {
  await page(); await manual()
  vi.mocked(computeNode.test).mockResolvedValue({ ...ready, status: 'AUTH_FAILED', message: '认证凭据不匹配', capabilities: [] })
  await wrapper.get('form').trigger('submit'); await flushPromises()
  expect(computeNode.save).not.toHaveBeenCalled(); expect(wrapper.get('[aria-label="当前计算服务"]').text()).toContain('计算服务已就绪')
  expect(wrapper.text()).toContain('当前连接保持不变'); expect(wrapper.text()).toContain('认证凭据不匹配')
  await button('仅保存配置，稍后连接').trigger('click'); await flushPromises(); expect(computeNode.save).toHaveBeenCalledTimes(1)
})
it('当前连接检查不会覆盖草稿，停用不再发起连接测试', async () => {
  await page(); await manual()
  let resolve!: (value: NodeCheck) => void
  vi.mocked(computeNode.test).mockReturnValueOnce(new Promise(r => { resolve = r }))
  await button('重新检查').trigger('click'); await wrapper.get('[role="switch"]').trigger('click')
  resolve(ready); await flushPromises()
  expect(wrapper.get('[role="switch"]').attributes('aria-checked')).toBe('false')
  const calls = vi.mocked(computeNode.test).mock.calls.length
  await wrapper.get('form').trigger('submit'); await flushPromises()
  expect(computeNode.save).toHaveBeenCalledWith(expect.objectContaining({ mode: 'DISABLED' })); expect(computeNode.test).toHaveBeenCalledTimes(calls)
  expect(wrapper.text()).toContain('暂未启用计算服务'); expect(wrapper.find('form').exists()).toBe(false)
})
it('没有自动节点时直接引导连接其他设备，无需理解部署模式', async () => {
  vi.mocked(computeNode.get).mockResolvedValue({ ...settings, automaticEndpoint: '', automaticHasToken: false })
  vi.mocked(computeNode.test).mockResolvedValue({ ...ready, status: 'DISABLED', capabilities: [] })
  await page(); expect(wrapper.text()).toContain('还没有连接计算服务')
  await button('连接其他设备').trigger('click'); await flushPromises()
  expect(wrapper.find('input[aria-label="计算服务地址"]').exists()).toBe(true)
})
it('取消编辑不改当前连接，离开未保存的草稿有保护', async () => {
  await page(); await manual(); await button('取消').trigger('click'); expect(wrapper.find('form').exists()).toBe(false); expect(computeNode.save).not.toHaveBeenCalled()
  await manual(); const confirm = vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue('cancel')
  await router.push('/other'); expect(router.currentRoute.value.path).toBe('/system/compute'); expect(wrapper.find('form').exists()).toBe(true)
  confirm.mockResolvedValue('confirm' as Awaited<ReturnType<typeof ElMessageBox.confirm>>); await router.push('/other'); expect(router.currentRoute.value.path).toBe('/other')
})
it('读取失败可重试；连接空能力不假称文件标签可用', async () => {
  vi.mocked(computeNode.get).mockRejectedValueOnce(new Error('读取失败'))
  await page(); await button('重新读取').trigger('click'); await flushPromises()
  vi.mocked(computeNode.test).mockResolvedValue({ ...ready, capabilities: [] })
  await button('重新检查').trigger('click'); await flushPromises()
  expect(wrapper.text()).toContain('已连接，暂无可用功能')
})

it('按实际能力展示用途，未提供的功能不加载设置', async () => {
  await page()
  expect(wrapper.find('[aria-label="可用功能"]').exists()).toBe(false)
  expect(wrapper.find('[data-test="hardware-settings"]').exists()).toBe(false)
  expect(wrapper.find('[data-test="audio-model-settings"]').exists()).toBe(false)
  expect(wrapper.text()).toContain('此服务暂未提供视频转码')
  await wrapper.get('[role="tab"][id$="audio"]').trigger('click'); await flushPromises()
  expect(wrapper.text()).toContain('此服务暂未提供伴奏处理')
  expect(router.currentRoute.value.query.tab).toBe('audio')
})
it('仅在需要时加载功能设置，切换页签保留已打开的设置', async () => {
  vi.mocked(computeNode.test).mockResolvedValue({ ...ready, capabilities: ['media-remote-tags', 'transcode-hls', 'audio-separation'] })
  await page()
  expect(wrapper.find('[data-test="hardware-settings"]').exists()).toBe(true)
  expect(wrapper.find('[data-test="audio-model-settings"]').exists()).toBe(false)
  await wrapper.get('[role="tab"][id$="audio"]').trigger('click'); await flushPromises()
  expect(wrapper.find('[data-test="audio-model-settings"]').exists()).toBe(true)
  const hardware = wrapper.get('[data-test="hardware-settings"]').element
  await button('重新检查').trigger('click'); await flushPromises()
  expect(wrapper.get('[data-test="hardware-settings"]').element).toBe(hardware)
})
it('停用时不读取硬件和模型，旧模型链接仍可直接进入伴奏页签', async () => {
  vi.mocked(computeNode.get).mockResolvedValue({ ...settings, mode: 'DISABLED' })
  await page('/system/compute#audio-models')
  expect(computeNode.test).not.toHaveBeenCalled()
  expect(wrapper.find('[data-test="hardware-settings"]').exists()).toBe(false)
  expect(wrapper.find('[data-test="audio-model-settings"]').exists()).toBe(false)
  expect(wrapper.get('[role="tab"][id$="audio"]').attributes('aria-selected')).toBe('true')
  expect(wrapper.text()).toContain('连接后准备伴奏模型')
})
it('连接编辑在独立侧栏中打开，不在主页面堆叠', async () => {
  await page(); await manual()
  const sections = wrapper.findAll('section[aria-label]').map(section => section.attributes('aria-label'))
  expect(wrapper.find('.connection-drawer').exists()).toBe(true)
  expect(sections).toContain('功能设置')
  expect(wrapper.find('.connection-drawer form').exists()).toBe(true)
})

it('启停和连接方式在同一表单中，重新启用保留手动地址草稿', async () => {
  await page(); await manual()
  await wrapper.get('[role="switch"]').trigger('click')
  expect(wrapper.find('input[type="radio"]').exists()).toBe(false)
  expect(wrapper.find('input[aria-label="计算服务地址"]').exists()).toBe(false)
  await wrapper.get('[role="switch"]').trigger('click')
  expect(wrapper.find('input[aria-label="计算服务地址"]').exists()).toBe(true)
  expect((wrapper.get('input[aria-label="计算服务地址"]').element as HTMLInputElement).value).toBe('http://192.168.1.20:18090')
  expect(computeNode.save).not.toHaveBeenCalled()
  expect(wrapper.findAll('form')).toHaveLength(1)
})

it('统一表单自动填入部署地址，保留部署密钥且打开表单不产生修改', async () => {
  await page(); await edit()
  expect((wrapper.get('input[aria-label="计算服务地址"]').element as HTMLInputElement).value).toBe(settings.automaticEndpoint)
  expect(wrapper.get('input[type="password"]').attributes('placeholder')).toContain('部署密钥')
  expect(wrapper.find('input[type="radio"]').exists()).toBe(false)
  expect(button('保存设置').attributes('disabled')).toBeDefined()
  await button('仅测试连接').trigger('click'); await flushPromises()
  expect(computeNode.test).toHaveBeenLastCalledWith(expect.objectContaining({ mode: 'AUTO', token: '' }))
})
it('修改自动地址需要新密钥，还原地址后继续使用部署配置', async () => {
  await page(); await edit()
  const input = wrapper.get('input[aria-label="计算服务地址"]')
  await input.setValue('http://192.168.1.22:18090')
  await wrapper.get('form').trigger('submit'); await flushPromises()
  expect(computeNode.save).not.toHaveBeenCalled()
  expect(wrapper.text()).toContain('请填写连接密钥')
  await input.setValue(settings.automaticEndpoint)
  await button('仅测试连接').trigger('click'); await flushPromises()
  expect(computeNode.test).toHaveBeenLastCalledWith(expect.objectContaining({ mode: 'AUTO' }))
})

it('连接帮助按需打开，不挤占表单，关闭帮助保留草稿', async () => {
  await page(); await manual()
  await button('如何获取连接信息？').trigger('click'); await flushPromises()
  expect(document.body.textContent).toContain('在计算设备上执行以下命令获取密钥')
  const close = Array.from(document.querySelectorAll('button')).find(item => item.textContent?.trim() === '关闭')!
  close.click(); await flushPromises()
  expect((wrapper.get('input[aria-label="计算服务地址"]').element as HTMLInputElement).value).toBe('http://192.168.1.20:18090')
})

it.each([
  ['media-probe'],
  ['media-probe', 'audio-role-fast'],
  ['media-probe', 'audio-role-fast', 'audio-role-fast-classifier'],
])('模型未试跑时仍开放配置入口：%j', async (...capabilities: string[]) => {
  vi.mocked(computeNode.test).mockResolvedValue({ ...ready, capabilities })
  await page('/system/compute?tab=audio')
  expect(wrapper.find('[data-test="audio-model-settings"]').exists()).toBe(true)
  expect(wrapper.text()).not.toContain('此服务暂未提供伴奏处理')
})

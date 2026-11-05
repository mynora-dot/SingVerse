// @vitest-environment jsdom
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { ElMessageBox } from 'element-plus'
import ComputeSettingsView from '../src/admin/modules/compute-settings/views/ComputeSettingsView.vue'
import { computeNode } from '../src/admin/modules/compute-settings/api'
import { ApiError } from '../src/admin/core/auth/api'
import type { NodeSettings, NodeDiscovery } from '../src/admin/modules/compute-settings/types'

vi.mock('../src/admin/modules/compute-settings/api')
vi.mock('../src/admin/modules/compute-settings/HardwareSettings.vue', () => ({ default: { template: '<div>硬件设置</div>' } }))
vi.mock('../src/admin/modules/compute-settings/AudioModelSettings.vue', () => ({ default: { template: '<div>伴奏设置</div>' } }))
const initial: NodeSettings = { mode: 'AUTO', endpoint: '', hasToken: false, mappings: [], automaticEndpoint: 'http://singverse-compute:8090', automaticHasToken: true, revision: 4 }
const found: NodeDiscovery = { message: '扫描完成，找到 2 个节点', items: [
  { id: 'discovery-ticket-1', nodeId: 'node1', name: '客厅性能节点', endpoint: 'http://192.168.1.20:18090', pairingAvailable: true, message: '可配对' },
  { id: 'discovery-ticket-2', nodeId: 'node2', name: '备用性能节点', endpoint: 'http://192.168.1.21:18090', pairingAvailable: false, message: '此节点尚未开放配对，请手动填写连接信息' },
] }
const paired: NodeSettings = { ...initial, mode: 'MANUAL', endpoint: found.items[0]!.endpoint, hasToken: true, revision: 5 }
let wrapper: VueWrapper | undefined, router: Router
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(computeNode.get).mockResolvedValue(structuredClone(initial))
  vi.mocked(computeNode.test).mockResolvedValue({ status: 'AVAILABLE', message: '连接与认证成功', capabilities: [], elapsedMs: 10 })
  vi.mocked(computeNode.discover).mockResolvedValue(structuredClone(found))
  vi.mocked(computeNode.pair).mockResolvedValue(structuredClone(paired))
})
afterEach(() => { wrapper?.unmount(); wrapper = undefined; vi.restoreAllMocks(); document.body.innerHTML = '' })
function button(text: string) { return wrapper!.findAll('button').find(item => item.text() === text)! }
function connect() { return wrapper!.findAll('button').find(item => item.text() === '连接此节点')! }
async function page() {
  router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/system/compute', component: ComputeSettingsView }, { path: '/other', component: { template: '<p>其他页面</p>' } }] })
  await router.push('/system/compute')
  wrapper = mount({ template: '<RouterView />' }, { attachTo: document.body, global: { plugins: [router] } })
  await flushPromises(); await button('编辑连接').trigger('click'); await flushPromises()
}
async function scan() { await button('扫描局域网内计算节点').trigger('click'); await flushPromises() }
function confirm() { return vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as Awaited<ReturnType<typeof ElMessageBox.confirm>>) }

it('显式扫描才请求，发现列表不改草稿，不可配对的节点解释原因', async () => {
  await page()
  expect(computeNode.discover).not.toHaveBeenCalled()
  expect(wrapper!.get('.discovery-range').attributes('open')).toBeUndefined()
  expect(wrapper!.text()).toContain('由服务节点扫描局域网')
  await scan()
  expect(computeNode.discover).toHaveBeenCalledWith({})
  expect(wrapper!.findAll('.discovery-list li')).toHaveLength(2)
  expect(wrapper!.text()).toContain('客厅性能节点')
  expect(wrapper!.text()).toContain('此节点尚未开放配对')
  expect(wrapper!.findAll('.discovery-list li')[1]!.get('button').attributes('disabled')).toBeDefined()
  expect(wrapper!.get('input[aria-label="计算服务地址"]').element).toHaveProperty('value', initial.automaticEndpoint)
  expect(wrapper!.get('input[aria-label="计算节点认证凭据"]').element).toHaveProperty('value', '')
  expect(computeNode.pair).not.toHaveBeenCalled()
  expect(computeNode.save).not.toHaveBeenCalled()
})

it('指定网段和端口交给服务节点扫描，范围输入不混入连接保存', async () => {
  await page()
  await wrapper!.get('input[aria-label="扫描网段"]').setValue('192.168.30.0/24')
  await wrapper!.get('input[aria-label="扫描端口"]').setValue('28090')
  await scan()
  expect(computeNode.discover).toHaveBeenCalledWith({ network: '192.168.30.0/24', port: 28090 })
  expect(computeNode.save).not.toHaveBeenCalled()
})

it.each(['127.0.0.1/32', '172.15.1.0/24', '192.168.1.0/16', '192.168.1.7/24', '192.168.256.0/24'])('拒绝无效或非局域网扫描范围：%s', async network => {
  await page(); await wrapper!.get('input[aria-label="扫描网段"]').setValue(network); await scan()
  expect(computeNode.discover).not.toHaveBeenCalled()
  expect(wrapper!.text()).toContain('请填写局域网 IPv4 网段')
})

it('端口必须为1至65535的整数', async () => {
  await page(); await wrapper!.get('input[aria-label="扫描端口"]').setValue('0'); await scan()
  expect(computeNode.discover).not.toHaveBeenCalled()
  expect(wrapper!.text()).toContain('扫描端口应为')
})

it('选用节点由服务端配对保存，无需填写密钥，成功后刷新当前连接', async () => {
  await page(); await scan(); await connect().trigger('click'); await flushPromises()
  expect(computeNode.pair).toHaveBeenCalledWith('discovery-ticket-1', 4)
  expect(computeNode.save).not.toHaveBeenCalled()
  expect(wrapper!.find('#compute-connection-form').exists()).toBe(false)
  expect(wrapper!.get('[aria-label="当前计算服务"]').text()).toContain(paired.endpoint)
  expect(wrapper!.text()).toContain('配置已保存')
  expect(computeNode.test).toHaveBeenLastCalledWith(expect.objectContaining({ endpoint: paired.endpoint, token: '', revision: 5 }))
})

it('配对前保护手动草稿，取消或配对失败都保留原连接和草稿', async () => {
  await page()
  await wrapper!.get('input[aria-label="计算服务地址"]').setValue('http://192.168.1.99:18090')
  await scan()
  const ask = confirm().mockRejectedValueOnce('cancel')
  await connect().trigger('click'); await flushPromises()
  expect(ask).toHaveBeenCalledTimes(1)
  expect(computeNode.pair).not.toHaveBeenCalled()
  vi.mocked(computeNode.pair).mockRejectedValueOnce(new ApiError(422, '配对失败，当前连接保持不变'))
  await connect().trigger('click'); await flushPromises()
  expect(wrapper!.text()).toContain('配对失败，当前连接保持不变')
  expect(wrapper!.get('[aria-label="当前计算服务"]').text()).toContain(initial.automaticEndpoint)
  expect(wrapper!.get('input[aria-label="计算服务地址"]').element).toHaveProperty('value', 'http://192.168.1.99:18090')
  expect(wrapper!.findAll('.discovery-list li')).toHaveLength(2)
})

it('配置冲突提供重新读取入口，保留草稿直到显式读取', async () => {
  await page(); await scan()
  vi.mocked(computeNode.pair).mockRejectedValueOnce(new ApiError(409, '配置已被修改，请重新读取'))
  await connect().trigger('click'); await flushPromises()
  expect(wrapper!.text()).toContain('配置已被修改')
  expect(connect().attributes('disabled')).toBeDefined()
  vi.mocked(computeNode.get).mockResolvedValueOnce({ ...initial, revision: 6 })
  await button('放弃草稿，读取最新配置').trigger('click'); await flushPromises()
  expect(wrapper!.find('.discovery-list').exists()).toBe(false)
  expect(wrapper!.text()).not.toContain('配置已被修改')
  await scan(); await connect().trigger('click'); await flushPromises()
  expect(computeNode.pair).toHaveBeenLastCalledWith('discovery-ticket-1', 6)
})

it('节点配对409后重新扫描，新的发现结果可以再次选用', async () => {
  await page(); await scan()
  vi.mocked(computeNode.pair).mockRejectedValueOnce(new ApiError(409, '节点身份已变化，请重新扫描'))
  await connect().trigger('click'); await flushPromises()
  expect(connect().attributes('disabled')).toBeDefined()
  expect(wrapper!.text()).toContain('节点身份已变化')
  const refreshed = { ...found.items[0]!, id: 'fresh-discovery-ticket', nodeId: 'replacement-node', endpoint: 'http://192.168.1.22:18090' }
  vi.mocked(computeNode.discover).mockResolvedValueOnce({ items: [refreshed], message: '发现 1 个计算节点' })
  await scan()
  expect(connect().attributes('disabled')).toBeUndefined()
  expect(wrapper!.text()).not.toContain('节点身份已变化')
  await connect().trigger('click'); await flushPromises()
  expect(computeNode.pair).toHaveBeenLastCalledWith('fresh-discovery-ticket', 4)
  expect(wrapper!.text()).toContain('配置已保存')
})

it('扫描请求防重，取消后忽略迟到结果并允许重试', async () => {
  let resolve!: (value: NodeDiscovery) => void
  vi.mocked(computeNode.discover).mockReturnValueOnce(new Promise(value => { resolve = value }))
  await page(); await scan(); await button('扫描局域网内计算节点').trigger('click')
  expect(computeNode.discover).toHaveBeenCalledTimes(1)
  expect(wrapper!.text()).toContain('正在扫描，请稍候')
  await button('取消扫描').trigger('click'); await flushPromises()
  resolve(found); await flushPromises()
  expect(wrapper!.find('.discovery-list').exists()).toBe(false)
  expect(wrapper!.text()).toContain('已取消等待扫描结果')
  await scan()
  expect(wrapper!.findAll('.discovery-list li')).toHaveLength(2)
})

it('配对中阻止重复请求和离页，失败后恢复编辑', async () => {
  let reject!: (reason: Error) => void
  vi.mocked(computeNode.pair).mockReturnValueOnce(new Promise((_, failure) => { reject = failure }))
  await page(); await scan(); await connect().trigger('click'); await connect().trigger('click')
  expect(computeNode.pair).toHaveBeenCalledTimes(1)
  expect(button('取消').attributes('disabled')).toBeDefined()
  expect(wrapper!.get('input[aria-label="计算服务地址"]').attributes('disabled')).toBeDefined()
  await router.push('/other')
  expect(router.currentRoute.value.path).toBe('/system/compute')
  reject(new Error('连接超时')); await flushPromises()
  expect(wrapper!.text()).toContain('连接超时')
  expect(button('取消').attributes('disabled')).toBeUndefined()
})

it('空结果和扫描错误可重试，关闭抽屉不会被迟到结果重新打开', async () => {
  await page()
  vi.mocked(computeNode.discover).mockRejectedValueOnce(new Error('扫描服务暂不可用'))
  await scan(); expect(wrapper!.text()).toContain('扫描服务暂不可用')
  vi.mocked(computeNode.discover).mockResolvedValueOnce({ items: [], message: '扫描完成' })
  await scan(); expect(wrapper!.text()).toContain('未发现可连接的计算节点')
  let resolve!: (value: NodeDiscovery) => void
  vi.mocked(computeNode.discover).mockReturnValueOnce(new Promise(value => { resolve = value }))
  await scan(); await button('取消').trigger('click'); await flushPromises()
  resolve(found); await flushPromises()
  expect(wrapper!.find('.discovery-list').exists()).toBe(false)
  expect(wrapper!.find('#compute-connection-form').exists()).toBe(false)
})

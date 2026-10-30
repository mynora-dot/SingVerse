// @vitest-environment jsdom
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { beforeEach, afterEach, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import AiSettingsView from '../src/admin/modules/ai-settings/views/AiSettingsView.vue'
import { ai } from '../src/admin/modules/ai-settings/api/ai'
import type { AiService, Feature } from '../src/admin/modules/ai-settings/types'
vi.mock('../src/admin/modules/ai-settings/api/ai')
const model: AiService = { id: 'model-1', kind: 'MODEL', name: '分类模型', provider: 'custom', protocol: 'CHAT_COMPLETIONS', endpoint: 'https://example.test/chat/completions', model: 'fixture', outputMode: 'JSON_SCHEMA', enabled: true, hasKey: true, timeoutSeconds: 60, maxResults: 5, revision: 1 }
const search: AiService = { ...model, id: 'search-1', kind: 'SEARCH', name: '搜索服务', provider: 'custom-search', protocol: 'CUSTOM-SEARCH', model: '' }
const features: Feature[] = [{ id: 'assistant', modelServiceId: null, searchServiceId: null, enabled: false, intervalSeconds: 3, revision: 1, displayMode: 'PET', petId: 'music-sprite' }, { id: 'classification', modelServiceId: 'model-1', searchServiceId: null, enabled: false, intervalSeconds: 3, revision: 1 }, { id: 'default-search', modelServiceId: null, searchServiceId: null, enabled: false, intervalSeconds: 3, revision: 1 }]
let wrapper: VueWrapper
const button = (text: string) => wrapper.findAll('button').find(b => b.text() === text)!
async function page(section = 'models') {
  const router = createRouter({ history: createMemoryHistory(), routes: ['models', 'search', 'features', 'features/assistant'].map(s => ({ path: '/system/ai/' + s, component: AiSettingsView })) })
  await router.push('/system/ai/' + section)
  wrapper = mount({ template: '<RouterView />' }, { attachTo: document.body, global: { plugins: [router] } })
  await flushPromises(); return router
}
beforeEach(() => {
  vi.mocked(ai.providers).mockResolvedValue([{ id: 'custom', name: '通用模型', kind: 'MODEL', protocol: 'CHAT_COMPLETIONS', endpoint: '', outputMode: 'JSON_SCHEMA' }, { id: 'anthropic', name: 'Anthropic', kind: 'MODEL', protocol: 'ANTHROPIC', endpoint: 'https://api.anthropic.com/v1/messages', outputMode: 'JSON_SCHEMA' }, { id: 'custom-search', name: '通用搜索', kind: 'SEARCH', protocol: 'CUSTOM-SEARCH', endpoint: '', outputMode: '' }])
  vi.mocked(ai.services).mockImplementation(async kind => kind === 'MODEL' ? [model] : [search])
  vi.mocked(ai.features).mockResolvedValue(features.map(f => ({ ...f })))
  vi.mocked(ai.save).mockResolvedValue(model)
  vi.mocked(ai.bind).mockImplementation(async f => ({ ...f, revision: f.revision + 1 }))
  vi.mocked(ai.test).mockResolvedValue({ success: true, message: '查询成功', elapsedMs: 15, results: [{ title: '测试结果', url: 'https://example.test/result', snippet: '返回摘要' }] })
})
afterEach(() => { wrapper?.unmount(); vi.clearAllMocks(); vi.restoreAllMocks(); document.body.innerHTML = '' })
it('模型配置和分类任务分离，编辑密钥不回填，保存不会自动请求模型', async () => {
  await page(); expect(wrapper.text()).toContain('分类模型')
  await button('编辑').trigger('click'); await flushPromises()
  expect((wrapper.get('input[type="password"]').element as HTMLInputElement).value).toBe('')
  await wrapper.get('input[placeholder="例如：日常分类模型"]').setValue('新的配置名')
  await button('保存服务').trigger('click'); await flushPromises()
  expect(ai.save).toHaveBeenCalledWith('MODEL', expect.objectContaining({ name: '新的配置名', apiKey: '', revision: 1 }), 'model-1')
  expect(ai.test).not.toHaveBeenCalled()
})
it('厂商预设切换更新协议和地址，不携带前一个密钥', async () => {
  await page(); await button('添加模型服务').trigger('click'); await flushPromises()
  await wrapper.get('input[type="password"]').setValue('fixture-key')
  const provider = wrapper.findAllComponents({ name: 'ElSelect' })[0]!
  provider.vm.$emit('update:modelValue', 'anthropic'); provider.vm.$emit('change', 'anthropic'); await flushPromises()
  expect((wrapper.get('input[type="password"]').element as HTMLInputElement).value).toBe('')
  expect((wrapper.get('input[placeholder="https://服务地址/接口路径"]').element as HTMLInputElement).value).toBe('https://api.anthropic.com/v1/messages')
})
it('功能配置绑定模型，分类页面仍由曲库入口承载', async () => {
  await page('features'); expect(wrapper.find('a[href="/library/classification"]').exists()).toBe(true)
  expect(wrapper.text()).not.toContain('分类任务记录')
  await wrapper.get('[role="switch"]').trigger('click'); await button('保存功能配置').trigger('click'); await flushPromises()
  expect(ai.bind).toHaveBeenCalledWith(expect.objectContaining({ id: 'classification', modelServiceId: 'model-1', enabled: true }))
})
it('默认搜索选择不会发起查询，测试仅返回服务端结果', async () => {
  await page('search')
  const select = wrapper.findComponent({ name: 'ElSelect' }); select.vm.$emit('change', 'search-1'); await flushPromises()
  expect(ai.bind).toHaveBeenCalledWith(expect.objectContaining({ id: 'default-search', searchServiceId: 'search-1' }))
  expect(ai.test).not.toHaveBeenCalled()
  await button('测试查询').trigger('click'); await flushPromises()
  await wrapper.get('input[placeholder="输入测试关键词"]').setValue('测试查询')
  await button('开始测试').trigger('click'); await flushPromises()
  expect(ai.test).toHaveBeenCalledWith(search, 'generation', '测试查询')
  expect(wrapper.text()).toContain('返回摘要')
})
it('配置草稿离开保护，取消离开后保留输入', async () => {
  const router = await page(); await button('编辑').trigger('click'); await flushPromises()
  await wrapper.get('input[placeholder="例如：日常分类模型"]').setValue('未保存的名称')
  vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue('cancel')
  await router.push('/system/ai/search'); await flushPromises()
  expect(router.currentRoute.value.path).toBe('/system/ai/models')
  expect((wrapper.get('input[placeholder="例如：日常分类模型"]').element as HTMLInputElement).value).toBe('未保存的名称')
})
it('读取失败可重试，不显示虚假已保存状态', async () => {
  vi.mocked(ai.services).mockRejectedValueOnce(new Error('服务暂时不可用'))
  await page(); expect(wrapper.text()).toContain('服务暂时不可用')
  await button('重新加载').trigger('click'); await flushPromises()
  expect(wrapper.text()).toContain('分类模型')
})

it('助手独立配置形态并保护未保存草稿，保存不调用模型', async () => {
  const router = await page('features/assistant')
  expect(wrapper.text()).toContain('宠物形象')
  expect(wrapper.text()).not.toContain('分类任务请求间隔')
  await wrapper.get('[role="switch"]').trigger('click')
  vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue('cancel')
  await router.push('/system/ai/features'); await flushPromises()
  expect(router.currentRoute.value.path).toBe('/system/ai/features/assistant')
  await wrapper.get('input[value="CHAT"]').setValue(); await flushPromises()
  await button('保存助手配置').trigger('click'); await flushPromises()
  expect(ai.bind).toHaveBeenCalledWith(expect.objectContaining({ id: 'assistant', enabled: true, displayMode: 'CHAT', petId: 'music-sprite' }))
  expect(ai.test).not.toHaveBeenCalled()
  await router.push('/system/ai/features'); await flushPromises()
  expect(router.currentRoute.value.path).toBe('/system/ai/features')
  expect(wrapper.find('a[href="/system/ai/features/assistant"]').exists()).toBe(true)
})

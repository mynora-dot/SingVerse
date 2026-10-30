// @vitest-environment jsdom
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import AssistantWidget from '../src/admin/modules/ai-settings/components/AssistantWidget.vue'
import { ai } from '../src/admin/modules/ai-settings/api/ai'
import { updateAssistantSettings } from '../src/admin/modules/ai-settings/assistant-state'
import type { Feature } from '../src/admin/modules/ai-settings/types'
vi.mock('../src/admin/modules/ai-settings/api/ai')
let wrapper: VueWrapper
const feature: Feature = { id: 'assistant', enabled: false, modelServiceId: null, searchServiceId: null, intervalSeconds: 3, revision: 1, displayMode: 'PET', petId: 'music-sprite' }
async function widget(value = feature) {
  vi.mocked(ai.features).mockResolvedValue([{ ...value }])
  wrapper = mount(AssistantWidget, { attachTo: document.body, global: { stubs: { RouterLink: { props: ['to'], template: '<a :href="to"><slot /></a>' } } } })
  await flushPromises()
}
beforeEach(() => { updateAssistantSettings(null); vi.clearAllMocks() })
afterEach(() => { wrapper?.unmount(); document.body.innerHTML = '' })
it('默认关闭不显示入口，保存启用配置后即时出现宠物', async () => {
  await widget(); expect(wrapper.find('[aria-label="打开 AI 助手"]').exists()).toBe(false)
  updateAssistantSettings({ ...feature, enabled: true }); await flushPromises()
  expect(wrapper.find('.pet-launcher').exists()).toBe(true)
  expect(ai.test).not.toHaveBeenCalled()
})
it('宠物展开可通过 Escape 收起并恢复焦点，不伪造对话或工具能力', async () => {
  await widget({ ...feature, enabled: true })
  await wrapper.get('[aria-label="打开 AI 助手"]').trigger('click'); await flushPromises()
  expect(wrapper.get('[role="dialog"]').text()).toContain('对话与工具能力尚未开放')
  expect(document.activeElement).toBe(wrapper.get('[aria-label="收起 AI 助手"]').element)
  expect(wrapper.find('a[href="/system/ai/features/assistant"]').exists()).toBe(true)
  await wrapper.get('[role="dialog"]').trigger('keydown', { key: 'Escape' }); await flushPromises()
  expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
  expect(document.activeElement).toBe(wrapper.get('[aria-label="打开 AI 助手"]').element)
})
it('聊天框形态切换与关闭配置即时生效', async () => {
  await widget({ ...feature, enabled: true, displayMode: 'CHAT' })
  expect(wrapper.find('.pet-launcher').exists()).toBe(false)
  await wrapper.get('[aria-label="打开 AI 助手"]').trigger('click'); await flushPromises()
  expect(wrapper.find('[role="dialog"]').exists()).toBe(true)
  updateAssistantSettings({ ...feature, enabled: false }); await flushPromises()
  expect(wrapper.find('[aria-label="打开 AI 助手"]').exists()).toBe(false)
})
it('配置读取失败时隐藏助手，不将旧账号状态带入页面', async () => {
  vi.mocked(ai.features).mockRejectedValue(new Error('读取失败'))
  wrapper = mount(AssistantWidget); await flushPromises()
  expect(wrapper.find('aside').exists()).toBe(false)
})
function pointer(target: EventTarget, type: string, x: number, y: number) {
  const event = new MouseEvent(type, { bubbles: true, clientX: x, clientY: y, button: 0 })
  Object.defineProperty(event, 'pointerId', { value: 1 }); target.dispatchEvent(event)
}
it('宠物可拖动且拖后点击不误打开，下一次正常点击可打开', async () => {
  await widget({ ...feature, enabled: true })
  const button = wrapper.get('.assistant-launcher')
  vi.spyOn(button.element, 'getBoundingClientRect').mockReturnValue({ left: 500, top: 500, right: 600, bottom: 600, width: 100, height: 100 } as DOMRect)
  pointer(button.element, 'pointerdown', 550, 550); pointer(window, 'pointermove', 350, 250); pointer(window, 'pointerup', 350, 250)
  await flushPromises()
  expect(wrapper.get('aside').attributes('style')).toContain('left: 300px')
  expect(wrapper.get('aside').attributes('style')).toContain('top: 200px')
  button.element.dispatchEvent(new MouseEvent('click', { bubbles: true, detail: 1 })); await flushPromises(); expect(wrapper.find('[role=dialog]').exists()).toBe(false)
  pointer(button.element, 'pointerdown', 350, 250); pointer(window, 'pointerup', 350, 250)
  button.element.dispatchEvent(new MouseEvent('click', { bubbles: true, detail: 1 })); await flushPromises(); await flushPromises(); expect(wrapper.find('[role=dialog]').exists()).toBe(true)
})
it('聊天形态拖动不能移出窗口，取消后解除拖动', async () => {
  await widget({ ...feature, enabled: true, displayMode: 'CHAT' })
  const button = wrapper.get('.assistant-launcher')
  vi.spyOn(button.element, 'getBoundingClientRect').mockReturnValue({ left: 500, top: 500, right: 600, bottom: 600, width: 100, height: 100 } as DOMRect)
  pointer(button.element, 'pointerdown', 550, 550); pointer(window, 'pointermove', -1000, -1000); pointer(window, 'pointercancel', -1000, -1000)
  await flushPromises(); expect(wrapper.get('aside').attributes('style')).toContain('left: 8px'); expect(wrapper.get('aside').attributes('style')).toContain('top: 8px')
  expect(wrapper.get('aside').classes()).not.toContain('dragging')
})

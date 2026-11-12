// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { h } from 'vue'
import { expect, it, vi } from 'vitest'
import PageHeader from '../src/admin/shared/ui/PageHeader.vue'

it('页面操作保持可用，说明按需展开并保留说明插槽中的链接', async () => {
  const action = vi.fn()
  const wrapper = mount(PageHeader, {
    attachTo: document.body,
    props: { title: '文件名识别', description: '原有说明' },
    slots: {
      actions: () => h('button', { onClick: action }, '保存方案'),
      meta: () => h('span', '3 个方案'),
      description: () => h('a', { href: '#/library/media/recognition?sourceId=source-1' }, '查看来源方案'),
    },
  })
  try {
    expect(wrapper.get('h1').text()).toBe('文件名识别')
    expect(wrapper.text()).toContain('3 个方案')
    expect(document.querySelector('.sv-page-help-popover')).toBeNull()
    await wrapper.get('.sv-page-header-actions button').trigger('click')
    expect(action).toHaveBeenCalledOnce()
    const help = wrapper.get('button[aria-label="文件名识别使用说明"]')
    await help.trigger('click')
    await flushPromises()
    await vi.waitFor(() => expect(document.querySelector('.sv-page-help-popover a')).not.toBeNull())
    const link = document.querySelector<HTMLAnchorElement>('.sv-page-help-popover a')
    expect(link?.textContent).toBe('查看来源方案')
    expect(link?.getAttribute('href')).toBe('#/library/media/recognition?sourceId=source-1')
    expect(help.attributes('aria-expanded')).toBe('true')
    link?.focus()
    link?.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    await flushPromises()
    await vi.waitFor(() => expect(help.attributes('aria-expanded')).toBe('false'))
    expect(document.activeElement).toBe(help.element)
  } finally { wrapper.unmount() }
})

it('没有操作和说明时仅保留语义标题，不生成空操作栏', () => {
  const wrapper = mount(PageHeader, { props: { title: '服务状态' } })
  try {
    expect(wrapper.get('h1').text()).toBe('服务状态')
    expect(wrapper.find('button').exists()).toBe(false)
    expect(wrapper.classes()).not.toContain('has-controls')
  } finally { wrapper.unmount() }
})

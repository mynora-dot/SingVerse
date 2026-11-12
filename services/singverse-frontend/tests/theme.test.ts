// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { DEFAULT_THEME, THEMES, initTheme, setTheme, useTheme } from '../src/admin/app/composables/use-theme'
import ThemeSwitcher from '../src/admin/shared/ui/ThemeSwitcher.vue'

const STORAGE_KEY = 'singverse.admin.theme'

beforeEach(() => {
  localStorage.clear()
  delete document.documentElement.dataset.theme
  setTheme(DEFAULT_THEME)
  localStorage.clear()
})
afterEach(() => {
  vi.restoreAllMocks()
  localStorage.clear()
  delete document.documentElement.dataset.theme
})

it('仅提供浅色与深色，默认浅色', () => {
  expect(DEFAULT_THEME).toBe('light')
  expect(THEMES.map(theme => [theme.id, theme.label, theme.scheme])).toEqual([
    ['light', '浅色', 'light'], ['dark', '深色', 'dark'],
  ])
})

it('无存储时同步应用默认浅色', () => {
  initTheme()
  expect(document.documentElement.dataset.theme).toBe('light')
  expect(useTheme().currentTheme.value).toBe('light')
})

it.each(['light', 'dark'])('读取并保留 %s 偏好，重新初始化后恢复', theme => {
  setTheme(theme)
  delete document.documentElement.dataset.theme
  useTheme().currentTheme.value = DEFAULT_THEME
  initTheme()
  expect(document.documentElement.dataset.theme).toBe(theme)
  expect(useTheme().currentTheme.value).toBe(theme)
  expect(localStorage.getItem(STORAGE_KEY)).toBe(theme)
})

it.each([
  ['indigo', 'light'], ['pine', 'light'], ['vermilion', 'light'], ['celadon', 'light'],
  ['midnight', 'dark'], ['graphite', 'dark'],
])('将旧偏好 %s 迁移并持久化为 %s', (legacy, theme) => {
  localStorage.setItem(STORAGE_KEY, legacy)
  initTheme()
  expect(document.documentElement.dataset.theme).toBe(theme)
  expect(useTheme().currentTheme.value).toBe(theme)
  expect(localStorage.getItem(STORAGE_KEY)).toBe(theme)
  initTheme()
  expect(document.documentElement.dataset.theme).toBe(theme)
})

it.each(['not-a-theme', '', 'toString', '__proto__'])('非法存储值 %s 回退浅色并规范化存储', stored => {
  localStorage.setItem(STORAGE_KEY, stored)
  initTheme()
  expect(document.documentElement.dataset.theme).toBe('light')
  expect(useTheme().currentTheme.value).toBe('light')
  expect(localStorage.getItem(STORAGE_KEY)).toBe('light')
})

it('切换同步更新 dataset、响应式状态与本地存储', () => {
  setTheme('dark')
  expect(document.documentElement.dataset.theme).toBe('dark')
  expect(useTheme().currentTheme.value).toBe('dark')
  expect(localStorage.getItem(STORAGE_KEY)).toBe('dark')
})

it('非法切换值回退默认并持久化合法值', () => {
  setTheme('bogus')
  expect(document.documentElement.dataset.theme).toBe('light')
  expect(localStorage.getItem(STORAGE_KEY)).toBe('light')
})

it('读取存储失败时正常应用浅色', () => {
  setTheme('dark')
  vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('storage unavailable') })
  expect(initTheme).not.toThrow()
  expect(document.documentElement.dataset.theme).toBe('light')
  expect(useTheme().currentTheme.value).toBe('light')
})

it('迁移与切换遇到存储写入失败时仍能应用主题', () => {
  localStorage.setItem(STORAGE_KEY, 'graphite')
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('storage unavailable') })
  expect(initTheme).not.toThrow()
  expect(document.documentElement.dataset.theme).toBe('dark')
  expect(() => setTheme('light')).not.toThrow()
  expect(document.documentElement.dataset.theme).toBe('light')
  expect(useTheme().currentTheme.value).toBe('light')
})

it('顶栏切换器有无障碍名称，菜单仅列出明暗两个选项', async () => {
  const wrapper = mount(ThemeSwitcher, { attachTo: document.body })
  try {
    expect(wrapper.get('.theme-trigger').attributes('aria-label')).toBe('外观主题')
    await wrapper.get('.theme-trigger').trigger('click')
    await flushPromises()
    const options = Array.from(document.querySelectorAll<HTMLElement>('.theme-option'))
    expect(options).toHaveLength(2)
    expect(options.map(option => option.querySelector('strong')?.textContent)).toEqual(['浅色', '深色'])
    options[1]!.click()
    await flushPromises()
    expect(document.documentElement.dataset.theme).toBe('dark')
    expect(localStorage.getItem(STORAGE_KEY)).toBe('dark')
  } finally { wrapper.unmount() }
})

it('移动面板列出两个主题，点击即切换并更新选中态', async () => {
  const wrapper = mount(ThemeSwitcher, { props: { variant: 'sheet' } })
  try {
    const cards = wrapper.findAll('.theme-card')
    expect(cards).toHaveLength(2)
    expect(cards[0].classes()).toContain('is-active')
    expect(cards[0].attributes('aria-pressed')).toBe('true')
    await cards[1].trigger('click')
    expect(document.documentElement.dataset.theme).toBe('dark')
    expect(localStorage.getItem(STORAGE_KEY)).toBe('dark')
    expect(cards[1].classes()).toContain('is-active')
    expect(cards[1].attributes('aria-pressed')).toBe('true')
    expect(cards[0].attributes('aria-pressed')).toBe('false')
  } finally { wrapper.unmount() }
})

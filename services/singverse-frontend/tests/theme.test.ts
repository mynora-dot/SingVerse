// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it } from 'vitest'
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
  localStorage.clear()
  delete document.documentElement.dataset.theme
})

it('默认靛蓝电波，六套主题元数据完整且明暗分类正确', () => {
  expect(DEFAULT_THEME).toBe('indigo')
  expect(THEMES.map(theme => theme.id)).toEqual(['indigo', 'pine', 'vermilion', 'celadon', 'midnight', 'graphite'])
  for (const theme of THEMES) {
    expect(theme.label).toBeTruthy()
    expect(theme.en).toBeTruthy()
    expect(theme.description).toBeTruthy()
    expect(theme.swatches).toHaveLength(3)
    expect(['light', 'dark']).toContain(theme.scheme)
  }
  expect(THEMES.filter(theme => theme.scheme === 'dark').map(theme => theme.id)).toEqual(['midnight', 'graphite'])
})

it('initTheme 无存储时应用默认主题', () => {
  initTheme()
  expect(document.documentElement.dataset.theme).toBe('indigo')
  expect(useTheme().currentTheme.value).toBe('indigo')
})

it('initTheme 读取本地偏好并应用', () => {
  localStorage.setItem(STORAGE_KEY, 'graphite')
  initTheme()
  expect(document.documentElement.dataset.theme).toBe('graphite')
  expect(useTheme().currentTheme.value).toBe('graphite')
})

it('initTheme 非法存储值回退默认', () => {
  localStorage.setItem(STORAGE_KEY, 'not-a-theme')
  initTheme()
  expect(document.documentElement.dataset.theme).toBe('indigo')
  expect(useTheme().currentTheme.value).toBe('indigo')
})

it('setTheme 同步 dataset、响应式状态与本地存储', () => {
  setTheme('midnight')
  expect(document.documentElement.dataset.theme).toBe('midnight')
  expect(useTheme().currentTheme.value).toBe('midnight')
  expect(localStorage.getItem(STORAGE_KEY)).toBe('midnight')
})

it('setTheme 非法值回退默认并持久化合法值', () => {
  setTheme('bogus')
  expect(document.documentElement.dataset.theme).toBe('indigo')
  expect(localStorage.getItem(STORAGE_KEY)).toBe('indigo')
})

it('顶栏切换器渲染触发按钮并标注无障碍名称', () => {
  const wrapper = mount(ThemeSwitcher)
  expect(wrapper.get('.theme-trigger').attributes('aria-label')).toBe('外观主题')
  wrapper.unmount()
})

it('移动端面板列出六套主题，点击即切换、持久化并更新选中态', async () => {
  const wrapper = mount(ThemeSwitcher, { props: { variant: 'sheet' } })
  const cards = wrapper.findAll('.theme-card')
  expect(cards).toHaveLength(6)
  expect(cards[0].classes()).toContain('is-active')
  expect(cards[0].attributes('aria-pressed')).toBe('true')
  await cards[4].trigger('click')
  expect(document.documentElement.dataset.theme).toBe('midnight')
  expect(localStorage.getItem(STORAGE_KEY)).toBe('midnight')
  const updated = wrapper.findAll('.theme-card')
  expect(updated[4].classes()).toContain('is-active')
  expect(updated[4].attributes('aria-pressed')).toBe('true')
  expect(updated[0].attributes('aria-pressed')).toBe('false')
  wrapper.unmount()
})
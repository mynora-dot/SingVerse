import { ref } from 'vue'

export type ThemeId = 'light' | 'dark'
export type ThemeScheme = ThemeId

export interface ThemeMeta {
  id: ThemeId
  label: string
  scheme: ThemeScheme
  description: string
}

/** 管理端仅提供明暗两种外观，亲友 H5 不引用这些主题。 */
export const THEMES: ThemeMeta[] = [
  { id: 'light', label: '浅色', scheme: 'light', description: '白色面板与品牌蓝' },
  { id: 'dark', label: '深色', scheme: 'dark', description: '石墨面板与浅蓝强调' },
]

export const DEFAULT_THEME: ThemeId = 'light'
const STORAGE_KEY = 'singverse.admin.theme'
const currentTheme = ref<ThemeId>(DEFAULT_THEME)
const legacyThemes: Record<string, ThemeId> = {
  indigo: 'light', pine: 'light', vermilion: 'light', celadon: 'light',
  midnight: 'dark', graphite: 'dark',
}

function normalize(id: string | null | undefined): ThemeId {
  if (id === 'light' || id === 'dark') return id
  return id && Object.hasOwn(legacyThemes, id) ? legacyThemes[id]! : DEFAULT_THEME
}

function apply(id: string | null | undefined): ThemeId {
  const theme = normalize(id)
  currentTheme.value = theme
  document.documentElement.dataset.theme = theme
  return theme
}

function persist(theme: ThemeId) {
  try { localStorage.setItem(STORAGE_KEY, theme) } catch { /* 存储不可用时仍保留本次选择。 */ }
}

/** 挂载前同步应用偏好；旧主题按明暗迁移，非法值回退浅色。 */
export function initTheme() {
  let stored: string | null = null
  try { stored = localStorage.getItem(STORAGE_KEY) } catch { /* 读取失败时采用默认浅色。 */ }
  const theme = apply(stored)
  if (stored !== theme) persist(theme)
}

export function setTheme(id: string) {
  persist(apply(id))
}

export function useTheme() {
  return { themes: THEMES, currentTheme, setTheme }
}

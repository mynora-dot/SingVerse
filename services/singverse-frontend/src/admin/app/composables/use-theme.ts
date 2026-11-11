import { ref } from 'vue'

export type ThemeScheme = 'light' | 'dark'

export interface ThemeMeta {
  id: string
  label: string
  en: string
  scheme: ThemeScheme
  description: string
  swatches: [string, string, string]
}

/** 六套管理端主题；靛蓝电波为默认，其余可在顶栏切换。令牌定义见 styles/tokens.css 与 styles/themes.css。 */
export const THEMES: ThemeMeta[] = [
  { id: 'indigo', label: '靛蓝电波', en: 'Indigo Airwave', scheme: 'light', description: '电光靛蓝与品红，冷静而明亮', swatches: ['#2743c7', '#e6399b', '#eef1f8'] },
  { id: 'pine', label: '冷调松绿', en: 'Porcelain Pine', scheme: 'light', description: '瓷白配松绿，沉稳内敛', swatches: ['#16584a', '#e2582f', '#eef1ee'] },
  { id: 'vermilion', label: '柿红剧场', en: 'Vermilion Stage', scheme: 'light', description: '柿红与青碧，热烈有戏剧感', swatches: ['#d83a2b', '#1f7a6d', '#f6f4f2'] },
  { id: 'celadon', label: '青瓷茶室', en: 'Celadon Room', scheme: 'light', description: '青瓷与陶土，温润安静', swatches: ['#3a6b5a', '#c86b4a', '#e9efe9'] },
  { id: 'midnight', label: '午夜聆听', en: 'Midnight Listening', scheme: 'dark', description: '深夜底色，珊瑚与青碧点缀', swatches: ['#ff7a66', '#4fd1c5', '#15171c'] },
  { id: 'graphite', label: '石墨霓虹', en: 'Graphite Neon', scheme: 'dark', description: '石墨霓虹，酸绿与品红高对比', swatches: ['#a3e635', '#ec4899', '#121417'] },
]

export const DEFAULT_THEME = 'indigo'
const STORAGE_KEY = 'singverse.admin.theme'
const themeIds = new Set(THEMES.map(theme => theme.id))
const currentTheme = ref(DEFAULT_THEME)

function normalize(id: string | null | undefined): string {
  return id && themeIds.has(id) ? id : DEFAULT_THEME
}

function apply(id: string | null | undefined) {
  const theme = normalize(id)
  currentTheme.value = theme
  document.documentElement.dataset.theme = theme
}

/** 启动时读取本地偏好并应用，避免首屏闪烁；非法或缺失值回退到默认靛蓝。 */
export function initTheme() {
  let stored: string | null = null
  try { stored = localStorage.getItem(STORAGE_KEY) } catch { /* 隐私模式等读取失败时用默认主题。 */ }
  apply(stored)
}

export function setTheme(id: string) {
  apply(id)
  try { localStorage.setItem(STORAGE_KEY, currentTheme.value) } catch { /* 写入失败不影响本次切换。 */ }
}

export function useTheme() {
  return { themes: THEMES, currentTheme, setTheme }
}
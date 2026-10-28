export const dimensionNames = { LANGUAGE: '语种', DECADE: '发行年代', GENRE: '风格', MOOD: '情绪', SCENE: '场景', CUSTOM: '自定义' } as const
export type Dimension = keyof typeof dimensionNames
export interface Tag { id: string; dimension: Dimension; name: string; aliases: string[]; active: boolean; revision: number; count: number }
export interface ClassValue { tagIds: string[]; origin: string; locked: boolean; confirmed: boolean }
export interface ClassState { songId: string; revision: number; dimensions: Partial<Record<Dimension, ClassValue>> }
export interface Settings { enabled: boolean; endpoint: string; model: string; hasKey: boolean; timeoutSeconds: number; intervalSeconds: number; revision: number }
export interface SaveSettings { enabled: boolean; endpoint: string; model: string; apiKey: string; clearKey: boolean; timeoutSeconds: number; intervalSeconds: number }
export interface SongInput { songId: string; title: string; artists: string[]; album: string | null; releaseDate: string | null; language: string; version: string; qualityTags: string[] }
export interface Suggestion { dimensions: Partial<Record<Dimension, string[]>>; reasons: Partial<Record<Dimension, string>>; newTagSuggestions: string[] }
export interface Item { id: string; songId: string; status: string; input: SongInput; revision: number; suggestion: Suggestion | null; error: string | null; current: ClassState }
export interface Task { id: string; status: string; dimensions: Dimension[]; autoDimensions: Dimension[]; model: string; total: number; completed: number; counts: Record<string, number>; items: Item[]; page: number; size: number; totalElements: number; createdAt: string; updatedAt: string }
export interface Start { all: boolean; songIds: string[]; dimensions: Dimension[]; autoDimensions: Dimension[] }
export interface Preview { items: SongInput[]; total: number; model: Settings }
export interface History { id: string; songId: string; taskId: string | null; origin: string; before: ClassState['dimensions']; after: ClassState['dimensions']; afterRevision: number; reverted: boolean; createdAt: string }
export const statusNames: Record<string, string> = { RUNNING: '运行中', PAUSED: '已暂停', COMPLETED: '生成完成', CANCELLED: '已终止', PENDING: '等待处理', PROCESSING: '正在分类', REVIEW: '待审核', APPLIED: '已应用', UNCHANGED: '无有效建议', FAILED: '失败' }
export const originNames: Record<string, string> = { AI: 'AI 生成', MANUAL: '人工整理', AI_REVIEWED: 'AI 建议 · 人工确认', UNDO: '撤回' }

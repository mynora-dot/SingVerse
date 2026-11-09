import { authRequest } from '../../core/auth/api'

export interface TaskExecution {
  stage?: string; backend?: string; modelVersion?: string; fallbackReason?: string; reason?: string; resultState?: string; jobState?: string
  progress?: { completedFrames: number; frames: number }
  timingsMs?: Record<string, number>
}
export interface BackgroundTask {
  isBatch: boolean; batchKind: string; scopeSealed: boolean; parentId: string | null
  batches: { id: string; name: string }[]; childrenCounts: Record<string, number>; detailMode: 'ITEMS' | 'CHILDREN'
  operation: { status: string; businessStatus: string; stage: string; progress: BackgroundTask['progress']; finishedAt: string | null }
  id: string; type: string; businessId: string; name: string; subjectName: string
  sourceId: string | null; songId: string | null; status: string; businessStatus: string; stage: string
  progress: { completed: number | null; total: number | null; percent: number | null; unit: string }
  statistics: Record<string, number | boolean | Record<string, number>>
  execution: TaskExecution; waitingReason: string; error: string; createdAt: string | null; updatedAt: string | null
  startedAt: string | null; finishedAt: string | null; itemsAvailable: boolean; timeIncomplete: boolean
  relatedAvailable: boolean; relatedPath: string; observedAt: string
}
export interface TaskItem { id: string; songId: string | null; title: string; status: string; message: string; execution: TaskExecution }
export interface TaskPage { items: BackgroundTask[]; page: number; size: number; totalElements: number; observedAt: string }
export interface ChildPage extends TaskPage { counts: Record<string, number> }
export interface ItemPage { items: TaskItem[]; page: number; size: number; totalElements: number; counts: Record<string, number> }
export interface TaskFilter { view: string; type: string; status: string; q: string; page: number }
const base = '/api/v1/background-tasks'
export const backgroundTasks = {
  list: (filter: TaskFilter) => {
    const query = new URLSearchParams({ view: filter.view, page: String(filter.page), size: '20' })
    for (const key of ['type', 'status', 'q'] as const) if (filter[key]) query.set(key, filter[key])
    return authRequest<TaskPage>(base + '?' + query)
  },
  detail: (id: string) => authRequest<BackgroundTask>(base + '/' + encodeURIComponent(id)),
  children: (id: string, page: number, status: string, type: string) => authRequest<ChildPage>(base + '/' + encodeURIComponent(id) + '/children?' + new URLSearchParams({ page: String(page), size: '20', ...(status ? { status } : {}), ...(type ? { type } : {}) })),
  items: (id: string, page: number, status: string) => authRequest<ItemPage>(base + '/' + encodeURIComponent(id) + '/items?' + new URLSearchParams({ page: String(page), size: '20', ...(status ? { status } : {}) })),
}

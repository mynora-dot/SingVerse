import { onBeforeUnmount, ref, watch } from 'vue'
import { auth, authRequest } from '../../core/auth/api'

/** 顶栏任务徽章：只取活跃总数，30s 轮询，页面隐藏或未登录时暂停。 */
export function useActiveTaskCount(intervalMs = 30000) {
  const count = ref(0)
  let timer: ReturnType<typeof setTimeout> | undefined
  let active = true, generation = 0
  async function refresh() {
    if (!auth.account) { count.value = 0; return }
    const token = ++generation
    try {
      const page = await authRequest<{ totalElements?: number }>('/api/v1/background-tasks?view=ACTIVE&page=0&size=1')
      if (active && token === generation) count.value = page.totalElements ?? 0
    } catch { /* 徽章是辅助信息，读取失败保留上次数值。 */ }
  }
  function schedule() {
    if (timer) clearTimeout(timer)
    if (!active || !auth.account || document.visibilityState === 'hidden') return
    timer = setTimeout(() => { void refresh().then(schedule) }, intervalMs)
  }
  function onVisibility() {
    if (document.visibilityState === 'visible') { void refresh(); schedule() }
    else if (timer) clearTimeout(timer)
  }
  watch(() => auth.account, account => {
    if (account) { void refresh(); schedule() }
    else { count.value = 0; if (timer) clearTimeout(timer) }
  }, { immediate: true })
  document.addEventListener('visibilitychange', onVisibility)
  onBeforeUnmount(() => { active = false; generation++; if (timer) clearTimeout(timer); document.removeEventListener('visibilitychange', onVisibility) })
  return { count, refresh }
}
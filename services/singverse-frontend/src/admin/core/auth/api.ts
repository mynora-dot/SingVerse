import { auth, type Account } from './state'
export { auth, type Account } from './state'

export class ApiError extends Error {
  constructor(readonly status: number, message: string,
    readonly details?: { ruleId?: string | null; ruleIndex?: number; field?: string; referencedBy?: { id: string; name: string }[] }) { super(message) }
}
export async function authRequest<T = void>(path: string, method = 'GET', body?: unknown, timeoutMs = 10000): Promise<T> {
  if (!path.startsWith('/api/v1/')) throw new Error('接口必须使用同源 /api/v1/')
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (method !== 'GET') {
    const csrf = await authRequest<{ token: string }>('/api/v1/auth/csrf')
    headers['X-CSRF-Token'] = csrf.token
    if (body !== undefined) headers['Content-Type'] = 'application/json'
  }
  let response: Response
  try {
    response = await fetch(path, {
      method, headers, body: body === undefined ? undefined : JSON.stringify(body),
      credentials: 'same-origin', cache: 'no-store', redirect: 'error', signal: AbortSignal.timeout(timeoutMs),
    })
  } catch { throw new Error('连接失败，请检查网络后重试') }
  if (!response.ok) {
    const problem = await response.json().catch(() => ({})) as { detail?: string; type?: string; ruleId?: string | null; ruleIndex?: number; field?: string; referencedBy?: { id: string; name: string }[] }
    if (response.status === 401 && (method === 'GET' || problem.type === 'urn:singverse:auth:session-required')) auth.account = null
    if (problem.type === 'urn:singverse:auth:session-required' && path !== '/api/v1/auth/session') {
      window.dispatchEvent(new Event('singverse:session-expired'))
    }
    throw new ApiError(response.status, problem.detail ?? `请求失败（${response.status}）`, problem)
  }
  const text = await response.text()
  return text ? JSON.parse(text) as T : undefined as T
}
export async function refreshAccount() {
  try { auth.account = await authRequest<Account>('/api/v1/auth/session') }
  catch (error) { auth.account = null; if (!(error instanceof ApiError && error.status === 401)) throw error }
  return auth.account
}

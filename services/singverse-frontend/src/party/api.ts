export type Song = { id: string; title: string; artists: string[]; version: string; classification: unknown; coverPath?: string }
export type Item = { id: string; songId: string; ownerId: string; ownerName: string; status: string; song: Song }
export type Snapshot = { id: string; revision: number; actorId: string; host: boolean; status: string; acceptingRequests: boolean; queue: Item[]; playback?: { state: string; positionMs: number } }
export type Credential = { token: string; sessionId: string; name: string }
export class PartyHttpError extends Error { constructor(readonly status: number, message: string) { super(message) } }
export async function request<T>(path: string, method = 'GET', body?: unknown, token?: string, signal?: AbortSignal): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (token) headers.Authorization = `Bearer ${token}`
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  const controller = new AbortController()
  const abort = () => controller.abort()
  if (signal?.aborted) abort()
  else signal?.addEventListener('abort', abort, { once: true })
  const timer = setTimeout(abort, 10000)
  try {
    const result = await fetch(`/api/v1/party/${path}`, { method, headers, credentials: 'omit', cache: 'no-store', redirect: 'error', signal: controller.signal, body: body === undefined ? undefined : JSON.stringify(body) })
    if (!result.ok) { const problem = await result.json().catch(() => ({})) as { detail?: string }; throw new PartyHttpError(result.status, problem.detail ?? `请求失败（${result.status}）`) }
    return await result.json() as T
  } finally { clearTimeout(timer); signal?.removeEventListener('abort', abort) }
}

export { requestId } from '../shared/request-id'

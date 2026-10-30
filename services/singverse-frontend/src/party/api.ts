export type Song = { id: string; title: string; artists: string[]; version: string; classification: unknown; coverPath?: string }
export type Item = { id: string; songId: string; ownerId: string; ownerName: string; status: string; song: Song }
export type Snapshot = { id: string; revision: number; actorId: string; host: boolean; status: string; acceptingRequests: boolean; queue: Item[]; playback?: { state: string; positionMs: number } }
export type Credential = { token: string; sessionId: string; name: string }
export class PartyHttpError extends Error { constructor(readonly status: number, message: string) { super(message) } }
export async function request<T>(path: string, method = 'GET', body?: unknown, token?: string): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (token) headers.Authorization = `Bearer ${token}`
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  const result = await fetch(`/api/v1/party/${path}`, { method, headers, credentials: 'omit', cache: 'no-store', redirect: 'error', signal: AbortSignal.timeout(10000), body: body === undefined ? undefined : JSON.stringify(body) })
  if (!result.ok) { const problem = await result.json().catch(() => ({})) as { detail?: string }; throw new PartyHttpError(result.status, problem.detail ?? `请求失败（${result.status}）`) }
  return await result.json() as T
}

// getRandomValues is also available on owner-configured LAN HTTP origins.
export function requestId(): string { return Array.from(crypto.getRandomValues(new Uint8Array(16)), byte => byte.toString(16).padStart(2, '0')).join('') }

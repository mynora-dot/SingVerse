import { authRequest, ApiError } from '../../core/auth/api'

export type TvBanner = { id: string; label: string; enabled: boolean; version: string; imagePath: string }
export type TvSettings = { revision: number; headline: string; subtitle: string; autoRotate: boolean; rotationSeconds: number; banners: TvBanner[] }
const base = '/api/v1/admin/tv-settings'
export const tvSettings = {
  read: () => authRequest<TvSettings>(base),
  save: (settings: TvSettings) => authRequest<TvSettings>(base, 'PUT', settings),
  remove: (id: string, revision: number) => authRequest<TvSettings>(`${base}/banners/${encodeURIComponent(id)}?revision=${revision}`, 'DELETE'),
  upload: async (file: File, revision: number): Promise<TvSettings> => {
    const csrf = await authRequest<{ token: string }>('/api/v1/auth/csrf')
    const form = new FormData(); form.append('file', file)
    let response: Response
    try { response = await fetch(`${base}/banners?revision=${revision}`, { method: 'POST', headers: { 'X-CSRF-Token': csrf.token }, body: form, credentials: 'same-origin', redirect: 'error', signal: AbortSignal.timeout(30000) }) }
    catch { throw new Error('上传失败，请检查网络后重试') }
    if (!response.ok) {
      const problem = await response.json().catch(() => ({})) as { detail?: string }
      if (response.status === 401) window.dispatchEvent(new Event('singverse:session-expired'))
      throw new ApiError(response.status, problem.detail ?? (response.status === 413 ? '图片被上传入口拒绝（413），请检查代理的封面上传大小限制' : `封面上传失败（${response.status}）`))
    }
    return response.json() as Promise<TvSettings>
  },
}

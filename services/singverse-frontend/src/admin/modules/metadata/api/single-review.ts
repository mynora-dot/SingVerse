import { authRequest, ApiError } from '../../../core/auth/api'
import type { MusicProvider, SearchResult } from '../types'
import type { SingleReview, ReviewApply, ReviewResult, CoverAsset, CoverChoice } from '../single-types'
const base = '/api/v1/metadata', segment = encodeURIComponent
export const singleReview = {
  load: (id: string) => authRequest<SingleReview>(`${base}/songs/${segment(id)}/review`),
  apply: (id: string, input: ReviewApply) => authRequest<ReviewResult>(`${base}/songs/${segment(id)}/review/apply`, 'POST', input),
  applyBatch: (batchId: string, itemId: string, input: ReviewApply) => authRequest<ReviewResult>(`${base}/batches/${segment(batchId)}/items/${segment(itemId)}/review/apply`, 'POST', input),
  covers: (id: string, keyword: string, providers: MusicProvider[]) => authRequest<{ items: CoverChoice[]; errors: SearchResult['errors'] }>(`${base}/songs/${segment(id)}/covers/search`, 'POST', { keyword, providers }, 90000),
  assetUrl: (id: string) => `${base}/cover-assets/${segment(id)}`,
  upload: async (id: string, fingerprint: string, file: File, progress: (value: number) => void, signal: AbortSignal): Promise<CoverAsset> => {
    const csrf = await authRequest<{ token: string }>('/api/v1/auth/csrf')
    if (signal.aborted) throw new Error('上传已取消')
    return new Promise((resolve, reject) => {
      const request = new XMLHttpRequest()
      const abort = () => request.abort()
      const finish = () => signal.removeEventListener('abort', abort)
      request.open('POST', `${base}/songs/${segment(id)}/covers/upload`); request.withCredentials = true; request.timeout = 30000
      request.setRequestHeader('X-CSRF-Token', csrf.token)
      request.upload.onprogress = event => { if (event.lengthComputable) progress(Math.round(event.loaded / event.total * 100)) }
      request.onerror = () => { finish(); reject(new Error('上传连接失败，请重试')) }
      request.ontimeout = () => { finish(); reject(new Error('上传超时，请重试')) }
      request.onabort = () => { finish(); reject(new Error('上传已取消')) }
      request.onload = () => {
        finish()
        let body: Record<string, unknown>
        try { body = JSON.parse(request.responseText) } catch { reject(new Error('服务器响应无效')); return }
        if (request.status >= 200 && request.status < 300) resolve(body as unknown as CoverAsset)
        else { if (request.status === 401) window.dispatchEvent(new Event('singverse:session-expired')); reject(new ApiError(request.status, typeof body.detail === 'string' ? body.detail : '上传失败')) }
      }
      signal.addEventListener('abort', abort, { once: true })
      const form = new FormData(); form.append('file', file); form.append('fingerprint', fingerprint); request.send(form)
    })
  },
}

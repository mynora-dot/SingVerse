import { authRequest } from '../../core/auth/api'

export type FeedbackStatus = 'PENDING' | 'PROCESSING' | 'RESOLVED' | 'CLOSED'
export interface MissingFeedback {
  id: string
  title: string
  artist: string
  versionLabel: string
  description: string
  submittedBy: string
  source: 'ADMIN'
  status: FeedbackStatus
  handlingNote: string
  resolvedSongId: string | null
  resolvedSongTitle: string | null
  resolvedSongArtists: string[]
  resolvedSongAvailable: boolean
  revision: number
  createdAt: string
  updatedAt: string
  resolvedAt: string | null
}
export interface FeedbackCreate {
  requestId: string
  title: string
  artist: string
  versionLabel: string
  description: string
  submittedBy: string
}
export interface FeedbackUpdate {
  revision: number
  status: FeedbackStatus
  handlingNote: string
  resolvedSongId: string | null
}
export interface FeedbackPage { items: MissingFeedback[]; page: number; size: number; totalElements: number }
const base = '/api/v1/missing-song-feedback'
export const missingFeedback = {
  list: (q = '', status = 'ALL', page = 0) => authRequest<FeedbackPage>(`${base}?${new URLSearchParams({ q, status, page: String(page), size: '20' })}`),
  get: (id: string) => authRequest<MissingFeedback>(`${base}/${encodeURIComponent(id)}`),
  create: (input: FeedbackCreate) => authRequest<MissingFeedback>(base, 'POST', input),
  update: (id: string, input: FeedbackUpdate) => authRequest<MissingFeedback>(`${base}/${encodeURIComponent(id)}`, 'PUT', input),
}

import { authRequest } from '../../../core/auth/api'
import { media } from '../../media-library/api/media'
import type { MediaSource, SongPage } from '../../media-library/types'

export interface SongFilters { sourceId: string; q: string; status: string; page: number; size?: number; tagIds?: string[]; unclassified?: boolean }
export const songs = {
  list: (filters: SongFilters) => {
    const query = new URLSearchParams({ page: String(filters.page), size: String(filters.size ?? 20), status: filters.status })
    if (filters.sourceId) query.set('sourceId', filters.sourceId)
    if (filters.q.trim()) query.set('q', filters.q.trim())
    filters.tagIds?.forEach(id => query.append('tagIds', id))
    if (filters.unclassified) query.set('unclassified', 'true')
    return authRequest<SongPage>(`/api/v1/media-songs?${query}`)
  },
  sources: async () => {
    const items: MediaSource[] = []
    for (let page = 0; ; page++) {
      const result = await media.list(page, 100)
      items.push(...result.items)
      if (!result.items.length || items.length >= result.totalElements) return items
    }
  },
}

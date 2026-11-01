export interface Artist {
  automatic?: boolean; fetchState?: string; portraitFetchState?: string; backgroundFetchState?: string; portraitOrigin?: string; backgroundOrigin?: string; portraitSource?: string; backgroundSource?: string; portraitAttribution?: string; backgroundAttribution?: string; id: string; name: string; aliases: string[]; type: 'PERSON' | 'GROUP' | 'UNKNOWN'; biography: string; active: boolean; revision: number; managed: boolean; count?: number; portraitPath?: string; portraitVersion?: string; backgroundPath?: string; backgroundVersion?: string }
export interface Credit { songId: string; title: string; credit: string; revision: number; artistId?: string; artistName?: string; artistActive?: boolean }
export interface Page<T> { items: T[]; page: number; size: number; totalElements: number }
export type ImageKind = 'portrait' | 'background'
export const artistTypes = { PERSON: '个人', GROUP: '组合 / 乐队', UNKNOWN: '待确认' }

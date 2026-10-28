import type { MediaSong } from '../media-library/types'
import type { AppliedMetadata, MusicProvider } from './types'
export const reviewFields = ['title', 'artists', 'album', 'releaseDate', 'aliases', 'durationMs', 'cover'] as const
export type ReviewField = typeof reviewFields[number]
export type Value = string | string[] | number | null
export type Values = Record<ReviewField, Value>
export interface SingleReview { song: MediaSong; fingerprint: string; version: string; values: Values }
export interface ReviewApply { requestId: string; fingerprint: string; version: string; candidateId: string | null; fields: ReviewField[]; edits: Partial<Values>; coverCandidateId: string | null; coverAssetId: string | null }
export interface ReviewResult { metadata: AppliedMetadata | null; updated: ReviewField[]; skipped: ReviewField[]; retained: ReviewField[]; before: Values; after: Values }
export interface CoverAsset { id: string; mime: string; width: number; height: number; createdAt: string }
export interface CoverChoice { candidateId: string; provider: MusicProvider; externalId: string; title: string; artists: string[]; album: string | null; coverUrl: string; width: number | null; height: number | null }
export interface CoverSelection { kind: 'candidate' | 'asset' | 'current'; id: string; value: string; url: string; label: string; width?: number | null; height?: number | null }

import type { MetadataCandidate } from './types'
/** Select a preview, never apply metadata. Equal scores retain the server's ordering. */
export function highSimilarityCandidate(candidates: MetadataCandidate[], threshold: number): MetadataCandidate | null {
  let best: MetadataCandidate | null = null
  for (const candidate of candidates) if (candidate.score >= threshold && (!best || candidate.score > best.score)) best = candidate
  return best
}
export function hasCloseAlternative(candidates: MetadataCandidate[], selected: MetadataCandidate): boolean {
  return candidates.some(candidate => candidate.id !== selected.id && selected.score - candidate.score < .08)
}

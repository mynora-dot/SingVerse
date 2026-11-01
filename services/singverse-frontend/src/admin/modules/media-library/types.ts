export type SourceType = 'LOCAL' | 'WEBDAV'
export interface MediaSource {
  id: string
  name: string
  type: SourceType
  location: string
  username: string
  parserProfileId: string
  scanEnabled?: boolean
  scanCron?: string | null
  nextScanAt?: string | null
  lastScanAt?: string | null
  lastScanStatus?: string | null
  lastScanError?: string | null
  passwordConfigured: boolean
  createdAt: string
  updatedAt: string
}
export interface SourceInput {
  scanEnabled?: boolean
  scanCron?: string
  name: string
  type: SourceType
  location: string
  username?: string
  parserProfileId?: string
  password?: string
}
export interface SourcePage { items: MediaSource[]; page: number; size: number; totalElements: number }

export interface MediaSong {
  classification?: import('../classification/types').ClassState
  id: string; sourceId: string; sourceName: string; relativePath: string; fileSize: number | null
  modifiedAt: string | null; scannedAt: string; parserProfileId: string; parserRevision: number
  selectedRuleId: string | null
  metadata?: import('../metadata/types').AppliedMetadata | null
  result: import('../filename-parser/types').RulePreview['result']
}
export interface SongPage { items: MediaSong[]; page: number; size: number; totalElements: number }
export interface ScanResult {
  sourceId: string; parserProfileId: string; parserProfileName: string; parserRevision: number
  totalSongs: number; matchedSongs: number; needsReviewSongs: number; skippedFiles: number; scannedAt: string
}

export interface ScanTask {
  id: string; sourceId: string
  status: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED' | 'INTERRUPTED'
  phase: 'SCANNING' | 'COMMITTING'
  addedSongs?: number; reusedSongs?: number; missingSongs?: number; removedSongs?: number
  directories: number; entries: number; totalSongs: number; matchedSongs: number
  needsReviewSongs: number; skippedFiles: number; cancelRequested: boolean
  error: string | null; createdAt: string; startedAt: string | null; finishedAt: string | null
}

export interface DirectoryPage { path: string; parent: string | null; items: { name: string; path: string }[]; hasMore: boolean }

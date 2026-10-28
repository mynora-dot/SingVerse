import type { MediaSong } from '../media-library/types'
export function songTitle(song: MediaSong): string {
  return song.metadata && !song.metadata.stale && song.metadata.fields.includes('title') ? song.metadata.track.title || song.result.title || song.result.originalFilename : song.result.title || song.result.originalFilename
}
export function songArtists(song: MediaSong): string[] {
  return song.metadata && !song.metadata.stale && song.metadata.fields.includes('artists') ? song.metadata.track.artists : song.result.artists
}

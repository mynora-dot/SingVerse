package library

import (
	"context"
	"strings"

	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

// SourceQuality counts the same effective fields shown in the song library.
// Extraction completion and playable-media observation are separate facts.
func (s *Service) SourceQuality(ctx context.Context, db store.DBTX, id string) (map[string]int64, error) {
	rows, err := db.QueryxContext(ctx, s.q["song-columns"]+s.q["song-quality"], id)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string]int64{"total": 0, "identityComplete": 0, "informationMissing": 0, "pendingVerification": 0, "mediaIssues": 0}
	for rows.Next() {
		song, err := readSong(rows)
		if err != nil {
			return nil, err
		}
		title := optional(song.Result.Title)
		artists := any(song.Result.Artists)
		if meta, ok := song.Metadata.(map[string]any); ok && meta["stale"] != true {
			track, _ := meta["track"].(map[string]any)
			if !projectionEmpty(track["title"]) {
				title = track["title"]
			}
			if !projectionEmpty(track["artists"]) {
				artists = track["artists"]
			}
		}
		out["total"]++
		titleText, _ := title.(string)
		if strings.TrimSpace(titleText) != "" && !projectionEmpty(artists) {
			out["identityComplete"]++
		} else {
			out["informationMissing"]++
		}
		if song.MediaVerification != nil {
			switch *song.MediaVerification {
			case "PENDING":
				out["pendingVerification"]++
			case "MISSING", "MISMATCH", "UNREACHABLE":
				out["mediaIssues"]++
			}
		}
	}
	return out, rows.Err()
}

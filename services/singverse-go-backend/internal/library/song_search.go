package library

import (
	"context"
	"strings"

	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

// searchSongs streams the established effective projection in the same transaction.
// Metadata validity depends on content fingerprints and field origins, so a raw
// JSON LIKE on saved candidates would also match stale or superseded identities.
// Only the requested page is retained; filtering and counting precede pagination.
// q remains the existing path-only SQL predicate, independent of search.
func (s *Service) searchSongs(ctx context.Context, tx store.DBTX, filter string, args []any, search string, out *Page[Song]) error {
	rows, err := tx.QueryxContext(ctx, s.q["song-columns"]+filter+s.q["song-search-order"], args...)
	if err != nil {
		return err
	}
	defer rows.Close()
	needle := strings.ToLower(search)
	offset := int64(out.Page * out.Size)
	for rows.Next() {
		song, err := readSong(rows)
		if err != nil {
			return err
		}
		if !matchesSong(song, needle) {
			continue
		}
		if out.TotalElements >= offset && len(out.Items) < out.Size {
			out.Items = append(out.Items, song)
		}
		out.TotalElements++
	}
	return rows.Err()
}

func matchesSong(song Song, needle string) bool {
	title := song.Result.OriginalFilename
	if song.Result.Title != nil && *song.Result.Title != "" {
		title = *song.Result.Title
	}
	artists := song.Result.Artists
	if metadata, ok := song.Metadata.(map[string]any); ok && metadata["stale"] == false {
		fields, _ := metadata["fields"].([]string)
		track, _ := metadata["track"].(map[string]any)
		if hasField(fields, "title") {
			switch value := track["title"].(type) {
			case string:
				if value != "" {
					title = value
				}
			case *string:
				if value != nil && *value != "" {
					title = *value
				}
			}
		}
		if hasField(fields, "artists") {
			artists = nil
			switch value := track["artists"].(type) {
			case []string:
				artists = value
			case []any:
				for _, item := range value {
					if name, ok := item.(string); ok {
						artists = append(artists, name)
					}
				}
			}
		}
	}
	if strings.Contains(strings.ToLower(song.RelativePath), needle) || strings.Contains(strings.ToLower(title), needle) {
		return true
	}
	for _, artist := range artists {
		if strings.Contains(strings.ToLower(artist), needle) {
			return true
		}
	}
	return false
}

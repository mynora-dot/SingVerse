package library

import (
	"context"

	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

// SourceOverview summarizes media source health for the admin home page.
type SourceOverview struct {
	Total        int64          `json:"total"`
	FailedTotal  int64          `json:"failedTotal"`
	NeverScanned int64          `json:"neverScanned"`
	Failed       []FailedSource `json:"failed"`
}

// FailedSource is one media source whose last scan reported an error.
type FailedSource struct {
	ID            string `json:"id"`
	Name          string `json:"name"`
	LastScanError string `json:"lastScanError"`
}

// OverviewCounts reuses the song list projection with an empty filter to count
// all present songs and those still needing parse review.
func (s *Service) OverviewCounts(ctx context.Context) (total, review int64, err error) {
	err = store.WithinTx(ctx, s.db, func(tx store.DBTX) error {
		filter := s.q["song-filter"]
		if e := tx.QueryRowContext(ctx, s.q["song-count"]+filter, overviewSongArgs(-1)...).Scan(&total); e != nil {
			return e
		}
		return tx.QueryRowContext(ctx, s.q["song-count"]+filter, overviewSongArgs(1)...).Scan(&review)
	})
	return total, review, err
}

// FormalCounts reuses the formal statistics query, returning suppressed-excluded
// totals for songs, variants and media documents.
func (s *Service) FormalCounts(ctx context.Context) (songs, variants, media int64, err error) {
	rows, err := store.Maps(ctx, s.db, s.q["formal-stats"])
	if err != nil {
		return 0, 0, 0, err
	}
	for _, row := range rows {
		kind, _ := row["entity_type"].(string)
		switch kind {
		case "SONG":
			songs = countValue(row["total"])
		case "VARIANT":
			variants = countValue(row["total"])
		case "MEDIA":
			media = countValue(row["total"])
		}
	}
	return songs, variants, media, nil
}

// RecentSongs returns the newest songs using the exact /media-songs projection.
func (s *Service) RecentSongs(ctx context.Context, n int) ([]Song, error) {
	out := []Song{}
	if n <= 0 {
		return out, nil
	}
	err := store.WithinTx(ctx, s.db, func(tx store.DBTX) error {
		args := append(overviewSongArgs(-1), n, 0)
		rows, e := tx.QueryxContext(ctx, s.q["song-columns"]+s.q["song-filter"]+s.q["song-order"], args...)
		if e != nil {
			return e
		}
		defer rows.Close()
		for rows.Next() {
			song, e := readSong(rows)
			if e != nil {
				return e
			}
			out = append(out, song)
		}
		return rows.Err()
	})
	return out, err
}

// SourceOverview counts sources, scan failures and never-scanned sources, and
// lists the most recently updated failing sources.
func (s *Service) SourceOverview(ctx context.Context) (SourceOverview, error) {
	out := SourceOverview{Failed: []FailedSource{}}
	err := store.WithinTx(ctx, s.db, func(tx store.DBTX) error {
		if e := tx.QueryRowContext(ctx, s.q["source-overview"]).Scan(&out.Total, &out.FailedTotal, &out.NeverScanned); e != nil {
			return e
		}
		rows, e := tx.QueryContext(ctx, s.q["source-overview-failed"])
		if e != nil {
			return e
		}
		defer rows.Close()
		for rows.Next() {
			var item FailedSource
			if e := rows.Scan(&item.ID, &item.Name, &item.LastScanError); e != nil {
				return e
			}
			out.Failed = append(out.Failed, item)
		}
		return rows.Err()
	})
	return out, err
}

// overviewSongArgs mirrors the empty-filter arguments used by songsHTTP: no
// source, no search text, no unclassified restriction, and the given review flag.
func overviewSongArgs(review int) []any {
	return []any{"", "", "", pattern(""), review, review, false}
}

func countValue(v any) int64 {
	switch n := v.(type) {
	case int64:
		return n
	case int:
		return int64(n)
	case float64:
		return int64(n)
	case []byte:
		return countValue(string(n))
	case string:
		var parsed int64
		for _, r := range n {
			if r < '0' || r > '9' {
				return 0
			}
			parsed = parsed*10 + int64(r-'0')
		}
		return parsed
	default:
		return 0
	}
}

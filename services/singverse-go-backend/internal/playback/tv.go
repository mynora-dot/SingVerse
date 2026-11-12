package playback

import (
	"context"
	"database/sql"
	"encoding/json"
	"time"

	"github.com/jmoiron/sqlx"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

// TVHistoryQuery is supplied by the party domain after resolving the authorized
// device's sessions. Clients cannot supply either ownership or visibility lists.
type TVHistoryQuery struct {
	SessionIDs    []string
	SongIDs       []string
	AvailableOnly bool
	LatestPerSong bool
	Page          int
	Size          int
}

type tvPopularRow struct {
	SongID             string `db:"song_id"`
	PlayCount          int64  `db:"play_count"`
	EffectivePlayCount int64  `db:"effective_play_count"`
	CompletedCount     int64  `db:"completed_count"`
	LastPlayedAt       int64  `db:"last_played_at"`
}

type tvHistoryRow struct {
	ID           string        `db:"id"`
	SongID       string        `db:"song_id"`
	Title        string        `db:"title"`
	ArtistsJSON  string        `db:"artists_json"`
	VersionLabel string        `db:"version_label"`
	SourceName   string        `db:"source_name"`
	Status       string        `db:"status"`
	StartedAt    int64         `db:"started_at"`
	EndedAt      sql.NullInt64 `db:"ended_at"`
	PlayedMS     int64         `db:"played_ms"`
	DurationMS   sql.NullInt64 `db:"duration_ms"`
	Effective    int           `db:"effective_play"`
}

// TVPopular ranks actual TV/mobile plays over the last 30 days. The current TV
// library determines visibility before counting and paging, not after paging.
func (s *Service) TVPopular(ctx context.Context, tx store.DBTX, songIDs []string, page, size int) ([]map[string]any, int64, error) {
	items := []map[string]any{}
	if len(songIDs) == 0 {
		return items, 0, nil
	}
	now := s.now()
	encodedSongs, err := json.Marshal(songIDs)
	if err != nil {
		return nil, 0, err
	}
	// A bound JSON rowset avoids one placeholder per library song and supports
	// libraries larger than SQLite's parameter limit.
	values := map[string]any{"from": now - 30*86400000, "until": now, "songIds": string(encodedSongs), "size": size, "offset": page * size}
	query, args, err := s.statement("tvPopularCount", values)
	if err != nil {
		return nil, 0, err
	}
	var total int64
	if err = tx.QueryRowContext(ctx, query, args...).Scan(&total); err != nil {
		return nil, 0, err
	}
	query, args, err = s.statement("tvPopular", values)
	if err != nil {
		return nil, 0, err
	}
	rows := []tvPopularRow{}
	if err = sqlx.SelectContext(ctx, tx, &rows, query, args...); err != nil {
		return nil, 0, err
	}
	for _, row := range rows {
		items = append(items, map[string]any{"songId": row.SongID, "playCount": row.PlayCount, "effectivePlayCount": row.EffectivePlayCount, "completedCount": row.CompletedCount, "lastPlayedAt": tvInstant(row.LastPlayedAt)})
	}
	return items, total, nil
}

// TVHistory returns terminal TV playback instances for an already authorized
// device scope. Home can request the latest instance per currently visible song.
func (s *Service) TVHistory(ctx context.Context, tx store.DBTX, filter TVHistoryQuery) ([]map[string]any, int64, error) {
	items := []map[string]any{}
	if len(filter.SessionIDs) == 0 || (filter.AvailableOnly && len(filter.SongIDs) == 0) {
		return items, 0, nil
	}
	songIDs := filter.SongIDs
	if songIDs == nil {
		songIDs = []string{}
	}
	encodedSongs, err := json.Marshal(songIDs)
	if err != nil {
		return nil, 0, err
	}
	encodedSessions, err := json.Marshal(filter.SessionIDs)
	if err != nil {
		return nil, 0, err
	}
	values := map[string]any{"until": s.now(), "sessionIds": string(encodedSessions), "songIds": string(encodedSongs), "availableOnly": filter.AvailableOnly, "latest": filter.LatestPerSong, "size": filter.Size, "offset": filter.Page * filter.Size}
	query, args, err := s.statement("tvHistoryCount", values)
	if err != nil {
		return nil, 0, err
	}
	var total int64
	if err = tx.QueryRowContext(ctx, query, args...).Scan(&total); err != nil {
		return nil, 0, err
	}
	name := "tvHistory"
	if filter.LatestPerSong {
		name = "tvHistoryLatest"
	}
	query, args, err = s.statement(name, values)
	if err != nil {
		return nil, 0, err
	}
	rows := []tvHistoryRow{}
	if err = sqlx.SelectContext(ctx, tx, &rows, query, args...); err != nil {
		return nil, 0, err
	}
	for _, row := range rows {
		artists := []string{}
		if err = json.Unmarshal([]byte(row.ArtistsJSON), &artists); err != nil {
			return nil, 0, err
		}
		var ended, duration any
		if row.EndedAt.Valid {
			ended = tvInstant(row.EndedAt.Int64)
		}
		if row.DurationMS.Valid {
			duration = row.DurationMS.Int64
		}
		items = append(items, map[string]any{"id": row.ID, "songId": row.SongID, "title": row.Title, "artists": artists, "versionLabel": row.VersionLabel, "sourceName": row.SourceName, "status": row.Status, "startedAt": tvInstant(row.StartedAt), "endedAt": ended, "playedMs": row.PlayedMS, "durationMs": duration, "effective": row.Effective == 1})
	}
	return items, total, nil
}

func tvInstant(milliseconds int64) string {
	return time.UnixMilli(milliseconds).UTC().Format(time.RFC3339Nano)
}

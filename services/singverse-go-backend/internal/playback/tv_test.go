package playback

import (
	"context"
	"fmt"
	"testing"

	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/testutil"
)

func seedTVReadHistory(t *testing.T, s *Service, id, song, client, session, status string, started, played, duration int64) {
	t.Helper()
	var ended any
	if status != "PLAYING" {
		ended = started + played
	}
	_, err := s.db.Exec(`INSERT INTO playback_history
		(id,song_id,title,artists_json,client_type,session_id,started_at,ended_at,played_ms,duration_ms,status)
		VALUES (?,?,?,'["歌手"]',?,?,?,?,?,?,?)`, id, song, "历史标题", client, session, started, ended, played, duration, status)
	if err != nil {
		t.Fatal(err)
	}
}

func TestTVPopularEffectiveWindowVisibilityAndStablePagination(t *testing.T) {
	s, now := fixture(t)
	exerciseTVPopularReads(t, s, now)
}

func exerciseTVPopularReads(t *testing.T, s *Service, now *int64) {
	t.Helper()
	ctx := context.Background()
	from := *now - 30*86400000
	seedTVReadHistory(t, s, "a-tv", "a", "TV", "living", "SKIPPED", *now-60000, 30000, 180000)
	seedTVReadHistory(t, s, "a-mobile", "a", "MOBILE", "phone", "COMPLETED", *now-70000, 19999, 19999)
	seedTVReadHistory(t, s, "a-short", "a", "TV", "other", "STOPPED", *now-80000, 29999, 180000)
	seedTVReadHistory(t, s, "admin", "b", "ADMIN", "admin", "COMPLETED", *now-40000, 180000, 180000)
	seedTVReadHistory(t, s, "boundary", "b", "TV", "other", "COMPLETED", from, 30000, 30000)
	seedTVReadHistory(t, s, "too-old", "b", "MOBILE", "phone", "COMPLETED", from-1, 30000, 30000)
	seedTVReadHistory(t, s, "c-boundary", "c", "TV", "other", "COMPLETED", from, 30000, 30000)
	seedTVReadHistory(t, s, "hidden", "hidden", "TV", "living", "COMPLETED", *now-60000, 30000, 30000)
	seedTVReadHistory(t, s, "not-complete", "short", "MOBILE", "phone", "SKIPPED", *now-60000, 19999, 19999)
	seedTVReadHistory(t, s, "future", "future", "TV", "living", "COMPLETED", *now+1, 30000, 30000)
	visible := []string{"a", "b", "c", "short", "future"}
	for page, song := range []string{"a", "b", "c"} {
		rows, total, err := s.TVPopular(ctx, s.db, visible, page, 1)
		if err != nil || total != 3 || len(rows) != 1 || text(rows[0], "songId") != song {
			t.Fatalf("page %d: rows=%v total=%d err=%v", page, rows, total, err)
		}
		if page == 0 && (n(rows[0], "effectivePlayCount") != 2 || n(rows[0], "playCount") != 3 || n(rows[0], "completedCount") != 1) {
			t.Fatal(rows)
		}
	}
	rows, total, err := s.TVPopular(ctx, s.db, visible, 3, 1)
	if err != nil || total != 3 || len(rows) != 0 {
		t.Fatal(rows, total, err)
	}
	rows, total, err = s.TVPopular(ctx, s.db, nil, 0, 20)
	if err != nil || total != 0 || len(rows) != 0 {
		t.Fatal(rows, total, err)
	}
	largeLibrary := []string{"a"}
	for index := range 35000 {
		largeLibrary = append(largeLibrary, fmt.Sprintf("unplayed-%d", index))
	}
	rows, total, err = s.TVPopular(ctx, s.db, largeLibrary, 0, 20)
	if err != nil || total != 1 || len(rows) != 1 || text(rows[0], "songId") != "a" {
		t.Fatal(rows, total, err)
	}
}

func TestTVHistoryTerminalDeviceScopeAndHomeDeduplication(t *testing.T) {
	s, now := fixture(t)
	exerciseTVHistoryReads(t, s, now)
}

func exerciseTVHistoryReads(t *testing.T, s *Service, now *int64) {
	t.Helper()
	ctx := context.Background()
	seedTVReadHistory(t, s, "a-old", "a", "TV", "prior", "COMPLETED", *now-40*86400000, 30000, 180000)
	seedTVReadHistory(t, s, "tie-a", "a", "TV", "current", "SKIPPED", *now-60000, 15000, 180000)
	seedTVReadHistory(t, s, "tie-z", "a", "TV", "current", "ERROR", *now-60000, 5000, 180000)
	seedTVReadHistory(t, s, "missing", "gone", "TV", "prior", "INTERRUPTED", *now-61000, 0, 180000)
	seedTVReadHistory(t, s, "active", "b", "TV", "current", "PLAYING", *now-50000, 30000, 180000)
	seedTVReadHistory(t, s, "other-device", "b", "TV", "other", "COMPLETED", *now-50000, 30000, 180000)
	seedTVReadHistory(t, s, "mobile", "b", "MOBILE", "current", "COMPLETED", *now-50000, 30000, 180000)
	seedTVReadHistory(t, s, "admin", "b", "ADMIN", "current", "COMPLETED", *now-50000, 30000, 180000)
	seedTVReadHistory(t, s, "future", "b", "TV", "current", "COMPLETED", *now+1, 30000, 180000)
	filter := TVHistoryQuery{SessionIDs: []string{"prior", "current"}, Size: 2}
	rows, total, err := s.TVHistory(ctx, s.db, filter)
	if err != nil || total != 4 || len(rows) != 2 || text(rows[0], "id") != "tie-z" || text(rows[1], "id") != "tie-a" {
		t.Fatal(rows, total, err)
	}
	filter.Page = 1
	rows, total, err = s.TVHistory(ctx, s.db, filter)
	if err != nil || total != 4 || len(rows) != 2 || text(rows[0], "songId") != "gone" || text(rows[1], "id") != "a-old" || !rows[1]["effective"].(bool) {
		t.Fatal(rows, total, err)
	}
	filter.Page, filter.Size, filter.AvailableOnly, filter.LatestPerSong, filter.SongIDs = 0, 8, true, true, []string{"a", "b"}
	rows, total, err = s.TVHistory(ctx, s.db, filter)
	if err != nil || total != 1 || len(rows) != 1 || text(rows[0], "id") != "tie-z" || text(rows[0], "status") != "ERROR" {
		t.Fatal(rows, total, err)
	}
	filter.SongIDs = nil
	rows, total, err = s.TVHistory(ctx, s.db, filter)
	if err != nil || total != 0 || len(rows) != 0 {
		t.Fatal(rows, total, err)
	}
	filter.SessionIDs, filter.AvailableOnly = nil, false
	rows, total, err = s.TVHistory(ctx, s.db, filter)
	if err != nil || total != 0 || len(rows) != 0 {
		t.Fatal(rows, total, err)
	}
	filter.SessionIDs = []string{"prior"}
	for index := range 1200 {
		filter.SessionIDs = append(filter.SessionIDs, fmt.Sprintf("session-%d", index))
	}
	rows, total, err = s.TVHistory(ctx, s.db, filter)
	if err != nil || total != 2 || len(rows) != 2 {
		t.Fatal(rows, total, err)
	}
}

func TestMySQLTVPlaybackReads(t *testing.T) {
	for name, exercise := range map[string]func(*testing.T, *Service, *int64){"popular": exerciseTVPopularReads, "history": exerciseTVHistoryReads} {
		t.Run(name, func(t *testing.T) {
			db, _ := testutil.MySQLDatabase(t)
			service, err := New(db, "mysql", nil, nil)
			if err != nil {
				t.Fatal(err)
			}
			service.cancel()
			service.wg.Wait()
			t.Cleanup(func() { service.Close() })
			now := int64(2000000000000)
			service.now = func() int64 { return now }
			exercise(t, service, &now)
		})
	}
}

package playback

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"sync"
	"testing"

	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/testutil"
)

func fixture(t *testing.T) (*Service, *int64) {
	t.Helper()
	s, e := New(testutil.Database(t), "sqlite", func(ctx context.Context, tx store.DBTX, id string) (map[string]any, error) {
		if id == "missing" {
			return nil, errors.New("missing")
		}
		return map[string]any{"title": "中国", "artists": []string{"歌手"}, "sourceName": "本地", "versionType": "ORIGINAL"}, nil
	}, slog.New(slog.NewTextHandler(io.Discard, nil)))
	if e != nil {
		t.Fatal(e)
	}
	s.cancel()
	s.wg.Wait()
	now := int64(2000000000000)
	s.now = func() int64 { return now }
	return s, &now
}
func event(seq, actual, elapsed int64, state, token string) map[string]any {
	return map[string]any{"eventSeq": seq, "actualPlayedMs": actual, "elapsedMs": elapsed, "state": state, "writeToken": token}
}
func TestPlaybackClocksAndIdempotency(t *testing.T) {
	s, now := fixture(t)
	ctx := context.Background()
	started := *now - 60000
	r, e := s.Create(ctx, "MOBILE", "owner", "song", "手机", &started)
	if e != nil {
		t.Fatal(e)
	}
	id, token := text(r, "attemptId"), text(r, "writeToken")
	first, e := s.Report(ctx, "MOBILE", "owner", id, event(0, 30000, 30000, "PLAYING", token))
	if e != nil || !first["effective"].(bool) {
		t.Fatal(first, e)
	}
	again, e := s.Report(ctx, "MOBILE", "owner", id, event(0, 99999, 99999, "ERROR", token))
	if e != nil || text(again, "state") != "PLAYING" {
		t.Fatal(again, e)
	}
	if _, e = s.Report(ctx, "MOBILE", "other", id, event(1, 31000, 31000, "PLAYING", token)); e == nil {
		t.Fatal("cross-session report allowed")
	}
	if _, e = s.Report(ctx, "MOBILE", "owner", id, event(1, 10000, 40000, "PLAYING", token)); e == nil {
		t.Fatal("clock rollback allowed")
	}
	if _, e = s.Report(ctx, "MOBILE", "owner", id, event(1, 60000, 31000, "PLAYING", token)); e == nil {
		t.Fatal("seek counted as play")
	}
	r, e = s.Report(ctx, "MOBILE", "owner", id, event(1, 45000, 60000, "COMPLETED", token))
	if e != nil || text(r, "state") != "COMPLETED" {
		t.Fatal(r, e)
	}
	var heat float64
	var total int
	var played int64
	if e = s.db.QueryRow("SELECT heat FROM song_playback_profile WHERE song_id='song'").Scan(&heat); e != nil || heat != .05 {
		t.Fatal(heat, e)
	}
	if e = s.db.QueryRow("SELECT COUNT(*),SUM(played_ms) FROM playback_history").Scan(&total, &played); e != nil || total != 1 || played != 45000 {
		t.Fatal(total, played, e)
	}
}
func TestTVUsesCallerTransactionAndRollsBack(t *testing.T) {
	s, _ := fixture(t)
	ctx := context.Background()
	sentinel := errors.New("rollback")
	e := store.WithinTx(ctx, s.db, func(tx store.DBTX) error {
		if e := s.StartTV(ctx, tx, "tv1", "session", "song", "电视", "亲友"); e != nil {
			return e
		}
		if e := s.ReportTV(ctx, tx, "session", "tv1", map[string]any{"eventSeq": int64(0), "state": "PLAYING"}); e != nil {
			return e
		}
		return sentinel
	})
	if !errors.Is(e, sentinel) {
		t.Fatal(e)
	}
	var count int
	if e = s.db.QueryRow("SELECT COUNT(*) FROM playback_attempt").Scan(&count); e != nil || count != 0 {
		t.Fatal(count, e)
	}
	if e = s.db.QueryRow("SELECT COUNT(*) FROM playback_history").Scan(&count); e != nil || count != 0 {
		t.Fatal(count, e)
	}
}
func TestLeaseExpiresWithoutInventingTime(t *testing.T) {
	s, now := fixture(t)
	ctx := context.Background()
	r, e := s.Create(ctx, "ADMIN", "owner", "song", "", nil)
	if e != nil {
		t.Fatal(e)
	}
	id, token := text(r, "attemptId"), text(r, "writeToken")
	if _, e = s.Report(ctx, "ADMIN", "owner", id, event(0, 0, 0, "PLAYING", token)); e != nil {
		t.Fatal(e)
	}
	*now += leaseMS + 1
	if e = s.Maintain(ctx); e != nil {
		t.Fatal(e)
	}
	_, e = s.Report(ctx, "ADMIN", "owner", id, event(1, 30000, 30000, "PLAYING", token))
	var f *failure
	if !errors.As(e, &f) || f.Status != 410 {
		t.Fatal(e)
	}
	var state string
	var played int64
	if e = s.db.QueryRow("SELECT status,played_ms FROM playback_history").Scan(&state, &played); e != nil || state != "INTERRUPTED" || played != 0 {
		t.Fatal(state, played, e)
	}
}
func TestConcurrentEventsAggregateOnlyOnce(t *testing.T) {
	s, now := fixture(t)
	ctx := context.Background()
	start := *now - 60000
	r, e := s.Create(ctx, "MOBILE", "owner", "song", "", &start)
	if e != nil {
		t.Fatal(e)
	}
	var wg sync.WaitGroup
	for i := range 12 {
		wg.Add(1)
		go func(seq int) {
			defer wg.Done()
			_, e := s.Report(ctx, "MOBILE", "owner", text(r, "attemptId"), event(int64(seq), 30000, 30000, "PLAYING", text(r, "writeToken")))
			if e != nil {
				t.Error(e)
			}
		}(i)
	}
	wg.Wait()
	var heat float64
	if e = s.db.QueryRow("SELECT heat FROM song_playback_profile WHERE song_id='song'").Scan(&heat); e != nil || heat != .05 {
		t.Fatal(heat, e)
	}
}
func TestCounterPreservesIntegerRange(t *testing.T) {
	v, e := counter(map[string]any{"eventSeq": json.Number("9223372036854775807")}, "eventSeq")
	if e != nil || v != 9223372036854775807 {
		t.Fatal(v, e)
	}
	for _, bad := range []any{json.Number("1.5"), json.Number("9223372036854775808"), float64(1), nil} {
		if _, e := counter(map[string]any{"n": bad}, "n"); e == nil {
			t.Fatal(fmt.Sprint(bad))
		}
	}
}

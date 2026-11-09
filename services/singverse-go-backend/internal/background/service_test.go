package background

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"regexp"
	"strings"
	"testing"
	"time"

	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/testutil"
)

func fixture(t *testing.T) *Service {
	t.Helper()
	s, e := New(testutil.Database(t), "sqlite", slog.New(slog.NewTextHandler(io.Discard, nil)))
	if e != nil {
		t.Fatal(e)
	}
	s.cancel()
	s.wg.Wait()
	return s
}
func seedBatch(t *testing.T, s *Service) {
	t.Helper()
	at := time.Now().UTC().Format(time.RFC3339Nano)
	_, e := s.db.Exec(`INSERT INTO metadata_batch(id,mode,status,providers_json,auto_apply,threshold,created_at,updated_at) VALUES('batch','MISSING','RUNNING','[]',0,0.9,?,?)`, at, at)
	if e != nil {
		t.Fatal(e)
	}
}
func TestProjectionCommitsWithBusinessMutation(t *testing.T) {
	s := fixture(t)
	ctx := context.Background()
	seedBatch(t, s)
	want := errors.New("rollback")
	e := store.WithinTx(ctx, s.db, func(tx store.DBTX) error {
		if e := s.Project(ctx, tx, "METADATA", "batch"); e != nil {
			return e
		}
		return want
	})
	if !errors.Is(e, want) {
		t.Fatal(e)
	}
	var n int
	if e = s.db.QueryRow("SELECT COUNT(*) FROM background_task").Scan(&n); e != nil || n != 0 {
		t.Fatal(n, e)
	}
	e = store.WithinTx(ctx, s.db, func(tx store.DBTX) error { return s.Project(ctx, tx, "METADATA", "batch") })
	if e != nil {
		t.Fatal(e)
	}
	r, e := s.row(ctx, s.db, "find", doc{"id": ID("METADATA", "batch")})
	if e != nil || text(r, "status") != "RUNNING" || number(r, "is_batch") != 1 || text(r, "batch_kind") != "OPERATION" {
		t.Fatal(r, e)
	}
	firstUpdated := r["updated_at"]
	time.Sleep(2 * time.Millisecond)
	if e = store.WithinTx(ctx, s.db, func(tx store.DBTX) error { return s.Project(ctx, tx, "METADATA", "batch") }); e != nil {
		t.Fatal(e)
	}
	r, e = s.row(ctx, s.db, "find", doc{"id": ID("METADATA", "batch")})
	if e != nil || r["updated_at"] != firstUpdated {
		t.Fatal("poll invented progress", r, e)
	}
}
func TestSourceDeletionExpiresTaskAndPreservesSnapshot(t *testing.T) {
	s := fixture(t)
	ctx := context.Background()
	seedBatch(t, s)
	for i := range 2 {
		e := store.WithinTx(ctx, s.db, func(tx store.DBTX) error {
			if i == 1 {
				if _, e := tx.ExecContext(ctx, "DELETE FROM metadata_batch WHERE id='batch'"); e != nil {
					return e
				}
			}
			return s.Project(ctx, tx, "METADATA", "batch")
		})
		if e != nil {
			t.Fatal(e)
		}
	}
	r, e := s.row(ctx, s.db, "find", doc{"id": ID("METADATA", "batch")})
	if e != nil || text(r, "status") != "EXPIRED" || r["expires_at"] == nil {
		t.Fatal(r, e)
	}
}
func TestAllEmbeddedSQLiteQueriesParse(t *testing.T) {
	s := fixture(t)
	params := regexp.MustCompile(`@\{([^}]+)\}`)
	for name, q := range s.q {
		if !strings.HasSuffix(name, "-sqlite") {
			continue
		}
		t.Run(name, func(t *testing.T) {
			v := doc{}
			for _, m := range params.FindAllStringSubmatch(q, -1) {
				v[m[1]] = nil
			}
			statement, args, e := store.Bind(q, v)
			if e != nil {
				t.Fatal(e)
			}
			rows, e := s.db.Query("EXPLAIN "+statement, args...)
			if e != nil {
				t.Fatal(e)
			}
			rows.Close()
		})
	}
}
func TestSanitizesTaskDiagnostics(t *testing.T) {
	value := Message("请求 https://user:secret@example.test/song?token=abc Bearer sensitive password=hidden /private/songs/file.mp4 10.0.0.2:8000 sk-abcXYZ")
	for _, secret := range []string{"example.test", "sensitive", "hidden", "/private/songs", "10.0.0.2", "sk-abcXYZ"} {
		if strings.Contains(value, secret) {
			t.Fatal(value)
		}
	}
	result := execution(doc{"stage": "INFERENCE", "token": "never", "progress": doc{"frames": int64(100), "completedFrames": int64(200)}, "timingsMs": doc{"total": int64(200), "secret": "no"}})
	if _, ok := result["token"]; ok || number(object(result, "progress"), "completedFrames") != 100 {
		t.Fatal(result)
	}
}

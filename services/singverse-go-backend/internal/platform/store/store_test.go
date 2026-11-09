package store

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"testing"

	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/config"
)

func cfg(t *testing.T) config.Config {
	t.Helper()
	p := filepath.Join(t.TempDir(), "database ?# special.sqlite")
	c, e := config.Parse(func(k string) string {
		if k == "SQLITE_PATH" {
			return p
		}
		return ""
	})
	if e != nil {
		t.Fatal(e)
	}
	return c
}
func TestDeploymentLockAndRollback(t *testing.T) {
	ctx := context.Background()
	c := cfg(t)
	db, e := Open(ctx, c)
	if e != nil {
		t.Fatal(e)
	}
	defer db.Close()
	if _, e = Open(ctx, c); e == nil {
		t.Fatal("second process entered deployment")
	}
	if _, e = os.Stat(c.SQLitePath); e != nil {
		t.Fatal("database path was interpreted as URL", e)
	}
	var foreign int
	if e = db.QueryRow("PRAGMA foreign_keys").Scan(&foreign); e != nil || foreign != 1 {
		t.Fatal(foreign, e)
	}
	want := errors.New("rollback")
	e = WithinTx(ctx, db.DB, func(tx DBTX) error {
		if _, e := tx.ExecContext(ctx, "INSERT INTO song_playback_profile(song_id) VALUES('rollback')"); e != nil {
			return e
		}
		return want
	})
	if !errors.Is(e, want) {
		t.Fatal(e)
	}
	var n int
	if e = db.QueryRow("SELECT COUNT(*) FROM song_playback_profile WHERE song_id='rollback'").Scan(&n); e != nil || n != 0 {
		t.Fatal(n, e)
	}
}
func TestMigrationFailureReleasesLock(t *testing.T) {
	c := cfg(t)
	c.DBMateBin = "/nonexistent/dbmate"
	if _, e := Open(context.Background(), c); e == nil {
		t.Fatal("migration failure ignored")
	}
	c.DBMateBin = "/usr/bin/false"
	if _, e := Open(context.Background(), c); e == nil || e.Error() == "backend is already running; stop it before migration or recovery" {
		t.Fatal(e)
	}
}
func TestNamedValuesAndClosedRows(t *testing.T) {
	q, a, e := Bind("SELECT @{value}, @{value}", map[string]any{"value": "' OR 1=1"})
	if e != nil || q != "SELECT ?, ?" || len(a) != 2 || a[0] != "' OR 1=1" {
		t.Fatal(q, a, e)
	}
	if _, _, e = Bind("SELECT @{missing}", nil); e == nil {
		t.Fatal("missing SQL parameter accepted")
	}
}

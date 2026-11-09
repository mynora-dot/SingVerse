// Package testutil creates disposable databases through the production migration path.
package testutil

import (
	"context"
	"github.com/jmoiron/sqlx"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/config"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
	"path/filepath"
	"testing"
)

func Database(t *testing.T) *sqlx.DB {
	t.Helper()
	dir := t.TempDir()
	cfg, err := config.Parse(func(k string) string {
		if k == "SQLITE_PATH" {
			return filepath.Join(dir, "business.db")
		}
		return ""
	})
	if err != nil {
		t.Fatal(err)
	}
	db, err := store.Open(context.Background(), cfg)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { db.Close() })
	return db.DB
}

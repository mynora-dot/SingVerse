package store_test

import (
	"context"
	"path/filepath"
	"testing"

	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/testutil"
)

func TestMySQLDeploymentLockAcrossDifferentLocalFiles(t *testing.T) {
	db, cfg := testutil.MySQLDatabase(t)
	if max := db.Stats().MaxOpenConnections; max != 5 {
		t.Fatalf("MySQL connection cap = %d", max)
	}
	// Simulate a second host: its local lock file cannot see the first one.
	cfg.LockFile = filepath.Join(t.TempDir(), "other-host.lock")
	if other, err := store.Open(context.Background(), cfg); err == nil {
		other.Close()
		t.Fatal("another host acquired the same business database")
	}
	if err := db.PingContext(context.Background()); err != nil {
		t.Fatal("rejected instance disrupted the active database", err)
	}
}

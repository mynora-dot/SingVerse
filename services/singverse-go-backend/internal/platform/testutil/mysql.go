package testutil

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"net"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/go-sql-driver/mysql"
	"github.com/jmoiron/sqlx"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/config"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

// MySQLDatabase creates and removes only its own random test database. The
// dedicated script supplies credentials for a disposable, loopback-only server.
func MySQLDatabase(t *testing.T) (*sqlx.DB, config.Config) {
	t.Helper()
	port := os.Getenv("SINGVERSE_TEST_MYSQL_PORT")
	if port == "" {
		t.Skip("run make go-mysql-check for a real isolated MySQL database")
	}
	host := os.Getenv("SINGVERSE_TEST_MYSQL_HOST")
	if host == "" {
		host = "127.0.0.1"
	}
	ip := net.ParseIP(host)
	if ip == nil || !ip.IsLoopback() {
		t.Fatal("test MySQL must bind a loopback IP")
	}
	password := os.Getenv("SINGVERSE_TEST_MYSQL_PASSWORD")
	if password == "" {
		t.Fatal("isolated MySQL credentials missing")
	}
	var random [12]byte
	if _, e := rand.Read(random[:]); e != nil {
		t.Fatal(e)
	}
	name := "singverse_go_test_" + hex.EncodeToString(random[:])
	mc := mysql.NewConfig()
	mc.User = "root"
	mc.Passwd = password
	mc.Net = "tcp"
	mc.Addr = net.JoinHostPort(host, port)
	mc.Timeout = 5 * time.Second
	admin, e := sqlx.Open("mysql", mc.FormatDSN())
	if e != nil {
		t.Fatal("open isolated MySQL admin")
	}
	t.Cleanup(func() { admin.Close() })
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	// The identifier consists entirely of the fixed prefix and random hex above.
	if _, e = admin.ExecContext(ctx, "CREATE DATABASE `"+name+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"); e != nil {
		t.Fatal(e)
	}
	t.Cleanup(func() {
		ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
		defer cancel()
		if _, e := admin.ExecContext(ctx, "DROP DATABASE `"+name+"`"); e != nil {
			t.Error("remove isolated MySQL database", e)
		}
	})
	dir := t.TempDir()
	values := map[string]string{"SINGVERSE_DB_DRIVER": "mysql", "MYSQL_HOST": host, "MYSQL_PORT": port, "MYSQL_DATABASE": name, "MYSQL_USER": "root", "MYSQL_PASSWORD": password, "SINGVERSE_AUTH_KEY_FILE": filepath.Join(dir, "master.key"), "WEBDAV_METADATA_PATH": filepath.Join(dir, "metadata-files"), "METADATA_CACHE_PATH": filepath.Join(dir, "covers"), "TRANSCODE_VERSION_PATH": filepath.Join(dir, "transcode"), "AUDIO_DERIVED_PATH": filepath.Join(dir, "audio"), "SINGVERSE_SONG_CACHE_PATH": filepath.Join(dir, "cache")}
	cfg, e := config.Parse(func(k string) string { return values[k] })
	if e != nil {
		t.Fatal(e)
	}
	cfg.LockFile = filepath.Join(dir, "mysql.auth.lock")
	db, e := store.Open(ctx, cfg)
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(func() { db.Close() })
	return db.DB, cfg
}

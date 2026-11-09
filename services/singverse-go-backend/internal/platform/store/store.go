// Package store owns connections, deployment locks, and explicit transactions.
package store

import (
	"context"
	"crypto/sha256"
	"database/sql"
	_ "embed"
	"errors"
	"fmt"
	"io/fs"
	"net"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"

	"github.com/go-sql-driver/mysql"
	"github.com/jmoiron/sqlx"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/config"
	"golang.org/x/sys/unix"
	_ "modernc.org/sqlite"
)

type DBTX interface {
	sqlx.ExtContext
	QueryRowContext(context.Context, string, ...any) *sql.Row
}

var (
	_ DBTX = (*sqlx.DB)(nil)
	_ DBTX = (*sqlx.Tx)(nil)
)

type Database struct {
	*sqlx.DB
	lock         *os.File
	databaseLock *sql.Conn
}

//go:embed sql/deployment-lock-mysql.sql
var deploymentLockSQL string

//go:embed sql/deployment-unlock-mysql.sql
var deploymentUnlockSQL string

func Open(ctx context.Context, c config.Config) (result *Database, err error) {
	if err = os.MkdirAll(filepath.Dir(c.LockFile), 0700); err != nil {
		return nil, errors.New("cannot create database directory")
	}
	lock, err := os.OpenFile(c.LockFile, os.O_CREATE|os.O_RDWR, 0600)
	if err != nil {
		return nil, errors.New("cannot open deployment lock")
	}
	if err = unix.Flock(int(lock.Fd()), unix.LOCK_EX|unix.LOCK_NB); err != nil {
		lock.Close()
		return nil, errors.New("backend is already running; stop it before migration or recovery")
	}
	var db *sqlx.DB
	var databaseLock *sql.Conn
	defer func() {
		if err != nil {
			if databaseLock != nil {
				databaseLock.Close()
			}
			if db != nil {
				db.Close()
			}
			unix.Flock(int(lock.Fd()), unix.LOCK_UN)
			lock.Close()
		}
	}()
	var dsn, migrationURL, migrationDirectory string
	driver := c.Driver
	if c.Driver == "sqlite" {
		path := filepath.ToSlash(c.SQLitePath)
		dsn = (&url.URL{Scheme: "file", Path: path, RawQuery: "_pragma=foreign_keys(1)&_pragma=busy_timeout(5000)"}).String()
		// dbmate unescapes SQLite URLs before the SQLite driver parses its DSN.
		// Run it through a private, fixed-name symlink so reserved filename
		// characters cannot become driver options. Only the alias is temporary.
		file, openErr := os.OpenFile(c.SQLitePath, os.O_CREATE|os.O_RDWR, 0600)
		if openErr != nil {
			return nil, errors.New("cannot open SQLite database")
		}
		if openErr = file.Close(); openErr != nil {
			return nil, openErr
		}
		migrationDirectory, err = os.MkdirTemp("", "singverse-migrate-")
		if err != nil {
			return nil, err
		}
		defer os.RemoveAll(migrationDirectory)
		if err = os.Symlink(c.SQLitePath, filepath.Join(migrationDirectory, "database.sqlite")); err != nil {
			return nil, err
		}
		migrationURL = "sqlite:database.sqlite"
	} else {
		mc := mysql.NewConfig()
		mc.User = c.MySQLUser
		mc.Passwd = c.MySQLPassword
		mc.Net = "tcp"
		mc.Addr = net.JoinHostPort(c.MySQLHost, fmt.Sprint(c.MySQLPort))
		mc.DBName = c.MySQLDatabase
		mc.Timeout = 5 * time.Second
		mc.ReadTimeout = 5 * time.Second
		mc.WriteTimeout = 5 * time.Second
		mc.Loc = time.UTC
		mc.ParseTime = true
		dsn = mc.FormatDSN()
		migrationURL = (&url.URL{Scheme: "mysql", User: url.UserPassword(c.MySQLUser, c.MySQLPassword), Host: mc.Addr, Path: "/" + c.MySQLDatabase}).String()
		// Hold a server-side session lock across migration and the lifetime of
		// the service. A local file lock alone cannot exclude another host.
		db, err = sqlx.Open(driver, dsn)
		if err != nil {
			return nil, errors.New("database driver initialization failed")
		}
		db.SetMaxOpenConns(5)
		db.SetMaxIdleConns(5)
		db.SetConnMaxLifetime(5 * time.Minute)
		lockCtx, lockCancel := context.WithTimeout(ctx, 5*time.Second)
		defer lockCancel()
		databaseLock, err = db.Conn(lockCtx)
		if err != nil {
			return nil, errors.New("database connection failed")
		}
		key := fmt.Sprintf("%x", sha256.Sum256([]byte("singverse-business:"+c.MySQLDatabase)))
		var acquired sql.NullInt64
		if err = databaseLock.QueryRowContext(lockCtx, deploymentLockSQL, key).Scan(&acquired); err != nil || !acquired.Valid || acquired.Int64 != 1 {
			return nil, errors.New("database is already in use or deployment lock could not be acquired")
		}
	}
	migrationCtx, cancel := context.WithTimeout(ctx, 60*time.Second)
	defer cancel()
	command := exec.CommandContext(migrationCtx, c.DBMateBin, "--env-file", "/dev/null", "--no-dump-schema", "--migrations-dir", filepath.Join(c.MigrationsDir, c.Driver), "migrate")
	command.Dir = migrationDirectory
	command.Env = append(os.Environ(), "DATABASE_URL="+migrationURL)
	if err = command.Run(); err != nil {
		return nil, errors.New("database migration failed; check access and dbmate installation")
	}
	if db == nil {
		db, err = sqlx.Open(driver, dsn)
		if err != nil {
			return nil, errors.New("database driver initialization failed")
		}
		db.SetMaxOpenConns(1)
		db.SetMaxIdleConns(1)
	}
	probeCtx, probeCancel := context.WithTimeout(ctx, 5*time.Second)
	defer probeCancel()
	if err = db.PingContext(probeCtx); err != nil {
		db.Close()
		return nil, errors.New("database connection failed")
	}
	return &Database{DB: db, lock: lock, databaseLock: databaseLock}, nil
}
func (d *Database) Close() error {
	if d.databaseLock != nil {
		ctx, cancel := context.WithTimeout(context.Background(), time.Second)
		// Closing the dedicated session also releases the lock when the server
		// is unavailable, so shutdown cannot retain deployment ownership.
		_, _ = d.databaseLock.ExecContext(ctx, deploymentUnlockSQL)
		cancel()
		d.databaseLock.Close()
	}
	err := d.DB.Close()
	unix.Flock(int(d.lock.Fd()), unix.LOCK_UN)
	d.lock.Close()
	return err
}

func WithinTx(ctx context.Context, db *sqlx.DB, fn func(DBTX) error) error {
	tx, err := db.BeginTxx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if err = fn(tx); err != nil {
		return err
	}
	return tx.Commit()
}

// ReadQueries loads one parameterized statement per .sql file, named by basename.
func ReadQueries(files fs.FS, dir string) (map[string]string, error) {
	queries := make(map[string]string)
	err := fs.WalkDir(files, dir, func(path string, e fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if e.IsDir() || !strings.HasSuffix(path, ".sql") {
			return nil
		}
		data, err := fs.ReadFile(files, path)
		if err != nil {
			return err
		}
		name := strings.TrimSuffix(filepath.Base(path), ".sql")
		if _, exists := queries[name]; exists {
			return fmt.Errorf("duplicate query %s", name)
		}
		queries[name] = string(data)
		return nil
	})
	return queries, err
}

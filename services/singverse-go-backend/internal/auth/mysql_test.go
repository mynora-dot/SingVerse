package auth

import (
	"context"
	"io"
	"log/slog"
	"testing"
	"time"

	"github.com/jmoiron/sqlx"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/testutil"
)

func TestMySQLFactorCASAcrossIndependentServices(t *testing.T) {
	db, cfg := testutil.MySQLDatabase(t)
	f := newDatabaseFixture(t, db, "mysql", cfg)
	f.login(t, "admin", "admin")
	r, setup := f.request(t, "POST", "/api/v1/system/parameters/mfa/setup", map[string]string{"password": "admin"}, true)
	expect(t, r, 200)
	secret := setup["secret"].(string)
	code, err := totp(secret, f.now/30000, 6)
	if err != nil {
		t.Fatal(err)
	}
	r, confirmed := f.request(t, "POST", "/api/v1/system/parameters/mfa/confirm", map[string]string{"code": code}, true)
	expect(t, r, 200)
	peer, err := New(db, "mysql", cfg, slog.New(slog.NewTextHandler(io.Discard, nil)))
	if err != nil {
		t.Fatal(err)
	}
	peer.now = func() int64 { return f.now }
	services := []*Service{f.service, peer}
	f.now += 30000
	code, err = totp(secret, f.now/30000, 6)
	if err != nil {
		t.Fatal(err)
	}
	concurrentFactor(t, services, code)
	current, err := readAccount(context.Background(), db)
	if err != nil || current.LastStep != f.now/30000 {
		t.Fatalf("TOTP step was not persisted: %+v, %v", current, err)
	}
	recovery := confirmed["recoveryCodes"].([]any)[0].(string)
	concurrentFactor(t, services, recovery)
	var remaining int
	if err = db.QueryRow("SELECT COUNT(*) FROM auth_recovery").Scan(&remaining); err != nil || remaining != 9 {
		t.Fatalf("recovery inventory = %d, error=%v", remaining, err)
	}
	// A previously read factor cannot authenticate after administrator recovery
	// increments the revision and revokes all prior authentication state.
	stale := current
	f.now += 30000
	code, err = totp(secret, f.now/30000, 6)
	if err != nil {
		t.Fatal(err)
	}
	if err = f.service.ResetAdmin(context.Background(), "MySQL local recovery password"); err != nil {
		t.Fatal(err)
	}
	accepted, err := peer.transaction(context.Background(), func(tx *sqlx.Tx) (any, error) {
		return peer.verifyFactor(context.Background(), tx, code, stale)
	})
	if err != nil || accepted != false {
		t.Fatalf("stale revision authenticated after reset: accepted=%v, error=%v", accepted, err)
	}
	f.login(t, "admin", "MySQL local recovery password")
}

// Both transactions read the same account before either consumes the factor.
// Separate Service instances avoid the process mutex and exercise InnoDB's
// conditional UPDATE/DELETE behavior using independent pooled connections.
func concurrentFactor(t *testing.T, services []*Service, code string) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	ready := make(chan error, len(services))
	release := make(chan struct{})
	type result struct {
		value any
		err   error
	}
	results := make(chan result, len(services))
	for _, service := range services {
		go func() {
			value, err := service.transaction(ctx, func(tx *sqlx.Tx) (any, error) {
				a, err := readAccount(ctx, tx)
				ready <- err
				if err != nil {
					return nil, err
				}
				select {
				case <-release:
				case <-ctx.Done():
					return nil, ctx.Err()
				}
				return service.verifyFactor(ctx, tx, code, a)
			})
			results <- result{value, err}
		}()
	}
	for range services {
		select {
		case err := <-ready:
			if err != nil {
				t.Fatal(err)
			}
		case <-ctx.Done():
			t.Fatal("factor transactions did not reach the shared read barrier")
		}
	}
	close(release)
	accepted := 0
	for range services {
		r := <-results
		if r.err != nil {
			t.Fatal(r.err)
		}
		if r.value == true {
			accepted++
		}
	}
	if accepted != 1 {
		t.Fatalf("factor authenticated %d concurrent transactions; want exactly one", accepted)
	}
}

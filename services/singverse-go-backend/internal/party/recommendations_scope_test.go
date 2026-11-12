package party

import (
	"context"
	"fmt"
	"testing"

	"github.com/jmoiron/sqlx"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/testutil"
)

func TestTVDeviceSessionScopeHasNoAggregateListCap(t *testing.T) {
	exerciseTVDeviceSessionScope(t, testutil.Database(t), "sqlite")
}

func TestMySQLTVDeviceSessionScope(t *testing.T) {
	db, _ := testutil.MySQLDatabase(t)
	exerciseTVDeviceSessionScope(t, db, "mysql")
}

func exerciseTVDeviceSessionScope(t *testing.T, db *sqlx.DB, dialect string) {
	t.Helper()
	queries, err := store.ReadQueries(queryFiles, "sql")
	if err != nil {
		t.Fatal(err)
	}
	service := &Service{db: db, dialect: dialect, q: queries}
	ctx := context.Background()
	err = store.WithinTx(ctx, db, func(tx store.DBTX) error {
		for index := range 1201 {
			if _, err := tx.ExecContext(ctx, queries["insert"], fmt.Sprintf("owned-session-%04d", index), "session", nil, encode(document{"deviceId": "authorized-device"})); err != nil {
				return err
			}
		}
		for _, record := range []document{{"id": "foreign-session", "kind": "session", "deviceId": "other-device"}, {"id": "same-device-non-session", "kind": "device", "deviceId": "authorized-device"}} {
			if _, err := tx.ExecContext(ctx, queries["insert"], record["id"], record["kind"], nil, encode(record)); err != nil {
				return err
			}
		}
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	ids, err := service.deviceSessions(ctx, db, document{"id": "authorized-device", "kind": "device"})
	if err != nil || len(ids) != 1201 || ids[0] != "owned-session-0000" || ids[len(ids)-1] != "owned-session-1200" {
		t.Fatal(len(ids), err)
	}
	if _, err = service.deviceSessions(ctx, db, document{"id": "authorized-device", "kind": "participant"}); err == nil {
		t.Fatal("participant accepted as a device")
	}
}

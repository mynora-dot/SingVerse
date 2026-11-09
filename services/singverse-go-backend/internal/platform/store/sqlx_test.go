package store_test

import (
	"bytes"
	"context"
	"database/sql"
	"errors"
	"reflect"
	"strings"
	"testing"
	"time"

	"github.com/jmoiron/sqlx"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/testutil"
)

func TestSQLXMappedQueriesShareTransaction(t *testing.T) {
	testMappedQueries(t, testutil.Database(t))
}

func TestMySQLSQLXMappedQueriesShareTransaction(t *testing.T) {
	db, _ := testutil.MySQLDatabase(t)
	// One connection is held by the deployment lock; only one is left for SQL.
	db.SetMaxOpenConns(2)
	testMappedQueries(t, db)
}

func testMappedQueries(t *testing.T, db *sqlx.DB) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if _, err := db.ExecContext(ctx, "CREATE TABLE sqlx_rows(id INTEGER PRIMARY KEY,label VARCHAR(100) NOT NULL,optional VARCHAR(100),payload BLOB)"); err != nil {
		t.Fatal(err)
	}
	type storedRow struct {
		ID       int64   `db:"id"`
		Label    string  `db:"label"`
		Optional *string `db:"optional"`
		Payload  []byte  `db:"payload"`
	}
	want := storedRow{ID: 1, Label: "中文 🎵 '; DROP TABLE sqlx_rows; --", Payload: []byte{0, 255, 128, 1}}
	rollback := errors.New("roll back mapped writes")
	err := store.WithinTx(ctx, db, func(tx store.DBTX) error {
		if _, err := sqlx.NamedExecContext(ctx, tx, "INSERT INTO sqlx_rows(id,label,optional,payload) VALUES(:id,:label,:optional,:payload)", want); err != nil {
			return err
		}
		var got storedRow
		// Column order differs from the struct; SQL NULL remains nil.
		if err := sqlx.GetContext(ctx, tx, &got, "SELECT optional,payload,label,id FROM sqlx_rows WHERE id=?", want.ID); err != nil {
			return err
		}
		if !reflect.DeepEqual(got, want) {
			t.Fatalf("mapped transaction row: %#v", got)
		}
		var rows []storedRow
		if err := sqlx.SelectContext(ctx, tx, &rows, "SELECT * FROM sqlx_rows ORDER BY id"); err != nil {
			return err
		}
		if len(rows) != 1 || !reflect.DeepEqual(rows[0], want) {
			t.Fatalf("mapped transaction list: %#v", rows)
		}
		return rollback
	})
	if !errors.Is(err, rollback) {
		t.Fatal("transaction did not return its rollback cause", err)
	}
	var count int
	if err = db.GetContext(ctx, &count, "SELECT COUNT(*) FROM sqlx_rows"); err != nil || count != 0 {
		t.Fatal("mapped writes escaped rollback", count, err)
	}
	var missing storedRow
	if err = db.GetContext(ctx, &missing, "SELECT * FROM sqlx_rows WHERE id=1"); !errors.Is(err, sql.ErrNoRows) {
		t.Fatal("missing row lost sql.ErrNoRows", err)
	}
	if err = db.GetContext(ctx, &missing, "SELECT 1 AS unexpected_column"); err == nil {
		t.Fatal("unmapped columns silently accepted")
	}
	cancelled, stop := context.WithCancel(ctx)
	stop()
	if err = db.GetContext(cancelled, &count, "SELECT COUNT(*) FROM sqlx_rows"); !errors.Is(err, context.Canceled) {
		t.Fatal("mapped query ignored cancellation", err)
	}
	// Successful, empty and failed mappings must all release the only SQL lease.
	if err = db.GetContext(ctx, &count, "SELECT COUNT(*) FROM sqlx_rows"); err != nil {
		t.Fatal("mapped query retained the connection", err)
	}
}

func TestSQLXNamedBindingPreservesLiteralsAndValues(t *testing.T) {
	db := testutil.Database(t)
	ctx := context.Background()
	input := "' OR 1=1; -- 中文"
	query, args, err := store.Bind(`SELECT 'https://example.invalid:8443/a' AS url,'12:34:56' AS clock,'{"key":"value"}' AS document,'::1' AS ipv6,@{song.id} AS value,@{song.id} AS repeated,@{optional} AS optional`, map[string]any{"song.id": input, "optional": nil})
	if err != nil {
		t.Fatal(err)
	}
	var got struct {
		URL      string  `db:"url"`
		Clock    string  `db:"clock"`
		Document string  `db:"document"`
		IPv6     string  `db:"ipv6"`
		Value    string  `db:"value"`
		Repeated string  `db:"repeated"`
		Optional *string `db:"optional"`
	}
	if err = db.GetContext(ctx, &got, query, args...); err != nil {
		t.Fatal(err)
	}
	if got.URL != "https://example.invalid:8443/a" || got.Clock != "12:34:56" || got.Document != `{"key":"value"}` || got.IPv6 != "::1" || got.Value != input || got.Repeated != input || got.Optional != nil {
		t.Fatalf("named binding changed a literal or value: %#v", got)
	}
	const secret = "test-secret-must-not-appear-in-errors"
	if _, _, err = store.Bind("SELECT @{missing}", map[string]any{"secret": secret}); err == nil || strings.Contains(err.Error(), secret) {
		t.Fatal("missing argument accepted or credential leaked")
	}
}

func TestSQLXMapNullEmptyAndRowOwnership(t *testing.T) {
	db := testutil.Database(t)
	ctx := context.Background()
	if _, err := db.Exec("CREATE TABLE sqlx_map_rows(id INTEGER PRIMARY KEY,text_value TEXT,bytes_value BLOB)"); err != nil {
		t.Fatal(err)
	}
	first, second := []byte{0, 255, 128}, []byte{1, 2, 3}
	for i, payload := range [][]byte{first, second} {
		if _, err := db.Exec("INSERT INTO sqlx_map_rows VALUES(?,NULL,?)", i+1, payload); err != nil {
			t.Fatal(err)
		}
	}
	rows, err := store.Maps(ctx, db, "SELECT * FROM sqlx_map_rows ORDER BY id")
	if err != nil || len(rows) != 2 {
		t.Fatal(rows, err)
	}
	for i, want := range [][]byte{first, second} {
		payload, ok := rows[i]["bytes_value"].([]byte)
		if !ok || !bytes.Equal(payload, want) || rows[i]["text_value"] != nil {
			t.Fatal("mapping changed binary ownership or SQL NULL", rows[i])
		}
	}
	empty, err := store.Maps(ctx, db, "SELECT * FROM sqlx_map_rows WHERE id<0")
	if err != nil || empty == nil || len(empty) != 0 {
		t.Fatal("empty map list changed its non-nil contract", empty, err)
	}
	if _, err = store.Map(ctx, db, "SELECT * FROM sqlx_map_rows WHERE id<0"); !errors.Is(err, sql.ErrNoRows) {
		t.Fatal("empty single map lost sql.ErrNoRows", err)
	}
	if _, err = store.Map(ctx, db, "SELECT * FROM sqlx_map_rows"); err == nil {
		t.Fatal("single map accepted multiple rows")
	}
}

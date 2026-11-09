package store_test

import (
	"bytes"
	"context"
	"testing"

	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/testutil"
)

func TestMapsPreservesBinaryColumns(t *testing.T) {
	db := testutil.Database(t)
	if _, e := db.Exec("CREATE TABLE row_types(text_value TEXT,bytes_value BLOB)"); e != nil {
		t.Fatal(e)
	}
	binary := []byte{0, 255, 128, 1}
	if _, e := db.Exec("INSERT INTO row_types VALUES(?,?)", "中文", binary); e != nil {
		t.Fatal(e)
	}
	row, e := store.Map(context.Background(), db, "SELECT * FROM row_types")
	if e != nil {
		t.Fatal(e)
	}
	got, ok := row["bytes_value"].([]byte)
	if row["text_value"] != "中文" || !ok || !bytes.Equal(got, binary) {
		t.Fatal(row)
	}
}
func TestMySQLMapsPreservesBinaryColumns(t *testing.T) {
	db, _ := testutil.MySQLDatabase(t)
	binary := []byte{0, 255, 128, 1}
	row, e := store.Map(context.Background(), db, "SELECT CAST(? AS CHAR) AS text_value,CAST(? AS BINARY) AS bytes_value", "中文", binary)
	if e != nil {
		t.Fatal(e)
	}
	got, ok := row["bytes_value"].([]byte)
	if row["text_value"] != "中文" || !ok || !bytes.Equal(got, binary) {
		t.Fatal(row)
	}
}

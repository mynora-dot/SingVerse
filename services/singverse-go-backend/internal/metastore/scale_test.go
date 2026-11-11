package metastore

import (
	"context"
	"encoding/json"
	"fmt"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/metastandard"
	"strings"
	"testing"
)

func TestHundredThousandDocumentsUseBoundedPageAndIndexedStatistics(t *testing.T) {
	s, db := setup(t)
	ctx := context.Background()
	tx, e := db.BeginTxx(ctx, nil)
	if e != nil {
		t.Fatal(e)
	}
	defer tx.Rollback()
	stmt, e := tx.PrepareContext(ctx, s.q["document-insert"])
	if e != nil {
		t.Fatal(e)
	}
	for i := 0; i < 100000; i++ {
		id := fmt.Sprintf("00000000-0000-4000-8000-%012d", i)
		d := metastandard.Document{SchemaVersion: "1.0", ID: id, Type: "SONG", Revision: "1", Fields: map[string]any{"title": fmt.Sprintf("歌曲 %06d", i)}, States: map[string]metastandard.FieldState{"title": {Origin: metastandard.Origin{Kind: "FILENAME"}, Status: "PRESENT", Decision: "AUTO", Revision: "1"}}}
		b, e := json.Marshal(d)
		if e != nil {
			t.Fatal(e)
		}
		if _, e = stmt.ExecContext(ctx, id, "SONG", nil, "1", string(b), d.Fields["title"], strings.ToLower(d.Fields["title"].(string)), timestamp()); e != nil {
			t.Fatal(e)
		}
	}
	if e = stmt.Close(); e != nil {
		t.Fatal(e)
	}
	if e = tx.Commit(); e != nil {
		t.Fatal(e)
	}
	page, total, e := s.Page(ctx, db, "SONG", "", 99980, 20)
	if e != nil || len(page) != 20 || total != 100000 {
		t.Fatal(len(page), total, e)
	}
	rows, e := db.QueryContext(ctx, "EXPLAIN QUERY PLAN "+s.q["document-page"], "SONG", "%", 20, 0)
	if e != nil {
		t.Fatal(e)
	}
	defer rows.Close()
	indexed := false
	for rows.Next() {
		var id, parent, unused int
		var detail string
		if e = rows.Scan(&id, &parent, &unused, &detail); e != nil {
			t.Fatal(e)
		}
		if strings.Contains(detail, "idx_metadata_kind_title") {
			indexed = true
		}
	}
	if e = rows.Err(); e != nil {
		t.Fatal(e)
	}
	if !indexed {
		t.Fatal("kind/title pagination lost its index")
	}
}

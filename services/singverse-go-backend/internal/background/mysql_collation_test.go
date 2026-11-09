package background

import (
	"context"
	"io"
	"log/slog"
	"testing"

	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/testutil"
)

// The database uses utf8mb4_0900_ai_ci while CAST(... AS CHAR) follows the
// connection's utf8mb4_general_ci. Every item projector must compare the stored
// progress stamp without relying on either collation, including unchanged rows.
func TestMySQLItemProjectionChangeDetectionAcrossCollations(t *testing.T) {
	db, _ := testutil.MySQLDatabase(t)
	s, err := New(db, "mysql", slog.New(slog.NewTextHandler(io.Discard, nil)))
	if err != nil {
		t.Fatal(err)
	}
	s.cancel()
	s.wg.Wait()
	ctx := context.Background()
	cases := []struct {
		kind, table, state string
		seed               string
	}{
		{"METADATA", "metadata_item", "status", `INSERT INTO metadata_item(id,batch_id,song_id,fingerprint,title,artists_json,status,updated_at) VALUES('item','batch','song','fingerprint','Song','[]','PENDING','1')`},
		{"CLASSIFICATION", "classification_item", "status", `INSERT INTO classification_item(id,task_id,song_id,status,input_json,input_version,song_revision,updated_at) VALUES('item','batch','song','PENDING','{}','1',1,'1')`},
		{"LX_DOWNLOAD", "lx_download_item", "status", `INSERT INTO lx_download_item(id,batch_id,track_key,track_json,requested_quality,status,updated_at) VALUES('item','batch','key','{}','128k','PENDING','1')`},
		{"AUDIO_BATCH", "audio_batch_item", "state", `INSERT INTO audio_batch_item(batch_id,song_id,fingerprint,title,category,state,error,updated_at) VALUES('batch','item','fingerprint','Song','READY','PENDING','',1)`},
	}
	for _, tc := range cases {
		t.Run(tc.kind, func(t *testing.T) {
			if _, err := db.ExecContext(ctx, tc.seed); err != nil {
				t.Fatal(err)
			}
			if _, err := db.ExecContext(ctx, `INSERT INTO background_task_item(task_id,id,song_id,title,status,message,execution_json,source_updated_at,execution_updated_at) VALUES(?,'item','song','Song','PENDING','','{}','1',0)`, tc.kind); err != nil {
				t.Fatal(err)
			}
			changed := func(want int) {
				t.Helper()
				rows, err := s.rows(ctx, db, "sourceItems-"+tc.kind, doc{"taskId": tc.kind, "businessId": "batch", "cursor": ""})
				if err != nil || len(rows) != want {
					t.Fatalf("changed rows=%d, want=%d, error=%v", len(rows), want, err)
				}
			}
			changed(0)
			// Identifiers below are fixed test cases, never request input.
			if _, err := db.ExecContext(ctx, "UPDATE "+tc.table+" SET "+tc.state+"='RUNNING'"); err != nil {
				t.Fatal(err)
			}
			changed(1)
			if _, err := db.ExecContext(ctx, "UPDATE "+tc.table+" SET "+tc.state+"='PENDING',updated_at=2"); err != nil {
				t.Fatal(err)
			}
			changed(1)
			if _, err := db.ExecContext(ctx, "UPDATE background_task_item SET source_updated_at='2' WHERE task_id=?", tc.kind); err != nil {
				t.Fatal(err)
			}
			changed(0)
		})
	}
}

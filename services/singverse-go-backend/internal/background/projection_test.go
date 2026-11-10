package background

import (
	"context"
	"strings"
	"testing"
	"time"

	"github.com/jmoiron/sqlx"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/testutil"
)

func TestTaskProjectionProgressAndRetention(t *testing.T) {
	testTaskProjectionProgressAndRetention(t, testutil.Database(t), "sqlite")
}

func TestMySQLTaskProjectionProgressAndRetention(t *testing.T) {
	db, _ := testutil.MySQLDatabase(t)
	testTaskProjectionProgressAndRetention(t, db, "mysql")
}

func testTaskProjectionProgressAndRetention(t *testing.T, db *sqlx.DB, dialect string) {
	t.Helper()
	service, err := New(db, dialect, nil)
	if err != nil {
		t.Fatal(err)
	}
	service.cancel()
	service.wg.Wait()
	t.Cleanup(func() { service.Close() })
	ctx := context.Background()
	exec := func(statement string, args ...any) {
		t.Helper()
		if _, err := db.ExecContext(ctx, statement, args...); err != nil {
			t.Fatal(err)
		}
	}
	project := func(kind, businessID string, historical bool) doc {
		t.Helper()
		if err := store.WithinTx(ctx, db, func(tx store.DBTX) error {
			return service.refresh(ctx, tx, kind, businessID, historical)
		}); err != nil {
			t.Fatal(err)
		}
		row, err := service.row(ctx, db, "find", doc{"id": ID(kind, businessID)})
		if err != nil {
			t.Fatal(err)
		}
		return row
	}
	at := time.Now().UTC().Format(time.RFC3339Nano)

	t.Run("media import separates preparation merging and verification", func(t *testing.T) {
		exec(`INSERT INTO media_source(id,name,source_type,location,location_hash,username,created_at,updated_at)
			VALUES('workflow-source','共享曲库','WEBDAV','https://example.invalid/dav/','workflow-root','',?,?)`, at, at)
		exec(`INSERT INTO media_import_job(id,source_id,binding,state,phase,created_at,updated_at)
			VALUES('workflow-import','workflow-source','workflow-binding','PARSING','PARSING',?,?)`, at, at)
		row := project("SHARE_IMPORT", "workflow-import", false)
		if text(row, "status") != "RUNNING" || row["total"] != nil || row["completed"] != nil {
			t.Fatal("parsing invented a total", row)
		}
		exec(`UPDATE media_import_job SET state='PREVIEW',phase='PREVIEW',total_count=10 WHERE id='workflow-import'`)
		row = project("SHARE_IMPORT", "workflow-import", false)
		if text(row, "status") != "AWAITING_CONFIRMATION" || number(row, "total") != 10 || number(row, "completed") != 0 || related(row) != "/library/media?source=workflow-source&import=workflow-import" {
			t.Fatal("preview lost confirmation or resume link", row)
		}
		exec(`UPDATE media_import_job SET state='VERIFYING',phase='VERIFYING',processed_count=10,verified_count=4,added_count=3,updated_count=5,preserved_count=2,present_count=2,unreachable_count=2 WHERE id='workflow-import'`)
		row = project("SHARE_IMPORT", "workflow-import", false)
		stats := object(read(row["statistics_json"]), "statistics")
		if text(row, "status") != "RUNNING" || text(row, "stage") != "VERIFYING" || number(row, "completed") != 4 || number(stats, "preserved_count") != 2 || number(stats, "unreachable_count") != 2 {
			t.Fatal("verification confused imported songs with observed media", row, stats)
		}
		exec(`INSERT INTO media_ingest_request(id,source_id,state,created_at,updated_at)
			VALUES('workflow-ingest','workflow-source','QUEUED',?,?)`, at, at)
		row = project("SOURCE_INGEST", "workflow-ingest", false)
		if text(row, "status") != "QUEUED" || related(row) != "/library/media?source=workflow-source" {
			t.Fatal("durable ingest lost its source", row)
		}
	})

	t.Run("metadata failures and legacy skipped songs", func(t *testing.T) {
		exec(`INSERT INTO metadata_batch(id,mode,status,providers_json,auto_apply,threshold,skipped_existing,created_at,updated_at)
			VALUES('metadata-progress','MISSING','COMPLETED','[]',0,0.9,3,?,?)`, at, at)
		for _, status := range []string{"FAILED", "SKIPPED", "AUTO_APPLIED"} {
			exec(`INSERT INTO metadata_item(id,batch_id,song_id,fingerprint,artists_json,status,updated_at)
				VALUES(?,'metadata-progress',?,'fingerprint','[]',?,?)`, status, status, status, at)
		}
		row := project("METADATA", "metadata-progress", false)
		statistics := object(read(row["statistics_json"]), "statistics")
		if text(row, "status") != "PARTIAL" || number(row, "total") != 5 || number(row, "completed") != 5 ||
			number(statistics, "skipped") != 3 || number(statistics, "failed") != 1 || number(statistics, "applied") != 1 {
			t.Fatal("metadata progress changed", row, statistics)
		}
		repeated := project("METADATA", "metadata-progress", false)
		if row["updated_at"] != repeated["updated_at"] || row["finished_at"] != repeated["finished_at"] {
			t.Fatal("reconciliation invented timestamps", row, repeated)
		}
	})

	t.Run("audio preview does not count preparation as completion", func(t *testing.T) {
		exec(`INSERT INTO audio_batch(id,state,backend,created_at,updated_at,selection_total,prepared_count)
			VALUES('audio-preview','PREVIEW','AUTO',?,?,3,1)`, now(), now())
		row := project("AUDIO_BATCH", "audio-preview", false)
		if text(row, "status") != "AWAITING_CONFIRMATION" || number(row, "total") != 3 || number(row, "completed") != 0 || number(row, "items_available") != 1 {
			t.Fatal("audio preview progress changed", row)
		}
	})

	t.Run("cache cleanup keeps bounded and sanitized error details", func(t *testing.T) {
		result := doc{"removed": 2, "skipped": 1, "failed": 4, "freedBytes": -1, "targetReached": false,
			"errors": []any{doc{"id": "cache-item", "message": "https://example.invalid/song?token=hidden password=hidden"}}}
		exec(`INSERT INTO song_cache_cleanup_job(id,mode,state,created_at,updated_at,result_json)
			VALUES('cleanup-progress','CLEAR','COMPLETED',?,?,?)`, now(), now(), encode(result))
		row := project("CACHE_CLEANUP", "cleanup-progress", false)
		statistics := object(read(row["statistics_json"]), "statistics")
		if text(row, "stage") != "CLEAR" || number(row, "completed") != 7 || number(statistics, "freedBytes") != 0 || statistics["errorDetailsLimited"] != true {
			t.Fatal("cleanup progress changed", row, statistics)
		}
		var message string
		if err := db.QueryRowContext(ctx, `SELECT message FROM background_task_item WHERE task_id=?`, ID("CACHE_CLEANUP", "cleanup-progress")).Scan(&message); err != nil {
			t.Fatal(err)
		}
		if strings.Contains(message, "hidden") || strings.Contains(message, "example.invalid") {
			t.Fatal("cleanup error leaked upstream details", message)
		}
	})

	t.Run("old terminal work is omitted and historical timing stays unknown", func(t *testing.T) {
		old := time.Now().Add(-45 * 24 * time.Hour).UTC().Format(time.RFC3339Nano)
		exec(`INSERT INTO metadata_batch(id,mode,status,providers_json,auto_apply,threshold,created_at,updated_at)
			VALUES('old-progress','MISSING','COMPLETED','[]',0,0.9,?,?)`, old, old)
		if row := project("METADATA", "old-progress", true); len(row) != 0 {
			t.Fatal("expired terminal task was imported", row)
		}
		exec(`UPDATE metadata_batch SET status='RUNNING' WHERE id='old-progress'`)
		row := project("METADATA", "old-progress", true)
		if text(row, "status") != "RUNNING" || row["started_at"] != nil || number(row, "time_incomplete") != 1 {
			t.Fatal("historical projection invented a start time", row)
		}
	})
}

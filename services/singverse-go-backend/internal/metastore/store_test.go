package metastore

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"image"
	"image/png"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"testing"
	"time"

	"github.com/jmoiron/sqlx"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/metastandard"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
	_ "modernc.org/sqlite"
)

func setup(t *testing.T) (*Store, *sqlx.DB) {
	t.Helper()
	db, e := sqlx.Open("sqlite", filepath.Join(t.TempDir(), "metadata.db"))
	if e != nil {
		t.Fatal(e)
	}
	db.SetMaxOpenConns(1)
	t.Cleanup(func() { db.Close() })
	paths, e := filepath.Glob("../../../../database/migrations/sqlite/*.sql")
	if e != nil || len(paths) == 0 {
		t.Fatal(e)
	}
	sort.Strings(paths)
	for _, path := range paths {
		b, e := os.ReadFile(path)
		if e != nil {
			t.Fatal(e)
		}
		if _, e = db.Exec(strings.Split(string(b), "-- migrate:down")[0]); e != nil {
			t.Fatal(path, e)
		}
	}
	s, e := New(t.TempDir())
	if e != nil {
		t.Fatal(e)
	}
	return s, db
}
func TestDecisionsBaselinesRevisionsAndReceipts(t *testing.T) {
	s, db := setup(t)
	ctx := context.Background()
	id := NewID()
	collection := NewID()
	var d metastandard.Document
	e := store.WithinTx(ctx, db, func(tx store.DBTX) error {
		var e error
		d, e = s.Create(ctx, tx, "SONG", id, "")
		if e != nil {
			return e
		}
		d, _, e = s.Observe(ctx, tx, id, Evidence{Origin: metastandard.Origin{Kind: "FILE"}, Fields: map[string]any{"title": "错误标签", "artists": []string{"歌手"}}})
		if e != nil {
			return e
		}
		d, e = s.Update(ctx, tx, id, Update{ExpectedRevision: d.Revision, RequestID: NewID(), Operations: []Operation{{Field: "title", Op: "SET", Value: "正确歌名"}, {Field: "artists", Op: "CLEAR"}}})
		return e
	})
	if e != nil {
		t.Fatal(e)
	}
	request := Update{ExpectedRevision: d.Revision, RequestID: NewID(), Operations: []Operation{{Field: "title", Op: "SET", Value: "正确歌名"}}}
	e = store.WithinTx(ctx, db, func(tx store.DBTX) error { var e error; d, e = s.Update(ctx, tx, id, request); return e })
	if e != nil {
		t.Fatal(e)
	}
	rev := d.Revision
	e = store.WithinTx(ctx, db, func(tx store.DBTX) error { var e error; d, e = s.Update(ctx, tx, id, request); return e })
	if e != nil || d.Revision != rev {
		t.Fatal("receipt not idempotent", d, e)
	}
	e = store.WithinTx(ctx, db, func(tx store.DBTX) error {
		var diffs []string
		var e error
		d, diffs, e = s.Observe(ctx, tx, id, Evidence{Origin: metastandard.Origin{Kind: "SHARED", CollectionID: collection}, Revision: "1", Fields: map[string]any{"title": "共享订正", "artists": []string{"共享歌手"}, "album": "首版"}})
		if len(diffs) != 2 {
			t.Fatalf("local decisions not protected: %v", diffs)
		}
		return e
	})
	if e != nil || d.Fields["title"] != "正确歌名" || d.Fields["artists"] != nil {
		t.Fatal(d, e)
	}
	e = store.WithinTx(ctx, db, func(tx store.DBTX) error {
		var e error
		d, _, e = s.Observe(ctx, tx, id, Evidence{Origin: metastandard.Origin{Kind: "SHARED", CollectionID: collection}, Revision: "2", Fields: map[string]any{"title": "新共享订正", "album": "修订"}})
		return e
	})
	if e != nil || d.Fields["album"] != "修订" {
		t.Fatal(d, e)
	}
	e = store.WithinTx(ctx, db, func(tx store.DBTX) error {
		_, _, e := s.Observe(ctx, tx, id, Evidence{Origin: metastandard.Origin{Kind: "SHARED", CollectionID: collection}, Revision: "2", Fields: map[string]any{"album": "伪造同版本"}})
		return e
	})
	if e == nil {
		t.Fatal("same revision different content accepted")
	}
	e = store.WithinTx(ctx, db, func(tx store.DBTX) error {
		var e error
		d, e = s.Update(ctx, tx, id, Update{ExpectedRevision: d.Revision, RequestID: NewID(), Operations: []Operation{{Field: "title", Op: "RESET"}}})
		return e
	})
	if e != nil || d.Fields["title"] != "新共享订正" || d.States["title"].Decision != "AUTO" {
		t.Fatal(d, e)
	}
	// Evidence and receipts survive a new service instance (task retention is irrelevant).
	s2, e := New(s.Root)
	if e != nil {
		t.Fatal(e)
	}
	again, e := s2.Read(ctx, db, id)
	if e != nil || again.Fields["title"] != "新共享订正" {
		t.Fatal(again, e)
	}
}
func TestAssetBytesReferencesAndRollback(t *testing.T) {
	s, db := setup(t)
	ctx := context.Background()
	id := NewID()
	var original bytes.Buffer
	if e := png.Encode(&original, image.NewRGBA(image.Rect(0, 0, 2, 2))); e != nil {
		t.Fatal(e)
	}
	data := append(original.Bytes(), bytes.Repeat([]byte{0}, 3<<20)...)
	var asset Asset
	e := store.WithinTx(ctx, db, func(tx store.DBTX) error { var e error; asset, e = s.PutAsset(ctx, tx, data, "image/png"); return e })
	if e != nil {
		t.Fatal(e)
	}
	e = store.WithinTx(ctx, db, func(tx store.DBTX) error {
		parent := NewID()
		if _, e := s.Create(ctx, tx, "SONG", parent, ""); e != nil {
			return e
		}
		_, e := s.Create(ctx, tx, "VARIANT", id, parent)
		if e != nil {
			return e
		}
		return s.Decide(ctx, tx, id, map[string]any{"cover": metastandard.AssetRef{AssetID: asset.ID}}, metastandard.Origin{Kind: "MANUAL"})
	})
	if e != nil {
		t.Fatal(e)
	}
	_, got, e := s.ReadAsset(ctx, db, asset.ID)
	if e != nil || !bytes.Equal(got, data) || asset.ID != metastandard.Digest(data) {
		t.Fatal("original asset bytes changed", e)
	}
	_, e = db.Exec("UPDATE metadata_asset SET unreferenced_at=?", time.Now().Add(-8*24*time.Hour).UTC().Format(time.RFC3339Nano))
	if e != nil {
		t.Fatal(e)
	}
	e = store.WithinTx(ctx, db, func(tx store.DBTX) error { return s.Cleanup(ctx, tx) })
	if e != nil {
		t.Fatal(e)
	}
	if _, _, e = s.ReadAsset(ctx, db, asset.ID); e != nil {
		t.Fatal("current reference collected", e)
	}
	before, e := s.Read(ctx, db, id)
	if e != nil {
		t.Fatal(e)
	}
	e = store.WithinTx(ctx, db, func(tx store.DBTX) error {
		_, e := s.Update(ctx, tx, id, Update{ExpectedRevision: before.Revision, RequestID: NewID(), Operations: []Operation{{Field: "cover", Op: "SET", Value: metastandard.AssetRef{AssetID: strings.Repeat("0", 64)}}}})
		return e
	})
	if e == nil {
		t.Fatal("dangling formal asset reference accepted")
	}
	after, e := s.Read(ctx, db, id)
	a, _ := json.Marshal(before)
	b, _ := json.Marshal(after)
	if e != nil || !bytes.Equal(a, b) {
		t.Fatal("failed write changed formal metadata", e)
	}
	rollback := errors.New("simulated crash")
	e = store.WithinTx(ctx, db, func(tx store.DBTX) error {
		if _, e := s.PutAsset(ctx, tx, []byte("orphan"), "text/plain"); e != nil {
			return e
		}
		return rollback
	})
	if !errors.Is(e, rollback) {
		t.Fatal(e)
	}
}

func TestEqualConfirmationKeepsOriginAndProtectsValue(t *testing.T) {
	s, db := setup(t)
	ctx := context.Background()
	id, collection := NewID(), NewID()
	var d metastandard.Document
	err := store.WithinTx(ctx, db, func(tx store.DBTX) error {
		if _, e := s.Create(ctx, tx, "SONG", id, ""); e != nil {
			return e
		}
		var e error
		d, _, e = s.Observe(ctx, tx, id, Evidence{Origin: metastandard.Origin{Kind: "SHARED", CollectionID: collection}, Revision: "1", Fields: map[string]any{"title": "已确认歌名"}})
		if e != nil {
			return e
		}
		d, e = s.Update(ctx, tx, id, Update{ExpectedRevision: d.Revision, RequestID: NewID(), Operations: []Operation{{Field: "title", Op: "SET", Value: "已确认歌名"}}})
		return e
	})
	if err != nil || d.States["title"].Decision != "SET" || d.States["title"].Origin.Kind != "SHARED" || d.States["title"].Origin.CollectionID != collection {
		t.Fatal("confirmation conflated origin with local decision", d, err)
	}
	err = store.WithinTx(ctx, db, func(tx store.DBTX) error {
		var e error
		d, _, e = s.Observe(ctx, tx, id, Evidence{Origin: metastandard.Origin{Kind: "SHARED", CollectionID: collection}, Revision: "2", Fields: map[string]any{"title": "后续共享修改"}})
		return e
	})
	if err != nil || d.Fields["title"] != "已确认歌名" || d.States["title"].Decision != "SET" {
		t.Fatal("equal confirmation did not protect the field", d, err)
	}
}

func TestInvalidLyricsNeverBecomeFormalReferences(t *testing.T) {
	s, db := setup(t)
	ctx := context.Background()
	id := NewID()
	err := store.WithinTx(ctx, db, func(tx store.DBTX) error {
		parent := NewID()
		if _, e := s.Create(ctx, tx, "SONG", parent, ""); e != nil {
			return e
		}
		_, e := s.Create(ctx, tx, "VARIANT", id, parent)
		return e
	})
	if err != nil {
		t.Fatal(err)
	}
	for _, data := range [][]byte{bytes.Repeat([]byte("x"), (256<<10)+1), []byte(`{"format":"UNKNOWN","lines":[]}`)} {
		var asset Asset
		err = store.WithinTx(ctx, db, func(tx store.DBTX) error {
			var e error
			asset, e = s.PutAsset(ctx, tx, data, "application/json")
			return e
		})
		if err != nil {
			t.Fatal(err)
		}
		before, e := s.Read(ctx, db, id)
		if e != nil {
			t.Fatal(e)
		}
		err = store.WithinTx(ctx, db, func(tx store.DBTX) error {
			_, e := s.Update(ctx, tx, id, Update{ExpectedRevision: before.Revision, RequestID: NewID(), Operations: []Operation{{Field: "lyricsAsset", Op: "SET", Value: metastandard.AssetRef{AssetID: asset.ID}}}})
			return e
		})
		if err == nil {
			t.Fatal("unreadable lyrics accepted as formal metadata")
		}
		after, e := s.Read(ctx, db, id)
		if e != nil || after.Revision != before.Revision || after.Fields["lyricsAsset"] != nil {
			t.Fatal("failed lyrics write changed formal metadata", after, e)
		}
	}
}

func TestAssetMIMECannotBeRedefined(t *testing.T) {
	s, db := setup(t)
	ctx := context.Background()
	data := []byte(`{"timing":"none","text":"原始歌词"}`)
	err := store.WithinTx(ctx, db, func(tx store.DBTX) error {
		_, e := s.PutAsset(ctx, tx, data, "application/json")
		return e
	})
	if err != nil {
		t.Fatal(err)
	}
	err = store.WithinTx(ctx, db, func(tx store.DBTX) error {
		_, e := s.PutAsset(ctx, tx, data, "text/plain")
		return e
	})
	if err == nil {
		t.Fatal("same content registered with a contradictory resource type")
	}
	asset, got, err := s.ReadAsset(ctx, db, metastandard.Digest(data))
	if err != nil || asset.MIME != "application/json" || !bytes.Equal(got, data) {
		t.Fatal("rejected upload changed the original resource", asset, err)
	}
}

func TestSharedClearResetAndAssetLeaseRetention(t *testing.T) {
	s, db := setup(t)
	ctx := context.Background()
	id, collection := NewID(), NewID()
	run := func(fn func(store.DBTX) error) {
		t.Helper()
		if e := store.WithinTx(ctx, db, fn); e != nil {
			t.Fatal(e)
		}
	}
	run(func(tx store.DBTX) error {
		if _, e := s.Create(ctx, tx, "SONG", id, ""); e != nil {
			return e
		}
		_, _, e := s.Observe(ctx, tx, id, Evidence{Origin: metastandard.Origin{Kind: "SHARED", CollectionID: collection}, Revision: "1", Fields: map[string]any{"title": "共享首版"}})
		return e
	})
	run(func(tx store.DBTX) error {
		_, _, e := s.Observe(ctx, tx, id, Evidence{Origin: metastandard.Origin{Kind: "SHARED", CollectionID: collection}, Revision: "2", Clears: []string{"title"}})
		return e
	})
	d, e := s.Read(ctx, db, id)
	if e != nil || d.Fields["title"] != nil || d.States["title"].Decision != "AUTO" || d.States["title"].Status != "CLEARED" {
		t.Fatal(d, e)
	}
	run(func(tx store.DBTX) error {
		return s.Decide(ctx, tx, id, map[string]any{"title": "本机订正"}, metastandard.Origin{Kind: "MANUAL"})
	})
	run(func(tx store.DBTX) error {
		d, e := s.Read(ctx, tx, id)
		if e != nil {
			return e
		}
		_, e = s.Update(ctx, tx, id, Update{ExpectedRevision: d.Revision, RequestID: NewID(), Operations: []Operation{{Field: "title", Op: "RESET"}}})
		return e
	})
	d, e = s.Read(ctx, db, id)
	if e != nil || d.Fields["title"] != nil || d.States["title"].Status != "CLEARED" {
		t.Fatal("RESET lost shared clear", d, e)
	}
	var asset Asset
	expiry := LeaseExpiry()
	run(func(tx store.DBTX) error {
		var e error
		asset, e = s.PutAsset(ctx, tx, []byte("retained original"), "text/plain")
		if e != nil {
			return e
		}
		if e = s.References(ctx, tx, "IMPORT", NewID(), []string{asset.ID}, &expiry); e != nil {
			return e
		}
		return s.References(ctx, tx, "EXPORT", NewID(), []string{asset.ID}, nil)
	})
	if _, e = db.Exec("UPDATE metadata_asset SET unreferenced_at=?", time.Now().Add(-8*24*time.Hour).UTC().Format(time.RFC3339Nano)); e != nil {
		t.Fatal(e)
	}
	run(func(tx store.DBTX) error { return s.Cleanup(ctx, tx) })
	if _, _, e = s.ReadAsset(ctx, db, asset.ID); e != nil {
		t.Fatal("active task resource collected", e)
	}
	run(func(tx store.DBTX) error {
		_, e := tx.ExecContext(ctx, "DELETE FROM metadata_asset_reference")
		return e
	})
	run(func(tx store.DBTX) error { return s.Cleanup(ctx, tx) })
	if _, _, e = s.ReadAsset(ctx, db, asset.ID); e == nil {
		t.Fatal("unreferenced resource survived grace period")
	}
}

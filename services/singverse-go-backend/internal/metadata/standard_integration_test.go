package metadata

import (
	"archive/zip"
	"bytes"
	"context"
	"encoding/base64"
	"image"
	"image/png"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/metastandard"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/metastore"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/testutil"
)

func TestV4OriginalAssetStableIdentityLyricsAndLocalDecisions(t *testing.T) {
	exerciseV4OriginalAssets(t, setup(t))
}
func TestMySQLV4OriginalAssetStableIdentityLyricsAndLocalDecisions(t *testing.T) {
	db, cfg := testutil.MySQLDatabase(t)
	exerciseV4OriginalAssets(t, setupWithDatabase(t, db, "mysql", cfg))
}

func TestWebPOriginalSurvivesSnapshotAndShare(t *testing.T) {
	f := setup(t)
	ctx := context.Background()
	source, ids := f.local(t, "A-Song.mkv")
	song, _, err := f.lib.FormalIDs(ctx, f.db, ids[0])
	if err != nil {
		t.Fatal(err)
	}
	data, err := base64.StdEncoding.DecodeString("UklGRh4AAABXRUJQVlA4TBEAAAAvAAAAAAdQqFIUr/+BiOh/AAA=")
	if err != nil {
		t.Fatal(err)
	}
	err = store.WithinTx(ctx, f.db, func(tx store.DBTX) error {
		asset, e := f.lib.Formal().PutAsset(ctx, tx, data, "image/webp")
		if e != nil {
			return e
		}
		return f.lib.Formal().Decide(ctx, tx, song, map[string]any{"cover": metastandard.AssetRef{AssetID: asset.ID}}, metastandard.Origin{Kind: "MANUAL"})
	})
	if err != nil {
		t.Fatal(err)
	}
	snapshot, err := f.s.Snapshot(ctx, f.db, ids[0])
	if err != nil {
		t.Fatal(err)
	}
	got, err := f.s.SharedCover(text(snapshot["cover"]))
	if err != nil || !bytes.Equal(got, data) {
		t.Fatal("WebP original changed in compatibility snapshot", err)
	}
	exportJob(t, f, source, nil)
}
func exerciseV4OriginalAssets(t *testing.T, f *fixture) {
	t.Helper()
	ctx := context.Background()
	source, ids := f.local(t, "歌 手-歌 曲 100%.mkv")
	media := ids[0]
	song, variant, e := f.lib.FormalIDs(ctx, f.db, media)
	if e != nil {
		t.Fatal(e)
	}
	var pngBytes bytes.Buffer
	if e = png.Encode(&pngBytes, image.NewRGBA(image.Rect(0, 0, 3, 2))); e != nil {
		t.Fatal(e)
	}
	original := append(pngBytes.Bytes(), bytes.Repeat([]byte{0}, metastandard.ChunkLimit+37)...)
	e = store.WithinTx(ctx, f.db, func(tx store.DBTX) error {
		a, e := f.lib.Formal().PutAsset(ctx, tx, original, "image/png")
		if e != nil {
			return e
		}
		if e = f.lib.Formal().Decide(ctx, tx, song, map[string]any{"title": "正式订正", "referenceDurationMs": 456000, "cover": metastandard.AssetRef{AssetID: a.ID}}, metastandard.Origin{Kind: "MANUAL"}); e != nil {
			return e
		}
		lyrics := map[string]any{"timing": "milliseconds", "lines": []any{map[string]any{"time": 0, "text": "第一句"}, map[string]any{"time": 1234, "text": "第二句"}}}
		if e = f.lib.Formal().Decide(ctx, tx, variant, map[string]any{"lyrics": lyrics}, metastandard.Origin{Kind: "MANUAL"}); e != nil {
			return e
		}
		v, e := f.lib.Formal().Read(ctx, tx, variant)
		if e != nil {
			return e
		}
		// A reset without a version-specific cover inherits the song original.
		if _, e = f.lib.Formal().Update(ctx, tx, variant, metastore.Update{ExpectedRevision: v.Revision, RequestID: uuid(), Operations: []metastore.Operation{{Field: "cover", Op: "RESET"}}}); e != nil {
			return e
		}
		_, _, e = f.lib.Formal().Observe(ctx, tx, media, metastore.Evidence{Origin: metastandard.Origin{Kind: "FILE"}, Fields: map[string]any{"durationMs": 123500, "observation": map[string]any{"durationMs": 123500, "container": "matroska"}}})
		return e
	})
	if e != nil {
		t.Fatal(e)
	}
	snapshot, e := f.s.Snapshot(ctx, f.db, media)
	if e != nil || text(snapshot["cover"]) == "" {
		t.Fatal("version reset removed inherited export cover", snapshot, e)
	}
	job := exportJob(t, f, source, nil)
	id := text(job["id"])
	if number(job["package_version"]) != 4 || number(job["part_count"]) < 3 {
		t.Fatal(job)
	}
	w := httptest.NewRecorder()
	f.router.ServeHTTP(w, httptest.NewRequest("GET", "/api/v1/media-sources/"+source+"/embedded-metadata/share-jobs/"+id+"/download", nil))
	if w.Code != 200 {
		t.Fatal(w.Code, w.Body.String())
	}
	bundle := w.Body.Bytes()
	path := filepath.Join(t.TempDir(), "full.zip")
	if e = os.WriteFile(path, bundle, 0600); e != nil {
		t.Fatal(e)
	}
	if e = ValidateShareFile(path); e != nil {
		t.Fatal(e)
	}
	if e = visitSharePackage(path, func(doc map[string]any) error {
		for _, data := range doc["_v4Chunks"].(map[string][]byte) {
			if len(data) > metastandard.ChunkLimit {
				t.Fatal("oversized resource block")
			}
		}
		return nil
	}); e != nil {
		t.Fatal(e)
	}
	// A new NAS reconstructs stable catalog identities without compute, access
	// to the sender's source, or a platform/image cache.
	receiver := setup(t)
	target := newDAV(t, receiver, "own-root")
	applied := applyWorkflow(t, receiver, target, bundle)
	if number(applied["addedCount"]) != 1 {
		t.Fatal(applied)
	}
	receivedSong, receivedVariant, e := receiver.lib.FormalIDs(ctx, receiver.db, media)
	if e != nil || receivedSong != song || receivedVariant != variant {
		t.Fatal("portable identity changed", receivedSong, receivedVariant, e)
	}
	if e = os.RemoveAll(receiver.cfg.MetadataCachePath); e != nil {
		t.Fatal(e)
	}
	cover, e := receiver.s.SongCover(ctx, media)
	if e != nil || !bytes.Equal(cover.Bytes, original) {
		t.Fatal("original bytes were not preserved", e)
	}
	d, e := receiver.lib.Formal().Read(ctx, receiver.db, song)
	if e != nil || d.States["title"].Decision != "AUTO" || d.States["title"].Origin.Kind != "SHARED" {
		t.Fatal("sender decision became receiver-local", d, e)
	}
	lyrics, e := receiver.lib.FormalLyrics(ctx, receiver.db, media)
	if e != nil || len(array(lyrics["lines"])) != 2 {
		t.Fatal(lyrics, e)
	}
	currentSong, e := receiver.s.song(ctx, receiver.db, media)
	if e != nil || number(current(currentSong)["durationMs"]) != 456000 {
		t.Fatal(currentSong, e)
	}
	m, e := receiver.lib.Formal().Read(ctx, receiver.db, media)
	if e != nil || number(m.Fields["durationMs"]) != 123500 {
		t.Fatal("media and reference duration mixed", m, e)
	}
	request(t, receiver.router, "POST", "/api/v1/media-sources/"+target+"/embedded-metadata/import-jobs/"+text(applied["id"])+"/cancel", nil, 200)
	request(t, receiver.router, "PATCH", "/api/v1/metadata/entities/"+song, metastore.Update{ExpectedRevision: d.Revision, RequestID: uuid(), Operations: []metastore.Operation{{Field: "title", Op: "CLEAR"}}}, 200)
	repeat := applyWorkflow(t, receiver, target, bundle)
	if number(repeat["addedCount"]) != 0 {
		t.Fatal("repeat created media", repeat)
	}
	d, e = receiver.lib.Formal().Read(ctx, receiver.db, song)
	if e != nil || d.Fields["title"] != nil || d.States["title"].Decision != "CLEAR" {
		t.Fatal("import refilled local clear", d, e)
	}
}

func TestLegacyMigrationRetainsEvidenceAndValidFields(t *testing.T) {
	f := setup(t)
	ctx := context.Background()
	source, ids := f.local(t, "A-Song.mkv")
	legacy := map[string]any{
		"tags":      map[string]any{"title": "旧共享正式标题", "releaseDate": "2020-02-30"},
		"lyrics":    map[string]any{"timing": "unsupported-old-timing", "text": "旧原文保留"},
		"technical": map[string]any{"streams": []any{"unsupported old stream"}},
	}
	args := map[string]any{"songId": ids[0], "sourceId": source, "collectionId": uuid(), "revision": 1, "snapshot": encode(legacy), "size": nil, "now": now()}
	if _, e := f.s.exec(ctx, f.db, "shared-insert", args); e != nil {
		t.Fatal(e)
	}
	if _, e := f.s.exec(ctx, f.db, "v4-migration-write", map[string]any{"after": ""}); e != nil {
		t.Fatal(e)
	}
	if e := f.s.bootstrapShared(ctx); e != nil {
		t.Fatal("one unsupported legacy field blocked startup", e)
	}
	song, variant, e := f.lib.FormalIDs(ctx, f.db, ids[0])
	if e != nil {
		t.Fatal(e)
	}
	d, e := f.lib.Formal().Read(ctx, f.db, song)
	if e != nil || d.Fields["title"] != "旧共享正式标题" || d.Fields["releaseDate"] != nil {
		t.Fatal("valid legacy fields were lost or invalid date became formal", d, e)
	}
	v, e := f.lib.Formal().Read(ctx, f.db, variant)
	if e != nil || v.Fields["lyrics"] != nil {
		t.Fatal("invalid old lyrics became formal", v, e)
	}
	retained, e := f.s.sharedSnapshot(ctx, f.db, ids[0])
	if e != nil || encode(retained) != encode(legacy) {
		t.Fatal("migration changed original evidence", retained, e)
	}
	if e = f.s.bootstrapShared(ctx); e != nil {
		t.Fatal("completed migration could not restart", e)
	}
	snapshot, e := f.s.Snapshot(ctx, f.db, ids[0])
	if e != nil || snapshot["tags"].(map[string]any)["releaseDate"] != nil || snapshot["lyrics"] != nil || snapshot["technical"] != nil {
		t.Fatal("export bypassed formal fields with invalid legacy evidence", snapshot, e)
	}
}

func TestV4IntegrityRejectsModifiedPart(t *testing.T) {
	f := setup(t)
	source, _ := f.local(t, "A-Song.mkv")
	job := exportJob(t, f, source, nil)
	index, e := f.s.shareIndex(context.Background(), source, text(job["id"]))
	if e != nil {
		t.Fatal(e)
	}
	if e = checkV4Index(index); e != nil {
		t.Fatal(e)
	}
	for _, field := range []string{"version", "total", "baseId", "parts"} {
		copy := map[string]any{}
		if e = decode(encode(index), &copy); e != nil {
			t.Fatal(e)
		}
		if field == "baseId" {
			copy[field] = nil
		} else {
			copy[field] = "4"
		}
		delete(copy, "contentDigest")
		digest, err := metastandard.DocumentDigest([]byte(encode(copy)))
		if err != nil {
			t.Fatal(err)
		}
		copy["contentDigest"] = digest
		if err = checkV4Index(copy); err == nil {
			t.Fatal("valid digest bypassed strict index type", field)
		}
	}
	index["total"] = 999
	if e = checkV4Index(index); e == nil {
		t.Fatal("modified index accepted")
	}
	if e = checkV4Part(index, "part-000001.zip", []byte("altered")); e == nil {
		t.Fatal("modified part accepted")
	}
	// Strict key parsing applies to the public manifest as well.
	var b bytes.Buffer
	z := zip.NewWriter(&b)
	w, e := z.Create("manifest.json")
	if e != nil {
		t.Fatal(e)
	}
	_, _ = w.Write([]byte(`{"format":"singverse-media-index","version":4,"version":3,"entries":[]}`))
	if e = z.Close(); e != nil {
		t.Fatal(e)
	}
	if _, e = readManifest(b.Bytes()); e == nil {
		t.Fatal("duplicate manifest keys accepted")
	}
}

func TestStandaloneV4ValidatorChecksOriginalTypesAndReferences(t *testing.T) {
	original := tinyPNG(t)
	assetID := metastandard.Digest(original)
	for _, tc := range []struct {
		name, mime, field string
		valid             bool
	}{
		{"original cover", "image/png", "cover", true},
		{"wrong MIME", "image/jpeg", "cover", false},
		{"image used as lyrics", "image/png", "lyricsAsset", false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			song, variant, media := uuid(), uuid(), uuid()
			state := metastandard.FieldState{Origin: metastandard.Origin{Kind: "MANUAL"}, Decision: "SET", Status: "PRESENT", Revision: "1"}
			docs := []metastandard.Document{
				{SchemaVersion: "1.0", ID: song, Type: "SONG", Revision: "1", Fields: map[string]any{}, States: map[string]metastandard.FieldState{}},
				{SchemaVersion: "1.0", ID: variant, Type: "VARIANT", ParentID: song, Revision: "1", Fields: map[string]any{tc.field: metastandard.AssetRef{AssetID: assetID}}, States: map[string]metastandard.FieldState{tc.field: state}},
				{SchemaVersion: "1.0", ID: media, Type: "MEDIA", ParentID: variant, Revision: "1", Fields: map[string]any{}, States: map[string]metastandard.FieldState{}},
			}
			doc := v4Header(map[string]any{"id": uuid(), "collection_id": uuid(), "collection_revision": 1})
			doc["part"] = 1
			doc["entries"] = []any{map[string]any{"rootId": "media", "path": "歌手/歌曲.mkv", "size": nil, "mediaId": media, "documents": docs}}
			doc["resources"] = []any{map[string]any{"id": assetID, "mime": tc.mime, "size": len(original), "chunks": []any{map[string]any{"id": assetID, "size": len(original)}}}}
			packed, err := packV4(doc, map[string][]byte{assetID: original})
			if err != nil {
				t.Fatal(err)
			}
			filename := filepath.Join(t.TempDir(), "share.zip")
			if err = os.WriteFile(filename, packed, 0600); err != nil {
				t.Fatal(err)
			}
			err = ValidateShareFile(filename)
			if (err == nil) != tc.valid {
				t.Fatal("standalone resource validation", err)
			}
		})
	}
}

func TestPendingLocalCoverSurvivesSharedObservationAndConcurrentClear(t *testing.T) {
	f := setup(t)
	ctx := context.Background()
	_, media := f.local(t, "A-Song.mkv")
	song, _, err := f.lib.FormalIDs(ctx, f.db, media[0])
	if err != nil {
		t.Fatal(err)
	}
	oldBytes := tinyPNG(t)
	incomingBytes := append(append([]byte{}, oldBytes...), 1)
	downloadBytes := append(append([]byte{}, oldBytes...), 2)
	err = f.s.transaction(ctx, func(tx store.DBTX) error {
		old, err := f.lib.Formal().PutAsset(ctx, tx, oldBytes, "image/png")
		if err != nil {
			return err
		}
		_, _, err = f.lib.Formal().Observe(ctx, tx, song, metastore.Evidence{Origin: metastandard.Origin{Kind: "FILE"}, Fields: map[string]any{"cover": metastandard.AssetRef{AssetID: old.ID}}})
		if err != nil {
			return err
		}
		return f.s.enqueueAttachment(ctx, tx, media[0], "download:chosen", Origin{Kind: "DOWNLOAD"})
	})
	if err != nil {
		t.Fatal(err)
	}
	started, finish := make(chan struct{}), make(chan struct{})
	f.s.SetDownloadCover(func(context.Context, string) (Cover, error) {
		close(started)
		<-finish
		return validateRemoteCover(downloadBytes)
	})
	done := make(chan error, 1)
	go func() { _, err := f.s.PersistCover(ctx, media[0]); done <- err }()
	select {
	case <-started:
	case <-time.After(5 * time.Second):
		close(finish)
		t.Fatal("attachment did not start")
	}
	err = f.s.transaction(ctx, func(tx store.DBTX) error {
		a, err := f.lib.Formal().PutAsset(ctx, tx, incomingBytes, "image/png")
		if err != nil {
			return err
		}
		_, _, err = f.lib.Formal().Observe(ctx, tx, song, metastore.Evidence{Origin: metastandard.Origin{Kind: "SHARED", CollectionID: uuid()}, Revision: "1", Fields: map[string]any{"cover": metastandard.AssetRef{AssetID: a.ID}}})
		return err
	})
	if err != nil {
		close(finish)
		t.Fatal(err)
	}
	d, err := f.lib.Formal().Read(ctx, f.db, song)
	if err != nil || text(d.Fields["cover"].(map[string]any)["assetId"]) != metastandard.Digest(oldBytes) {
		close(finish)
		t.Fatal("pending local choice was replaced", d, err)
	}
	request(t, f.router, "PATCH", "/api/v1/metadata/entities/"+song, metastore.Update{ExpectedRevision: d.Revision, RequestID: uuid(), Operations: []metastore.Operation{{Field: "cover", Op: "CLEAR"}}}, 200)
	close(finish)
	select {
	case err = <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("attachment did not finish")
	}
	d, err = f.lib.Formal().Read(ctx, f.db, song)
	if err != nil || d.Fields["cover"] != nil || d.States["cover"].Decision != "CLEAR" {
		t.Fatal("late download replaced a newer clear", d, err)
	}
	if _, _, err = f.lib.Formal().ReadAsset(ctx, f.db, metastandard.Digest(downloadBytes)); err == nil {
		t.Fatal("superseded attachment was committed")
	}
}

func TestExplicitPlatformSelectionProtectsOnlySelectedFields(t *testing.T) {
	f := setup(t)
	ctx := context.Background()
	_, ids := f.local(t, "A-Song.mkv")
	id := ids[0]
	f.settings(t, true)
	candidate := f.candidate(t, id, testTrack("先前采用"))
	request(t, f.router, "POST", "/api/v1/metadata/songs/"+id+"/apply", ApplyInput{candidate, []string{"title"}}, 200)
	song, _, err := f.lib.FormalIDs(ctx, f.db, id)
	if err != nil {
		t.Fatal(err)
	}
	d, err := f.lib.Formal().Read(ctx, f.db, song)
	if err != nil || d.States["title"].Decision != "SET" || d.States["album"].Decision == "SET" {
		t.Fatal("selection protected unrelated fields", d, err)
	}
	newCandidate := f.candidate(t, id, testTrack("后台新候选"))
	err = f.s.transaction(ctx, func(tx store.DBTX) error {
		_, err := f.s.applyLocked(ctx, tx, id, ApplyInput{newCandidate, []string{"title", "album"}}, "", true)
		return err
	})
	if err != nil {
		t.Fatal(err)
	}
	d, err = f.lib.Formal().Read(ctx, f.db, song)
	if err != nil || d.Fields["title"] != "先前采用" || d.Fields["album"] != "Album" || d.States["album"].Decision != "AUTO" {
		t.Fatal("automatic adoption did not respect local selection", d, err)
	}
}

func TestV4GroupsPreScannedMediaAndKeepsLocallyConfirmedBranch(t *testing.T) {
	for _, protect := range []bool{false, true} {
		t.Run(map[bool]string{false: "automatic scan placeholders", true: "local decision retained"}[protect], func(t *testing.T) {
			ctx := context.Background()
			sender := setup(t)
			source, ids := sender.local(t, "A-First.mkv", "A-Second.mkv")
			_, variant, err := sender.lib.FormalIDs(ctx, sender.db, ids[0])
			if err != nil {
				t.Fatal(err)
			}
			err = sender.s.transaction(ctx, func(tx store.DBTX) error {
				d, err := sender.lib.Formal().Read(ctx, tx, ids[1])
				if err != nil {
					return err
				}
				_, err = sender.lib.Formal().Link(ctx, tx, ids[1], variant, d.Revision)
				return err
			})
			if err != nil {
				t.Fatal(err)
			}
			job := exportJob(t, sender, source, nil)
			w := httptest.NewRecorder()
			sender.router.ServeHTTP(w, httptest.NewRequest("GET", "/api/v1/media-sources/"+source+"/embedded-metadata/share-jobs/"+text(job["id"])+"/download", nil))
			if w.Code != 200 {
				t.Fatal(w.Code, w.Body.String())
			}
			receiver := setup(t)
			target, localIDs := receiver.local(t, "A-First.mkv", "A-Second.mkv")
			localSongs := map[string]string{}
			last := ids[0]
			if ids[1] > last {
				last = ids[1]
			}
			lastSong, err := sender.lib.Song(ctx, last)
			if err != nil {
				t.Fatal(err)
			}
			protectedMedia := ""
			for _, id := range localIDs {
				s, _, err := receiver.lib.FormalIDs(ctx, receiver.db, id)
				if err != nil {
					t.Fatal(err)
				}
				localSongs[id] = s
				localSong, err := receiver.lib.Song(ctx, id)
				if err != nil {
					t.Fatal(err)
				}
				if localSong["relativePath"] == lastSong["relativePath"] {
					protectedMedia = id
				}
			}
			if protectedMedia == "" {
				t.Fatal("fixture path mapping failed")
			}
			if protect {
				err = receiver.s.transaction(ctx, func(tx store.DBTX) error {
					return receiver.lib.Formal().Decide(ctx, tx, localSongs[protectedMedia], map[string]any{"title": "本机确认"}, metastandard.Origin{Kind: "MANUAL"})
				})
				if err != nil {
					t.Fatal(err)
				}
			}
			applied := applyWorkflow(t, receiver, target, w.Body.Bytes())
			if number(applied["addedCount"]) != 0 {
				t.Fatal("already scanned files duplicated", applied)
			}
			stats := request(t, receiver.router, "GET", "/api/v1/metadata/statistics", nil, 200)
			want := int64(1)
			if protect {
				want = 2
			}
			if number(stats["SONG"]) != want || number(stats["VARIANT"]) != want || number(stats["MEDIA"]) != 2 {
				t.Fatal("grouping left extra placeholders or hid a local branch", stats)
			}
			if protect {
				d, err := receiver.lib.Formal().Read(ctx, receiver.db, localSongs[protectedMedia])
				if err != nil || d.Fields["title"] != "本机确认" {
					t.Fatal(d, err)
				}
			}
		})
	}
}

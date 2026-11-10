package metadata

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/testutil"
)

func workflowPackage(t *testing.T, collection string, revision int, entries ...any) []byte {
	t.Helper()
	b, err := packManifest(map[string]any{"format": "singverse-media-index", "version": 3, "collectionId": collection, "collectionRevision": revision, "setId": uuid(), "entries": entries})
	if err != nil {
		t.Fatal(err)
	}
	return b
}
func uploadWorkflow(t *testing.T, f *fixture, source string, data []byte) map[string]any {
	t.Helper()
	w := httptest.NewRecorder()
	f.router.ServeHTTP(w, httptest.NewRequest("POST", "/api/v1/media-sources/"+source+"/embedded-metadata/import-jobs", bytes.NewReader(data)))
	if w.Code != 202 {
		t.Fatal(w.Code, w.Body.String())
	}
	out := map[string]any{}
	if err := json.Unmarshal(w.Body.Bytes(), &out); err != nil {
		t.Fatal(err)
	}
	return out
}
func importStep(t *testing.T, f *fixture, source, id, want string) map[string]any {
	t.Helper()
	if err := f.s.ProcessImports(context.Background()); err != nil {
		t.Fatal(err)
	}
	job := request(t, f.router, "GET", "/api/v1/media-sources/"+source+"/embedded-metadata/import-jobs/"+id, nil, 200)
	if text(job["state"]) != want {
		t.Fatalf("wanted %s: %v", want, job)
	}
	return job
}
func applyWorkflow(t *testing.T, f *fixture, source string, data []byte) map[string]any {
	t.Helper()
	job := uploadWorkflow(t, f, source, data)
	id := text(job["id"])
	importStep(t, f, source, id, "PREVIEW")
	request(t, f.router, "POST", "/api/v1/media-sources/"+source+"/embedded-metadata/import-jobs/"+id+"/apply", nil, 200)
	return importStep(t, f, source, id, "VERIFYING")
}
func TestWorkflowMergesExistingUpdatesAndProtectsLocalEdits(t *testing.T) {
	exerciseWorkflowMerge(t, setup(t))
}
func TestMySQLWorkflowMergesExistingUpdatesAndProtectsLocalEdits(t *testing.T) {
	db, cfg := testutil.MySQLDatabase(t)
	exerciseWorkflowMerge(t, setupWithDatabase(t, db, "mysql", cfg))
}
func exerciseWorkflowMerge(t *testing.T, f *fixture) {
	t.Helper()
	ctx := context.Background()
	source := newDAV(t, f, "receiver")
	collection := uuid()
	var songID string
	if err := store.WithinTx(ctx, f.db, func(tx store.DBTX) error {
		var err error
		songID, _, err = f.lib.ImportSong(ctx, tx, source, "目录/歌曲 空格.mkv", nil)
		return err
	}); err != nil {
		t.Fatal(err)
	}
	first := entry("目录/歌曲 空格.mkv", nil)
	first["metadata"].(map[string]any)["tags"] = map[string]any{"title": "共享歌名", "artists": []any{"共享歌手"}, "album": "首版专辑"}
	data := workflowPackage(t, collection, 1, first)
	job := applyWorkflow(t, f, source, data)
	if number(job["addedCount"]) != 0 || number(job["updatedCount"]) != 1 {
		t.Fatal(job)
	}
	song, err := f.s.song(ctx, f.db, songID)
	if err != nil || text(current(song)["title"]) != "共享歌名" {
		t.Fatal(song, err)
	}
	detail := request(t, f.router, "GET", "/api/v1/media-songs/"+songID+"/embedded-metadata", nil, 200)
	if detail["state"] != "IMPORTED" {
		t.Fatal(detail)
	}
	// Cancellation preserves the committed data and permits a newer update.
	request(t, f.router, "POST", "/api/v1/media-sources/"+source+"/embedded-metadata/import-jobs/"+text(job["id"])+"/cancel", nil, 200)
	review := request(t, f.router, "GET", "/api/v1/metadata/songs/"+songID+"/review", nil, 200)
	request(t, f.router, "POST", "/api/v1/metadata/songs/"+songID+"/review/apply", ReviewApply{RequestID: uuid(), Fingerprint: text(review["fingerprint"]), Version: text(review["version"]), Fields: []string{"title"}, Edits: map[string]any{"title": "我的订正"}}, 200)
	second := entry("目录/歌曲 空格.mkv", nil)
	second["metadata"].(map[string]any)["tags"] = map[string]any{"title": "共享新歌名", "artists": []any{"共享新歌手"}, "album": ""}
	job = applyWorkflow(t, f, source, workflowPackage(t, collection, 2, second))
	if number(job["preservedCount"]) != 1 {
		t.Fatal(job)
	}
	song, err = f.s.song(ctx, f.db, songID)
	if err != nil {
		t.Fatal(err)
	}
	values := current(song)
	if text(values["title"]) != "我的订正" || text(array(values["artists"])[0]) != "共享新歌手" || text(values["album"]) != "首版专辑" {
		t.Fatal(values)
	}
	diff := request(t, f.router, "GET", "/api/v1/media-sources/"+source+"/embedded-metadata/import-jobs/"+text(job["id"])+"/differences", nil, 200)
	if len(array(diff["items"])) != 1 || number(diff["nextCursor"]) != 1 {
		t.Fatal(diff)
	}
	request(t, f.router, "POST", "/api/v1/media-sources/"+source+"/embedded-metadata/import-jobs/"+text(job["id"])+"/cancel", nil, 200)
	job = applyWorkflow(t, f, source, workflowPackage(t, collection, 2, second))
	if number(job["addedCount"]) != 0 {
		t.Fatal("duplicate song", job)
	}
	request(t, f.router, "POST", "/api/v1/media-sources/"+source+"/embedded-metadata/import-jobs/"+text(job["id"])+"/cancel", nil, 200)
	blank := entry("目录/歌曲 空格.mkv", nil)
	blank["metadata"].(map[string]any)["tags"] = map[string]any{"title": " \t ", "artists": []any{" ", ""}}
	blank["metadata"].(map[string]any)["technical"] = map[string]any{"video": map[string]any{"codec": "hevc"}}
	job = applyWorkflow(t, f, source, workflowPackage(t, collection, 3, blank))
	song, err = f.s.song(ctx, f.db, songID)
	if err != nil || text(array(current(song)["artists"])[0]) != "共享新歌手" || number(current(song)["durationMs"]) != 123500 {
		t.Fatal("blank tags or partial technical update erased saved values", song, err)
	}
	request(t, f.router, "POST", "/api/v1/media-sources/"+source+"/embedded-metadata/import-jobs/"+text(job["id"])+"/cancel", nil, 200)
	old := uploadWorkflow(t, f, source, data)
	importStep(t, f, source, text(old["id"]), "FAILED")
	songs := request(t, f.router, "GET", "/api/v1/media-sources/"+source+"/songs", nil, 200)
	if number(songs["totalElements"]) != 1 {
		t.Fatal(songs)
	}
}

func TestWorkflowOwnRootVerificationPlaybackAndRebind(t *testing.T) {
	exerciseWorkflowBinding(t, setup(t))
}
func TestMySQLWorkflowOwnRootVerificationPlaybackAndRebind(t *testing.T) {
	db, cfg := testutil.MySQLDatabase(t)
	exerciseWorkflowBinding(t, setupWithDatabase(t, db, "mysql", cfg))
}
func exerciseWorkflowBinding(t *testing.T, f *fixture) {
	t.Helper()
	ctx := context.Background()
	var propfinds, gets atomic.Int64
	var status atomic.Int64
	status.Store(207)
	root := "/dav/我的 曲库/"
	relative := "华语/歌 手/歌 曲.mkv"
	remote := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == "PROPFIND" {
			propfinds.Add(1)
			w.WriteHeader(int(status.Load()))
			if status.Load() != 207 {
				return
			}
			if r.URL.Path == root {
				fmt.Fprintf(w, `<d:multistatus xmlns:d="DAV:"><d:response><d:href>%s</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>`, r.URL.EscapedPath())
				return
			}
			href := (&url.URL{Path: root + relative}).EscapedPath()
			fmt.Fprintf(w, `<d:multistatus xmlns:d="DAV:"><d:response><d:href>%s</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response><d:response><d:href>%s</d:href><d:propstat><d:prop><d:resourcetype/><d:getcontentlength>6</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>`, r.URL.EscapedPath(), href)
			return
		}
		gets.Add(1)
		if r.URL.Path != root+relative {
			t.Error("wrong receiver path", r.URL.Path)
		}
		w.Header().Set("Content-Type", "video/x-matroska")
		w.Header().Set("Content-Length", "6")
		fmt.Fprint(w, "media!")
	}))
	defer remote.Close()
	src := request(t, f.router, "POST", "/api/v1/media-sources", map[string]any{"name": "自己的曲库", "type": "WEBDAV", "location": remote.URL + root}, 201)
	source := text(src["id"])
	item := entry("旧目录/"+relative, 6)
	job := uploadWorkflow(t, f, source, workflowPackage(t, uuid(), 1, item))
	id := text(job["id"])
	importStep(t, f, source, id, "PREVIEW")
	job = request(t, f.router, "PUT", "/api/v1/media-sources/"+source+"/embedded-metadata/import-jobs/"+id+"/mapping", map[string]any{"removePrefix": "旧目录", "addPrefix": ""}, 200)
	sample := array(job["samples"])[0].(map[string]any)
	parsed, err := url.Parse(text(sample["mediaURL"]))
	if err != nil || parsed.Path != root+relative || parsed.Host != strings.TrimPrefix(remote.URL, "http://") {
		t.Fatal(sample, err)
	}
	if propfinds.Load() != 0 || gets.Load() != 0 {
		t.Fatal("preview accessed media")
	}
	request(t, f.router, "POST", "/api/v1/media-sources/"+source+"/embedded-metadata/import-jobs/"+id+"/apply", nil, 200)
	importStep(t, f, source, id, "VERIFYING")
	var songID string
	if err = f.db.Get(&songID, "SELECT id FROM media_song WHERE source_id=?", source); err != nil {
		t.Fatal(err)
	}
	song, _ := f.s.song(ctx, f.db, songID)
	if text(song["mediaVerification"]) != "PENDING" {
		t.Fatal(song)
	}
	status.Store(401)
	job = importStep(t, f, source, id, "SUCCEEDED")
	if number(job["unreachableCount"]) != 1 || number(job["missingCount"]) != 0 {
		t.Fatal(job)
	}
	if gets.Load() != 0 {
		t.Fatal("verification downloaded media")
	}
	status.Store(207)
	request(t, f.router, "POST", "/api/v1/media-sources/"+source+"/embedded-metadata/import-jobs/"+id+"/retry", nil, 200)
	job = importStep(t, f, source, id, "SUCCEEDED")
	if number(job["presentCount"]) != 1 {
		t.Fatal(job)
	}
	if err = f.s.ProcessFiles(ctx); err != nil {
		t.Fatal(err)
	}
	b, err := os.ReadFile(filepath.Join(f.cfg.MetadataPath, "library", source, songID, "media.strm"))
	if err != nil {
		t.Fatal(err)
	}
	playURL, _ := url.Parse(strings.TrimSpace(string(b)))
	if playURL.Path != root+relative || playURL.Host != parsed.Host {
		t.Fatal("wrong local STRM", playURL)
	}
	w := httptest.NewRecorder()
	f.router.ServeHTTP(w, httptest.NewRequest("GET", "/api/v1/media-songs/"+songID+"/video", nil))
	if w.Code != 200 || w.Body.String() != "media!" || gets.Load() != 1 {
		t.Fatal(w.Code, w.Body.String())
	}
	diagnostic := request(t, f.router, "POST", "/api/v1/media-sources/"+source+"/diagnostics", nil, 200)
	if diagnostic["directory"] != "PRESENT" || diagnostic["media"] != "RANGE_UNSUPPORTED" {
		t.Fatal("directory success must not imply range support", diagnostic)
	}
	newRoot := remote.URL + "/dav/重新绑定/"
	job = request(t, f.router, "POST", "/api/v1/media-sources/"+source+"/rebind", map[string]any{"location": newRoot, "username": "", "password": ""}, 202)
	song, err = f.s.song(ctx, f.db, songID)
	if err != nil || text(song["mediaVerification"]) != "PENDING" || text(current(song)["title"]) == "" {
		t.Fatal(song, err)
	}
	if text(job["state"]) != "VERIFYING" || number(job["totalCount"]) != 1 {
		t.Fatal(job)
	}
	summary := request(t, f.router, "GET", "/api/v1/media-sources/"+source+"/embedded-metadata", nil, 200)
	quality := summary["quality"].(map[string]any)
	if number(quality["total"]) != 1 || number(quality["identityComplete"]) != 1 || number(quality["pendingVerification"]) != 1 {
		t.Fatal(quality)
	}
}

func TestWorkflowRejectsMissingDeltaBaselineAndResumesChunks(t *testing.T) {
	f := setup(t)
	source := newDAV(t, f, "checkpoints")
	collection := uuid()
	ctx := context.Background()
	delta, err := packManifest(map[string]any{"format": "singverse-media-index", "version": 3, "collectionId": collection, "collectionRevision": 2, "setId": uuid(), "baseId": uuid(), "entries": []any{entry("a.mkv", nil)}})
	if err != nil {
		t.Fatal(err)
	}
	job := uploadWorkflow(t, f, source, delta)
	importStep(t, f, source, text(job["id"]), "FAILED")
	entries := []any{}
	for i := 0; i < 201; i++ {
		entries = append(entries, entry(fmt.Sprintf("a-%03d.mkv", i), nil))
	}
	job = uploadWorkflow(t, f, source, workflowPackage(t, collection, 3, entries...))
	id := text(job["id"])
	importStep(t, f, source, id, "PREVIEW")
	// Fail the second chunk: first 100 outcomes and songs must survive atomically.
	if _, err = f.db.Exec("CREATE TRIGGER fail_import BEFORE INSERT ON media_shared_song WHEN (SELECT COUNT(*) FROM media_shared_song)>=100 BEGIN SELECT RAISE(ABORT,'forced failure'); END"); err != nil {
		t.Fatal(err)
	}
	request(t, f.router, "POST", "/api/v1/media-sources/"+source+"/embedded-metadata/import-jobs/"+id+"/apply", nil, 200)
	job = importStep(t, f, source, id, "FAILED")
	if number(job["processedCount"]) != 100 {
		t.Fatal(job)
	}
	if _, err = f.db.Exec("DROP TRIGGER fail_import"); err != nil {
		t.Fatal(err)
	}
	request(t, f.router, "POST", "/api/v1/media-sources/"+source+"/embedded-metadata/import-jobs/"+id+"/retry", nil, 200)
	// A new service instance resumes durable rows after the browser is gone.
	if err = f.s.Close(); err != nil {
		t.Fatal(err)
	}
	f.s, err = New(f.db, "sqlite", f.cfg, fixtureCipher{}, f.lib, nil)
	if err != nil {
		t.Fatal(err)
	}
	f.s.cancel()
	f.s.wg.Wait()
	f.s.ctx, f.s.cancel = context.WithCancel(ctx)
	if err = f.s.ProcessImports(ctx); err != nil {
		t.Fatal(err)
	}
	stored, err := f.s.importJob(ctx, source, id)
	if err != nil || number(stored["processedCount"]) != 201 || number(stored["addedCount"]) != 201 || text(stored["state"]) != "VERIFYING" {
		t.Fatal(stored, err)
	}
	var songs int
	if err = f.db.Get(&songs, "SELECT COUNT(*) FROM media_song WHERE source_id=?", source); err != nil || songs != 201 {
		t.Fatal(songs, err)
	}
}

func TestWorkflowIndividualPartDoesNotEstablishDeltaBaseline(t *testing.T) {
	f := setup(t)
	source := newDAV(t, f, "partial")
	collection, snapshot := uuid(), uuid()
	part, err := packManifest(map[string]any{"format": "singverse-media-index", "version": 3, "collectionId": collection, "collectionRevision": 1, "setId": snapshot, "part": 1, "entries": []any{entry("a.mkv", nil)}})
	if err != nil {
		t.Fatal(err)
	}
	job := applyWorkflow(t, f, source, part)
	request(t, f.router, "POST", "/api/v1/media-sources/"+source+"/embedded-metadata/import-jobs/"+text(job["id"])+"/cancel", nil, 200)
	var baselines int
	if err = f.db.Get(&baselines, "SELECT COUNT(*) FROM media_shared_baseline WHERE source_id=?", source); err != nil || baselines != 0 {
		t.Fatal("individual part established a complete baseline", baselines, err)
	}
	delta, err := packManifest(map[string]any{"format": "singverse-media-index", "version": 3, "collectionId": collection, "collectionRevision": 2, "setId": uuid(), "baseId": snapshot, "entries": []any{entry("b.mkv", nil)}})
	if err != nil {
		t.Fatal(err)
	}
	job = uploadWorkflow(t, f, source, delta)
	job = importStep(t, f, source, text(job["id"]), "FAILED")
	if !strings.Contains(text(job["error"]), "基线") {
		t.Fatal(job)
	}
}

func TestWorkflowPrefixMappingKeepsOtherDirectoriesAndRejectsCollisions(t *testing.T) {
	exerciseWorkflowPrefixMapping(t, setup(t))
}
func TestMySQLWorkflowPrefixMappingKeepsOtherDirectoriesAndRejectsCollisions(t *testing.T) {
	db, cfg := testutil.MySQLDatabase(t)
	exerciseWorkflowPrefixMapping(t, setupWithDatabase(t, db, "mysql", cfg))
}
func exerciseWorkflowPrefixMapping(t *testing.T, f *fixture) {
	t.Helper()
	source := newDAV(t, f, "mixed-folders")
	job := uploadWorkflow(t, f, source, workflowPackage(t, uuid(), 1, entry("旧华语/歌曲.mkv", nil), entry("粤语/歌曲.mkv", nil)))
	id := text(job["id"])
	importStep(t, f, source, id, "PREVIEW")
	endpoint := "/api/v1/media-sources/" + source + "/embedded-metadata/import-jobs/" + id
	job = request(t, f.router, "PUT", endpoint+"/mapping", map[string]any{"removePrefix": "旧华语", "addPrefix": "华语"}, 200)
	samples := array(job["samples"])
	if samples[0].(map[string]any)["relativePath"] != "华语/歌曲.mkv" || samples[1].(map[string]any)["relativePath"] != "粤语/歌曲.mkv" {
		t.Fatal("mapping changed an unrelated folder", job)
	}
	request(t, f.router, "PUT", endpoint+"/mapping", map[string]any{"removePrefix": "旧华语", "addPrefix": "粤语"}, 400)
	request(t, f.router, "PUT", endpoint+"/mapping", map[string]any{"removePrefix": "不存在", "addPrefix": "华语"}, 400)
	job = request(t, f.router, "GET", endpoint, nil, 200)
	if job["addPrefix"] != "华语" || array(job["samples"])[0].(map[string]any)["relativePath"] != "华语/歌曲.mkv" {
		t.Fatal("failed mapping did not roll back", job)
	}
}

func TestArchivedMediaRetainsSavedInformationAndLocalCorrections(t *testing.T) {
	exerciseArchivedInformation(t, setup(t))
}
func TestMySQLArchivedMediaRetainsSavedInformationAndLocalCorrections(t *testing.T) {
	db, cfg := testutil.MySQLDatabase(t)
	exerciseArchivedInformation(t, setupWithDatabase(t, db, "mysql", cfg))
}
func exerciseArchivedInformation(t *testing.T, f *fixture) {
	t.Helper()
	ctx := context.Background()
	source := newDAV(t, f, "archived-information")
	applyWorkflow(t, f, source, workflowPackage(t, uuid(), 1, entry("共享歌曲.mkv", nil)))
	var sharedID string
	if err := f.db.Get(&sharedID, "SELECT id FROM media_song WHERE source_id=?", source); err != nil {
		t.Fatal(err)
	}
	var localID, temporaryID string
	if err := store.WithinTx(ctx, f.db, func(tx store.DBTX) error {
		var err error
		localID, _, err = f.lib.ImportSong(ctx, tx, source, "本机整理.mkv", nil)
		if err != nil {
			return err
		}
		temporaryID, _, err = f.lib.ImportSong(ctx, tx, source, "临时索引.mkv", nil)
		if err != nil {
			return err
		}
		return f.s.prepare(ctx, tx, source, true)
	}); err != nil {
		t.Fatal(err)
	}
	for _, id := range []string{sharedID, localID} {
		review := request(t, f.router, "GET", "/api/v1/metadata/songs/"+id+"/review", nil, 200)
		request(t, f.router, "POST", "/api/v1/metadata/songs/"+id+"/review/apply", ReviewApply{RequestID: uuid(), Fingerprint: text(review["fingerprint"]), Version: text(review["version"]), Fields: []string{"title"}, Edits: map[string]any{"title": "保留我的订正"}}, 200)
	}
	old := time.Now().Add(-40 * 24 * time.Hour).UTC().Format(time.RFC3339Nano)
	if _, err := f.db.Exec("UPDATE webdav_resource SET archived_at=? WHERE source_id=?", old, source); err != nil {
		t.Fatal(err)
	}
	if _, err := f.db.Exec("DELETE FROM media_song WHERE source_id=?", source); err != nil {
		t.Fatal(err)
	}
	if err := f.s.Cleanup(ctx); err != nil {
		t.Fatal(err)
	}
	for _, id := range []string{sharedID, localID} {
		var track string
		var resources int
		if err := f.db.Get(&track, "SELECT track_json FROM song_metadata WHERE song_id=?", id); err != nil || !strings.Contains(track, "保留我的订正") {
			t.Fatal("archival removed a local correction", id, track, err)
		}
		if err := f.db.Get(&resources, "SELECT COUNT(*) FROM webdav_resource WHERE song_id=?", id); err != nil || resources != 1 {
			t.Fatal("archival lost saved media identity", id, resources, err)
		}
	}
	var resources int
	if err := f.db.Get(&resources, "SELECT COUNT(*) FROM webdav_resource WHERE song_id=?", temporaryID); err != nil || resources != 0 {
		t.Fatal("temporary resource retention changed", resources, err)
	}
}

func TestSourceSaveCompatibilityAndDurableAutomaticIngest(t *testing.T) {
	f := setup(t)
	folder := t.TempDir()
	if err := os.WriteFile(filepath.Join(folder, "歌手-歌曲.mp3"), []byte("media"), 0600); err != nil {
		t.Fatal(err)
	}
	legacy := request(t, f.router, "POST", "/api/v1/media-sources", map[string]any{"name": "旧客户端", "type": "LOCAL", "location": folder}, 201)
	if legacy["initialTaskId"] != nil {
		t.Fatal(legacy)
	}
	request(t, f.router, "GET", "/api/v1/media-sources/"+text(legacy["id"])+"/ingest-tasks/latest", nil, 204)
	request(t, f.router, "DELETE", "/api/v1/media-sources/"+text(legacy["id"]), nil, 204)
	source := request(t, f.router, "POST", "/api/v1/media-sources", map[string]any{"name": "保存并扫描", "type": "LOCAL", "location": folder, "startScan": true}, 201)
	if text(source["initialTaskId"]) == "" {
		t.Fatal(source)
	}
	f.lib.RunIngest(context.Background())
	job := request(t, f.router, "GET", "/api/v1/media-sources/"+text(source["id"])+"/ingest-tasks/latest", nil, 200)
	if job["state"] != "SUCCEEDED" {
		t.Fatal(job)
	}
	songs := request(t, f.router, "GET", "/api/v1/media-sources/"+text(source["id"])+"/songs", nil, 200)
	if number(songs["totalElements"]) != 1 {
		t.Fatal(songs)
	}
	request(t, f.router, "PUT", "/api/v1/media-sources/"+text(source["id"]), map[string]any{"name": "普通编辑", "type": "LOCAL", "location": folder}, 200)
	var count int
	if err := f.db.Get(&count, "SELECT COUNT(*) FROM media_ingest_request WHERE source_id=?", source["id"]); err != nil || count != 1 {
		t.Fatal(count, err)
	}
	dav := request(t, f.router, "POST", "/api/v1/media-sources", map[string]any{"name": "导入专用", "type": "WEBDAV", "location": "https://my.example.invalid/dav/", "startScan": false, "autoExtractEmbedded": false}, 201)
	summary := request(t, f.router, "GET", "/api/v1/media-sources/"+text(dav["id"])+"/embedded-metadata", nil, 200)
	if summary["enabled"] != false {
		t.Fatal(summary)
	}
}

func TestSharedFieldsKeepIndependentSeriesOwnershipAndBlankValues(t *testing.T) {
	f := setup(t)
	ctx := context.Background()
	source := newDAV(t, f, "field-owners")
	a, b := uuid(), uuid()
	build := func(tags map[string]any) map[string]any {
		v := entry("song.mkv", nil)
		v["metadata"].(map[string]any)["tags"] = tags
		return v
	}
	legacy := shareBundle(t, []any{build(map[string]any{"title": "旧包歌名", "artists": []any{"旧包歌手"}})}, nil)
	if _, err := f.s.ImportPart(ctx, source, legacy); err != nil {
		t.Fatal(err)
	}
	for _, step := range []struct {
		collection string
		revision   int
		tags       map[string]any
	}{
		{a, 1, map[string]any{"title": "系列A歌名", "album": "专辑A"}},
		{b, 1, map[string]any{"releaseDate": "1999"}},
		{a, 2, map[string]any{"album": "专辑A修订", "releaseDate": "2000", "title": "   "}},
		{b, 2, map[string]any{"releaseDate": "2001", "album": "抢占A专辑"}},
	} {
		if _, err := f.s.ImportPart(ctx, source, workflowPackage(t, step.collection, step.revision, build(step.tags))); err != nil {
			t.Fatal(err)
		}
	}
	var id string
	if err := f.db.Get(&id, "SELECT id FROM media_song WHERE source_id=?", source); err != nil {
		t.Fatal(err)
	}
	song, err := f.s.song(ctx, f.db, id)
	if err != nil {
		t.Fatal(err)
	}
	v := current(song)
	if text(v["title"]) != "旧包歌名" || text(v["album"]) != "专辑A修订" || text(v["releaseDate"]) != "2001" {
		t.Fatal(v)
	}
	meta := metaOf(song)
	if meta == nil || meta.Origins["album"].ExternalID == nil || *meta.Origins["album"].ExternalID != a || meta.Origins["releaseDate"].ExternalID == nil || *meta.Origins["releaseDate"].ExternalID != b {
		t.Fatal(meta)
	}
	snapshot, err := f.s.sharedSnapshot(ctx, f.db, id)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = os.Stat(filepath.Join(f.cfg.MetadataPath, text(snapshot["lyricsRef"])+".lyrics.json")); err != nil {
		t.Fatal("lyrics were not persisted", err)
	}
}

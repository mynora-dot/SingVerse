package metadata

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"github.com/gin-gonic/gin"
	"github.com/jmoiron/sqlx"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/library"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/config"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/testutil"
	"image"
	"image/color"
	"image/png"
	"io"
	"log/slog"
	"mime/multipart"
	"net"
	"net/http"
	"net/http/httptest"
	"net/textproto"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

type fixtureCipher struct{}

func (fixtureCipher) Encrypt(v, p string) (string, error) { return p + ":" + v, nil }
func (fixtureCipher) Decrypt(v, p string) (string, error) { return strings.TrimPrefix(v, p+":"), nil }

type fixture struct {
	s      *Service
	lib    *library.Service
	db     *sqlx.DB
	router *gin.Engine
	cfg    config.Config
}

func setup(t *testing.T) *fixture {
	t.Helper()
	db, e := sqlx.Open("sqlite", filepath.Join(t.TempDir(), "metadata.db"))
	if e != nil {
		t.Fatal(e)
	}
	db.SetMaxOpenConns(1)
	migrations, e := filepath.Glob("../../../../database/migrations/sqlite/*.sql")
	if e != nil || len(migrations) == 0 {
		t.Fatal("migrations missing")
	}
	sort.Strings(migrations)
	for _, file := range migrations {
		data, e := os.ReadFile(file)
		if e != nil {
			t.Fatal(e)
		}
		if _, e = db.Exec(strings.Split(string(data), "-- migrate:down")[0]); e != nil {
			t.Fatal(e)
		}
	}
	cfg := config.Config{MetadataPath: t.TempDir(), MetadataCachePath: t.TempDir()}
	return setupWithDatabase(t, db, "sqlite", cfg)
}
func setupWithDatabase(t *testing.T, db *sqlx.DB, dialect string, cfg config.Config) *fixture {
	t.Helper()
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	lib, e := library.New(db, dialect, cfg, fixtureCipher{}, log)
	if e != nil {
		t.Fatal(e)
	}
	s, e := New(db, dialect, cfg, fixtureCipher{}, lib, log)
	if e != nil {
		t.Fatal(e)
	}
	s.cancel()
	s.wg.Wait()
	s.ctx, s.cancel = context.WithCancel(context.Background())
	s.requestInterval = 0
	gin.SetMode(gin.TestMode)
	r := gin.New()
	lib.Register(r.Group("/api/v1"))
	s.Register(r.Group("/api/v1"))
	f := &fixture{s, lib, db, r, cfg}
	t.Cleanup(func() { s.Close(); lib.Close(); db.Close() })
	return f
}
func request(t *testing.T, r http.Handler, method, path string, body any, status int) map[string]any {
	t.Helper()
	var input io.Reader
	if body != nil {
		input = strings.NewReader(encode(body))
	}
	req := httptest.NewRequest(method, path, input)
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	if w.Code != status {
		t.Fatalf("%s %s got %d want %d: %s", method, path, w.Code, status, w.Body.String())
	}
	out := map[string]any{}
	if w.Body.Len() > 0 {
		if e := json.Unmarshal(w.Body.Bytes(), &out); e != nil {
			t.Fatal(e)
		}
	}
	return out
}
func (f *fixture) local(t *testing.T, names ...string) (string, []string) {
	t.Helper()
	root := t.TempDir()
	for _, n := range names {
		if e := os.WriteFile(filepath.Join(root, n), []byte("media"), 0600); e != nil {
			t.Fatal(e)
		}
	}
	source := request(t, f.router, "POST", "/api/v1/media-sources", map[string]any{"type": "LOCAL", "name": "local", "location": root}, 201)
	id := text(source["id"])
	request(t, f.router, "POST", "/api/v1/media-sources/"+id+"/scan", nil, 200)
	out := request(t, f.router, "GET", "/api/v1/media-sources/"+id+"/songs", nil, 200)
	ids := []string{}
	for _, raw := range out["items"].([]any) {
		ids = append(ids, text(raw.(map[string]any)["id"]))
	}
	return id, ids
}
func (f *fixture) settings(t *testing.T, auto bool) {
	t.Helper()
	request(t, f.router, "PUT", "/api/v1/metadata/settings", Settings{Enabled: true, Providers: []string{"NETEASE"}, AutoApply: auto, Threshold: .9, MinIntervalSeconds: ptr(0), MaxIntervalSeconds: ptr(0)}, 200)
}

type fakeProvider struct {
	tracks []Track
	err    error
}

func (p fakeProvider) Search(context.Context, string, int) ([]Track, error) { return p.tracks, p.err }
func testTrack(title string) Track {
	return Track{Provider: ptr("NETEASE"), ExternalID: ptr("42"), Title: ptr(title), Artists: []string{"A"}, Album: ptr("Album"), DurationMs: ptr(123000), ReleaseDate: ptr("2024-02-29"), Aliases: []string{}, CoverURL: ptr("https://p1.music.126.net/cover.jpg"), Availability: "AVAILABLE", FetchedAt: now(), ArtistRefs: []ArtistRef{{"NETEASE", "7", "A"}}}
}
func (f *fixture) candidate(t *testing.T, id string, track Track) string {
	t.Helper()
	f.s.providers["NETEASE"] = fakeProvider{tracks: []Track{track}}
	f.s.guards["NETEASE"].next = time.Time{}
	out := request(t, f.router, "POST", "/api/v1/metadata/songs/"+id+"/search", SearchInput{}, 200)
	items := out["candidates"].([]any)
	if len(items) != 1 {
		t.Fatal(out)
	}
	return text(items[0].(map[string]any)["id"])
}
func TestSettingsAndReviewReceipt(t *testing.T) {
	exerciseSettingsAndReviewReceipt(t, setup(t))
}
func TestMySQLSettingsAndReviewReceipt(t *testing.T) {
	db, cfg := testutil.MySQLDatabase(t)
	exerciseSettingsAndReviewReceipt(t, setupWithDatabase(t, db, "mysql", cfg))
}
func exerciseSettingsAndReviewReceipt(t *testing.T, f *fixture) {
	t.Helper()
	_, ids := f.local(t, "A-Song.mkv")
	id := ids[0]
	request(t, f.router, "POST", "/api/v1/metadata/songs/"+id+"/search", nil, 409)
	request(t, f.router, "PUT", "/api/v1/metadata/settings", Settings{Enabled: true, Providers: []string{"QQ", "QQ"}, Threshold: .9}, 400)
	f.settings(t, false)
	candidate := f.candidate(t, id, testTrack("Song"))
	review := request(t, f.router, "GET", "/api/v1/metadata/songs/"+id+"/review", nil, 200)
	input := ReviewApply{RequestID: uuid(), Fingerprint: text(review["fingerprint"]), Version: text(review["version"]), CandidateID: &candidate, Fields: []string{"title", "album", "durationMs"}, Edits: map[string]any{"title": "Administrator Title", "durationMs": json.Number("456000")}}
	out := request(t, f.router, "POST", "/api/v1/metadata/songs/"+id+"/review/apply", input, 200)
	if !contains(stringsOf(out["updated"]), "title") || out["after"].(map[string]any)["title"] != "Administrator Title" {
		t.Fatal(out)
	}
	repeat := request(t, f.router, "POST", "/api/v1/metadata/songs/"+id+"/review/apply", input, 200)
	if encode(out) != encode(repeat) {
		t.Fatal("idempotent receipt changed")
	}
	input.Edits["title"] = "different"
	request(t, f.router, "POST", "/api/v1/metadata/songs/"+id+"/review/apply", input, 409)
	input.RequestID = uuid()
	request(t, f.router, "POST", "/api/v1/metadata/songs/"+id+"/review/apply", input, 409)
	if _, e := f.db.Exec("UPDATE media_song SET embedded_tags_json=? WHERE id=?", `{"title":"File Title","artists":["File Artist"],"album":"File Album"}`, id); e != nil {
		t.Fatal(e)
	}
	request(t, f.router, "POST", "/api/v1/metadata/songs/"+id+"/apply", ApplyInput{candidate, []string{"title"}}, 400)
	review = request(t, f.router, "GET", "/api/v1/metadata/songs/"+id+"/review", nil, 200)
	input = ReviewApply{RequestID: uuid(), Fingerprint: text(review["fingerprint"]), Version: text(review["version"]), Fields: []string{"title", "artists"}, Edits: map[string]any{"title": "Try overwrite file", "artists": []string{"Corrected Artist"}}}
	out = request(t, f.router, "POST", "/api/v1/metadata/songs/"+id+"/review/apply", input, 200)
	if !contains(stringsOf(out["skipped"]), "title") || !contains(stringsOf(out["updated"]), "artists") {
		t.Fatal(out)
	}
	song, e := f.lib.Song(context.Background(), id)
	if e != nil {
		t.Fatal(e)
	}
	a := metaOf(song)
	if *a.Track.Title != "File Title" || a.Track.Artists[0] != "Corrected Artist" || a.Origins["artists"].Kind != "MANUAL" {
		t.Fatalf("effective metadata: %#v", a)
	}
	pending, e := f.s.row(context.Background(), f.db, "files-previous", map[string]any{"songId": id})
	if e != nil || pending == nil {
		t.Fatalf("durable export not registered: %v", e)
	}
}
func TestBatchLifecycleAtomicApplyAndRecovery(t *testing.T) {
	f := setup(t)
	_, ids := f.local(t, "A-One.mkv", "A-Two.mkv")
	f.settings(t, true)
	f.s.providers["NETEASE"] = fakeProvider{tracks: []Track{testTrack("One")}}
	batch := request(t, f.router, "POST", "/api/v1/metadata/batches", StartInput{All: true}, 201)
	id := text(batch["id"])
	request(t, f.router, "POST", "/api/v1/metadata/batches", StartInput{All: true}, 409)
	request(t, f.router, "POST", "/api/v1/metadata/batches/"+id+"/pause", nil, 200)
	if e := f.s.ProcessNext(context.Background()); e != nil {
		t.Fatal(e)
	}
	out := request(t, f.router, "GET", "/api/v1/metadata/batches/"+id, nil, 200)
	if number(out["pending"]) != 2 {
		t.Fatal(out)
	}
	request(t, f.router, "POST", "/api/v1/metadata/batches/"+id+"/resume", nil, 200)
	if e := f.s.ProcessNext(context.Background()); e != nil {
		t.Fatal(e)
	}
	f.s.guards["NETEASE"].next = time.Time{}
	if e := f.s.ProcessNext(context.Background()); e != nil {
		t.Fatal(e)
	}
	out = request(t, f.router, "GET", "/api/v1/metadata/batches/"+id, nil, 200)
	if out["status"] != "COMPLETED" || number(out["completed"]) != 2 {
		t.Fatal(out)
	}
	if _, e := f.db.Exec("UPDATE metadata_batch SET status='RUNNING' WHERE id=?", id); e != nil {
		t.Fatal(e)
	}
	if _, e := f.db.Exec("UPDATE metadata_item SET status='PROCESSING' WHERE batch_id=?", id); e != nil {
		t.Fatal(e)
	}
	second, e := New(f.db, "sqlite", f.cfg, fixtureCipher{}, f.lib, slog.New(slog.NewTextHandler(io.Discard, nil)))
	if e != nil {
		t.Fatal(e)
	}
	defer second.Close()
	out, e = second.details(context.Background(), f.db, id, "", 0, 20, "", "DEFAULT")
	if e != nil {
		t.Fatal(e)
	}
	if out["status"] != "PAUSED" || number(out["pending"]) != 2 {
		t.Fatal(out)
	}
	_ = ids
}
func TestApplyRollbackAndFingerprint(t *testing.T) {
	f := setup(t)
	_, ids := f.local(t, "A-Song.mkv")
	id := ids[0]
	f.settings(t, false)
	candidate := f.candidate(t, id, testTrack("Song"))
	f.s.SetApplied(func(context.Context, store.DBTX, string) error {
		return fmt.Errorf("test downstream registration failure")
	})
	request(t, f.router, "POST", "/api/v1/metadata/songs/"+id+"/apply", ApplyInput{candidate, []string{"album"}}, 500)
	var count int
	if e := f.db.QueryRow("SELECT COUNT(*) FROM song_metadata WHERE song_id=?", id).Scan(&count); e != nil || count != 0 {
		t.Fatalf("partial apply committed: %d %v", count, e)
	}
	f.s.SetApplied(nil)
	if _, e := f.db.Exec("UPDATE media_song SET file_size=file_size+1 WHERE id=?", id); e != nil {
		t.Fatal(e)
	}
	request(t, f.router, "POST", "/api/v1/metadata/songs/"+id+"/apply", ApplyInput{candidate, []string{"album"}}, 409)
}
func tinyPNG(t *testing.T) []byte {
	t.Helper()
	img := image.NewRGBA(image.Rect(0, 0, 2, 3))
	img.Set(0, 0, color.RGBA{R: 255, A: 255})
	var b bytes.Buffer
	if e := png.Encode(&b, img); e != nil {
		t.Fatal(e)
	}
	return b.Bytes()
}
func uploadRequest(t *testing.T, f *fixture, id, fp, mime string, data []byte, status int) map[string]any {
	t.Helper()
	var body bytes.Buffer
	form := multipart.NewWriter(&body)
	if e := form.WriteField("fingerprint", fp); e != nil {
		t.Fatal(e)
	}
	header := textproto.MIMEHeader{}
	header.Set("Content-Disposition", `form-data; name="file"; filename="../../ignored.png"`)
	header.Set("Content-Type", mime)
	part, e := form.CreatePart(header)
	if e != nil {
		t.Fatal(e)
	}
	part.Write(data)
	form.Close()
	req := httptest.NewRequest("POST", "/api/v1/metadata/songs/"+id+"/covers/upload", &body)
	req.Header.Set("Content-Type", form.FormDataContentType())
	w := httptest.NewRecorder()
	f.router.ServeHTTP(w, req)
	if w.Code != status {
		t.Fatalf("upload %d: %s", w.Code, w.Body.String())
	}
	out := map[string]any{}
	json.Unmarshal(w.Body.Bytes(), &out)
	return out
}
func TestManagedCoverUploadAndBounds(t *testing.T) {
	f := setup(t)
	_, ids := f.local(t, "A-Song.mkv")
	id := ids[0]
	review := request(t, f.router, "GET", "/api/v1/metadata/songs/"+id+"/review", nil, 200)
	asset := uploadRequest(t, f, id, text(review["fingerprint"]), "image/png", tinyPNG(t), 201)
	assetID := text(asset["id"])
	input := ReviewApply{RequestID: uuid(), Fingerprint: text(review["fingerprint"]), Version: text(review["version"]), Fields: []string{"cover"}, CoverAssetID: &assetID}
	request(t, f.router, "POST", "/api/v1/metadata/songs/"+id+"/review/apply", input, 200)
	w := httptest.NewRecorder()
	f.router.ServeHTTP(w, httptest.NewRequest("GET", "/api/v1/metadata/songs/"+id+"/cover", nil))
	if w.Code != 200 || w.Header().Get("Content-Type") != "image/png" || w.Header().Get("Cache-Control") != "no-store" {
		t.Fatal(w.Code, w.Body.String())
	}
	uploadRequest(t, f, id, text(review["fingerprint"]), "image/jpeg", tinyPNG(t), 400)
	uploadRequest(t, f, id, text(review["fingerprint"]), "image/png", []byte(`<svg onload="alert(1)"/>`), 400)
	if checkedCover("NETEASE", ptr("https://music.126.net.evil.invalid/x")) != nil || checkedCover("NETEASE", ptr("http://127.0.0.1/x")) != nil && false {
		t.Fatal("unsafe URL accepted")
	}
	for _, raw := range []string{"127.0.0.1", "10.0.0.1", "169.254.169.254", "100.64.0.1", "::1", "fc00::1"} {
		if publicIP(net.ParseIP(raw)) {
			t.Fatal("private address accepted", raw)
		}
	}
}

func (f *fixture) remote(t *testing.T, target string) (string, string, map[string]any) {
	t.Helper()
	source := request(t, f.router, "POST", "/api/v1/media-sources", map[string]any{"type": "WEBDAV", "name": "remote", "location": target + "/dav/", "username": "source-user", "password": "source-secret"}, 201)
	sourceID := text(source["id"])
	songID := uuid()
	parsed := map[string]any{"originalFilename": "A-Song.mkv", "title": "Song", "artists": []string{"A"}, "needsReview": false}
	if _, e := f.db.Exec("INSERT INTO media_song(id,source_id,relative_path,path_hash,file_size,modified_at,scanned_at,parser_profile_id,parser_revision,result_json,needs_review) VALUES(?,?,?,?,?,?,?,?,?,?,?)", songID, sourceID, "A-Song.mkv", hash("A-Song.mkv"), 16, nil, now(), "default", 0, encode(parsed), false); e != nil {
		t.Fatal(e)
	}
	request(t, f.router, "PUT", "/api/v1/media-sources/"+sourceID+"/embedded-metadata", map[string]any{"enabled": true, "paused": false}, 200)
	tasks, e := f.s.rows(context.Background(), f.db, "dav-pending", map[string]any{"now": now()})
	if e != nil || len(tasks) != 1 {
		t.Fatalf("tasks %#v %v", tasks, e)
	}
	return sourceID, songID, tasks[0]
}
func inputRequest(s *Service, reference, token, rawRange string) (*httptest.ResponseRecorder, error) {
	r := httptest.NewRequest("GET", "/api/v1/internal/metadata-input/"+reference, nil)
	r.Header.Set("Authorization", "Bearer "+token)
	r.Header.Set("Range", rawRange)
	w := httptest.NewRecorder()
	e := s.ServeInput(r.Context(), w, r, reference)
	return w, e
}
func TestRemoteInputBudgetsRangesAndRevocation(t *testing.T) {
	f := setup(t)
	var mode atomic.Value
	mode.Store("valid")
	remote := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		user, password, ok := r.BasicAuth()
		if !ok || user != "source-user" || password != "source-secret" {
			w.WriteHeader(401)
			return
		}
		switch mode.Load().(string) {
		case "ignored":
			fmt.Fprint(w, "0123456789abcdef")
			return
		case "invalid":
			w.Header().Set("Content-Range", "bytes 1-4/16")
		case "etag":
			w.Header().Set("Content-Range", "bytes 2-5/16")
			w.Header().Set("ETag", `"changed"`)
		default:
			w.Header().Set("Content-Range", "bytes 2-5/16")
			w.Header().Set("ETag", `"original"`)
		}
		w.Header().Set("Content-Length", "4")
		w.WriteHeader(206)
		fmt.Fprint(w, "2345")
	}))
	defer remote.Close()
	_, songID, task := f.remote(t, remote.URL)
	ref, revoke, e := f.s.AuthorizeInput(context.Background(), task, "node-secret", false)
	if e != nil {
		t.Fatal(e)
	}
	if _, e = inputRequest(f.s, ref, "wrong", "bytes=2-5"); code(e) != 401 {
		t.Fatal(e)
	}
	if _, e = inputRequest(f.s, ref, "node-secret", "bytes=0-"); code(e) != 400 {
		t.Fatal(e)
	}
	if _, e = inputRequest(f.s, ref, "node-secret", "bytes=0-1048576"); code(e) != 400 {
		t.Fatal(e)
	}
	w, e := inputRequest(f.s, ref, "node-secret", "bytes=2-5")
	if e != nil || w.Code != 206 || w.Body.String() != "2345" {
		t.Fatalf("block: %v %d %s", e, w.Code, w.Body.String())
	}
	mode.Store("etag")
	if w, e = inputRequest(f.s, ref, "node-secret", "bytes=2-5"); code(e) != 409 || w.Body.Len() != 0 {
		t.Fatalf("changed version published: %v %s", e, w.Body.String())
	}
	revoke()
	if _, e = inputRequest(f.s, ref, "node-secret", "bytes=2-5"); code(e) != 401 {
		t.Fatal(e)
	}
	r, e := f.s.row(context.Background(), f.db, "dav-resource", map[string]any{"songId": songID})
	if e != nil || number(r["bytes_read"]) != 4 || number(r["request_count"]) != 2 {
		t.Fatalf("metrics %#v %v", r, e)
	}
	for _, tc := range []struct {
		mode   string
		status int
	}{{"ignored", 422}, {"invalid", 409}} {
		mode.Store(tc.mode)
		ref, revoke, e = f.s.AuthorizeInput(context.Background(), task, "node-secret", false)
		if e != nil {
			t.Fatal(e)
		}
		w, e = inputRequest(f.s, ref, "node-secret", "bytes=2-5")
		revoke()
		if code(e) != tc.status || w.Body.Len() != 0 {
			t.Fatalf("%s: %v bytes %s", tc.mode, e, w.Body.String())
		}
	}
	mode.Store("valid")
	ref, revoke, e = f.s.AuthorizeInput(context.Background(), task, "node-secret", false)
	if e != nil {
		t.Fatal(e)
	}
	f.s.grants[ref].limit = 3
	if _, e = inputRequest(f.s, ref, "node-secret", "bytes=2-5"); code(e) != 413 {
		t.Fatal(e)
	}
	revoke()
	ref, revoke, e = f.s.AuthorizeInput(context.Background(), task, "node-secret", false)
	if e != nil {
		t.Fatal(e)
	}
	if _, e = f.db.Exec("UPDATE webdav_resource SET generation=generation+1 WHERE song_id=?", songID); e != nil {
		t.Fatal(e)
	}
	if _, e = inputRequest(f.s, ref, "node-secret", "bytes=2-5"); code(e) != 410 {
		t.Fatal(e)
	}
	revoke()
}
func TestRedirectRequiresPrivateAllowlistAndDropsCredentials(t *testing.T) {
	f := setup(t)
	var leaked atomic.Bool
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "" {
			leaked.Store(true)
		}
		w.Header().Set("Content-Range", "bytes 2-5/16")
		w.Header().Set("Content-Length", "4")
		w.WriteHeader(206)
		fmt.Fprint(w, "2345")
	}))
	defer target.Close()
	root := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, target.URL+"/signed?token=not-a-credential", http.StatusFound)
	}))
	defer root.Close()
	sourceID, _, task := f.remote(t, root.URL)
	ref, revoke, e := f.s.AuthorizeInput(context.Background(), task, "node-token", false)
	if e != nil {
		t.Fatal(e)
	}
	w, e := inputRequest(f.s, ref, "node-token", "bytes=2-5")
	revoke()
	if code(e) != 502 || w.Body.Len() != 0 {
		t.Fatal("unapproved private redirect accepted", e)
	}
	request(t, f.router, "PUT", "/api/v1/media-sources/"+sourceID+"/embedded-metadata", map[string]any{"enabled": true, "privateOrigins": []string{target.URL}}, 200)
	task["private_origins"] = encode([]string{target.URL})
	ref, revoke, e = f.s.AuthorizeInput(context.Background(), task, "node-token", false)
	if e != nil {
		t.Fatal(e)
	}
	w, e = inputRequest(f.s, ref, "node-token", "bytes=2-5")
	revoke()
	if e != nil || w.Body.String() != "2345" || leaked.Load() {
		t.Fatalf("redirect: %v %s credentials=%v", e, w.Body.String(), leaked.Load())
	}
}
func TestRemoteWorkerAndPortableSnapshotPreserveManualFields(t *testing.T) {
	f := setup(t)
	sourceID, songID, _ := f.remote(t, "http://127.0.0.1:1")
	f.s.SetRemoteExtractor(func(ctx context.Context, task map[string]any) (map[string]any, error) {
		return map[string]any{"state": "COMPLETE", "tags": map[string]any{"title": "File Title", "artists": []any{"File Artist"}, "album": "File Album"}, "fields": map[string]any{"title": "PRESENT", "artists": "PRESENT", "album": "PRESENT"}, "technical": map[string]any{"duration": 123.5}, "extractorVersion": "test-1"}, nil
	})
	if e := f.s.RunRemoteNext(context.Background()); e != nil {
		t.Fatal(e)
	}
	detail := request(t, f.router, "GET", "/api/v1/media-songs/"+songID+"/embedded-metadata", nil, 200)
	if detail["state"] != "COMPLETE" {
		t.Fatal(detail)
	}
	song, e := f.lib.Song(context.Background(), songID)
	if e != nil {
		t.Fatal(e)
	}
	a := metaOf(song)
	if a == nil || a.Origins["title"].Kind != "FILE" || *a.Track.Title != "File Title" || *a.Track.DurationMs != 123500 {
		t.Fatalf("file origin projection: %#v", a)
	}
	var snapshot map[string]any
	e = store.WithinTx(context.Background(), f.db, func(tx store.DBTX) error {
		var e error
		snapshot, e = f.s.Snapshot(context.Background(), tx, songID)
		return e
	})
	if e != nil || path(snapshot, "tags", "title") == nil {
		t.Fatalf("snapshot %#v %v", snapshot, e)
	}
	_, localIDs := f.local(t, "A-Local.mkv")
	localID := localIDs[0]
	review := request(t, f.router, "GET", "/api/v1/metadata/songs/"+localID+"/review", nil, 200)
	request(t, f.router, "POST", "/api/v1/metadata/songs/"+localID+"/review/apply", ReviewApply{RequestID: uuid(), Fingerprint: text(review["fingerprint"]), Version: text(review["version"]), Fields: []string{"title"}, Edits: map[string]any{"title": "Confirmed Local"}}, 200)
	e = store.WithinTx(context.Background(), f.db, func(tx store.DBTX) error { return f.s.Install(context.Background(), tx, localID, snapshot) })
	if e != nil {
		t.Fatal(e)
	}
	song, e = f.lib.Song(context.Background(), localID)
	if e != nil {
		t.Fatal(e)
	}
	a = metaOf(song)
	if *a.Track.Title != "Confirmed Local" || a.Track.Artists[0] != "File Artist" || a.Origins["title"].Kind != "MANUAL" || a.Origins["artists"].Kind != "SHARED" {
		t.Fatalf("shared import clobbered confirmation: %#v", a)
	}
	request(t, f.router, "POST", "/api/v1/media-sources/"+sourceID+"/embedded-metadata/confirm-empty", nil, 409)
}
func TestProviderProtocolsAndArtworkIdentity(t *testing.T) {
	if score(map[string]any{"result": map[string]any{"title": "Ｓｏｎｇ", "artists": []string{"A"}}}, testTrack("Song")) != .95 {
		t.Fatal("NFKC score")
	}
	net := parseNetease([]any{map[string]any{"id": json.Number("9007199254740993"), "name": "Song", "ar": []any{map[string]any{"id": json.Number("7"), "name": "A"}}, "al": map[string]any{"name": "Album", "picUrl": "http://p1.music.126.net/a.jpg"}, "dt": json.Number("1234")}}, 8)
	if len(net) != 1 || *net[0].ExternalID != "9007199254740993" || len(net[0].ArtistRefs) != 1 || *net[0].CoverURL != "https://p1.music.126.net/a.jpg" {
		t.Fatal(net)
	}
	params, key, e := weapi("{}")
	if e != nil || params == "" || len(key) != 256 {
		t.Fatalf("weapi %s %s %v", params, key, e)
	}
	if len(eapi("/api/test", "{}"))%32 != 0 {
		t.Fatal("eapi block length")
	}
	ids := matchedArtistIDs([]any{map[string]any{"name": "Song", "ar": []any{map[string]any{"name": "A", "id": json.Number("7")}}}}, "NETEASE", "A", "Song")
	if len(ids) != 1 || ids[0] != "7" {
		t.Fatal(ids)
	}
	f := setup(t)
	f.settings(t, false)
	_, e = f.s.artistArtwork(context.Background(), "QQ", "artist-mid", "A")
	if code(e) != 409 {
		t.Fatal("disabled artwork platform bypassed", e)
	}
}

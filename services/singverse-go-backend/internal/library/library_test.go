package library

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/jmoiron/sqlx"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/config"
)

type testCipher struct{}

func (testCipher) Encrypt(v, p string) (string, error) { return p + ":" + v, nil }
func (testCipher) Decrypt(v, p string) (string, error) { return strings.TrimPrefix(v, p+":"), nil }
func newTestService(t *testing.T) (*Service, *gin.Engine) {
	t.Helper()
	db, e := sqlx.Open("sqlite", filepath.Join(t.TempDir(), "library.db"))
	if e != nil {
		t.Fatal(e)
	}
	db.SetMaxOpenConns(1)
	migrations, e := filepath.Glob("../../../../database/migrations/sqlite/*.sql")
	if e != nil || len(migrations) == 0 {
		t.Fatalf("migrations: %v", e)
	}
	sort.Strings(migrations)
	for _, file := range migrations {
		data, e := os.ReadFile(file)
		if e != nil {
			t.Fatal(e)
		}
		up := strings.Split(string(data), "-- migrate:down")[0]
		if _, e = db.Exec(up); e != nil {
			t.Fatalf("%s: %v", file, e)
		}
	}
	s, e := New(db, "sqlite", config.Config{}, testCipher{}, slog.New(slog.NewTextHandler(io.Discard, nil)))
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(func() { s.Close(); db.Close() })
	gin.SetMode(gin.TestMode)
	router := gin.New()
	s.Register(router.Group("/api/v1"))
	return s, router
}
func requestJSON(t *testing.T, router http.Handler, method, path string, input any, status int) map[string]any {
	t.Helper()
	var body io.Reader
	if input != nil {
		body = strings.NewReader(encode(input))
	}
	r := httptest.NewRequest(method, path, body)
	if input != nil {
		r.Header.Set("Content-Type", "application/json")
	}
	w := httptest.NewRecorder()
	router.ServeHTTP(w, r)
	if w.Code != status {
		t.Fatalf("%s %s: got %d want %d: %s", method, path, w.Code, status, w.Body.String())
	}
	out := map[string]any{}
	if w.Body.Len() > 0 {
		if e := json.Unmarshal(w.Body.Bytes(), &out); e != nil {
			t.Fatal(e)
		}
	}
	return out
}
func localSource(t *testing.T, r http.Handler, path string) map[string]any {
	return requestJSON(t, r, "POST", "/api/v1/media-sources", map[string]any{"name": "本地测试", "type": "LOCAL", "location": path}, 201)
}
func TestSourceScanProjectionAndRange(t *testing.T) {
	s, router := newTestService(t)
	media := t.TempDir()
	content := []byte("0123456789abcdef")
	if e := os.WriteFile(filepath.Join(media, "周杰伦-晴天.mkv"), content, 0600); e != nil {
		t.Fatal(e)
	}
	if e := os.WriteFile(filepath.Join(media, "ignore.txt"), []byte("notes"), 0600); e != nil {
		t.Fatal(e)
	}
	outside := filepath.Join(t.TempDir(), "outside.mkv")
	os.WriteFile(outside, []byte("secret"), 0600)
	os.Symlink(outside, filepath.Join(media, "outside.mkv"))
	src := localSource(t, router, media)
	id := src["id"].(string)
	base := "/api/v1/media-sources/" + id
	requestJSON(t, router, "POST", "/api/v1/media-sources", map[string]any{"name": "duplicate", "type": "LOCAL", "location": media}, 409)
	scan := requestJSON(t, router, "POST", base+"/scan", nil, 200)
	if scan["totalSongs"] != float64(1) || scan["skippedFiles"] != float64(2) {
		t.Fatalf("scan: %#v", scan)
	}
	page := requestJSON(t, router, "GET", "/api/v1/media-songs?q=%E6%99%B4%E5%A4%A9", nil, 200)
	songs := page["items"].([]any)
	if len(songs) != 1 {
		t.Fatal(page)
	}
	song := songs[0].(map[string]any)
	if song["result"].(map[string]any)["title"] != "晴天" {
		t.Fatal(song)
	}
	songID := song["id"].(string)
	tests := []struct {
		method, rangeValue string
		status             int
		body, rangeHeader  string
	}{{"GET", "bytes=2-5", 206, "2345", "bytes 2-5/16"}, {"GET", "bytes=-3", 206, "def", "bytes 13-15/16"}, {"HEAD", "bytes=1-2", 206, "", "bytes 1-2/16"}, {"GET", "bytes=100-", 416, "", "bytes */16"}, {"GET", "bytes=1-2,4-5", 400, "", ""}}
	for _, tt := range tests {
		r := httptest.NewRequest(tt.method, "/api/v1/media-songs/"+songID+"/video", nil)
		r.Header.Set("Range", tt.rangeValue)
		w := httptest.NewRecorder()
		router.ServeHTTP(w, r)
		if w.Code != tt.status {
			t.Fatalf("%s: %d %s", tt.rangeValue, w.Code, w.Body.String())
		}
		if tt.status != 400 && w.Body.String() != tt.body {
			t.Fatalf("unexpected bytes: %q", w.Body.String())
		}
		if tt.rangeHeader != "" && w.Header().Get("Content-Range") != tt.rangeHeader {
			t.Fatalf("range: %s", w.Header().Get("Content-Range"))
		}
	}
	tx, e := s.db.BeginTxx(context.Background(), nil)
	if e != nil {
		t.Fatal(e)
	}
	if _, e = s.SongWith(context.Background(), tx, songID); e != nil {
		tx.Rollback()
		t.Fatal(e)
	}
	tx.Rollback()
	requestJSON(t, router, "POST", base+"/scan", nil, 200)
	second := requestJSON(t, router, "GET", base+"/songs", nil, 200)
	if second["items"].([]any)[0].(map[string]any)["id"] != songID {
		t.Fatal("song id changed across scans")
	}
	requestJSON(t, router, "DELETE", base, nil, 204)
	requestJSON(t, router, "GET", base, nil, 404)
}
func TestRuleProfilesRevisionAndReferences(t *testing.T) {
	_, router := newTestService(t)
	w := requestJSON(t, router, "GET", "/api/v1/filename-parser/profiles", nil, 200)
	if w["revision"] != float64(0) {
		t.Fatal(w)
	}
	created := requestJSON(t, router, "POST", "/api/v1/filename-parser/rule-definitions", map[string]any{"revision": 0, "definition": map[string]any{"name": "custom", "type": "TEMPLATE", "expression": "{title}__{artist}"}}, 201)
	id := created["changedId"].(string)
	requestJSON(t, router, "POST", "/api/v1/filename-parser/profiles", map[string]any{"revision": 0, "profile": map[string]any{"name": "stale", "entries": []any{}}}, 409)
	saved := requestJSON(t, router, "POST", "/api/v1/filename-parser/profiles", map[string]any{"revision": 1, "profile": map[string]any{"name": "test profile", "entries": []any{map[string]any{"ruleId": id, "enabled": true}}}}, 201)
	profileID := saved["changedId"].(string)
	requestJSON(t, router, "DELETE", "/api/v1/filename-parser/rule-definitions/"+id+"?revision=2", nil, 409)
	preview := requestJSON(t, router, "POST", "/api/v1/filename-parser/profiles/preview", map[string]any{"filename": "晴天__周杰伦.mkv", "profileId": profileID}, 200)
	if preview["preview"].(map[string]any)["result"].(map[string]any)["title"] != "晴天" {
		t.Fatal(preview)
	}
	requestJSON(t, router, "POST", "/api/v1/filename-parser/preview", map[string]any{"filename": "周杰伦-晴天.mkv", "rules": []any{}}, 200)
	requestJSON(t, router, "DELETE", "/api/v1/filename-parser/profiles/default?revision=2", nil, 409)
	src := localSource(t, router, t.TempDir())
	src["parserProfileId"] = profileID
	src["password"] = nil
	requestJSON(t, router, "PUT", "/api/v1/media-sources/"+src["id"].(string), src, 200)
	requestJSON(t, router, "DELETE", "/api/v1/filename-parser/profiles/"+profileID+"?revision=2", nil, 409)
}
func TestParserBoundaries(t *testing.T) {
	defaults, _ := resolve(defaultDocument(), defaultDocument().Profiles[0].Entries)
	for _, tt := range []struct{ filename, title, language, version string }{{"周杰伦《晴天》.MKV", "晴天", "UNKNOWN", "UNKNOWN"}, {"晴天（周杰伦）.mp4", "晴天", "UNKNOWN", "UNKNOWN"}, {"周杰伦-晴天-国语-原唱-1080P.mkv", "晴天", "MANDARIN", "ORIGINAL"}, {"晴天_周杰伦_伴奏.flac", "晴天", "UNKNOWN", "ACCOMPANIMENT"}} {
		p, e := Parse(tt.filename, defaults)
		if e != nil {
			t.Fatal(e)
		}
		if p.Result.Title == nil || *p.Result.Title != tt.title || p.Result.Language != tt.language || p.Result.VersionType != tt.version {
			t.Fatalf("%s: %#v", tt.filename, p)
		}
	}
	custom := []Rule{{ID: "test", Name: "ambiguous", Type: "TEMPLATE", Expression: ptr("{artist}-{title}"), Enabled: ptr(true)}}
	p, e := Parse("A-B-C.mkv", custom)
	if e != nil || p.Result.ParseStatus != "NEEDS_REVIEW" || p.SelectedRule != nil {
		t.Fatalf("ambiguous: %#v %v", p, e)
	}
	custom[0].Type = "REGEX"
	custom[0].Expression = ptr(`(?P<artist>.*)(?=foo)(?P<title>.*)`)
	if _, e = Parse("x.mkv", custom); e == nil {
		t.Fatal("lookahead accepted")
	}
	p, e = Parse("anything.exe", defaults)
	if e != nil || p.Result.ParseStatus != "FAILED" {
		t.Fatal(p, e)
	}
	if _, e = Parse(strings.Repeat("长", 1025), defaults); e == nil {
		t.Fatal("unbounded filename")
	}
}
func TestWebDAVScanPreservesIndexOnFailureAndEmpty(t *testing.T) {
	_, router := newTestService(t)
	mode := "success"
	remote := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "PROPFIND" {
			t.Errorf("unexpected method %s", r.Method)
		}
		if r.Header.Get("Depth") != "1" {
			t.Error("missing depth")
		}
		w.Header().Set("Content-Type", "application/xml")
		if mode == "failure" {
			w.WriteHeader(401)
			return
		}
		w.WriteHeader(207)
		fmt.Fprint(w, `<d:multistatus xmlns:d="DAV:"><d:response><d:href>/dav/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>`)
		if mode == "success" {
			fmt.Fprint(w, `<d:response><d:href>/dav/A-Song.mkv</d:href><d:propstat><d:prop><d:resourcetype/><d:getcontentlength>50</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>`)
		}
		fmt.Fprint(w, `</d:multistatus>`)
	}))
	defer remote.Close()
	src := requestJSON(t, router, "POST", "/api/v1/media-sources", map[string]any{"name": "dav", "type": "WEBDAV", "location": remote.URL + "/dav/"}, 201)
	id := src["id"].(string)
	base := "/api/v1/media-sources/" + id
	run := func(want string) {
		task := requestJSON(t, router, "POST", base+"/scan-tasks", nil, 202)
		taskPath := base + "/scan-tasks/" + task["id"].(string)
		deadline := time.Now().Add(8 * time.Second)
		for time.Now().Before(deadline) {
			v := requestJSON(t, router, "GET", taskPath, nil, 200)
			status := v["status"].(string)
			if status != "QUEUED" && status != "RUNNING" {
				if status != want {
					t.Fatalf("task: %#v", v)
				}
				return
			}
			time.Sleep(20 * time.Millisecond)
		}
		t.Fatal("scan did not complete")
	}
	run("SUCCEEDED")
	page := requestJSON(t, router, "GET", base+"/songs", nil, 200)
	if page["totalElements"] != float64(1) {
		t.Fatal(page)
	}
	mode = "failure"
	run("FAILED")
	mode = "empty"
	run("SUCCEEDED")
	page = requestJSON(t, router, "GET", base+"/songs", nil, 200)
	if page["totalElements"] != float64(1) {
		t.Fatalf("failed/empty scan destroyed previous index: %#v", page)
	}
}
func TestDAVTraversalAndXMLProtection(t *testing.T) {
	root, _ := url.Parse("https://nas.test/dav/")
	for _, href := range []string{"https://evil.test/dav/x.mkv", "/dav/../x.mkv", "/dav/%2e%2e/x.mkv", "/dav/foo%2fbar.mkv", "/dav/foo%5cbar.mkv", "/other/x.mkv", "/dav/nested/x.mkv", "/dav/x.mkv?secret=x"} {
		if _, e := davPath(root, "/dav/", href, false); e == nil {
			t.Errorf("accepted %q", href)
		}
	}
	if got, e := davPath(root, "/dav/", "/dav/%E5%91%A8%E6%9D%B0%E4%BC%A6-%E6%99%B4%E5%A4%A9.mkv", false); e != nil || got != "/dav/周杰伦-晴天.mkv" {
		t.Fatal(got, e)
	}
}
func TestCronShanghaiAndUnixDayOR(t *testing.T) {
	after := time.Date(2026, 11, 9, 0, 0, 0, 0, time.UTC)
	next, e := nextCron("0 9 * * *", after)
	if e != nil || !next.Equal(time.Date(2026, 11, 9, 1, 0, 0, 0, time.UTC)) {
		t.Fatal(next, e)
	}
	next, e = nextCron("0 9 10 * MON", after)
	if e != nil || !next.Equal(time.Date(2026, 11, 9, 1, 0, 0, 0, time.UTC)) {
		t.Fatal(next, e)
	}
	for _, value := range []string{"@hourly", "0 0 0 * * *", "60 * * * *", "* * * * L", "* * * 13 *"} {
		if _, e = nextCron(value, after); e == nil {
			t.Errorf("accepted %q", value)
		}
	}
}

func TestFailedPublishRollsBackIndex(t *testing.T) {
	s, router := newTestService(t)
	media := t.TempDir()
	os.WriteFile(filepath.Join(media, "A-First.mkv"), []byte("first"), 0600)
	src := localSource(t, router, media)
	id := src["id"].(string)
	base := "/api/v1/media-sources/" + id
	requestJSON(t, router, "POST", base+"/scan", nil, 200)
	os.WriteFile(filepath.Join(media, "B-Second.mkv"), []byte("second"), 0600)
	// A real database failure after DELETE verifies transaction rollback of the old index.
	if _, err := s.db.Exec(`CREATE TRIGGER reject_song_insert BEFORE INSERT ON media_song BEGIN SELECT RAISE(ABORT,'isolated publish failure'); END`); err != nil {
		t.Fatal(err)
	}
	requestJSON(t, router, "POST", base+"/scan", nil, 500)
	page := requestJSON(t, router, "GET", base+"/songs", nil, 200)
	if page["totalElements"] != float64(1) || page["items"].([]any)[0].(map[string]any)["relativePath"] != "A-First.mkv" {
		t.Fatalf("partial index published: %#v", page)
	}
}

func TestWebDAVCredentialsAndRemoteRanges(t *testing.T) {
	s, router := newTestService(t)
	mode := "valid"
	remote := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if user, password, ok := r.BasicAuth(); !ok || user != "test-user" || password != "secret-password" {
			t.Errorf("missing saved source credentials")
		}
		if mode == "redirect" {
			http.Redirect(w, r, "http://127.0.0.1:1/forbidden", http.StatusFound)
			return
		}
		if mode == "ignored" {
			w.Header().Set("Content-Length", "16")
			fmt.Fprint(w, "0123456789abcdef")
			return
		}
		w.Header().Set("Accept-Ranges", "bytes")
		w.Header().Set("Content-Length", "4")
		header := "bytes 2-5/16"
		if mode == "mismatched" {
			header = "bytes 1-4/16"
		}
		w.Header().Set("Content-Range", header)
		w.WriteHeader(206)
		if r.Method != "HEAD" {
			fmt.Fprint(w, "2345")
		}
	}))
	defer remote.Close()
	src := requestJSON(t, router, "POST", "/api/v1/media-sources", map[string]any{"name": "remote", "type": "WEBDAV", "location": remote.URL + "/dav/", "username": "test-user", "password": "secret-password"}, 201)
	if strings.Contains(encode(src), "secret-password") || src["passwordConfigured"] != true {
		t.Fatal("source exposed or lost password")
	}
	id := src["id"].(string)
	src["name"] = "renamed"
	requestJSON(t, router, "PUT", "/api/v1/media-sources/"+id, src, 200)
	parsed, _ := Parse("A-Song.mkv", []Rule{{ID: "test", Name: "test", Type: "TEMPLATE", Expression: ptr("{artist}-{title}"), Enabled: ptr(true)}})
	song := songID(id, "A-Song.mkv")
	if _, e := s.db.Exec(s.q["song-insert"], song, id, "A-Song.mkv", hash("A-Song.mkv"), 16, nil, now(), "default", 0, "test", encode(parsed.Result), false, nil); e != nil {
		t.Fatal(e)
	}
	for _, tt := range []struct {
		mode   string
		status int
		body   string
	}{{"valid", 206, "2345"}, {"mismatched", 502, ""}, {"ignored", 502, ""}, {"redirect", 502, ""}} {
		mode = tt.mode
		r := httptest.NewRequest("GET", "/api/v1/media-songs/"+song+"/video", nil)
		r.Header.Set("Range", "bytes=2-5")
		w := httptest.NewRecorder()
		router.ServeHTTP(w, r)
		if w.Code != tt.status {
			t.Fatalf("%s: %d %s", tt.mode, w.Code, w.Body.String())
		}
		if tt.status == 206 && w.Body.String() != tt.body {
			t.Fatal(w.Body.String())
		}
	}
	src["location"] = remote.URL + "/other/"
	requestJSON(t, router, "PUT", "/api/v1/media-sources/"+id, src, 400)
}

func TestLocalScanSerializesDownloadPublication(t *testing.T) {
	s, router := newTestService(t)
	media := t.TempDir()
	if e := os.WriteFile(filepath.Join(media, "A-Old.mkv"), []byte("old"), 0600); e != nil {
		t.Fatal(e)
	}
	source := localSource(t, router, media)
	id := source["id"].(string)
	scanStarted, continueScan := make(chan struct{}), make(chan struct{})
	s.SetLocalTracker(func(ctx context.Context, sourceID string) (string, error) {
		close(scanStarted)
		select {
		case <-continueScan:
			return "local-task", nil
		case <-ctx.Done():
			return "", ctx.Err()
		}
	}, nil, nil, nil)
	scanDone := make(chan error, 1)
	go func() { _, e := s.scanLocal(context.Background(), id); scanDone <- e }()
	<-scanStarted
	// A pending publication is cancellable while the scanner owns the source gate.
	waitCtx, cancel := context.WithTimeout(context.Background(), 20*time.Millisecond)
	e := s.Publication(waitCtx, id, func() error { return fmt.Errorf("entered a locked source") })
	cancel()
	if e != context.DeadlineExceeded {
		t.Fatalf("publication was not excluded: %v", e)
	}
	publicationDone := make(chan error, 1)
	go func() {
		publicationDone <- s.Publication(context.Background(), id, func() error {
			if e := os.WriteFile(filepath.Join(media, "A-New.mkv"), []byte("new"), 0600); e != nil {
				return e
			}
			parsed, e := Parse("A-New.mkv", []Rule{{ID: "rule", Name: "rule", Type: "TEMPLATE", Expression: ptr("{artist}-{title}"), Enabled: ptr(true)}})
			if e != nil {
				return e
			}
			_, e = s.db.Exec(s.q["song-insert"], songID(id, "A-New.mkv"), id, "A-New.mkv", hash("A-New.mkv"), 3, nil, now(), "default", 0, "rule", encode(parsed.Result), false, nil)
			return e
		})
	}()
	close(continueScan)
	if e = <-scanDone; e != nil {
		t.Fatal(e)
	}
	if e = <-publicationDone; e != nil {
		t.Fatal(e)
	}
	songs, e := s.LibrarySongs(context.Background(), nil)
	if e != nil {
		t.Fatal(e)
	}
	if len(songs) != 2 {
		t.Fatalf("scan removed the concurrently published download: %#v", songs)
	}
}

func TestLocalTagsReadOutsidePublicationTransaction(t *testing.T) {
	s, r := newTestService(t)
	root := t.TempDir()
	if e := os.WriteFile(filepath.Join(root, "unparsed.mkv"), []byte("local"), 0600); e != nil {
		t.Fatal(e)
	}
	source := localSource(t, r, root)
	id := source["id"].(string)
	var calls int
	s.SetLocalTagReader(func(ctx context.Context, sourceID, relative string) (map[string]any, error) {
		calls++
		if sourceID != id || relative != "unparsed.mkv" {
			return nil, fmt.Errorf("wrong reader target")
		} // This query would deadlock on the isolated one-connection DB if tag IO held a transaction.
		var amount int
		if e := s.db.QueryRowContext(ctx, "SELECT COUNT(*) FROM media_source WHERE id=?", sourceID).Scan(&amount); e != nil {
			return nil, e
		}
		if amount != 1 {
			return nil, fmt.Errorf("source missing")
		}
		return map[string]any{"title": "Embedded Title", "artists": []string{"Embedded Artist"}, "album": "Embedded Album"}, nil
	})
	scan := requestJSON(t, r, "POST", "/api/v1/media-sources/"+id+"/scan", nil, 200)
	if calls != 1 || scan["needsReviewSongs"] != float64(0) {
		t.Fatal(calls, scan)
	}
	songs, e := s.LibrarySongs(context.Background(), nil)
	if e != nil || len(songs) != 1 {
		t.Fatal(e, songs)
	}
	meta := songs[0]["metadata"].(map[string]any)
	track := meta["track"].(map[string]any)
	if track["title"] != "Embedded Title" {
		t.Fatal(meta)
	}
	if meta["origins"].(map[string]any)["title"].(map[string]any)["kind"] != "FILE" {
		t.Fatal(meta)
	}
}

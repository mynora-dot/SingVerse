package app

import (
	"bytes"
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/cookiejar"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"sync"
	"testing"

	"github.com/jmoiron/sqlx"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/config"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/testutil"
)

type businessClient struct {
	t      *testing.T
	origin string
	client *http.Client
	csrf   string
}

func newBusinessClient(t *testing.T, a *Application) *businessClient {
	server := httptest.NewServer(a.Router)
	t.Cleanup(server.Close)
	jar, _ := cookiejar.New(nil)
	return &businessClient{t: t, origin: server.URL, client: &http.Client{Jar: jar}}
}
func (c *businessClient) call(method, path string, body any, bearer string, status int) map[string]any {
	c.t.Helper()
	if bearer == "" && method != "GET" && method != "HEAD" {
		csrf := c.call("GET", "/api/v1/auth/csrf", nil, "", 200)
		c.csrf = fmt.Sprint(csrf["token"])
	}
	var data io.Reader
	if body != nil {
		encoded, e := json.Marshal(body)
		if e != nil {
			c.t.Fatal(e)
		}
		data = bytes.NewReader(encoded)
	}
	r, e := http.NewRequest(method, c.origin+path, data)
	if e != nil {
		c.t.Fatal(e)
	}
	r.Header.Set("Content-Type", "application/json")
	if bearer != "" {
		r.Header.Set("Authorization", "Bearer "+bearer)
	} else if method != "GET" && method != "HEAD" {
		r.Header.Set("X-CSRF-Token", c.csrf)
	}
	response, e := c.client.Do(r)
	if e != nil {
		c.t.Fatal(e)
	}
	defer response.Body.Close()
	raw, e := io.ReadAll(response.Body)
	if e != nil {
		c.t.Fatal(e)
	}
	if response.StatusCode != status {
		c.t.Fatalf("%s %s: got %d expected %d: %s", method, path, response.StatusCode, status, raw)
	}
	if status == 204 {
		return nil
	}
	if len(raw) == 0 {
		return nil
	}
	var out map[string]any
	if e = json.Unmarshal(raw, &out); e != nil {
		c.t.Fatalf("%s %s invalid JSON: %s", method, path, raw)
	}
	return out
}
func requestID() string {
	var b [16]byte
	if _, e := rand.Read(b[:]); e != nil {
		panic(e)
	}
	b[6] = b[6]&15 | 64
	b[8] = b[8]&63 | 128
	v := hex.EncodeToString(b[:])
	return v[:8] + "-" + v[8:12] + "-" + v[12:16] + "-" + v[16:20] + "-" + v[20:]
}
func object(v any) map[string]any                   { m, _ := v.(map[string]any); return m }
func stringField(v map[string]any, k string) string { x, _ := v[k].(string); return x }
func TestMySQLBusinessHTTP(t *testing.T) {
	db, cfg := testutil.MySQLDatabase(t)
	a, e := New(cfg, db, slog.New(slog.NewTextHandler(io.Discard, nil)))
	if e != nil {
		t.Fatal(e)
	}
	defer a.Close()
	exerciseBusiness(t, a, db, cfg)
}
func TestSQLiteBusinessHTTP(t *testing.T) {
	a := application(t)
	exerciseBusiness(t, a, a.db, config.Config{Driver: "sqlite"})
}
func exerciseBusiness(t *testing.T, a *Application, db *sqlx.DB, cfg config.Config) {
	c := newBusinessClient(t, a)
	c.call("GET", "/api/health", nil, "", 200)
	c.call("POST", "/api/v1/auth/login", map[string]any{"username": "admin", "password": "admin"}, "", 200)
	dir, e := filepath.EvalSymlinks(t.TempDir())
	if e != nil {
		t.Fatal(e)
	}
	if e = os.WriteFile(filepath.Join(dir, "周杰伦 - 晴天.mp4"), []byte("isolated media"), 0600); e != nil {
		t.Fatal(e)
	}
	source := c.call("POST", "/api/v1/media-sources", map[string]any{"name": "业务验收", "type": "LOCAL", "location": dir, "useForLxDownloads": true}, "", 201)
	sourceID := stringField(source, "id")
	c.call("POST", "/api/v1/media-sources/"+sourceID+"/scan", map[string]any{}, "", 200)
	songs := c.call("GET", "/api/v1/media-sources/"+sourceID+"/songs", nil, "", 200)
	items, _ := songs["items"].([]any)
	if len(items) != 1 {
		t.Fatal(songs)
	}
	song := object(items[0])
	songID := stringField(song, "id")
	if object(song["result"])["title"] != "晴天" {
		t.Fatal(song)
	}
	review := c.call("GET", "/api/v1/metadata/songs/"+songID+"/review", nil, "", 200)
	change := map[string]any{"requestId": requestID(), "fingerprint": review["fingerprint"], "version": review["version"], "fields": []string{"title", "artists"}, "edits": map[string]any{"title": "晴天·人工", "artists": []string{"周杰伦"}}}
	applied := c.call("POST", "/api/v1/metadata/songs/"+songID+"/review/apply", change, "", 200)
	if object(applied["after"])["title"] != "晴天·人工" {
		t.Fatal(applied)
	}
	// Identity search uses the same effective manual metadata on both databases;
	// the legacy q parameter still searches only the stored relative path.
	identity := c.call("GET", "/api/v1/media-songs?search="+url.QueryEscape("人工")+"&sourceId="+sourceID+"&size=1", nil, "", 200)
	if identity["totalElements"] != float64(1) || stringField(object(identity["items"].([]any)[0]), "id") != songID {
		t.Fatal(identity)
	}
	legacyPath := c.call("GET", "/api/v1/media-songs?q="+url.QueryEscape("人工"), nil, "", 200)
	if legacyPath["totalElements"] != float64(0) {
		t.Fatal("q changed its path-only semantics", legacyPath)
	}
	again := c.call("POST", "/api/v1/metadata/songs/"+songID+"/review/apply", change, "", 200)
	if fmt.Sprint(applied) != fmt.Sprint(again) {
		t.Fatal("metadata receipt changed")
	}
	tag := c.call("POST", "/api/v1/classification/tags", map[string]any{"dimension": "GENRE", "name": "流行验收", "aliases": []string{}, "active": true}, "", 201)
	assignment := map[string]any{"requestId": requestID(), "revision": 0, "dimensions": map[string][]string{"GENRE": {stringField(tag, "id")}}, "lockedDimensions": []string{"GENRE"}}
	classified := c.call("PUT", "/api/v1/classification/songs/"+songID, assignment, "", 200)
	if classified["revision"] != float64(1) {
		t.Fatal(classified)
	}
	c.call("PUT", "/api/v1/classification/songs/"+songID, assignment, "", 200)
	// Two new requests at the same revision must have exactly one winner.
	var wg sync.WaitGroup
	codes := make(chan int, 2)
	for range 2 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			body, _ := json.Marshal(map[string]any{"requestId": requestID(), "revision": 1, "dimensions": map[string][]string{"GENRE": {}}, "lockedDimensions": []string{}})
			req, _ := http.NewRequest("PUT", c.origin+"/api/v1/classification/songs/"+songID, bytes.NewReader(body))
			req.Header.Set("Content-Type", "application/json")
			req.Header.Set("X-CSRF-Token", c.csrf)
			response, e := c.client.Do(req)
			if e != nil {
				t.Error(e)
				return
			}
			io.Copy(io.Discard, response.Body)
			response.Body.Close()
			codes <- response.StatusCode
		}()
	}
	wg.Wait()
	close(codes)
	wins, conflicts := 0, 0
	for code := range codes {
		if code == 200 {
			wins++
		} else if code == 409 {
			conflicts++
		} else {
			t.Errorf("unexpected concurrency status %d", code)
		}
	}
	if wins != 1 || conflicts != 1 {
		t.Fatal("revision concurrency", wins, conflicts)
	}
	playlistInput := map[string]any{"requestId": requestID(), "name": "家庭验收歌单", "description": "隔离数据库", "songIds": []string{songID}, "revision": 0}
	playlist := c.call("POST", "/api/v1/playlists", playlistInput, "", 201)
	same := c.call("POST", "/api/v1/playlists", playlistInput, "", 201)
	if playlist["id"] != same["id"] {
		t.Fatal("playlist duplicated")
	}
	device := c.call("POST", "/api/v1/device-authorizations", map[string]any{"name": "验收电视"}, "", 200)
	authorizationID := stringField(device, "id")
	c.call("POST", "/api/v1/admin/device-authorizations/"+authorizationID+"/approve", map[string]any{"code": device["code"]}, "", 200)
	grant := c.call("POST", "/api/v1/device-authorizations/"+authorizationID+"/poll", map[string]any{"pollSecret": device["pollSecret"]}, "", 200)
	token := stringField(grant, "token")
	session := c.call("POST", "/api/v1/tv/sessions", map[string]any{}, token, 200)
	sessionID := stringField(session, "id")
	invite := c.call("POST", "/api/v1/tv/sessions/"+sessionID+"/invitations", map[string]any{}, token, 200)
	guest := c.call("POST", "/api/v1/party/join", map[string]any{"invitation": invite["invitation"], "name": "亲友"}, "", 200)
	// Download-derived covers are shared by the management, TV and H5 clients.
	// Use a real lossless WebP rather than an uploaded PNG so the composition
	// boundary must decode the format actually accepted by LX downloads.
	cover, e := base64.StdEncoding.DecodeString("UklGRh4AAABXRUJQVlA4TBEAAAAvAAAAAAdQqFIUr/+BiOh/AAA=")
	if e != nil {
		t.Fatal(e)
	}
	if e = os.WriteFile(filepath.Join(dir, "周杰伦 - 晴天.webp"), cover, 0600); e != nil {
		t.Fatal(e)
	}
	trackJSON := `{"id":"fixture-track","platform":"mg","remoteId":"fixture","title":"晴天","artists":["周杰伦"],"coverUrl":"https://music.migu.cn/fixture.webp"}`
	if _, e = db.Exec(`INSERT INTO lx_download_asset(id,source_id,track_key,quality,relative_path,song_id,file_size,modified_at,content_hash,track_json,created_at) SELECT ?,source_id,?,'128k',relative_path,id,file_size,modified_at,?,?,scanned_at FROM media_song WHERE id=?`, requestID(), "fixture-track", "fixture-hash", trackJSON, songID); e != nil {
		t.Fatal(e)
	}
	for _, actor := range []string{"", token, stringField(guest, "token")} {
		path := "/api/v1/tv/library/songs/" + songID + "/cover"
		if actor == "" {
			path = "/api/v1/metadata/songs/" + songID + "/cover"
		}
		req, _ := http.NewRequest("GET", c.origin+path, nil)
		if actor != "" {
			req.Header.Set("Authorization", "Bearer "+actor)
		}
		response, err := c.client.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		data, _ := io.ReadAll(response.Body)
		response.Body.Close()
		if response.StatusCode != 200 || response.Header.Get("Content-Type") != "image/webp" || !bytes.Equal(data, cover) {
			t.Fatalf("shared download cover %s: HTTP %d MIME %s: %s", path, response.StatusCode, response.Header.Get("Content-Type"), data)
		}
	}
	queueCommand := map[string]any{"requestId": "acceptance-add", "expectedRevision": 0, "action": "ADD", "songId": songID}
	queue := c.call("POST", "/api/v1/party/sessions/"+sessionID+"/commands", queueCommand, stringField(guest, "token"), 200)
	if len(queue["queue"].([]any)) != 1 {
		t.Fatal(queue)
	}
	c.call("POST", "/api/v1/party/sessions/"+sessionID+"/commands", queueCommand, stringField(guest, "token"), 200)
	started := c.call("POST", "/api/v1/tv/sessions/"+sessionID+"/commands", map[string]any{"requestId": "acceptance-start", "expectedRevision": 1, "action": "START"}, token, 200)
	attempt := object(started["playback"])
	c.call("POST", "/api/v1/tv/sessions/"+sessionID+"/playback-events", map[string]any{"attemptId": attempt["attemptId"], "eventSeq": 1, "state": "PLAYING", "positionMs": 0, "durationMs": 180000}, token, 200)
	request, e := http.NewRequest("GET", c.origin+"/api/v1/tv/media/"+songID, nil)
	if e != nil {
		t.Fatal(e)
	}
	request.Header.Set("Authorization", "Bearer "+token)
	request.Header.Set("Range", "bytes=0-3")
	response, e := http.DefaultClient.Do(request)
	if e != nil {
		t.Fatal(e)
	}
	data, _ := io.ReadAll(response.Body)
	response.Body.Close()
	if response.StatusCode != 206 || string(data) != "isol" {
		t.Fatal(response.StatusCode, string(data))
	}
	c.call("GET", "/api/v1/background-tasks", nil, "", 200)
	artists := c.call("GET", "/api/v1/artists?q=zhoujielun", nil, "", 200)
	artist := object(artists["items"].([]any)[0])
	artistID := stringField(artist, "id")
	category := map[string]any{"artists": []any{map[string]any{"id": artistID, "revision": artist["revision"]}}, "type": "MALE"}
	c.call("POST", "/api/v1/artists/categories", category, "", 200)
	c.call("POST", "/api/v1/artists/categories", category, "", 409)
	male := c.call("GET", "/api/v1/artists?type=MALE&size=1", nil, "", 200)
	if male["totalElements"].(float64) != 1 {
		t.Fatal("artist category pagination", male)
	}
	// A deliberately removed portrait is never refilled by a batch.
	if _, e = db.Exec("INSERT INTO artist_image_policy(artist_id,image_kind,blocked) VALUES(?,'PORTRAIT',1)", artistID); e != nil {
		t.Fatal(e)
	}
	artworkInput := map[string]any{"requestId": requestID(), "provider": "AUTO", "artistIds": []string{artistID}}
	artwork := c.call("POST", "/api/v1/artists/artwork-batches", artworkInput, "", 201)
	repeatedArtwork := c.call("POST", "/api/v1/artists/artwork-batches", artworkInput, "", 201)
	if artwork["id"] != repeatedArtwork["id"] || object(artwork["counts"])["SKIPPED"].(float64) != 1 {
		t.Fatal("artwork receipt or removal policy", artwork)
	}
	projectedArtwork := c.call("GET", "/api/v1/background-tasks?view=ALL&type=ARTIST_ARTWORK", nil, "", 200)
	if projectedArtwork["totalElements"].(float64) != 1 {
		t.Fatal("artwork task missing", projectedArtwork)
	}
	unprivileged := &businessClient{t: t, origin: c.origin, client: &http.Client{}}
	unprivileged.call("GET", "/api/v1/artists/artwork-batches/"+stringField(artwork, "id"), nil, token, 401)
	c.call("GET", "/api/v1/transcoding/cache", nil, "", 200)
	var projected int
	if e = db.QueryRow("SELECT COUNT(*) FROM background_task WHERE type='LOCAL_SCAN'").Scan(&projected); e != nil || projected != 1 {
		t.Fatal("scan projection", projected, e)
	}
	c.call("DELETE", "/api/v1/admin/tv-devices/"+stringField(grant, "deviceId"), nil, "", 200)
	c.call("GET", "/api/v1/tv/home", nil, token, 401)
	// A separate transaction rolls back both the business write and its task
	// projection under the real database driver, independently of HTTP receipts.
	tx, e := db.Begin()
	if e != nil {
		t.Fatal(e)
	}
	if _, e = tx.Exec("UPDATE media_source SET name='uncommitted' WHERE id=?", sourceID); e != nil {
		t.Fatal(e)
	}
	if _, e = tx.Exec("UPDATE background_task SET name='uncommitted' WHERE type='LOCAL_SCAN'"); e != nil {
		t.Fatal(e)
	}
	if e = tx.Rollback(); e != nil {
		t.Fatal(e)
	}
	var name string
	if e = db.QueryRow("SELECT name FROM media_source WHERE id=?", sourceID).Scan(&name); e != nil || name != "业务验收" {
		t.Fatal(name, e)
	}
	t.Log("completed business HTTP and transaction acceptance", cfg.Driver)
}

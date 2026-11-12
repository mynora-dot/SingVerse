package party

import (
	"bytes"
	"context"
	"encoding/json"
	"image"
	"image/color"
	"image/png"
	"io"
	"log/slog"
	"mime/multipart"
	"net/http"
	"net/http/cookiejar"
	"net/http/httptest"
	"net/textproto"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/jmoiron/sqlx"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/auth"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/config"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
	_ "modernc.org/sqlite"
)

type fakeLibrary struct {
	songs []map[string]any
	media atomic.Int32
}

func (f *fakeLibrary) Song(ctx context.Context, key string) (map[string]any, error) {
	return f.SongWith(ctx, nil, key)
}
func (f *fakeLibrary) SongWith(_ context.Context, _ store.DBTX, key string) (map[string]any, error) {
	for _, v := range f.songs {
		if str(v, "id") == key {
			return clone(v), nil
		}
	}
	return nil, fail(404, "歌曲不存在")
}
func (f *fakeLibrary) LibrarySongs(context.Context, store.DBTX) ([]map[string]any, error) {
	return f.songs, nil
}
func (f *fakeLibrary) ServeOriginal(_ context.Context, w http.ResponseWriter, r *http.Request, key string) error {
	f.media.Add(1)
	w.Header().Set("Content-Type", "video/mp4")
	if r.Method != "HEAD" {
		_, _ = w.Write([]byte("media"))
	}
	return nil
}

type fixture struct {
	db     *sqlx.DB
	s      *Service
	lib    *fakeLibrary
	server *httptest.Server
	admin  *http.Client
	clock  time.Time
	cfg    config.Config
}

func newPartyFixture(t *testing.T) *fixture {
	t.Helper()
	gin.SetMode(gin.TestMode)
	root := t.TempDir()
	db, err := sqlx.Open("sqlite", filepath.Join(root, "test.db"))
	if err != nil {
		t.Fatal(err)
	}
	db.SetMaxOpenConns(1)
	t.Cleanup(func() { db.Close() })
	files, err := filepath.Glob("../../../../database/migrations/sqlite/*.sql")
	if err != nil || len(files) == 0 {
		t.Fatal("migrations missing", err)
	}
	sort.Strings(files)
	for _, path := range files {
		raw, e := os.ReadFile(path)
		if e != nil {
			t.Fatal(e)
		}
		up := strings.Split(strings.SplitN(string(raw), "-- migrate:up", 2)[1], "-- migrate:down")[0]
		if _, e = db.Exec(up); e != nil {
			t.Fatal(e)
		}
	}
	cfg := config.Config{AuthKeyFile: filepath.Join(root, "auth/master.key"), TVReleaseDirectory: filepath.Join(root, "releases")}
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	admin, err := auth.New(db, "sqlite", cfg, log)
	if err != nil {
		t.Fatal(err)
	}
	lib := &fakeLibrary{songs: []map[string]any{{"id": "song-a", "sourceId": "source", "sourceName": "家庭", "relativePath": "周杰伦-晴天.mp4", "fileSize": int64(5), "modifiedAt": "2026-11-09T00:00:00Z", "scannedAt": "2026-11-09T00:00:00Z", "result": document{"title": "晴天", "artists": []string{"周杰伦"}, "versionType": "ORIGINAL", "language": "MANDARIN", "extension": "mp4", "songCode": "00128"}, "metadata": nil, "classification": document{"dimensions": document{}}}, {"id": "song-b", "sourceName": "家庭", "scannedAt": "2026-11-08T00:00:00Z", "result": document{"title": "十年", "artists": []string{"陈奕迅"}, "versionType": "ORIGINAL", "extension": "flac", "language": "CANTONESE"}, "classification": document{"dimensions": document{}}}}}
	s, err := New(db, "sqlite", cfg, lib, log)
	if err != nil {
		t.Fatal(err)
	}
	s.SetCipher(admin.Cipher)
	t.Cleanup(func() { s.Close() })
	f := &fixture{db: db, s: s, lib: lib, clock: time.Now(), cfg: cfg}
	s.now = func() time.Time { return f.clock }
	r := gin.New()
	require := admin.RequireAdmin()
	r.Use(func(c *gin.Context) {
		p := c.Request.URL.Path
		if strings.HasPrefix(p, "/api/v1/tv/") || strings.HasPrefix(p, "/api/v1/party/") || p == "/api/v1/server-info" || strings.HasPrefix(p, "/api/v1/device-authorizations") {
			c.Next()
			return
		}
		require(c)
	})
	admin.Register(r)
	s.Register(r)
	f.server = httptest.NewServer(r)
	t.Cleanup(f.server.Close)
	jar, _ := cookiejar.New(nil)
	f.admin = &http.Client{Jar: jar}
	f.login(t)
	return f
}

type response struct {
	status int
	body   any
	header http.Header
}

func (f *fixture) request(t *testing.T, method, path string, body any, bearer string, admin, csrf bool) response {
	t.Helper()
	client := http.DefaultClient
	if admin {
		client = f.admin
	}
	var data io.Reader
	if body != nil {
		data = strings.NewReader(encode(body))
	}
	var csrfToken string
	if csrf {
		v := f.request(t, "GET", "/api/v1/auth/csrf", nil, "", true, false)
		csrfToken = str(v.body.(map[string]any), "token")
	}
	req, err := http.NewRequest(method, f.server.URL+path, data)
	if err != nil {
		t.Fatal(err)
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if bearer != "" {
		req.Header.Set("Authorization", "Bearer "+bearer)
	}
	if csrf {
		req.Header.Set("X-CSRF-Token", csrfToken)
	}
	r, err := client.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer r.Body.Close()
	raw, err := io.ReadAll(r.Body)
	if err != nil {
		t.Fatal(err)
	}
	var out any
	if len(raw) > 0 && strings.Contains(r.Header.Get("Content-Type"), "json") {
		d := json.NewDecoder(bytes.NewReader(raw))
		d.UseNumber()
		if err = d.Decode(&out); err != nil {
			t.Fatalf("invalid response %s", raw)
		}
	} else {
		out = raw
	}
	return response{r.StatusCode, out, r.Header}
}
func want(t *testing.T, r response, status int) document {
	t.Helper()
	if r.status != status {
		t.Fatalf("status %d want %d body %v", r.status, status, r.body)
	}
	v, _ := r.body.(map[string]any)
	return v
}
func (f *fixture) login(t *testing.T) {
	want(t, f.request(t, "POST", "/api/v1/auth/login", document{"username": "admin", "password": "admin"}, "", true, true), 200)
}
func (f *fixture) device(t *testing.T) (string, string) {
	t.Helper()
	v := want(t, f.request(t, "POST", "/api/v1/device-authorizations", document{"name": "客厅电视"}, "", false, false), 200)
	key := str(v, "id")
	secret := str(v, "pollSecret")
	want(t, f.request(t, "POST", "/api/v1/admin/device-authorizations/"+key+"/approve", document{"code": v["code"]}, "", true, true), 200)
	value := want(t, f.request(t, "POST", "/api/v1/device-authorizations/"+key+"/poll", document{"pollSecret": secret}, "", false, false), 200)
	return str(value, "token"), str(value, "deviceId")
}

func TestDeviceGrantInvitationQueuePlaybackAndRevocation(t *testing.T) {
	f := newPartyFixture(t)
	device, deviceID := f.device(t)
	want(t, f.request(t, "GET", "/api/v1/tv/home", nil, "", true, false), 401)
	want(t, f.request(t, "GET", "/api/v1/tv/home", nil, device, false, false), 200)
	session := want(t, f.request(t, "POST", "/api/v1/tv/sessions", nil, device, false, false), 200)
	sid := str(session, "id")
	invite := want(t, f.request(t, "POST", "/api/v1/tv/sessions/"+sid+"/invitations", nil, device, false, false), 200)
	want(t, f.request(t, "POST", "/api/v1/party/invitations/preview", invite, "", false, false), 200)
	participant := want(t, f.request(t, "POST", "/api/v1/party/join", document{"invitation": invite["invitation"], "name": "客人"}, "", false, false), 200)
	guest := str(participant, "token")
	want(t, f.request(t, "GET", "/api/v1/tv/home", nil, guest, false, false), 403)
	input := document{"requestId": "add-1", "expectedRevision": 0, "action": "ADD", "songId": "song-a"}
	first := want(t, f.request(t, "POST", "/api/v1/party/sessions/"+sid+"/commands", input, guest, false, false), 200)
	if number(first, "revision") != 1 || len(array(first, "queue")) != 1 {
		t.Fatal(first)
	}
	repeat := want(t, f.request(t, "POST", "/api/v1/party/sessions/"+sid+"/commands", input, guest, false, false), 200)
	if len(array(repeat, "queue")) != 1 {
		t.Fatal(repeat)
	}
	input["songId"] = "song-b"
	want(t, f.request(t, "POST", "/api/v1/party/sessions/"+sid+"/commands", input, guest, false, false), 409)
	want(t, f.request(t, "POST", "/api/v1/party/sessions/"+sid+"/commands", document{"requestId": "guest-start", "expectedRevision": 1, "action": "START"}, guest, false, false), 403)
	started := want(t, f.request(t, "POST", "/api/v1/tv/sessions/"+sid+"/commands", document{"requestId": "start", "expectedRevision": 1, "action": "START"}, device, false, false), 200)
	p := object(started, "playback")
	event := document{"attemptId": p["attemptId"], "eventSeq": 1, "state": "PLAYING", "positionMs": 1000, "durationMs": 180000}
	reported := want(t, f.request(t, "POST", "/api/v1/tv/sessions/"+sid+"/playback-events", event, device, false, false), 200)
	if number(reported, "revision") != 2 || number(reported, "stateRevision") <= number(started, "stateRevision") {
		t.Fatal(reported)
	}
	again := want(t, f.request(t, "POST", "/api/v1/tv/sessions/"+sid+"/playback-events", event, device, false, false), 200)
	if number(again, "stateRevision") != number(reported, "stateRevision") {
		t.Fatal("duplicate telemetry changed state")
	}
	event["durationMs"] = 1.5
	event["eventSeq"] = 2
	want(t, f.request(t, "POST", "/api/v1/tv/sessions/"+sid+"/playback-events", event, device, false, false), 400)
	recovered := want(t, f.request(t, "POST", "/api/v1/tv/sessions/"+sid+"/recover", nil, device, false, false), 200)
	if len(object(recovered, "playback")) != 0 || str(array(recovered, "queue")[0].(map[string]any), "status") != "WAITING" {
		t.Fatal(recovered)
	}
	event["durationMs"] = 180000
	want(t, f.request(t, "POST", "/api/v1/tv/sessions/"+sid+"/playback-events", event, device, false, false), 409)
	want(t, f.request(t, "GET", "/api/v1/tv/media/song-a", nil, device, false, false), 200)
	want(t, f.request(t, "HEAD", "/api/v1/tv/media/song-a", nil, guest, false, false), 403)
	want(t, f.request(t, "DELETE", "/api/v1/admin/tv-devices/"+deviceID, nil, "", true, true), 200)
	want(t, f.request(t, "GET", "/api/v1/tv/media/song-a", nil, device, false, false), 401)
	want(t, f.request(t, "GET", "/api/v1/party/sessions/"+sid+"/snapshot", nil, guest, false, false), 401)
}

func TestDevicePollingIdempotencyAndExpiry(t *testing.T) {
	f := newPartyFixture(t)
	pending := want(t, f.request(t, "POST", "/api/v1/device-authorizations", document{"name": "TV"}, "", false, false), 200)
	key := str(pending, "id")
	secret := str(pending, "pollSecret")
	want(t, f.request(t, "POST", "/api/v1/device-authorizations/"+key+"/poll", document{"pollSecret": "wrong"}, "", false, false), 401)
	want(t, f.request(t, "POST", "/api/v1/admin/device-authorizations/"+key+"/approve", document{"code": pending["code"]}, "", true, true), 200)
	a := want(t, f.request(t, "POST", "/api/v1/device-authorizations/"+key+"/poll", document{"pollSecret": secret}, "", false, false), 200)
	b := want(t, f.request(t, "POST", "/api/v1/device-authorizations/"+key+"/poll", document{"pollSecret": secret}, "", false, false), 200)
	if a["token"] != b["token"] {
		t.Fatal("credential changed on poll")
	}
	f.clock = f.clock.Add(11 * time.Minute)
	want(t, f.request(t, "POST", "/api/v1/device-authorizations/"+key+"/poll", document{"pollSecret": secret}, "", false, false), 410)
}

func TestInvitationRefreshHostPermissionsAndEndedParty(t *testing.T) {
	f := newPartyFixture(t)
	device, _ := f.device(t)
	session := want(t, f.request(t, "POST", "/api/v1/tv/sessions", nil, device, false, false), 200)
	sid := str(session, "id")
	old := want(t, f.request(t, "POST", "/api/v1/tv/sessions/"+sid+"/invitations", nil, device, false, false), 200)
	current := want(t, f.request(t, "POST", "/api/v1/tv/sessions/"+sid+"/invitations", nil, device, false, false), 200)
	want(t, f.request(t, "POST", "/api/v1/party/join", document{"invitation": old["invitation"], "name": "旧邀请"}, "", false, false), 410)
	guest := want(t, f.request(t, "POST", "/api/v1/party/join", document{"invitation": current["invitation"], "name": "客人"}, "", false, false), 200)
	credential := str(guest, "token")
	sidPath := "/api/v1/party/sessions/" + sid
	want(t, f.request(t, "POST", sidPath+"/commands", document{"action": "NEXT", "expectedRevision": 0, "requestId": "next"}, credential, false, false), 403)
	want(t, f.request(t, "POST", "/api/v1/tv/sessions/"+sid+"/commands", document{"action": "HOST", "expectedRevision": 0, "requestId": "host", "participantId": guest["id"]}, device, false, false), 200)
	want(t, f.request(t, "POST", sidPath+"/commands", document{"action": "NEXT", "expectedRevision": 1, "requestId": "next"}, credential, false, false), 200)
	want(t, f.request(t, "POST", sidPath+"/commands", document{"action": "END", "expectedRevision": 2, "requestId": "guest-end"}, credential, false, false), 403)
	want(t, f.request(t, "POST", "/api/v1/tv/sessions/"+sid+"/commands", document{"action": "END", "expectedRevision": 2, "requestId": "end"}, device, false, false), 200)
	want(t, f.request(t, "GET", sidPath+"/snapshot", nil, credential, false, false), 401)
}

func TestReadProjectionSearchMobileIdentityAndInt64Telemetry(t *testing.T) {
	f := newPartyFixture(t)
	device, _ := f.device(t)
	for _, q := range []string{"周杰伦", "zhoujielun", "zjl", "00128", "qingtian"} {
		v := want(t, f.request(t, "GET", "/api/v1/tv/library/songs?q="+q, nil, device, false, false), 200)
		if number(v, "totalElements") != 1 {
			t.Fatalf("query %s %v", q, v)
		}
	}
	want(t, f.request(t, "GET", "/api/v1/mobile/library/songs", nil, device, false, false), 401)
	mobile := want(t, f.request(t, "GET", "/api/v1/mobile/library/songs/song-b", nil, "", true, false), 200)
	if str(mobile, "format") != "FLAC" || str(mobile, "mediaType") != "AUDIO" {
		t.Fatal(mobile)
	}
	session := want(t, f.request(t, "POST", "/api/v1/tv/sessions", nil, device, false, false), 200)
	sid := str(session, "id")
	want(t, f.request(t, "POST", "/api/v1/tv/sessions/"+sid+"/commands", document{"action": "ADD", "songId": "song-a", "requestId": "add", "expectedRevision": 0}, device, false, false), 200)
	v := want(t, f.request(t, "POST", "/api/v1/tv/sessions/"+sid+"/commands", document{"action": "START", "requestId": "start", "expectedRevision": 1}, device, false, false), 200)
	p := object(v, "playback")
	seq := json.Number("9007199254740993")
	v = want(t, f.request(t, "POST", "/api/v1/tv/sessions/"+sid+"/playback-events", document{"attemptId": p["attemptId"], "eventSeq": seq, "state": "PLAYING", "positionMs": 0}, device, false, false), 200)
	if number(object(v, "playback"), "eventSeq") != 9007199254740993 {
		t.Fatal("int64 telemetry lost precision", v)
	}
}

func TestProjectionMetadataPriorityAndNoPaths(t *testing.T) {
	raw := document{"id": "id", "sourceName": "name", "relativePath": "secret/path", "sourceId": "secret-id", "result": document{"title": "原始", "artists": []string{"原始歌手"}, "versionType": "ORIGINAL"}, "metadata": document{"stale": false, "fields": []string{"title", "artists", "album", "durationMs", "cover"}, "track": document{"title": "确认", "artists": []string{"确认歌手"}, "album": "专辑", "durationMs": int64(2000)}}}
	v := Projection(raw)
	if str(v, "title") != "确认" || str(v, "album") != "专辑" || number(v, "durationMs") != 2000 {
		t.Fatal(v)
	}
	if strings.Contains(encode(v), "secret") {
		t.Fatal("internal path leaked", v)
	}
	object(raw, "metadata")["stale"] = true
	v = Projection(raw)
	if str(v, "title") != "原始" || str(v, "coverPath") != "" {
		t.Fatal(v)
	}
}

func TestTVReleaseValidation(t *testing.T) {
	f := newPartyFixture(t)
	if err := os.MkdirAll(f.cfg.TVReleaseDirectory, 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(f.cfg.TVReleaseDirectory, "release.json"), []byte(`{"versionCode":2,"versionName":"2.0"}`), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := f.s.release(); err == nil {
		t.Fatal("incomplete release accepted")
	}
	if err := os.WriteFile(filepath.Join(f.cfg.TVReleaseDirectory, "singverse-tv.apk"), []byte("public APK fixture"), 0600); err != nil {
		t.Fatal(err)
	}
	v, err := f.s.release()
	if err != nil || v["available"] != true || len(str(v, "sha256")) != 64 {
		t.Fatal(v, err)
	}
	if err := os.Remove(filepath.Join(f.cfg.TVReleaseDirectory, "singverse-tv.apk")); err != nil {
		t.Fatal(err)
	}
	outside := filepath.Join(t.TempDir(), "outside.apk")
	os.WriteFile(outside, []byte("outside"), 0600)
	if err := os.Symlink(outside, filepath.Join(f.cfg.TVReleaseDirectory, "singverse-tv.apk")); err != nil {
		t.Fatal(err)
	}
	if _, err = f.s.release(); err == nil {
		t.Fatal("symlink release accepted")
	}
}

func TestTextDecodingAndTimedLyrics(t *testing.T) {
	for _, value := range []string{"[00:01.20]歌词", "[001:59]歌词"} {
		if !hasTimedLyrics(value) {
			t.Fatal(value)
		}
	}
	for _, value := range []string{"[00:61]歌词", "[ti:歌名]", "[00:01]<b></b>"} {
		if hasTimedLyrics(value) {
			t.Fatal(value)
		}
	}
	v, err := decodeText([]byte{255, 254, 0x4c, 0x6b})
	if err != nil || v != "歌" {
		t.Fatal(v, err)
	}
	for _, raw := range [][]byte{{0xff}, {255, 254, 0, 216}, {255, 254, 1}} {
		if _, err = decodeText(raw); err == nil {
			t.Fatalf("invalid text accepted %x", raw)
		}
	}
}

func TestBannerUploadAndAuthorizedImageRoundTrip(t *testing.T) {
	f := newPartyFixture(t)
	device, _ := f.device(t)
	settings := want(t, f.request(t, "GET", "/api/v1/admin/tv-settings", nil, "", true, false), 200)
	for _, value := range array(settings, "banners") {
		banner := value.(map[string]any)
		settings = want(t, f.request(t, "DELETE", "/api/v1/admin/tv-settings/banners/"+str(banner, "id")+"?revision="+strconv.FormatInt(number(settings, "revision"), 10), nil, "", true, true), 200)
	}
	img := image.NewNRGBA(image.Rect(0, 0, 3, 2))
	img.Set(1, 1, color.NRGBA{R: 200, G: 50, B: 20, A: 255})
	var source bytes.Buffer
	if e := png.Encode(&source, img); e != nil {
		t.Fatal(e)
	}
	expected, e := normalizeImage(source.Bytes(), "image/png")
	if e != nil {
		t.Fatal(e)
	}
	var body bytes.Buffer
	m := multipart.NewWriter(&body)
	part, e := m.CreatePart(textproto.MIMEHeader{"Content-Disposition": {`form-data; name="file"; filename="banner.png"`}, "Content-Type": {"image/png"}})
	if e != nil {
		t.Fatal(e)
	}
	if _, e = part.Write(source.Bytes()); e != nil {
		t.Fatal(e)
	}
	if e = m.Close(); e != nil {
		t.Fatal(e)
	}
	csrf := want(t, f.request(t, "GET", "/api/v1/auth/csrf", nil, "", true, false), 200)
	req, e := http.NewRequest("POST", f.server.URL+"/api/v1/admin/tv-settings/banners?revision="+strconv.FormatInt(number(settings, "revision"), 10), &body)
	if e != nil {
		t.Fatal(e)
	}
	req.Header.Set("Content-Type", m.FormDataContentType())
	req.Header.Set("X-CSRF-Token", str(csrf, "token"))
	upload, e := f.admin.Do(req)
	if e != nil {
		t.Fatal(e)
	}
	defer upload.Body.Close()
	var saved document
	if e = json.NewDecoder(upload.Body).Decode(&saved); e != nil {
		t.Fatal(e)
	}
	if upload.StatusCode != 200 || len(array(saved, "banners")) != 1 {
		t.Fatal(upload.StatusCode, saved)
	}
	banner := array(saved, "banners")[0].(map[string]any)
	for _, query := range []struct {
		path, token string
		admin       bool
	}{{str(banner, "imagePath"), "", true}, {"/api/v1/tv/home/banners/" + str(banner, "id") + "/image", device, false}} {
		got := f.request(t, "GET", query.path, nil, query.token, query.admin, false)
		data, ok := got.body.([]byte)
		if got.status != 200 || !ok || !bytes.Equal(data, expected) || got.header.Get("Content-Type") != "image/jpeg" || got.header.Get("X-Content-Type-Options") != "nosniff" {
			t.Fatalf("banner round trip failed: %d %v", got.status, got.header)
		}
	}
	want(t, f.request(t, "GET", "/api/v1/tv/home/banners/"+str(banner, "id")+"/image", nil, "", false, false), 401)
}

type gonePlaybackError struct{}

func (gonePlaybackError) Error() string   { return "播放资源已过期" }
func (gonePlaybackError) StatusCode() int { return 410 }
func TestPlaybackNegotiationUsesDeviceIdentityAndPreservesBusinessStatus(t *testing.T) {
	f := newPartyFixture(t)
	device, deviceID := f.device(t)
	var calls atomic.Int32
	f.s.SetPlaybackNegotiator(func(ctx context.Context, song, owner string, in map[string]any, direct, prefix string) (map[string]any, error) {
		calls.Add(1)
		if song != "song-a" || owner != "tv:"+deviceID || direct != "/api/v1/tv/media/song-a" || prefix != "/api/v1/tv/transcoding" || in["songId"] != "song-a" {
			t.Errorf("invalid negotiation scope: %s %s %s %s %v", song, owner, direct, prefix, in)
		}
		return document{"kind": "DIRECT", "url": direct}, nil
	})
	want(t, f.request(t, "POST", "/api/v1/tv/playback-resources", document{"songId": "song-a"}, "", true, true), 401)
	want(t, f.request(t, "POST", "/api/v1/tv/playback-resources", document{"songId": "missing"}, device, false, false), 404)
	got := want(t, f.request(t, "POST", "/api/v1/tv/playback-resources", document{"songId": "song-a"}, device, false, false), 200)
	if calls.Load() != 1 || got["revision"] == nil || got["kind"] != "DIRECT" {
		t.Fatal(calls.Load(), got)
	}
	f.s.SetPlaybackNegotiator(func(context.Context, string, string, map[string]any, string, string) (map[string]any, error) {
		return nil, gonePlaybackError{}
	})
	want(t, f.request(t, "POST", "/api/v1/tv/playback-resources", document{"songId": "song-a"}, device, false, false), 410)
}

func TestLibraryQueryBindingMatchesExistingClients(t *testing.T) {
	f := newPartyFixture(t)
	device, _ := f.device(t)
	if _, e := f.db.Exec("INSERT INTO classification_tag(id,dimension,name,aliases_json,active,revision) VALUES('tag-a','GENRE','查询绑定回归A','[]',1,1),('tag-b','GENRE','查询绑定回归B','[]',1,1)"); e != nil {
		t.Fatal(e)
	}
	f.lib.songs[0]["classification"] = document{"dimensions": document{"GENRE": document{"tagIds": []string{"tag-a"}}}}
	for _, query := range []string{"tagIds=tag-a,tag-b", "tagIds=tag-a&tagIds=tag-b"} {
		got := want(t, f.request(t, "GET", "/api/v1/tv/library/songs?"+query, nil, device, false, false), 200)
		if number(got, "totalElements") != 1 || str(array(got, "items")[0].(map[string]any), "id") != "song-a" {
			t.Fatal(query, got)
		}
	}
	for _, value := range []string{"TRUE", "yes", "1", "on"} {
		got := want(t, f.request(t, "GET", "/api/v1/tv/library/songs?unclassified="+value, nil, device, false, false), 200)
		if number(got, "totalElements") != 1 || str(array(got, "items")[0].(map[string]any), "id") != "song-b" {
			t.Fatal(value, got)
		}
	}
	want(t, f.request(t, "GET", "/api/v1/tv/library/songs?unclassified=maybe", nil, device, false, false), 400)
	want(t, f.request(t, "GET", "/api/v1/tv/library/artists/any/images/PORTRAIT", nil, device, false, false), 400)
	if validLabel(strings.Repeat("😀", 41), 80, false) {
		t.Fatal("UTF-16 label boundary was not enforced")
	}
}

type fakePrefetch struct {
	starts  atomic.Int32
	cancels atomic.Int32
	active  atomic.Value
}

func (f *fakePrefetch) PrefetchOriginal(song, owner string, active func() bool) error {
	f.starts.Add(1)
	f.active.Store(active)
	return nil
}
func (f *fakePrefetch) CancelOriginalPrefetch(owner string) { f.cancels.Add(1) }
func TestPrefetchStartsOnceAfterPlaybackAndStopsOnCommittedStop(t *testing.T) {
	f := newPartyFixture(t)
	prefetch := &fakePrefetch{}
	f.s.SetPrefetch(prefetch)
	device, _ := f.device(t)
	session := want(t, f.request(t, "POST", "/api/v1/tv/sessions", nil, device, false, false), 200)
	sid := str(session, "id")
	base := "/api/v1/tv/sessions/" + sid
	for i, song := range []string{"song-a", "song-b"} {
		want(t, f.request(t, "POST", base+"/commands", document{"action": "ADD", "songId": song, "expectedRevision": i, "requestId": "add-" + song}, device, false, false), 200)
	}
	started := want(t, f.request(t, "POST", base+"/commands", document{"action": "START", "expectedRevision": 2, "requestId": "start"}, device, false, false), 200)
	attempt := object(started, "playback")["attemptId"]
	if prefetch.starts.Load() != 0 {
		t.Fatal("prefetch before actual playback")
	}
	for i := 1; i <= 2; i++ {
		want(t, f.request(t, "POST", base+"/playback-events", document{"attemptId": attempt, "eventSeq": i, "state": "PLAYING", "positionMs": 155000, "durationMs": 180000}, device, false, false), 200)
	}
	if prefetch.starts.Load() != 1 || prefetch.active.Load() == nil || !prefetch.active.Load().(func() bool)() {
		t.Fatal("prefetch missing or duplicated", prefetch.starts.Load())
	}
	want(t, f.request(t, "POST", base+"/commands", document{"action": "STOP", "expectedRevision": 3, "requestId": "stop"}, device, false, false), 200)
	if prefetch.cancels.Load() != 1 || prefetch.active.Load().(func() bool)() {
		t.Fatal("STOP did not cancel prefetch")
	}
	want(t, f.request(t, "POST", base+"/playback-events", document{"attemptId": attempt, "eventSeq": 3, "state": "PLAYING", "positionMs": 160000, "durationMs": 180000}, device, false, false), 200)
	if prefetch.starts.Load() != 1 {
		t.Fatal("late telemetry restarted stopped prefetch")
	}
}

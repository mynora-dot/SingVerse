package catalog

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"github.com/gin-gonic/gin"
	"github.com/jmoiron/sqlx"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/ai"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/config"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
	"image"
	"image/color"
	"image/png"
	"io"
	"log/slog"
	"mime/multipart"
	_ "modernc.org/sqlite"
	"net/http"
	"net/http/httptest"
	"net/textproto"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync/atomic"
	"testing"
)

type fakeCipher struct{}

func (fakeCipher) Encrypt(v, p string) (string, error) { return p + ":" + v, nil }
func (fakeCipher) Decrypt(v, p string) (string, error) {
	if !strings.HasPrefix(v, p+":") {
		return "", errors.New("bad credential")
	}
	return strings.TrimPrefix(v, p+":"), nil
}

type fakeLibrary struct {
	songs     []map[string]any
	bulkCalls atomic.Int64
}

func (l *fakeLibrary) Song(ctx context.Context, id string) (map[string]any, error) {
	return l.SongWith(ctx, nil, id)
}
func (l *fakeLibrary) SongWith(ctx context.Context, tx store.DBTX, id string) (map[string]any, error) {
	for _, s := range l.songs {
		if str(s["id"]) == id {
			return s, nil
		}
	}
	return nil, fail(404, "歌曲不存在")
}
func (l *fakeLibrary) LibrarySongs(context.Context, store.DBTX) ([]map[string]any, error) {
	l.bulkCalls.Add(1)
	return l.songs, nil
}

type fakeInvalidator struct{ calls atomic.Int64 }

func (f *fakeInvalidator) Invalidate() { f.calls.Add(1) }
func fixture(t *testing.T) (*Service, *gin.Engine) {
	t.Helper()
	gin.SetMode(gin.TestMode)
	db, e := sqlx.Open("sqlite", filepath.Join(t.TempDir(), "test.db"))
	if e != nil {
		t.Fatal(e)
	}
	db.SetMaxOpenConns(1)
	t.Cleanup(func() { db.Close() })
	files, _ := filepath.Glob("../../../../database/migrations/sqlite/*.sql")
	sort.Strings(files)
	if len(files) == 0 {
		t.Fatal("missing schema")
	}
	for _, p := range files {
		b, e := os.ReadFile(p)
		if e != nil {
			t.Fatal(e)
		}
		up := strings.Split(strings.SplitN(string(b), "-- migrate:up", 2)[1], "-- migrate:down")[0]
		if _, e = db.Exec(up); e != nil {
			t.Fatal(e)
		}
	}
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	models, e := ai.New(db, "sqlite", config.Config{}, fakeCipher{}, log)
	if e != nil {
		t.Fatal(e)
	}
	lib := &fakeLibrary{songs: []map[string]any{{"id": "song-a", "sourceId": "source", "relativePath": "周杰伦-晴天.mp4", "scannedAt": "2026-11-09T01:00:00Z", "result": map[string]any{"title": "晴天", "artists": []string{"周杰伦"}, "language": "MANDARIN", "versionType": "ORIGINAL", "qualityTags": []string{}}, "classification": map[string]any{"dimensions": map[string]any{}}}, {"id": "song-b", "sourceId": "source", "relativePath": "周杰伦-稻香.mp4", "scannedAt": "2026-11-08T01:00:00Z", "result": map[string]any{"title": "稻香", "artists": []string{"周杰伦"}, "language": "MANDARIN", "versionType": "ORIGINAL", "qualityTags": []string{}}, "classification": map[string]any{"dimensions": map[string]any{}}}}}
	s, e := New(db, "sqlite", config.Config{}, lib, models, fakeCipher{}, &fakeInvalidator{}, log)
	if e != nil {
		t.Fatal(e)
	}
	s.Close()
	r := gin.New()
	s.Register(r)
	models.Register(r)
	return s, r
}
func request(t *testing.T, r http.Handler, method, path string, v any, status int) map[string]any {
	t.Helper()
	var b io.Reader
	if v != nil {
		raw, _ := json.Marshal(v)
		b = bytes.NewReader(raw)
	}
	req := httptest.NewRequest(method, path, b)
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	if w.Code != status {
		t.Fatalf("%s %s: %d want %d %s", method, path, w.Code, status, w.Body.String())
	}
	out := map[string]any{}
	if status != 204 && w.Body.Len() > 0 {
		if e := json.Unmarshal(w.Body.Bytes(), &out); e != nil {
			t.Fatal(e, w.Body.String())
		}
	}
	return out
}
func TestClassificationReceiptsHistoryAndManualProtection(t *testing.T) {
	s, r := fixture(t)
	ctx := context.Background()
	tag, e := s.saveTag(ctx, "", SaveTag{"GENRE", "回归标签", []string{}, true, 0})
	if e != nil {
		t.Fatal(e)
	}
	tagID := tag.(Tag).ID
	change := Change{id(), 0, map[string][]string{"GENRE": {tagID}}, []string{"GENRE"}}
	first := request(t, r, "PUT", "/api/v1/classification/songs/song-a", change, 200)
	if num(first["revision"]) != 1 {
		t.Fatal(first)
	}
	second := request(t, r, "PUT", "/api/v1/classification/songs/song-a", change, 200)
	if encode(first) != encode(second) {
		t.Fatal("receipt did not preserve result")
	}
	change.Revision = 1
	request(t, r, "PUT", "/api/v1/classification/songs/song-a", change, 409)
	bad := Change{id(), 1, map[string][]string{"MOOD": {tagID}}, []string{}}
	request(t, r, "PUT", "/api/v1/classification/songs/song-a", bad, 409)
	state, e := s.stored(ctx, s.db, "song-a")
	if e != nil || state.Revision != 1 || !state.Dimensions["GENRE"].Locked {
		t.Fatal(state, e)
	}
	history, e := s.history(ctx, "song-a", 0, 20)
	if e != nil {
		t.Fatal(e)
	}
	h := history.([]map[string]any)[0]
	undone := request(t, r, "POST", "/api/v1/classification/history/"+str(h["id"])+"/undo", Undo{id(), 1}, 200)
	if num(undone["revision"]) != 2 || len(obj(undone["dimensions"])) != 0 {
		t.Fatal(undone)
	}
	request(t, r, "POST", "/api/v1/classification/history/"+str(h["id"])+"/undo", Undo{id(), 2}, 409)
	if _, e = s.saveTag(ctx, tagID, SaveTag{"MOOD", "换维度", []string{}, true, 1}); e == nil {
		t.Fatal("tag moved dimension")
	}
}
func configure(t *testing.T, s *Service, endpoint string) {
	t.Helper()
	m, e := s.ai.Save(context.Background(), "MODEL", "", ai.ServiceInput{Name: "test", Provider: "custom", Protocol: "CHAT_COMPLETIONS", Endpoint: endpoint, Model: "test", OutputMode: "JSON_SCHEMA", Enabled: true, TimeoutSeconds: 5, MaxResults: 5})
	if e != nil {
		t.Fatal(e)
	}
	f, e := s.ai.Feature(context.Background(), "classification")
	if e != nil {
		t.Fatal(e)
	}
	f.Enabled = true
	f.ModelServiceID = &m.ID
	f.IntervalSeconds = 2
	if _, e = s.ai.Bind(context.Background(), f.ID, f); e != nil {
		t.Fatal(e)
	}
}
func TestClassificationWorkerAutomaticReviewCancelAndProjection(t *testing.T) {
	s, r := fixture(t)
	ctx := context.Background()
	tag, e := s.saveTag(ctx, "", SaveTag{"GENRE", "模型标签", []string{}, true, 0})
	if e != nil {
		t.Fatal(e)
	}
	tagID := tag.(Tag).ID
	refuse := false
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		content := encode(Suggestion{map[string][]string{"GENRE": {tagID}}, map[string]string{"GENRE": "测试推断"}, []string{}})
		if refuse {
			content = `{"dimensions":{"GENRE":["outside"]},"reasons":{"GENRE":"x"},"newTagSuggestions":[]}`
		}
		json.NewEncoder(w).Encode(map[string]any{"choices": []any{map[string]any{"finish_reason": "stop", "message": map[string]any{"content": content}}}})
	}))
	defer server.Close()
	configure(t, s, server.URL)
	var projections atomic.Int64
	s.SetProjector(func(ctx context.Context, tx store.DBTX, kind, id string) error {
		if kind != "CLASSIFICATION" {
			t.Errorf("wrong kind %s", kind)
		}
		var n int
		if e := tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM classification_task WHERE id=?", id).Scan(&n); e != nil {
			return e
		}
		if n != 1 {
			t.Error("projected before write")
		}
		projections.Add(1)
		return nil
	})
	started := request(t, r, "POST", "/api/v1/classification/tasks", Start{SongIDs: []string{"song-a", "song-b"}, Dimensions: []string{"GENRE"}, AutoDimensions: []string{"GENRE"}}, 201)
	task := str(started["id"])
	manual := Change{id(), 0, map[string][]string{"GENRE": {tagID}}, []string{}}
	request(t, r, "PUT", "/api/v1/classification/songs/song-a", manual, 200)
	s.ProcessNext(ctx)
	if _, e = s.db.Exec("UPDATE classification_settings SET next_request_at=''; UPDATE classification_task SET next_at=''"); e != nil {
		t.Fatal(e)
	}
	s.ProcessNext(ctx)
	view := request(t, r, "GET", "/api/v1/classification/tasks/"+task, nil, 200)
	counts := obj(view["counts"])
	if num(counts["REVIEW"]) != 1 || num(counts["APPLIED"]) != 1 || str(view["status"]) != "COMPLETED" {
		t.Fatal(view)
	}
	if projections.Load() < 5 {
		t.Fatal("missing state projections", projections.Load())
	}
	state, e := s.stored(ctx, s.db, "song-a")
	if e != nil || state.Dimensions["GENRE"].Origin != "MANUAL" {
		t.Fatal(state, e)
	}
	state, e = s.stored(ctx, s.db, "song-b")
	if e != nil || state.Dimensions["GENRE"].Origin != "AI" || state.Dimensions["GENRE"].Confirmed {
		t.Fatal(state, e)
	}
	items := view["items"].([]any)
	var review map[string]any
	for _, v := range items {
		m := obj(v)
		if str(m["status"]) == "REVIEW" {
			review = m
		}
	}
	applied := request(t, r, "POST", "/api/v1/classification/tasks/"+task+"/items/"+str(review["id"])+"/apply", Review{id(), 1, []string{"GENRE"}}, 200)
	if num(applied["revision"]) != 2 {
		t.Fatal(applied)
	}
	refuse = true
	started = request(t, r, "POST", "/api/v1/classification/tasks", Start{SongIDs: []string{"song-a"}, Dimensions: []string{"GENRE"}, AutoDimensions: []string{}}, 201)
	task = str(started["id"])
	if _, e = s.db.Exec("UPDATE classification_settings SET next_request_at=''"); e != nil {
		t.Fatal(e)
	}
	s.ProcessNext(ctx)
	view = request(t, r, "GET", "/api/v1/classification/tasks/"+task, nil, 200)
	if num(obj(view["counts"])["FAILED"]) != 1 {
		t.Fatal("untrusted model output applied", view)
	}
	started = request(t, r, "POST", "/api/v1/classification/tasks", Start{SongIDs: []string{"song-b"}, Dimensions: []string{"GENRE"}, AutoDimensions: []string{}}, 201)
	request(t, r, "POST", "/api/v1/classification/tasks/"+str(started["id"])+"/cancel", nil, 200)
	view = request(t, r, "GET", "/api/v1/classification/tasks/"+str(started["id"]), nil, 200)
	if num(obj(view["counts"])["CANCELLED"]) != 1 {
		t.Fatal(view)
	}
}
func TestPlaylistIdempotencyRevisionMissingSongsAndArtists(t *testing.T) {
	s, r := fixture(t)
	desc := "test"
	in := PlaylistSave{RequestID: id(), Name: "歌单", Description: &desc, SongIDs: []string{"song-a", "song-b"}}
	first := request(t, r, "POST", "/api/v1/playlists", in, 201)
	second := request(t, r, "POST", "/api/v1/playlists", in, 201)
	if str(first["id"]) != str(second["id"]) {
		t.Fatal("duplicate playlist")
	}
	in.Name = "another"
	request(t, r, "POST", "/api/v1/playlists", in, 409)
	in.Revision = 1
	request(t, r, "PUT", "/api/v1/playlists/"+str(first["id"]), in, 200)
	request(t, r, "PUT", "/api/v1/playlists/"+str(first["id"]), in, 409)
	s.library.(*fakeLibrary).songs = s.library.(*fakeLibrary).songs[:1]
	detail := request(t, r, "GET", "/api/v1/playlists/"+str(first["id"]), nil, 200)
	if num(detail["count"]) != 1 || num(detail["missingCount"]) != 1 {
		t.Fatal(detail)
	}
	artists := request(t, r, "GET", "/api/v1/artists?q=zhoujielun", nil, 200)
	if num(artists["totalElements"]) != 1 {
		t.Fatal(artists)
	}
	artist := obj(artists["items"].([]any)[0])
	if str(artist["id"]) != artistID("周杰伦") || num(artist["count"]) != 1 {
		t.Fatal(artist)
	}
	request(t, r, "PUT", "/api/v1/artists/"+str(artist["id"])+"/category", map[string]any{"type": "MALE", "revision": 1}, 200)
	request(t, r, "PUT", "/api/v1/artists/"+str(artist["id"])+"/category", map[string]any{"type": "FEMALE", "revision": 1}, 409)
}
func TestImageNormalizationCASAndArtworkPolicy(t *testing.T) {
	s, r := fixture(t)
	ctx := context.Background()
	img := image.NewNRGBA(image.Rect(0, 0, 2, 2))
	img.Set(0, 0, color.NRGBA{255, 0, 0, 255})
	var b bytes.Buffer
	png.Encode(&b, img)
	data, e := normalizeImage(b.Bytes(), "image/png")
	if e != nil {
		t.Fatal(e)
	}
	if _, e = normalizeImage(b.Bytes(), "image/jpeg"); e == nil {
		t.Fatal("mime mismatch accepted")
	}
	tag, e := s.saveTag(ctx, "", SaveTag{"CUSTOM", "封面标签", []string{}, true, 0})
	if e != nil {
		t.Fatal(e)
	}
	v, e := s.tagCover(ctx, tag.(Tag).ID, "", data, false)
	if e != nil {
		t.Fatal(e)
	}
	version := *v.(Tag).CoverVersion
	uploadJPEG(t, r, "/api/v1/classification/tags/"+tag.(Tag).ID+"/cover", b.Bytes())
	assertJPEG(t, r, "/api/v1/classification/tags/"+tag.(Tag).ID+"/cover", data)
	request(t, r, "DELETE", "/api/v1/classification/tags/"+tag.(Tag).ID+"/cover", nil, 400)
	request(t, r, "PUT", "/api/v1/classification/tags/"+tag.(Tag).ID+"/cover", map[string]any{"file": "invalid"}, 415)
	if _, e = s.tagCover(ctx, tag.(Tag).ID, "stale", []byte("changed"), false); e == nil {
		t.Fatal("cover CAS ignored")
	}
	if _, e = s.tagCover(ctx, tag.(Tag).ID, version, nil, true); e != nil {
		t.Fatal(e)
	}
	var groups map[string][]map[string]any
	if e = store.WithinTx(ctx, s.db, func(tx store.DBTX) error { var e error; groups, e = s.ensureArtists(ctx, tx); return e }); e != nil {
		t.Fatal(e)
	}
	for artist := range groups {
		uploadJPEG(t, r, "/api/v1/artists/"+artist+"/images/portrait", b.Bytes())
		assertJPEG(t, r, "/api/v1/artists/"+artist+"/images/portrait", data)
		request(t, r, "DELETE", "/api/v1/artists/"+artist+"/images/portrait", nil, 400)
		blocked, e := s.artworkBlocked(ctx, s.db, artist, "PORTRAIT")
		if e != nil || !blocked {
			t.Fatal("upload policy absent", e)
		}
		if _, e = s.artistImage(ctx, artist, "portrait", sha(string(data)), nil, true); e != nil {
			t.Fatal(e)
		}
		blocked, e = s.artworkBlocked(ctx, s.db, artist, "PORTRAIT")
		if e != nil || !blocked {
			t.Fatal("delete did not block auto refill", e)
		}
		if e = s.retryArtwork(ctx, artist); e != nil {
			t.Fatal(e)
		}
		blocked, e = s.artworkBlocked(ctx, s.db, artist, "PORTRAIT")
		if e != nil || blocked {
			t.Fatal("explicit retry did not unblock", e)
		}
	}
}

func assertJPEG(t *testing.T, r http.Handler, path string, expected []byte) {
	t.Helper()
	w := httptest.NewRecorder()
	r.ServeHTTP(w, httptest.NewRequest("GET", path, nil))
	if w.Code != 200 || w.Header().Get("Content-Type") != "image/jpeg" || w.Header().Get("X-Content-Type-Options") != "nosniff" || !bytes.Equal(w.Body.Bytes(), expected) {
		t.Fatalf("stored image round trip failed: %s status=%d headers=%v bytes=%d", path, w.Code, w.Header(), w.Body.Len())
	}
	if _, kind, e := image.Decode(bytes.NewReader(w.Body.Bytes())); e != nil || kind != "jpeg" {
		t.Fatalf("stored image is not JPEG: %s %v", kind, e)
	}
}
func uploadJPEG(t *testing.T, r http.Handler, path string, pngData []byte) {
	t.Helper()
	var body bytes.Buffer
	m := multipart.NewWriter(&body)
	part, e := m.CreatePart(textproto.MIMEHeader{"Content-Disposition": {`form-data; name="file"; filename="cover.png"`}, "Content-Type": {"image/png"}})
	if e != nil {
		t.Fatal(e)
	}
	if _, e = part.Write(pngData); e != nil {
		t.Fatal(e)
	}
	if e = m.Close(); e != nil {
		t.Fatal(e)
	}
	req := httptest.NewRequest("PUT", path, &body)
	req.Header.Set("Content-Type", m.FormDataContentType())
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	if w.Code != 200 {
		t.Fatalf("image upload: %d %s", w.Code, w.Body.String())
	}
}

func TestPlaylistListUsesSingleLibrarySnapshot(t *testing.T) {
	s, r := fixture(t)
	description := "排序回归"
	for _, name := range []string{"一", "二", "三"} {
		request(t, r, "POST", "/api/v1/playlists", PlaylistSave{RequestID: id(), Name: name, Description: &description, SongIDs: []string{"song-a"}}, 201)
	}
	lib := s.library.(*fakeLibrary)
	lib.bulkCalls.Store(0)
	got := request(t, r, "GET", "/api/v1/playlists?sort=NAME_ASC", nil, 200)
	if num(got["totalElements"]) != 3 || len(got["items"].([]any)) != 3 || lib.bulkCalls.Load() != 1 {
		t.Fatalf("playlist list repeated full library projection: %v calls=%d", got, lib.bulkCalls.Load())
	}
}

func TestArtistOverrideAndDurableFileCallbackCommitTogether(t *testing.T) {
	s, _ := fixture(t)
	ctx := context.Background()
	source, relative := "source", "周杰伦-晴天.mp4"
	blocked := errors.New("durable file write unavailable")
	s.SetPathChanged(func(ctx context.Context, tx store.DBTX, gotSource, gotRelative string) error {
		if gotSource != source || gotRelative != relative {
			t.Errorf("incorrect durable identity %s %s", gotSource, gotRelative)
		}
		var value string
		if e := tx.QueryRowContext(ctx, "SELECT artists_json FROM song_artist_override WHERE source_id=? AND path_key=?", source, sha(relative)).Scan(&value); e != nil {
			t.Fatal(e)
		}
		if value != `["新歌手"]` {
			t.Fatalf("callback did not share updated transaction: %s", value)
		}
		return blocked
	})
	if e := store.WithinTx(ctx, s.db, func(tx store.DBTX) error { return s.ArtistEdited(ctx, tx, source, relative, []string{"新歌手"}) }); !errors.Is(e, blocked) {
		t.Fatal(e)
	}
	var count int
	if e := s.db.QueryRow("SELECT COUNT(*) FROM song_artist_override").Scan(&count); e != nil || count != 0 {
		t.Fatalf("failed durable update committed override: %d %v", count, e)
	}
	var calls int
	s.SetPathChanged(func(context.Context, store.DBTX, string, string) error { calls++; return nil })
	if e := store.WithinTx(ctx, s.db, func(tx store.DBTX) error { return s.ArtistEdited(ctx, tx, source, relative, []string{"新歌手"}) }); e != nil {
		t.Fatal(e)
	}
	view, e := s.artistOverride(ctx, "song-a", false, 0)
	if e != nil || num(obj(view)["revision"]) != 1 || obj(view)["overridden"] != true {
		t.Fatal(view, e)
	}
	if _, e = s.artistOverride(ctx, "song-a", true, 1); e != nil {
		t.Fatal(e)
	}
	view, e = s.artistOverride(ctx, "song-a", false, 0)
	if e != nil || num(obj(view)["revision"]) != 2 || obj(view)["overridden"] != false || calls != 2 {
		t.Fatal(view, e, calls)
	}
}

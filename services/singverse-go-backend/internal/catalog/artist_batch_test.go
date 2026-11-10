package catalog

import (
	"bytes"
	"context"
	"errors"
	"image"
	"image/png"
	"io"
	"net/http"
	"testing"

	"github.com/gin-gonic/gin"
	"github.com/jmoiron/sqlx"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/ai"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/background"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/metadata"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/config"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/testutil"
)

type batchArtistProvider struct {
	call    func(string, string) (*metadata.Artwork, error)
	enabled []string
}

func (p batchArtistProvider) ArtistArtwork(ctx context.Context, name string, titles []string, refs []metadata.ArtistRef) (*metadata.Artwork, error) {
	return p.ArtistArtworkFrom(ctx, name, titles, refs, "AUTO")
}
func (p batchArtistProvider) ArtistArtworkFrom(_ context.Context, name string, _ []string, _ []metadata.ArtistRef, provider string) (*metadata.Artwork, error) {
	return p.call(name, provider)
}
func (p batchArtistProvider) ArtistArtworkProviders(context.Context, store.DBTX) ([]string, error) {
	if p.enabled != nil {
		return p.enabled, nil
	}
	return []string{"NETEASE"}, nil
}

func batchFixture(t *testing.T, db *sqlx.DB, dialect string) (*Service, *gin.Engine) {
	t.Helper()
	models, e := ai.New(db, dialect, config.Config{}, fakeCipher{}, nil)
	if e != nil {
		t.Fatal(e)
	}
	lib := &fakeLibrary{}
	for _, name := range []string{"周杰伦", "刘若英", "五月天"} {
		lib.songs = append(lib.songs, map[string]any{"id": artistID(name), "sourceId": "source", "relativePath": name + "-歌曲.mp4", "result": map[string]any{"title": "歌曲", "artists": []string{name}}})
	}
	s, e := New(db, dialect, config.Config{}, lib, models, fakeCipher{}, nil, nil)
	if e != nil {
		t.Fatal(e)
	}
	s.Close()
	tasks, e := background.New(db, dialect, nil)
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(func() { tasks.Close() })
	s.SetProjector(tasks.Project)
	r := gin.New()
	s.Register(r)
	return s, r
}

func TestArtistArtworkBatchSQLite(t *testing.T) { testArtworkBatch(t, testutil.Database(t), "sqlite") }
func TestMySQLArtistArtworkBatch(t *testing.T) {
	db, _ := testutil.MySQLDatabase(t)
	testArtworkBatch(t, db, "mysql")
}
func testArtworkBatch(t *testing.T, db *sqlx.DB, dialect string) {
	s, r := batchFixture(t, db, dialect)
	ctx := context.Background()
	var encoded bytes.Buffer
	if e := png.Encode(&encoded, image.NewRGBA(image.Rect(0, 0, 8, 8))); e != nil {
		t.Fatal(e)
	}
	data, e := normalizeImage(encoded.Bytes(), "image/png")
	if e != nil {
		t.Fatal(e)
	}
	prior := sourceClient
	t.Cleanup(func() { sourceClient = prior })
	downloads := 0
	sourceClient = &http.Client{Transport: artworkTransport(func(r *http.Request) (*http.Response, error) {
		downloads++
		if r.URL.Hostname() != "p1.music.126.net" {
			t.Fatal("explicit platform unexpectedly used another source", r.URL.Hostname())
		}
		return &http.Response{StatusCode: 200, Header: http.Header{"Content-Type": {"image/jpg"}}, Body: io.NopCloser(bytes.NewReader(encoded.Bytes()))}, nil
	})}
	calls := 0
	s.SetArtistProvider(batchArtistProvider{call: func(name, provider string) (*metadata.Artwork, error) {
		calls++
		if provider != "NETEASE" {
			t.Fatal("platform selection lost", provider)
		}
		if name == "刘若英" {
			return nil, errors.New("upstream unavailable")
		}
		return &metadata.Artwork{Provider: provider, ExternalID: "7", Portrait: "https://p1.music.126.net/portrait.png"}, nil
	}})
	request(t, r, "GET", "/api/v1/artists", nil, 200)
	if _, e = s.artistImage(ctx, artistID("五月天"), "portrait", "", data, false); e != nil {
		t.Fatal(e)
	}
	request(t, r, "POST", "/api/v1/artists/artwork-batches", ArtworkBatchStart{RequestID: id(), Provider: "QQ", ArtistIDs: []string{artistID("周杰伦")}}, 409)
	input := ArtworkBatchStart{RequestID: id(), Provider: "NETEASE", AllMatching: true, Filter: ArtistFilter{}}
	first := request(t, r, "POST", "/api/v1/artists/artwork-batches", input, 201)
	batch := str(first["id"])
	again := request(t, r, "POST", "/api/v1/artists/artwork-batches", input, 201)
	if str(again["id"]) != batch || num(first["total"]) != 3 || num(obj(first["counts"])["SKIPPED"]) != 1 {
		t.Fatal("snapshot or receipt changed", first, again)
	}
	input.Provider = "AUTO"
	request(t, r, "POST", "/api/v1/artists/artwork-batches", input, 409)
	for range 2 {
		if !s.processArtworkBatch(ctx) {
			t.Fatal("durable batch queue stopped early")
		}
	}
	if s.processArtworkBatch(ctx) {
		t.Fatal("durable batch queue did not drain")
	}
	view := request(t, r, "GET", "/api/v1/artists/artwork-batches/"+batch, nil, 200)
	counts := obj(view["counts"])
	if str(view["status"]) != "COMPLETED" || num(view["completed"]) != 3 || num(counts["APPLIED"]) != 1 || num(counts["FAILED"]) != 1 || num(counts["SKIPPED"]) != 1 || calls != 2 || downloads != 1 {
		t.Fatal(view, calls, downloads)
	}
	var kind, state, unit string
	if e = db.QueryRow("SELECT type,status,progress_unit FROM background_task WHERE business_id=?", batch).Scan(&kind, &state, &unit); e != nil || kind != "ARTIST_ARTWORK" || state != "PARTIAL" || unit != "ARTISTS" {
		t.Fatal("task projection", kind, state, unit, e)
	}
	var items int
	if e = db.QueryRow("SELECT COUNT(*) FROM background_task_item WHERE task_id=?", background.ID("ARTIST_ARTWORK", batch)).Scan(&items); e != nil || items != 3 {
		t.Fatal("task items", items, e)
	}
	result := request(t, r, "GET", "/api/v1/artists?artwork=MISSING&size=1&page=0", nil, 200)
	if num(result["totalElements"]) != 1 || str(obj(result["items"].([]any)[0])["name"]) != "刘若英" {
		t.Fatal("filter applied after pagination", result)
	}
	// Atomic revision checks prevent a partially applied bulk category change.
	request(t, r, "POST", "/api/v1/artists/categories", map[string]any{"type": "MALE", "artists": []ArtistCategorySelection{{artistID("周杰伦"), 1}, {artistID("刘若英"), 999}}}, 409)
	untouched := request(t, r, "GET", "/api/v1/artists/"+artistID("周杰伦"), nil, 200)
	if str(untouched["type"]) != "UNKNOWN" || num(untouched["revision"]) != 1 {
		t.Fatal("bulk conflict partially committed", untouched)
	}
	request(t, r, "POST", "/api/v1/artists/categories", map[string]any{"type": "MALE", "artists": []ArtistCategorySelection{{artistID("周杰伦"), 1}}}, 200)
	filtered := request(t, r, "GET", "/api/v1/artists?type=MALE&size=1", nil, 200)
	if num(filtered["totalElements"]) != 1 {
		t.Fatal(filtered)
	}
	// Every filtered artist is selected on the server, not just the visible page.
	selected := request(t, r, "POST", "/api/v1/artists/artwork-batches", ArtworkBatchStart{RequestID: id(), Provider: "NETEASE", AllMatching: true, Filter: ArtistFilter{Type: "UNKNOWN", Artwork: "MISSING"}}, 201)
	if num(selected["total"]) != 1 || str(obj(selected["items"].([]any)[0])["name"]) != "刘若英" {
		t.Fatal(selected)
	}
}

func TestArtistArtworkBatchProtectsManualChangesAndRecovers(t *testing.T) {
	s, r := batchFixture(t, testutil.Database(t), "sqlite")
	ctx := context.Background()
	request(t, r, "GET", "/api/v1/artists", nil, 200)
	var encoded bytes.Buffer
	png.Encode(&encoded, image.NewRGBA(image.Rect(0, 0, 4, 4)))
	data, _ := normalizeImage(encoded.Bytes(), "image/png")
	prior := sourceClient
	t.Cleanup(func() { sourceClient = prior })
	sourceClient = &http.Client{Transport: artworkTransport(func(*http.Request) (*http.Response, error) {
		return &http.Response{StatusCode: 200, Header: make(http.Header), Body: io.NopCloser(bytes.NewReader(encoded.Bytes()))}, nil
	})}
	s.SetArtistProvider(batchArtistProvider{call: func(name, provider string) (*metadata.Artwork, error) {
		// A manual upload while the provider request is in flight must win.
		if _, e := s.artistImage(ctx, artistID(name), "portrait", "", data, false); e != nil {
			t.Fatal(e)
		}
		return &metadata.Artwork{Provider: provider, ExternalID: "7", Portrait: "https://p1.music.126.net/image.png"}, nil
	}})
	input := ArtworkBatchStart{RequestID: id(), Provider: "NETEASE", ArtistIDs: []string{artistID("周杰伦")}}
	batch := request(t, r, "POST", "/api/v1/artists/artwork-batches", input, 201)
	// Simulate a process restart after claiming the item.
	if _, e := s.db.Exec("UPDATE artist_artwork_item SET status='PROCESSING' WHERE batch_id=?", batch["id"]); e != nil {
		t.Fatal(e)
	}
	if e := s.recover(ctx); e != nil {
		t.Fatal(e)
	}
	s.processArtworkBatch(ctx)
	view := request(t, r, "GET", "/api/v1/artists/artwork-batches/"+str(batch["id"]), nil, 200)
	if num(obj(view["counts"])["SKIPPED"]) != 1 {
		t.Fatal("late result replaced manual upload", view)
	}
	artist := request(t, r, "GET", "/api/v1/artists/"+artistID("周杰伦"), nil, 200)
	if str(artist["portraitOrigin"]) != "UPLOAD" || str(artist["portraitFetchState"]) != "READY" {
		t.Fatal(artist)
	}
	request(t, r, "POST", "/api/v1/artists/artwork-batches", ArtworkBatchStart{RequestID: id(), Provider: "NETEASE", ArtistIDs: []string{artistID("周杰伦"), artistID("周杰伦")}}, 400)
	request(t, r, "GET", "/api/v1/artists?artwork=INVALID", nil, 400)
	request(t, r, "GET", "/api/v1/artists/artwork-batches/missing", nil, 404)
}

func TestArtistArtworkBatchCandidatesRequireConfirmation(t *testing.T) {
	s, r := batchFixture(t, testutil.Database(t), "sqlite")
	ctx := context.Background()
	var encoded bytes.Buffer
	png.Encode(&encoded, image.NewRGBA(image.Rect(0, 0, 4, 4)))
	prior := sourceClient
	t.Cleanup(func() { sourceClient = prior })
	sourceClient = &http.Client{Transport: artworkTransport(func(req *http.Request) (*http.Response, error) {
		body := []byte(`{"artists":[]}`)
		if req.URL.Hostname() == "www.theaudiodb.com" {
			body = []byte(`{"artists":[{"idArtist":"123","strArtist":"周杰伦","strArtistThumb":"https://p1.music.126.net/image.png"}]}`)
		}
		if req.URL.Hostname() == "p1.music.126.net" {
			body = encoded.Bytes()
		}
		return &http.Response{StatusCode: 200, Header: make(http.Header), Body: io.NopCloser(bytes.NewReader(body))}, nil
	})}
	batch := request(t, r, "POST", "/api/v1/artists/artwork-batches", ArtworkBatchStart{RequestID: id(), Provider: "THEAUDIODB", AllMatching: true, Filter: ArtistFilter{Q: "周杰伦"}}, 201)
	s.processArtworkBatch(ctx)
	view := request(t, r, "GET", "/api/v1/artists/artwork-batches/"+str(batch["id"]), nil, 200)
	if num(obj(view["counts"])["REVIEW"]) != 1 || num(obj(view["counts"])["APPLIED"]) != 0 {
		t.Fatal("unverified identity was applied", view)
	}
	artist := request(t, r, "GET", "/api/v1/artists/"+artistID("周杰伦"), nil, 200)
	if artist["portraitPath"] != nil {
		t.Fatal("candidate bypassed confirmation", artist)
	}
	choices, e := s.candidates(ctx, s.db, artistID("周杰伦"), "portrait")
	if e != nil || len(choices) != 1 {
		t.Fatal(choices, e)
	}
	request(t, r, "POST", "/api/v1/artists/"+artistID("周杰伦")+"/images/portrait/selection", map[string]any{"provider": "THEAUDIODB", "externalId": "123"}, 200)
	view = request(t, r, "GET", "/api/v1/artists/artwork-batches/"+str(batch["id"]), nil, 200)
	if num(obj(view["counts"])["REVIEW"]) != 0 || num(obj(view["counts"])["APPLIED"]) != 1 {
		t.Fatal("confirmed candidate did not resolve batch review", view)
	}
}

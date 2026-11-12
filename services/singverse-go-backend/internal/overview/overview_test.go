package overview

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/jmoiron/sqlx"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/ai"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/background"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/catalog"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/compute"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/execution"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/feedback"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/library"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/metadata"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/party"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/config"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/testutil"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/playback"
)

type testCipher struct{}

func (testCipher) Encrypt(v, p string) (string, error) { return p + ":" + v, nil }
func (testCipher) Decrypt(v, p string) (string, error) { return strings.TrimPrefix(v, p+":"), nil }

func discardLogger() *slog.Logger { return slog.New(slog.NewTextHandler(io.Discard, nil)) }

func overviewSongReader(media *library.Service) playback.SongReader {
	return func(ctx context.Context, tx store.DBTX, id string) (map[string]any, error) {
		song, err := media.SongWith(ctx, tx, id)
		if err != nil {
			return nil, err
		}
		projection := party.Projection(song)
		projection["versionType"] = projection["version"]
		return projection, nil
	}
}

func overviewFeedbackReader(media *library.Service) feedback.SongReader {
	return func(ctx context.Context, tx store.DBTX, id string) (feedback.Song, error) {
		song, err := media.SongWith(ctx, tx, id)
		if err != nil {
			return feedback.Song{}, err
		}
		projection := party.Projection(song)
		title, _ := projection["title"].(string)
		artists, _ := projection["artists"].([]string)
		return feedback.Song{Title: title, Artists: artists}, nil
	}
}

type overviewFixture struct {
	db       *sqlx.DB
	svc      *Service
	deps     Dependencies
	media    *library.Service
	tasks    *background.Service
	activity *playback.Service
	router   *gin.Engine
}

func overviewConfig(t *testing.T, driver string) config.Config {
	t.Helper()
	dir := t.TempDir()
	return config.Config{
		Driver:            driver,
		MetadataPath:      filepath.Join(dir, "metadata"),
		MetadataCachePath: filepath.Join(dir, "covers"),
		TranscodePath:     filepath.Join(dir, "transcode"),
		SongCachePath:     filepath.Join(dir, "cache"),
		AudioDerivedPath:  filepath.Join(dir, "audio"),
	}
}

func newOverviewFixture(t *testing.T, db *sqlx.DB, dialect string, cfg config.Config) *overviewFixture {
	t.Helper()
	log := discardLogger()
	cipher := testCipher{}
	var closers []func() error
	closeAll := func() {
		for i := len(closers) - 1; i >= 0; i-- {
			_ = closers[i]()
		}
	}
	t.Cleanup(closeAll)
	must := func(err error) {
		t.Helper()
		if err != nil {
			closeAll()
			t.Fatal(err)
		}
	}

	media, err := library.New(db, dialect, cfg, cipher, log)
	must(err)
	closers = append(closers, media.Close)
	activity, err := playback.New(db, dialect, overviewSongReader(media), log)
	must(err)
	closers = append(closers, activity.Close)
	devices, err := party.New(db, dialect, cfg, media, log)
	must(err)
	closers = append(closers, devices.Close)
	devices.SetActivity(activity)
	devices.SetCipher(cipher)
	tasks, err := background.New(db, dialect, log)
	must(err)
	closers = append(closers, tasks.Close)
	media.SetProjector(tasks.Project)
	node, err := compute.New(db, dialect, cfg, cipher, log)
	must(err)
	closers = append(closers, func() error { node.Close(); return nil })
	models, err := ai.New(db, dialect, cfg, cipher, log)
	must(err)
	curation, err := catalog.New(db, dialect, cfg, media, models, cipher, devices, log)
	must(err)
	closers = append(closers, curation.Close)
	curation.SetProjector(tasks.Project)
	tags, err := metadata.New(db, dialect, cfg, cipher, media, log)
	must(err)
	closers = append(closers, tags.Close)
	tags.SetProjector(tasks.Project)
	processing, err := execution.New(db, dialect, cfg, cipher, media, node, log)
	must(err)
	closers = append(closers, func() error { processing.Close(); return nil })
	missing, err := feedback.New(db, overviewFeedbackReader(media))
	must(err)

	deps := Dependencies{
		DB:         db,
		Library:    media,
		Metadata:   tags,
		Catalog:    curation,
		Party:      devices,
		Background: tasks,
		Playback:   activity,
		Cache:      processing,
		Compute:    node,
		AI:         models,
		Feedback:   missing,
		Log:        log,
	}
	gin.SetMode(gin.TestMode)
	router := gin.New()
	media.Register(router.Group("/api/v1"))
	tasks.Register(router.Group("/api/v1"))
	return &overviewFixture{db: db, svc: New(deps), deps: deps, media: media, tasks: tasks, activity: activity, router: router}
}

func (f *overviewFixture) httpJSON(t *testing.T, method, path string, body any, status int) map[string]any {
	t.Helper()
	var reader io.Reader
	if body != nil {
		encoded, err := json.Marshal(body)
		if err != nil {
			t.Fatal(err)
		}
		reader = strings.NewReader(string(encoded))
	}
	r := httptest.NewRequest(method, path, reader)
	if body != nil {
		r.Header.Set("Content-Type", "application/json")
	}
	w := httptest.NewRecorder()
	f.router.ServeHTTP(w, r)
	if w.Code != status {
		t.Fatalf("%s %s: got %d want %d: %s", method, path, w.Code, status, w.Body.String())
	}
	out := map[string]any{}
	if w.Body.Len() > 0 {
		if err := json.Unmarshal(w.Body.Bytes(), &out); err != nil {
			t.Fatal(err)
		}
	}
	return out
}

func (f *overviewFixture) exec(t *testing.T, statement string, args ...any) {
	t.Helper()
	if _, err := f.db.ExecContext(context.Background(), statement, args...); err != nil {
		t.Fatal(err)
	}
}

// seedSongs creates a local source with two cleanly parsed songs and marks one
// as needing review, returning the source id and both song ids.
func (f *overviewFixture) seedSongs(t *testing.T) (string, []string) {
	t.Helper()
	mediaDir := t.TempDir()
	for _, name := range []string{"周杰伦-晴天.mkv", "李娜-青藏高原.mp3"} {
		if err := os.WriteFile(filepath.Join(mediaDir, name), []byte("isolated media"), 0600); err != nil {
			t.Fatal(err)
		}
	}
	source := f.httpJSON(t, "POST", "/api/v1/media-sources", map[string]any{"name": "总览来源", "type": "LOCAL", "location": mediaDir}, 201)
	sourceID := source["id"].(string)
	f.httpJSON(t, "POST", "/api/v1/media-sources/"+sourceID+"/scan", map[string]any{}, 200)
	page := f.httpJSON(t, "GET", "/api/v1/media-songs?sourceId="+sourceID+"&size=10", nil, 200)
	items := page["items"].([]any)
	if len(items) != 2 {
		t.Fatalf("expected 2 scanned songs, got %d: %v", len(items), page)
	}
	ids := []string{}
	for _, item := range items {
		ids = append(ids, item.(map[string]any)["id"].(string))
	}
	f.exec(t, "UPDATE media_song SET needs_review=1 WHERE id=?", ids[0])
	return sourceID, ids
}

// jsonNumber coerces a numeric section value to float64. Collect returns native
// Go values (int64 from the domain helpers) rather than JSON-decoded float64, so
// accept both representations.
func jsonNumber(t *testing.T, v any) float64 {
	t.Helper()
	switch n := v.(type) {
	case float64:
		return n
	case int64:
		return float64(n)
	case int:
		return float64(n)
	case json.Number:
		f, err := n.Float64()
		if err != nil {
			t.Fatalf("invalid json.Number %v: %v", v, err)
		}
		return f
	default:
		t.Fatalf("expected numeric value, got %T (%v)", v, v)
		return 0
	}
}

// anySlice normalizes a section slice to []any. Collect returns native Go
// slices (e.g. []map[string]any from background) rather than JSON-decoded
// []any, so accept both.
func anySlice(t *testing.T, v any) []any {
	t.Helper()
	switch s := v.(type) {
	case []any:
		return s
	case []map[string]any:
		out := make([]any, len(s))
		for i, item := range s {
			out[i] = item
		}
		return out
	default:
		t.Fatalf("expected slice value, got %T (%v)", v, v)
		return nil
	}
}

func TestOverviewSQLite(t *testing.T) {
	exerciseOverview(t, testutil.Database(t), "sqlite", overviewConfig(t, "sqlite"))
}

func exerciseOverview(t *testing.T, db *sqlx.DB, dialect string, cfg config.Config) {
	t.Helper()
	f := newOverviewFixture(t, db, dialect, cfg)
	ctx := context.Background()
	_, songIDs := f.seedSongs(t)

	// Formal documents: two songs, one variant, one media; a suppressed song is
	// excluded. The scan auto-creates formal documents per song, so reset the
	// table to a controlled state to assert FormalCounts semantics precisely.
	// The song projection LEFT JOINs this table, so both /media-songs and
	// recentSongs stay consistent after the reset.
	f.exec(t, "DELETE FROM metadata_document")
	at := time.Now().UTC().Format(time.RFC3339Nano)
	for _, doc := range []struct{ id, kind string }{{"formal-song-1", "SONG"}, {"formal-song-2", "SONG"}, {"formal-variant-1", "VARIANT"}, {"formal-media-1", "MEDIA"}} {
		f.exec(t, "INSERT INTO metadata_document(id,entity_type,revision,document_json,search_text,suppressed,updated_at) VALUES(?,?,?,'{}','',0,?)", doc.id, doc.kind, "1", at)
	}
	f.exec(t, "INSERT INTO metadata_document(id,entity_type,revision,document_json,search_text,suppressed,updated_at) VALUES('formal-song-hidden','SONG','1','{}','',1,?)", at)

	// An active import task whose progress denominator is still unknown.
	f.exec(t, "INSERT INTO media_source(id,name,source_type,location,location_hash,username,created_at,updated_at) VALUES('overview-import-source','导入来源','WEBDAV','https://example.invalid/dav/','overview-import-root','',?,?)", at, at)
	f.exec(t, "INSERT INTO media_import_job(id,source_id,binding,state,phase,created_at,updated_at) VALUES('overview-import','overview-import-source','overview-binding','PARSING','PARSING',?,?)", at, at)
	if err := store.WithinTx(ctx, db, func(tx store.DBTX) error {
		return f.tasks.Project(ctx, tx, "SHARE_IMPORT", "overview-import")
	}); err != nil {
		t.Fatal(err)
	}

	// One effective playback within the trailing 30 days.
	started := time.Now().UnixMilli() - 60000
	attempt, err := f.activity.Create(ctx, "MOBILE", "overview-owner", songIDs[1], "验收设备", &started)
	if err != nil {
		t.Fatal(err)
	}
	attemptID, token := attempt["attemptId"].(string), attempt["writeToken"].(string)
	report := func(seq, actual, elapsed int64, state string) {
		t.Helper()
		if _, err := f.activity.Report(ctx, "MOBILE", "overview-owner", attemptID, map[string]any{"eventSeq": seq, "actualPlayedMs": actual, "elapsedMs": elapsed, "state": state, "writeToken": token}); err != nil {
			t.Fatal(err)
		}
	}
	report(0, 30000, 30000, "PLAYING")
	report(1, 45000, 60000, "COMPLETED")

	// A pending missing-song submission.
	if _, err := f.deps.Feedback.(interface {
		Create(context.Context, feedback.Create) (feedback.Feedback, error)
	}).Create(ctx, feedback.Create{RequestID: "11111111-1111-4111-8111-111111111111", Title: ptr("想唱的歌"), Artist: ptr("歌手"), VersionLabel: ptr(""), Description: ptr("描述"), SubmittedBy: ptr("亲友")}); err != nil {
		t.Fatal(err)
	}

	response, err := f.svc.Collect(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if len(response.Errors) != 0 {
		t.Fatalf("unexpected section errors: %v", response.Errors)
	}

	// Library count semantics: total vs review, and formal counts.
	lib := response.Library.(LibrarySection)
	if lib.TotalSongs != 2 || lib.ReviewSongs != 1 {
		t.Fatalf("library counts: %+v", lib)
	}
	if lib.FormalSongs != 2 || lib.FormalVariants != 1 || lib.FormalMedia != 1 {
		t.Fatalf("formal counts: %+v", lib)
	}

	// Sources section reflects the scanned local source.
	sources := response.Sources.(library.SourceOverview)
	if sources.Total < 1 {
		t.Fatalf("sources: %+v", sources)
	}

	// No metadata batch exists yet -> metadataBatch null; feedback pending counted.
	review := response.Review.(ReviewSection)
	if review.MetadataBatch != nil {
		t.Fatalf("expected null metadataBatch, got %v", review.MetadataBatch)
	}
	if review.MissingFeedbackPending != 1 {
		t.Fatalf("pending feedback: %+v", review)
	}
	if review.Classification == nil {
		t.Fatal("classification section missing")
	}

	// Tasks: unknown progress stays null and the projection matches /background-tasks.
	tasks := response.Tasks.(map[string]any)
	if jsonNumber(t, tasks["activeTotal"]) < 1 {
		t.Fatalf("active tasks: %v", tasks)
	}
	taskItems := anySlice(t, tasks["items"])
	if len(taskItems) == 0 {
		t.Fatal("expected active task items")
	}
	var unknown map[string]any
	for _, item := range taskItems {
		m := item.(map[string]any)
		if m["type"] == "SHARE_IMPORT" {
			unknown = m
		}
	}
	if unknown == nil {
		t.Fatalf("import task missing: %v", taskItems)
	}
	progress := unknown["progress"].(map[string]any)
	if progress["percent"] != nil || progress["completed"] != nil || progress["total"] != nil {
		t.Fatalf("unknown progress invented values: %v", progress)
	}
	if unknown["relatedPath"] == "" || unknown["relatedPath"] == nil {
		t.Fatalf("relatedPath missing: %v", unknown)
	}
	compareTaskProjection(t, f, taskItems)

	// recentSongs is byte-compatible with the /media-songs item projection.
	compareRecentSongs(t, f, response.RecentSongs)

	// 30-day playback summary.
	playback30d := response.Playback30d.(map[string]any)
	if jsonNumber(t, playback30d["totalPlays"]) != 1 || jsonNumber(t, playback30d["effectivePlays"]) != 1 || jsonNumber(t, playback30d["playedSongs"]) != 1 || jsonNumber(t, playback30d["playedMs"]) != 45000 {
		t.Fatalf("playback summary: %v", playback30d)
	}

	// Cache, readiness and playlists sections present with correct shapes.
	cache := response.Cache.(map[string]any)
	if _, ok := cache["usageBytes"]; !ok {
		t.Fatalf("cache usage: %v", cache)
	}
	if _, ok := cache["entryCount"]; !ok {
		t.Fatalf("cache entry count: %v", cache)
	}
	readiness := response.Readiness.(ReadinessSection)
	if readiness.Compute == nil || readiness.AI == nil {
		t.Fatalf("readiness: %+v", readiness)
	}
	if _, ok := readiness.Compute["configured"].(bool); !ok {
		t.Fatalf("compute readiness: %v", readiness.Compute)
	}
	if _, ok := readiness.AI["enabledFeatures"].([]string); !ok {
		t.Fatalf("ai readiness: %v", readiness.AI)
	}
	if response.Playlists == nil {
		t.Fatal("playlists section null")
	}
	if response.ObservedAt == "" {
		t.Fatal("observedAt missing")
	}

	// HTTP handler renders no-store JSON.
	w := httptest.NewRecorder()
	r := httptest.NewRequest("GET", "/api/v1/admin/overview", nil)
	ginCtx, _ := gin.CreateTestContext(w)
	ginCtx.Request = r
	f.svc.Handler(ginCtx)
	if w.Code != 200 {
		t.Fatalf("handler status %d: %s", w.Code, w.Body.String())
	}
	if w.Header().Get("Cache-Control") != "no-store" {
		t.Fatalf("cache control: %v", w.Header())
	}
	var wire map[string]any
	if err := json.Unmarshal(w.Body.Bytes(), &wire); err != nil {
		t.Fatal(err)
	}
	for _, key := range []string{"library", "sources", "review", "tv", "tasks", "recentSongs", "playback30d", "cache", "readiness", "playlists", "observedAt", "errors"} {
		if _, ok := wire[key]; !ok {
			t.Fatalf("wire contract missing %q: %v", key, wire)
		}
	}
}

func ptr(s string) *string { return &s }

// compareRecentSongs asserts the overview recentSongs items serialize identically
// to the /media-songs item projection (same library.Song struct, same order).
// Both sides are normalized through a JSON round-trip so map key ordering is
// canonical (alphabetical) on each side before the byte comparison.
func compareRecentSongs(t *testing.T, f *overviewFixture, recent any) {
	t.Helper()
	page := f.httpJSON(t, "GET", "/api/v1/media-songs?size=6", nil, 200)
	items := page["items"].([]any)
	overviewJSON, err := json.Marshal(recent)
	if err != nil {
		t.Fatal(err)
	}
	var overviewNorm []any
	if err := json.Unmarshal(overviewJSON, &overviewNorm); err != nil {
		t.Fatal(err)
	}
	overviewNormJSON, err := json.Marshal(overviewNorm)
	if err != nil {
		t.Fatal(err)
	}
	mediaJSON, err := json.Marshal(items)
	if err != nil {
		t.Fatal(err)
	}
	if string(overviewNormJSON) != string(mediaJSON) {
		t.Fatalf("recentSongs projection differs from /media-songs:\noverview=%s\nmedia=%s", overviewNormJSON, mediaJSON)
	}
}

// compareTaskProjection asserts the overview task items match the
// /background-tasks projection, ignoring the per-read observedAt timestamp.
func compareTaskProjection(t *testing.T, f *overviewFixture, overviewItems []any) {
	t.Helper()
	page := f.httpJSON(t, "GET", "/api/v1/background-tasks?view=ACTIVE&size=3", nil, 200)
	items := page["items"].([]any)
	normalize := func(list []any) string {
		for _, item := range list {
			delete(item.(map[string]any), "observedAt")
		}
		encoded, err := json.Marshal(list)
		if err != nil {
			t.Fatal(err)
		}
		return string(encoded)
	}
	if normalize(overviewItems) != normalize(items) {
		t.Fatalf("task projection differs from /background-tasks:\noverview=%s\nbackground=%s", normalize(overviewItems), normalize(items))
	}
}

// failingFeedback forces the review section to fail while other sections succeed.
type failingFeedback struct{}

func (failingFeedback) PendingCount(context.Context) (int64, error) {
	return 0, errors.New("feedback unavailable")
}

func TestOverviewSectionFailureIsNullWithErrors(t *testing.T) {
	f := newOverviewFixture(t, testutil.Database(t), "sqlite", overviewConfig(t, "sqlite"))
	f.seedSongs(t)
	deps := f.deps
	deps.Feedback = failingFeedback{}
	svc := New(deps)
	response, err := svc.Collect(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if response.Review != nil {
		t.Fatalf("failed review section must be null, got %v", response.Review)
	}
	if response.Errors["review"] == "" {
		t.Fatalf("expected review error entry, got %v", response.Errors)
	}
	// Independent sections still succeed.
	if response.Library == nil {
		t.Fatal("library section should succeed independently")
	}
	if _, ok := response.Errors["library"]; ok {
		t.Fatal("library section should not error")
	}
}

func TestOverviewEmptyDatabase(t *testing.T) {
	f := newOverviewFixture(t, testutil.Database(t), "sqlite", overviewConfig(t, "sqlite"))
	response, err := f.svc.Collect(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if len(response.Errors) != 0 {
		t.Fatalf("empty database errors: %v", response.Errors)
	}
	lib := response.Library.(LibrarySection)
	if lib != (LibrarySection{}) {
		t.Fatalf("empty library counts: %+v", lib)
	}
	sources := response.Sources.(library.SourceOverview)
	if sources.Total != 0 || sources.FailedTotal != 0 || sources.NeverScanned != 0 || len(sources.Failed) != 0 {
		t.Fatalf("empty sources: %+v", sources)
	}
	review := response.Review.(ReviewSection)
	if review.MetadataBatch != nil || review.MissingFeedbackPending != 0 {
		t.Fatalf("empty review: %+v", review)
	}
	tv := response.TV.(map[string]any)
	if jsonNumber(t, tv["activeDevices"]) != 0 || jsonNumber(t, tv["pending"]) != 0 {
		t.Fatalf("empty tv: %v", tv)
	}
	tasks := response.Tasks.(map[string]any)
	if jsonNumber(t, tasks["activeTotal"]) != 0 || len(anySlice(t, tasks["items"])) != 0 {
		t.Fatalf("empty tasks: %v", tasks)
	}
	if recent, ok := response.RecentSongs.([]library.Song); !ok || len(recent) != 0 {
		t.Fatalf("empty recentSongs: %v", response.RecentSongs)
	}
	playback30d := response.Playback30d.(map[string]any)
	if jsonNumber(t, playback30d["totalPlays"]) != 0 || jsonNumber(t, playback30d["playedMs"]) != 0 {
		t.Fatalf("empty playback: %v", playback30d)
	}
	cache := response.Cache.(map[string]any)
	if jsonNumber(t, cache["usageBytes"]) != 0 || jsonNumber(t, cache["entryCount"]) != 0 {
		t.Fatalf("empty cache: %v", cache)
	}
	readiness := response.Readiness.(ReadinessSection)
	if readiness.Compute["configured"] != false || readiness.AI["configured"] != false {
		t.Fatalf("empty readiness: %+v", readiness)
	}
	if playlists, ok := response.Playlists.([]map[string]any); !ok || len(playlists) != 0 {
		t.Fatalf("empty playlists: %v", response.Playlists)
	}
}

// closedDatabase verifies a totally unavailable database yields a 503 problem.
func TestOverviewDatabaseUnavailable(t *testing.T) {
	db := testutil.Database(t)
	f := newOverviewFixture(t, db, "sqlite", overviewConfig(t, "sqlite"))
	if err := db.Close(); err != nil {
		t.Fatal(err)
	}
	w := httptest.NewRecorder()
	r := httptest.NewRequest("GET", "/api/v1/admin/overview", nil)
	ginCtx, _ := gin.CreateTestContext(w)
	ginCtx.Request = r
	f.svc.Handler(ginCtx)
	if w.Code != 503 {
		t.Fatalf("expected 503, got %d: %s", w.Code, w.Body.String())
	}
	if !strings.HasPrefix(w.Header().Get("Content-Type"), "application/problem+json") {
		t.Fatalf("expected problem+json, got %v", w.Header())
	}
}

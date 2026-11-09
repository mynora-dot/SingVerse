package execution

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"github.com/gin-gonic/gin"
	"github.com/jmoiron/sqlx"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/background"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/compute"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/library"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/config"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/testutil"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

const testToken = "execution-test-only-credential-1234567890"

type fixtureCipher struct{}

func (fixtureCipher) Encrypt(value, purpose string) (string, error) {
	return purpose + ":" + value, nil
}
func (fixtureCipher) Decrypt(value, purpose string) (string, error) {
	return strings.TrimPrefix(value, purpose+":"), nil
}

type execFixture struct {
	s            *Service
	db           *sqlx.DB
	library      *library.Service
	compute      *compute.Service
	router       *gin.Engine
	cfg          config.Config
	source, song string
	content      []byte
}

func executionFixture(t *testing.T, node string) *execFixture {
	return executionFixtureDB(t, node, testutil.Database(t), "sqlite")
}
func executionFixtureDB(t *testing.T, node string, db *sqlx.DB, dialect string, callbackOrigins ...string) *execFixture {
	t.Helper()
	gin.SetMode(gin.TestMode)
	root := t.TempDir()
	cfg := config.Config{AudioDerivedPath: filepath.Join(root, "audio"), TranscodePath: filepath.Join(root, "versions"), SongCachePath: filepath.Join(root, "cache"), SongCacheBytes: 64 << 20, CallbackOrigin: "http://127.0.0.1:18080"}
	if len(callbackOrigins) > 0 {
		cfg.CallbackOrigin = callbackOrigins[0]
	}
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	lib, err := library.New(db, dialect, cfg, fixtureCipher{}, log)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { lib.Close() })
	nodeSvc, err := compute.New(db, dialect, cfg, fixtureCipher{}, log)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(nodeSvc.Close)
	if node != "" {
		if _, err = nodeSvc.Save(t.Context(), compute.Save{Mode: "MANUAL", Endpoint: node, Token: testToken, Revision: 0}); err != nil {
			t.Fatal(err)
		}
	}
	s, err := New(db, dialect, cfg, fixtureCipher{}, lib, nodeSvc, log)
	if err != nil {
		t.Fatal(err)
	}
	s.cancel()
	s.wg.Wait()
	s.ctx, s.cancel = context.WithCancel(context.Background())
	t.Cleanup(s.Close)
	r := gin.New()
	lib.Register(r.Group("/api/v1"))
	s.Register(r.Group("/api/v1"))
	requireTV := func(c *gin.Context) {
		if c.GetHeader("Authorization") != "Bearer tv-test" {
			c.AbortWithStatus(401)
			return
		}
		c.Set("partyActor", map[string]any{"id": "television"})
		c.Next()
	}
	s.RegisterPublic(r, requireTV, requireTV)
	lib.SetOriginalCache(s.ServeCachedOriginal)
	f := &execFixture{s: s, db: db, library: lib, compute: nodeSvc, router: r, cfg: s.cfg, source: uuid(), song: uuid(), content: []byte("0123456789abcdef")}
	media := t.TempDir()
	path := filepath.Join(media, "歌曲.mp4")
	if err = os.WriteFile(path, f.content, 0600); err != nil {
		t.Fatal(err)
	}
	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	stamp := time.Now().UTC().Format(time.RFC3339Nano)
	if _, err = db.Exec("INSERT INTO media_source(id,name,source_type,location,location_hash,username,created_at,updated_at) VALUES(?, '测试来源','LOCAL',?,?,'',?,?)", f.source, media, hash(media), stamp, stamp); err != nil {
		t.Fatal(err)
	}
	if _, err = db.Exec("INSERT INTO media_song(id,source_id,relative_path,path_hash,file_size,modified_at,scanned_at,parser_profile_id,parser_revision,result_json) VALUES(?,?,'歌曲.mp4',?,?,?,?,'default',1,?)", f.song, f.source, hash("歌曲.mp4"), len(f.content), info.ModTime().UTC().Format(time.RFC3339Nano), stamp, `{"title":"歌曲","artists":["歌手"],"status":"MATCHED"}`); err != nil {
		t.Fatal(err)
	}
	return f
}
func requireErrorStatus(t *testing.T, err error, status int) {
	t.Helper()
	var f interface{ StatusCode() int }
	if !errors.As(err, &f) || f.StatusCode() != status {
		t.Fatalf("want status %d: %v", status, err)
	}
}
func (s *Service) testProbe(t *testing.T, song string, streams []any) {
	t.Helper()
	media := map[string]any{"duration": 120.25, "streams": streams}
	if err := s.observeAudio(t.Context(), song, media); err != nil {
		t.Fatal(err)
	}
}
func audioStreams(n int) []any {
	out := []any{}
	for i := range n {
		out = append(out, map[string]any{"id": "stream:" + string(rune('1'+i)), "kind": "audio", "codec": "aac", "channels": 2})
	}
	return out
}

func TestAllExecutionQueriesPrepareAgainstFreshSchema(t *testing.T) {
	f := executionFixture(t, "")
	entries, err := statements.ReadDir("sql")
	if err != nil {
		t.Fatal(err)
	}
	params := regexp.MustCompile(`@\{([^}]+)\}`)
	for _, entry := range entries {
		if strings.HasSuffix(entry.Name(), "-mysql.sql") {
			continue
		}
		t.Run(entry.Name(), func(t *testing.T) {
			name := strings.TrimSuffix(entry.Name(), ".sql")
			q, err := statement(name)
			if err != nil {
				t.Fatal(err)
			}
			values := map[string]any{}
			for _, match := range params.FindAllStringSubmatch(q, -1) {
				values[match[1]] = nil
			}
			q, args, err := store.Bind(q, values)
			if err != nil {
				t.Fatal(err)
			}
			rows, err := f.db.Query("EXPLAIN "+q, args...)
			if err != nil {
				t.Fatal(err)
			}
			rows.Close()
		})
	}
}

func TestRolesCASSharedClaimsAndSourceChange(t *testing.T) {
	testRolesCASSharedClaimsAndSourceChange(t, executionFixture(t, ""))
}
func TestMySQLRolesCASSharedClaimsAndSourceChange(t *testing.T) {
	db, _ := testutil.MySQLDatabase(t)
	testRolesCASSharedClaimsAndSourceChange(t, executionFixtureDB(t, "", db, "mysql"))
}
func testRolesCASSharedClaimsAndSourceChange(t *testing.T, f *execFixture) {
	s := f.s
	ctx := t.Context()
	s.testProbe(t, f.song, audioStreams(2))
	value, err := s.correctRoles(ctx, f.song, "stream:1", "stream:2", 0)
	if err != nil || str(value, "origin") != "MANUAL" || integer(value["revision"]) != 1 {
		t.Fatal(value, err)
	}
	_, err = s.correctRoles(ctx, f.song, "stream:2", "stream:1", 0)
	requireErrorStatus(t, err, 409)
	_, err = s.correctRoles(ctx, f.song, "stream:1", "stream:3", 1)
	requireErrorStatus(t, err, 409)
	claim, err := s.PortableAudio(ctx, f.song)
	if err != nil || number(claim["duration"]) != 120.25 {
		t.Fatal(claim, err)
	}
	for _, key := range []string{"songId", "fingerprint", "revision", "jobs", "versions"} {
		if claim[key] != nil {
			t.Fatalf("portable claim retained %s", key)
		}
	}
	if err = s.transaction(ctx, "", "", func(tx *sqlx.Tx) error {
		return s.applySuggestion(ctx, tx, f.song, str(value, "fingerprint"), map[string]any{"state": "CONFIDENT", "original": "stream:2", "backing": "stream:1"}, false)
	}); err != nil {
		t.Fatal(err)
	}
	value, _ = s.audioStatus(ctx, f.song)
	if str(value, "originalId") != "stream:1" {
		t.Fatal("automatic result replaced manual roles")
	}
	if _, err = f.db.Exec("UPDATE media_song SET file_size=file_size+1 WHERE id=?", f.song); err != nil {
		t.Fatal(err)
	}
	value, err = s.audioStatus(ctx, f.song)
	if err != nil || str(value, "state") != "UNKNOWN" || len(array(value["jobs"])) != 0 {
		t.Fatal(value, err)
	}
}

func TestAudioComputeLifecycleVerifiedArtifactAndGrantRevocation(t *testing.T) {
	var fixture *execFixture
	var mu sync.Mutex
	tasks := map[string]map[string]any{}
	artifact := bytes.Repeat([]byte("verified-audio"), 256)
	sum := sha256.Sum256(artifact)
	var grantRead atomic.Int64
	node := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer "+testToken {
			w.WriteHeader(401)
			return
		}
		parts := strings.Split(strings.Trim(r.URL.Path, "/"), "/")
		if r.URL.Path == "/internal/v1/audio-roles/models" {
			io.WriteString(w, `{"selected":"cpu","resourceReady":true,"backends":[{"id":"cpu","state":"AVAILABLE"}]}`)
			return
		}
		if r.URL.Path == "/internal/v1/audio-roles/tasks" {
			var body map[string]any
			if json.NewDecoder(r.Body).Decode(&body) != nil {
				w.WriteHeader(400)
				return
			}
			id := str(body, "id")
			request := httptest.NewRequest("GET", "/api/v1/internal/audio-input/"+id, nil)
			request.Header.Set("Authorization", "Bearer "+testToken)
			request.Header.Set("Range", "bytes=2-5")
			out := httptest.NewRecorder()
			fixture.router.ServeHTTP(out, request)
			if out.Code != 206 || out.Body.String() != "2345" {
				w.WriteHeader(500)
				return
			}
			grantRead.Add(1)
			result := map[string]any{"streams": audioStreams(1), "duration": 120.25}
			if str(body, "mode") == "SEPARATE" {
				result = map[string]any{"artifact": id + ".m4a", "size": len(artifact), "sha256": hex.EncodeToString(sum[:]), "duration": 120.25}
			}
			mu.Lock()
			tasks[id] = result
			mu.Unlock()
			io.WriteString(w, `{"state":"ACCEPTED"}`)
			return
		}
		if len(parts) >= 5 {
			task := parts[4]
			if strings.HasSuffix(r.URL.Path, "/artifact") {
				w.Header().Set("Content-Length", stringInt(len(artifact)))
				w.Write(artifact)
				return
			}
			if strings.HasSuffix(r.URL.Path, "/cancel") || strings.HasSuffix(r.URL.Path, "/release") {
				io.WriteString(w, `{}`)
				return
			}
			mu.Lock()
			result := tasks[task]
			mu.Unlock()
			json.NewEncoder(w).Encode(map[string]any{"state": "SUCCEEDED", "result": result})
			return
		}
		w.WriteHeader(404)
	}))
	defer node.Close()
	fixture = executionFixture(t, node.URL)
	s := fixture.s
	job, err := s.SubmitAudio(t.Context(), fixture.song, "PROBE", "", nil)
	if err != nil {
		t.Fatal(err)
	}
	id := str(job, "id")
	s.tickAudio(t.Context())
	s.tickAudio(t.Context())
	status, err := s.audioStatus(t.Context(), fixture.song)
	if err != nil || str(status, "state") != "CONFIRMED" || grantRead.Load() != 1 {
		t.Fatal(status, err, grantRead.Load())
	}
	row, _ := s.one(t.Context(), s.db, "audio-job", map[string]any{"id": id})
	if str(row, "state") != "SUCCEEDED" {
		t.Fatal(row)
	}
	request := httptest.NewRequest("GET", "/api/v1/internal/audio-input/"+id, nil)
	request.Header.Set("Authorization", "Bearer "+testToken)
	out := httptest.NewRecorder()
	fixture.router.ServeHTTP(out, request)
	if out.Code != 404 {
		t.Fatal("completed grant remained valid", out.Code)
	}
	job, err = s.SubmitAudio(t.Context(), fixture.song, "SEPARATE", "cpu", nil)
	if err != nil {
		t.Fatal(err)
	}
	s.tickAudio(t.Context())
	s.tickAudio(t.Context())
	id = str(job, "id")
	file := filepath.Join(s.cfg.AudioDerivedPath, id+".m4a")
	data, err := os.ReadFile(file)
	if err != nil || !bytes.Equal(data, artifact) {
		t.Fatal("artifact", err)
	}
	status, err = s.audioStatus(t.Context(), fixture.song)
	if err != nil || str(obj(status["backingResource"]), "jobId") != id {
		t.Fatal(status, err)
	}
	request = httptest.NewRequest("GET", "/api/v1/media-songs/"+fixture.song+"/accompaniment/"+id, nil)
	request.Header.Set("Range", "bytes=1-7")
	out = httptest.NewRecorder()
	fixture.router.ServeHTTP(out, request)
	if out.Code != 206 || !bytes.Equal(out.Body.Bytes(), artifact[1:8]) {
		t.Fatal(out.Code, out.Body.String())
	}
	if _, err = fixture.db.Exec("UPDATE media_song SET file_size=file_size+1 WHERE id=?", fixture.song); err != nil {
		t.Fatal(err)
	}
	out = httptest.NewRecorder()
	fixture.router.ServeHTTP(out, request)
	if out.Code != 404 {
		t.Fatal("changed media artifact usable", out.Code)
	}
}
func stringInt(n int) string { return json.Number(fmtInt(int64(n))).String() }
func fmtInt(n int64) string  { return strconv.FormatInt(n, 10) }

func TestOriginalReadThroughRangesRestartAndProtectedCleanup(t *testing.T) {
	testOriginalReadThroughRangesRestartAndProtectedCleanup(t, executionFixture(t, ""))
}
func TestMySQLOriginalReadThroughRangesRestartAndProtectedCleanup(t *testing.T) {
	db, _ := testutil.MySQLDatabase(t)
	testOriginalReadThroughRangesRestartAndProtectedCleanup(t, executionFixtureDB(t, "", db, "mysql"))
}
func testOriginalReadThroughRangesRestartAndProtectedCleanup(t *testing.T, f *execFixture) {
	s := f.s
	ctx := t.Context()
	content := bytes.Repeat([]byte("0123456789abcdef"), int(originalBlockBytes/16)+4)
	if _, err := f.db.Exec("UPDATE media_source SET source_type='WEBDAV',location='http://127.0.0.1/media/' WHERE id=?", f.source); err != nil {
		t.Fatal(err)
	}
	if _, err := f.db.Exec("UPDATE media_song SET file_size=? WHERE id=?", len(content), f.song); err != nil {
		t.Fatal(err)
	}
	var fetched atomic.Int64
	fetch := func(w http.ResponseWriter, r *http.Request) error {
		fetched.Add(1)
		http.ServeContent(w, r, "song.mp4", time.Time{}, bytes.NewReader(content))
		return nil
	}
	read := func(value string) *httptest.ResponseRecorder {
		request := httptest.NewRequest("GET", "/media", nil)
		request.Header.Set("Range", value)
		out := httptest.NewRecorder()
		handled, err := s.ServeCachedOriginal(ctx, out, request, f.song, fetch, nil)
		if err != nil || !handled {
			t.Fatalf("range %s handled=%v: %v", value, handled, err)
		}
		return out
	}
	out := read("bytes=5-37")
	if out.Code != 206 || !bytes.Equal(out.Body.Bytes(), content[5:38]) || fetched.Load() != 1 {
		t.Fatal(out.Code, out.Body.Len(), fetched.Load())
	}
	out = read("bytes=18-99")
	if out.Code != 206 || fetched.Load() != 1 {
		t.Fatal("cache miss", fetched.Load())
	}
	out = read("bytes=-8")
	if !bytes.Equal(out.Body.Bytes(), content[len(content)-8:]) || fetched.Load() != 2 {
		t.Fatal("tail range", out.Code, fetched.Load())
	}
	out = read("bytes=999999999-")
	if out.Code != 416 {
		t.Fatal(out.Code)
	}
	rows, err := s.maps(ctx, s.db, "cache-entries", nil)
	if err != nil || len(rows) != 1 {
		t.Fatal(rows, err)
	}
	entry := rows[0]
	if integer(entry["bytes"]) != int64(len(content)) {
		t.Fatal("committed bytes", entry)
	}
	folder, _ := s.cacheFolder(str(entry, "id"))
	if err = os.WriteFile(filepath.Join(folder, "interrupted.part"), []byte("residue"), 0600); err != nil {
		t.Fatal(err)
	}
	s.cacheCensus(ctx)
	entry, _ = s.one(ctx, s.db, "cache-find", map[string]any{"id": str(entry, "id")})
	if integer(entry["residue_bytes"]) != 7 {
		t.Fatal(entry)
	}
	if _, err = f.db.Exec("UPDATE playback_activity_state SET ready=1 WHERE id=1"); err != nil {
		t.Fatal(err)
	}
	if _, err = s.pin(ctx, f.song, map[string]any{"pinned": true, "revision": json.Number("0")}); err != nil {
		t.Fatal(err)
	}
	_, err = s.deleteCacheEntry(ctx, entry, false)
	requireErrorStatus(t, err, 409)
	if !ownedFolder(folder) {
		t.Fatal("pinned folder removed")
	}
	if _, err = s.pin(ctx, f.song, map[string]any{"pinned": false, "revision": json.Number("1")}); err != nil {
		t.Fatal(err)
	}
	s.cacheMu.Lock()
	s.cacheReaders[str(entry, "id")] = 1
	s.cacheMu.Unlock()
	_, err = s.deleteCacheEntry(ctx, entry, false)
	requireErrorStatus(t, err, 409)
	s.cacheMu.Lock()
	delete(s.cacheReaders, str(entry, "id"))
	s.cacheMu.Unlock()
	job, err := s.submitCleanup(ctx, "DELETE", str(entry, "id"), false)
	if err != nil {
		t.Fatal(err)
	}
	result, err := s.awaitCleanup(ctx, str(job, "id"))
	if err != nil || integer(result["removed"]) != 1 || str(result, "state") != "SUCCEEDED" {
		t.Fatal(result, err)
	}
	if _, err = os.Lstat(folder); !errors.Is(err, os.ErrNotExist) {
		t.Fatal("owned folder retained", err)
	}
}

func TestRangeUnsupportedFallsBackBeforeHeadersAndGuardRejects(t *testing.T) {
	f := executionFixture(t, "")
	if _, err := f.db.Exec("UPDATE media_source SET source_type='WEBDAV' WHERE id=?", f.source); err != nil {
		t.Fatal(err)
	}
	if _, err := f.db.Exec("UPDATE media_song SET file_size=? WHERE id=?", originalBlockBytes+1, f.song); err != nil {
		t.Fatal(err)
	}
	req := httptest.NewRequest("GET", "/media", nil)
	out := httptest.NewRecorder()
	handled, err := f.s.ServeCachedOriginal(t.Context(), out, req, f.song, func(w http.ResponseWriter, r *http.Request) error {
		w.WriteHeader(200)
		_, err := w.Write(make([]byte, originalBlockBytes+1))
		return err
	}, nil)
	if handled || err != nil || out.Body.Len() != 0 || out.Flushed {
		t.Fatal("optional cache committed fallback", handled, err, out.Body.Len())
	}
	handled, err = f.s.ServeCachedOriginal(t.Context(), out, req, f.song, nil, func() error { return fail(401, "revoked") })
	if !handled {
		t.Fatal("revoked source bypassed")
	}
	requireErrorStatus(t, err, 401)
}

func TestBatchPreparationClassificationAndProjectionRollback(t *testing.T) {
	testBatchPreparationClassificationAndProjectionRollback(t, executionFixture(t, ""))
}
func TestMySQLBatchPreparationClassificationAndProjectionRollback(t *testing.T) {
	db, _ := testutil.MySQLDatabase(t)
	testBatchPreparationClassificationAndProjectionRollback(t, executionFixtureDB(t, "", db, "mysql"))
}
func testBatchPreparationClassificationAndProjectionRollback(t *testing.T, f *execFixture) {
	s := f.s
	s.testProbe(t, f.song, audioStreams(1))
	bg, err := background.New(f.db, f.s.dialect, nil)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { bg.Close() })
	s.SetProjector(bg.Project)
	s.SetAudioScopes(bg)
	batch, err := s.previewBatch(t.Context(), map[string]any{"ids": []any{f.song, f.song}, "backend": "cpu", "separationProfile": "DSP_FAST"})
	if err != nil {
		t.Fatal(err)
	}
	id := str(batch, "id")
	deadline := time.Now().Add(2 * time.Second)
	for {
		batch, err = s.batchDetail(t.Context(), id, 0, 50)
		if err != nil {
			t.Fatal(err)
		}
		if str(batch, "state") != "PREPARING" {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("batch preparation stalled")
		}
		time.Sleep(time.Millisecond)
	}
	if str(batch, "state") != "PREVIEW" || integer(batch["selectionTotal"]) != 1 || integer(obj(batch["categories"])["ELIGIBLE"]) != 1 {
		t.Fatal(batch)
	}
	var projected string
	if err = f.db.QueryRow("SELECT status FROM background_task WHERE id=?", background.ID("AUDIO_BATCH", id)).Scan(&projected); err != nil || projected != "AWAITING_CONFIRMATION" {
		t.Fatal(projected, err)
	}
	if _, err = s.cancelBatch(t.Context(), id); err != nil {
		t.Fatal(err)
	}
	batch, _ = s.batchDetail(t.Context(), id, 0, 50)
	if str(batch, "state") != "CANCELLED" || integer(obj(batch["counts"])["CANCELLED"]) != 1 {
		t.Fatal(batch)
	}
	s.SetProjector(func(context.Context, store.DBTX, string, string) error {
		return errors.New("forced transaction failure")
	})
	_, err = s.previewBatch(t.Context(), map[string]any{"ids": []any{f.song}})
	if err == nil {
		t.Fatal("projection failure did not abort batch")
	}
	var count int
	if err = f.db.QueryRow("SELECT COUNT(*) FROM audio_batch").Scan(&count); err != nil || count != 1 {
		t.Fatal("batch escaped failed projection", count, err)
	}
}

func TestTargetLimitsAndMeasuredVersionCompatibility(t *testing.T) {
	client := map[string]any{"video": []any{"h264"}, "fmp4": true, "max_width": 1920, "max_height": 1080, "max_fps": 60, "max_bitrate": 8000000, "profiles": map[string]any{"h264": []any{"Baseline"}}, "levels": map[string]any{"h264": 31}, "max_bit_depth": 8}
	v, err := target(map[string]any{"client": client}, "REALTIME")
	if err != nil {
		t.Fatal(err)
	}
	blocks := ((integer(v["width"]) + 15) / 16) * ((integer(v["height"]) + 15) / 16)
	if blocks > 3600 || float64(blocks)*number(v["fps"]) > 108000.01 || str(v, "profile") != "baseline" {
		t.Fatal(v)
	}
	if versionVideoCompatible(v, map[string]any{}, client, v) {
		t.Fatal("missing measured stream accepted")
	}
	if _, err = target(map[string]any{"start": -1}, "REALTIME"); err == nil {
		t.Fatal("negative offset")
	}
	if _, err = target(map[string]any{"codec": "hevc", "client": client}, "REALTIME"); err == nil {
		t.Fatal("unsupported codec")
	}
	spec, err := recipe(map[string]any{"separationProfile": "AI_CLEAN", "modelId": "htdemucs"})
	if err == nil {
		t.Fatal(spec)
	}
	for _, value := range []any{json.Number("1.2"), "1", true} {
		if _, ok := intInput(value); ok {
			t.Fatal("noninteger accepted")
		}
	}
}

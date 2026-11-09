package downloads

import (
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/jmoiron/sqlx"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/downloads/lxruntime"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/testutil"
)

type testCipher struct{}

func (testCipher) Encrypt(v, p string) (string, error) { return p + ":" + v, nil }
func (testCipher) Decrypt(v, p string) (string, error) {
	if !strings.HasPrefix(v, p+":") {
		return "", errors.New("purpose mismatch")
	}
	return strings.TrimPrefix(v, p+":"), nil
}

type testRuntime struct {
	mu      sync.Mutex
	calls   []string
	resolve func(context.Context, string) (string, error)
}

func (r *testRuntime) Inspect(context.Context, string, string) (map[string][]string, error) {
	return map[string][]string{"wy": {"flac", "128k"}}, nil
}
func (r *testRuntime) Resolve(ctx context.Context, _, _, _, q string, _ map[string]any) (string, error) {
	r.mu.Lock()
	r.calls = append(r.calls, q)
	r.mu.Unlock()
	return r.resolve(ctx, q)
}
func (*testRuntime) Close() {}

type testGate struct{ mu sync.Mutex }

func (g *testGate) Publication(ctx context.Context, _ string, fn func() error) error {
	g.mu.Lock()
	defer g.mu.Unlock()
	if ctx.Err() != nil {
		return ctx.Err()
	}
	return fn()
}

type testCatalog struct{}

func (testCatalog) Search(context.Context, string, string, int) (CatalogPage, error) {
	return CatalogPage{}, nil
}
func (testCatalog) Reference(context.Context, string) (CatalogReference, error) {
	return CatalogReference{}, nil
}
func (testCatalog) Collection(context.Context, CatalogReference, int) (Collection, error) {
	return Collection{}, nil
}
func (testCatalog) Detail(_ context.Context, t Track) (Track, error) { return t, nil }
func (testCatalog) Lyrics(context.Context, Track) (string, error) {
	return "[00:00.00]下载测试\n", nil
}

type publicationCheckingCatalog struct {
	testCatalog
	t    *testing.T
	gate *testGate
}

func (c publicationCheckingCatalog) Lyrics(context.Context, Track) (string, error) {
	if !c.gate.mu.TryLock() {
		c.t.Fatal("sidecar network request held the source publication gate")
	}
	c.gate.mu.Unlock()
	return "[00:00.00]恢复歌词\n", nil
}

type roundTripFunc func(*http.Request) (*http.Response, error)

func (f roundTripFunc) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }
func fixture(t *testing.T) (*Service, string, Track) {
	t.Helper()
	return fixtureWith(t, testutil.Database(t), "sqlite")
}
func fixtureWith(t *testing.T, db *sqlx.DB, dialect string) (*Service, string, Track) {
	t.Helper()
	root, err := filepath.EvalSymlinks(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	q, e := store.ReadQueries(queryFiles, "sql")
	if e != nil {
		t.Fatal(e)
	}
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	s := &Service{db: db, dialect: dialect, q: q, cipher: testCipher{}, library: &testGate{}, log: slog.New(slog.NewTextHandler(io.Discard, nil)), ctx: ctx, cancel: cancel, running: map[string]context.CancelFunc{}, catalogSlots: make(chan struct{}, 5), network: NewNetwork(), catalog: testCatalog{}}
	s.covers = newCovers(s.network)
	s.runtime = &testRuntime{resolve: func(context.Context, string) (string, error) { return "https://audio.example/audio", nil }}
	_, e = db.Exec(`INSERT INTO media_source(id,name,source_type,location,location_hash,username,created_at,updated_at) VALUES('source','Downloads','LOCAL',?,?,'',?,?)`, root, hash(root), now(), now())
	if e != nil {
		t.Fatal(e)
	}
	_, e = db.Exec(`UPDATE lx_download_settings SET source_id='source' WHERE id=1`)
	if e != nil {
		t.Fatal(e)
	}
	imported, e := s.Import(ctx, SourceInput{Name: "Fixture", Script: "// @name Test\nvar test = true;"})
	if e != nil {
		t.Fatal(e)
	}
	if _, e = s.Inspect(ctx, str(imported, "id"), true); e != nil {
		t.Fatal(e)
	}
	track := Track{ID: hash("wy:42"), Platform: "wy", RemoteID: "42", Title: "测试歌曲", Artists: []string{"歌手"}, MusicInfo: map[string]any{"songmid": "42"}}
	if e = s.remember(ctx, []Track{track}); e != nil {
		t.Fatal(e)
	}
	return s, root, track
}
func makeWAV() []byte {
	var out bytes.Buffer
	out.WriteString("RIFF")
	_ = binary.Write(&out, binary.LittleEndian, uint32(40))
	out.WriteString("WAVEfmt ")
	_ = binary.Write(&out, binary.LittleEndian, uint32(16))
	for _, v := range []uint16{1, 1} {
		_ = binary.Write(&out, binary.LittleEndian, v)
	}
	_ = binary.Write(&out, binary.LittleEndian, uint32(8000))
	_ = binary.Write(&out, binary.LittleEndian, uint32(16000))
	for _, v := range []uint16{2, 16} {
		_ = binary.Write(&out, binary.LittleEndian, v)
	}
	out.WriteString("data")
	_ = binary.Write(&out, binary.LittleEndian, uint32(4))
	out.Write([]byte{0, 0, 0, 0})
	return out.Bytes()
}
func serveAudio(s *Service, data []byte) {
	s.network.client.Transport = roundTripFunc(func(r *http.Request) (*http.Response, error) {
		return &http.Response{StatusCode: 200, Body: io.NopCloser(bytes.NewReader(data)), ContentLength: int64(len(data)), Header: make(http.Header), Request: r}, nil
	})
}
func itemFor(t *testing.T, s *Service, batch string) map[string]any {
	t.Helper()
	rows, e := s.rows(context.Background(), s.db, "items", map[string]any{"id": batch, "size": 100, "offset": 0})
	if e != nil || len(rows) != 1 {
		t.Fatal(rows, e)
	}
	return rows[0]
}
func claim(t *testing.T, s *Service, id string) map[string]any {
	t.Helper()
	_, e := s.exec(context.Background(), s.db, "claim", map[string]any{"id": id, "now": now()})
	if e != nil {
		t.Fatal(e)
	}
	row, e := s.row(context.Background(), s.db, "item", map[string]any{"id": id})
	if e != nil {
		t.Fatal(e)
	}
	return row
}
func createOne(t *testing.T, s *Service, track Track) string {
	t.Helper()
	out, e := s.Create(context.Background(), BatchInput{TrackIDs: []string{track.ID}, Quality: "auto"})
	if e != nil {
		t.Fatal(e)
	}
	return str(out["batch"].(map[string]any), "id")
}
func TestDownloadPublishesOnceAndPreservesExistingFile(t *testing.T) {
	s, root, track := fixture(t)
	serveAudio(s, makeWAV())
	rt := s.runtime.(*testRuntime)
	rt.resolve = func(_ context.Context, q string) (string, error) {
		if q == "flac" {
			return "", lxruntime.ErrQualityUnavailable
		}
		return "https://audio.example/file", nil
	}
	existing := filepath.Join(root, "歌手 - 测试歌曲.wav")
	if e := os.WriteFile(existing, []byte("original"), 0600); e != nil {
		t.Fatal(e)
	}
	batch := createOne(t, s, track)
	id := str(itemFor(t, s, batch), "id")
	item := claim(t, s, id)
	if e := s.download(context.Background(), item); e != nil {
		t.Fatal(e)
	}
	s.finalizeBatch(id)
	row, e := s.row(context.Background(), s.db, "item", map[string]any{"id": id})
	if e != nil {
		t.Fatal(e)
	}
	if str(row, "status") != "SUCCESS" || str(row, "resolved_quality") != "128k" {
		t.Fatal(row)
	}
	if got, _ := os.ReadFile(existing); string(got) != "original" {
		t.Fatal("original overwritten")
	}
	path := filepath.Join(root, str(row, "relative_path"))
	got, e := os.ReadFile(path)
	if e != nil || !bytes.Equal(got, makeWAV()) {
		t.Fatal(e)
	}
	if got, _ := os.ReadFile(strings.TrimSuffix(path, ".wav") + ".lrc"); !bytes.Contains(got, []byte("下载测试")) {
		t.Fatal("sidecar missing")
	}
	var songs, assets int
	if e = s.db.QueryRow("SELECT COUNT(*) FROM media_song").Scan(&songs); e != nil {
		t.Fatal(e)
	}
	if e = s.db.QueryRow("SELECT COUNT(*) FROM lx_download_asset").Scan(&assets); e != nil {
		t.Fatal(e)
	}
	if songs != 1 || assets != 1 {
		t.Fatal(songs, assets)
	}
	second := createOne(t, s, track)
	other := claim(t, s, str(itemFor(t, s, second), "id"))
	if e = s.download(context.Background(), other); e != nil {
		t.Fatal(e)
	}
	if state := str(itemFor(t, s, second), "status"); state != "SKIPPED" {
		t.Fatal(state)
	}
	if len(rt.calls) != 3 || rt.calls[2] != "flac" {
		t.Fatal("existing quality was resolved again", rt.calls)
	}
}
func TestPublicationIntentRecoversAndRollsBackProjection(t *testing.T) {
	s, root, track := fixture(t)
	batch := createOne(t, s, track)
	id := str(itemFor(t, s, batch), "id")
	claim(t, s, id)
	temp, e := temporary(root, id)
	if e != nil {
		t.Fatal(e)
	}
	if e = os.WriteFile(temp, makeWAV(), 0600); e != nil {
		t.Fatal(e)
	}
	digest, e := hashFile(context.Background(), temp)
	if e != nil {
		t.Fatal(e)
	}
	e = s.transaction(context.Background(), func(tx store.DBTX) error {
		return s.finish(context.Background(), tx, id, "PUBLISHING", "128k", "Recovered.wav", songID("source", "Recovered.wav"), digest, "正在加入曲库")
	})
	if e != nil {
		t.Fatal(e)
	}
	item, e := s.row(context.Background(), s.db, "item", map[string]any{"id": id})
	if e != nil {
		t.Fatal(e)
	}
	sentinel := errors.New("projection fail")
	s.SetPublished(func(ctx context.Context, tx store.DBTX, song string) error {
		_, err := tx.ExecContext(ctx, "INSERT INTO media_metadata_file(song_id,revision) VALUES (?,1) ON CONFLICT(song_id) DO UPDATE SET revision=revision+1", song)
		return err
	})
	s.SetProjector(func(context.Context, store.DBTX, string, string) error { return sentinel })
	if e = s.recoverFile(context.Background(), item); !errors.Is(e, sentinel) {
		t.Fatal(e)
	}
	var songs int
	if e = s.db.QueryRow("SELECT COUNT(*) FROM media_song").Scan(&songs); e != nil || songs != 0 {
		t.Fatal(songs, e)
	}
	var metadata int
	if e = s.db.QueryRow("SELECT COUNT(*) FROM media_metadata_file").Scan(&metadata); e != nil || metadata != 0 {
		t.Fatal("metadata escaped publication transaction", metadata, e)
	}
	if _, e = os.Stat(filepath.Join(root, "Recovered.wav")); e != nil {
		t.Fatal("published file lost", e)
	}
	s.SetProjector(nil)
	s.catalog = publicationCheckingCatalog{t: t, gate: s.library.(*testGate)}
	if e = s.recover(context.Background()); e != nil {
		t.Fatal(e)
	}
	row, e := s.row(context.Background(), s.db, "item", map[string]any{"id": id})
	if e != nil || str(row, "status") != "SUCCESS" {
		t.Fatal(row, e)
	}
	if e = s.db.QueryRow("SELECT COUNT(*) FROM media_song").Scan(&songs); e != nil || songs != 1 {
		t.Fatal(songs, e)
	}
	if e = s.db.QueryRow("SELECT revision FROM media_metadata_file").Scan(&metadata); e != nil || metadata != 2 {
		t.Fatal("recovery did not publish metadata", metadata, e)
	}
	lyrics, e := os.ReadFile(filepath.Join(root, "Recovered.lrc"))
	if e != nil || string(lyrics) != "[00:00.00]恢复歌词\n" {
		t.Fatal("recovery skipped sidecar completion", string(lyrics), e)
	}
}
func TestConcurrentSelectionAndCancellation(t *testing.T) {
	s, _, track := fixture(t)
	var wg sync.WaitGroup
	out := make(chan string, 8)
	for range 8 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			v, e := s.Create(context.Background(), BatchInput{TrackIDs: []string{track.ID}, Quality: "128k"})
			if e != nil {
				t.Error(e)
				return
			}
			out <- str(v["batch"].(map[string]any), "id")
		}()
	}
	wg.Wait()
	close(out)
	first := ""
	for id := range out {
		if first == "" {
			first = id
		} else if id != first {
			t.Fatal("concurrent duplicate", id, first)
		}
	}
	if e := s.Cancel(context.Background(), first); e != nil {
		t.Fatal(e)
	}
	row := itemFor(t, s, first)
	if str(row, "status") != "CANCELLED" || row["active_key"] != nil {
		t.Fatal(row)
	}
	id := str(row, "id")
	if e := s.transaction(context.Background(), func(tx store.DBTX) error {
		return s.finish(context.Background(), tx, id, "SUCCESS", "128k", "oops.wav", "song", "hash", "")
	}); e != nil {
		t.Fatal(e)
	}
	if str(itemFor(t, s, first), "status") != "CANCELLED" {
		t.Fatal("cancelled task revived")
	}
}
func TestAudioAndPathsRejectIncompleteAndEscapingFiles(t *testing.T) {
	root := t.TempDir()
	valid := makeWAV()
	for _, tc := range []struct {
		name string
		data []byte
		pass bool
	}{{"valid", valid, true}, {"truncated", valid[:len(valid)-1], false}, {"html", []byte("<html>not audio</html>"), false}, {"empty", nil, false}} {
		t.Run(tc.name, func(t *testing.T) {
			path := filepath.Join(root, tc.name)
			if e := os.WriteFile(path, tc.data, 0600); e != nil {
				t.Fatal(e)
			}
			e := validateAudio(context.Background(), path, "wav")
			if (e == nil) != tc.pass {
				t.Fatal(e)
			}
		})
	}
	for _, relative := range []string{"../outside", "/tmp/outside", "a/b", "a\\b", ".", ""} {
		if _, e := safeFile(root, relative); e == nil {
			t.Fatal(relative)
		}
	}
	if e := os.Symlink(t.TempDir(), filepath.Join(root, ".singverse-downloads")); e != nil {
		t.Fatal(e)
	}
	if _, e := temporary(root, uuid()); e == nil {
		t.Fatal("symlink accepted")
	}
	if _, e := writable(filepath.Join(root, ".."), false); e == nil {
		t.Fatal("noncanonical root accepted")
	}
}
func TestHTTPDownloadContractAndNoSensitiveSource(t *testing.T) {
	s, _, track := fixture(t)
	gin.SetMode(gin.ReleaseMode)
	r := gin.New()
	s.Register(r.Group("/api/v1"))
	server := httptest.NewServer(r)
	defer server.Close()
	response, e := http.Get(server.URL + "/api/v1/lx-downloads/sources")
	if e != nil {
		t.Fatal(e)
	}
	data, e := io.ReadAll(response.Body)
	response.Body.Close()
	if e != nil || response.StatusCode != 200 || bytes.Contains(data, []byte("secret_encrypted")) || bytes.Contains(data, []byte("var test")) {
		t.Fatal(string(data), e)
	}
	body := `{"trackIds":["` + track.ID + `"],"quality":"128k"}`
	response, e = http.Post(server.URL+"/api/v1/lx-downloads/batches", "application/json", strings.NewReader(body))
	if e != nil {
		t.Fatal(e)
	}
	data, _ = io.ReadAll(response.Body)
	response.Body.Close()
	if response.StatusCode != 200 || !bytes.Contains(data, []byte(`"status":"QUEUED"`)) {
		t.Fatal(response.StatusCode, string(data))
	}
	response, e = http.Get(server.URL + "/api/v1/lx-downloads/batches?page=-1")
	if e != nil {
		t.Fatal(e)
	}
	defer response.Body.Close()
	if response.StatusCode != 400 || !strings.HasPrefix(response.Header.Get("Content-Type"), "application/problem+json") {
		t.Fatal(response.StatusCode, response.Header)
	}
}
func TestAllDownloadStatementsParse(t *testing.T) {
	s, _, _ := fixture(t)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	parameter := regexpParameters()
	for name, query := range s.q {
		values := map[string]any{}
		for _, m := range parameter.FindAllStringSubmatch(query, -1) {
			values[m[1]] = nil
		}
		bound, args, e := store.Bind(query, values)
		if e != nil {
			t.Fatal(e)
		}
		rows, e := s.db.QueryContext(ctx, "EXPLAIN "+bound, args...)
		if e != nil {
			t.Errorf("%s: %v", name, e)
			continue
		}
		rows.Close()
	}
}
func regexpParameters() *regexp.Regexp { return regexp.MustCompile(`@\{([a-zA-Z_][a-zA-Z_0-9.]*)\}`) }

func TestMySQLDownloadPublicationAndProjection(t *testing.T) {
	db, cfg := testutil.MySQLDatabase(t)
	s, root, track := fixtureWith(t, db, cfg.Driver)
	serveAudio(s, makeWAV())
	s.runtime.(*testRuntime).resolve = func(context.Context, string) (string, error) { return "https://audio.example/file", nil }
	batch := createOne(t, s, track)
	id := str(itemFor(t, s, batch), "id")
	item := claim(t, s, id)
	if e := s.download(context.Background(), item); e != nil {
		t.Fatal(e)
	}
	s.finalizeBatch(id)
	view, e := s.batch(context.Background(), db, batch)
	if e != nil || view["status"] != "COMPLETED" {
		t.Fatal(view, e)
	}
	row := itemFor(t, s, batch)
	if str(row, "status") != "SUCCESS" {
		t.Fatal(row)
	}
	if e = validateAudio(context.Background(), filepath.Join(root, str(row, "relative_path")), "wav"); e != nil {
		t.Fatal(e)
	}
	sentinel := errors.New("projection failure")
	s.SetProjector(func(context.Context, store.DBTX, string, string) error { return sentinel })
	var before int
	if e = db.QueryRow("SELECT COUNT(*) FROM lx_download_batch").Scan(&before); e != nil {
		t.Fatal(e)
	}
	_, e = s.Create(context.Background(), BatchInput{TrackIDs: []string{track.ID}, Quality: "128k"})
	if !errors.Is(e, sentinel) {
		t.Fatal(e)
	}
	var after int
	if e = db.QueryRow("SELECT COUNT(*) FROM lx_download_batch").Scan(&after); e != nil || after != before {
		t.Fatal(before, after, e)
	}
}

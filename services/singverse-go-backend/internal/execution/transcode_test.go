package execution

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"github.com/gin-gonic/gin"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/compute"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/testutil"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func ginContext(w http.ResponseWriter, r *http.Request, id string) *gin.Context {
	c, _ := gin.CreateTestContext(w)
	c.Request = r
	c.Params = gin.Params{{Key: "id", Value: id}}
	return c
}

func TestRealtimeHLSCaptureReuseOfflineIntegrityAndOwners(t *testing.T) {
	testRealtimeHLSCaptureReuseOfflineIntegrityAndOwners(t, func(node string) *execFixture { return executionFixture(t, node) })
}
func TestMySQLRealtimeHLSCaptureReuseOfflineIntegrityAndOwners(t *testing.T) {
	db, _ := testutil.MySQLDatabase(t)
	testRealtimeHLSCaptureReuseOfflineIntegrityAndOwners(t, func(node string) *execFixture { return executionFixtureDB(t, node, db, "mysql") })
}
func testRealtimeHLSCaptureReuseOfflineIntegrityAndOwners(t *testing.T, fixture func(string) *execFixture) {
	var f *execFixture
	var mu sync.Mutex
	remote := map[string]map[string]any{}
	var complete atomic.Bool
	var ack, sourceReads atomic.Int64
	files := map[string][]byte{"master.m3u8": []byte("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nstream_0.m3u8\n"), "stream_0.m3u8": []byte("#EXTM3U\n#EXTINF:4,\nstream_0_00001.ts\n#EXT-X-ENDLIST\n"), "stream_0_00001.ts": bytes.Repeat([]byte{0x47, 0, 0, 1}, 256), "output.mp4": bytes.Repeat([]byte("verified-mp4"), 256)}
	manifest := map[string]any{}
	for name, data := range files {
		if name == "output.mp4" {
			continue
		}
		sum := sha256.Sum256(data)
		manifest[name] = map[string]any{"size": len(data), "sha256": hex.EncodeToString(sum[:])}
	}
	node := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer "+testToken {
			w.WriteHeader(401)
			return
		}
		path := strings.TrimPrefix(r.URL.Path, "/internal/v1/transcoding/")
		if path == "negotiate" {
			var body map[string]any
			_ = json.NewDecoder(r.Body).Decode(&body)
			ref := str(body, "reference")
			request := httptest.NewRequest("GET", "/api/v1/internal/transcode-input/"+ref, nil)
			request.Header.Set("Authorization", "Bearer "+testToken)
			request.Header.Set("Range", "bytes=0-3")
			out := httptest.NewRecorder()
			f.router.ServeHTTP(out, request)
			if out.Code != 206 || out.Body.String() != "0123" {
				w.WriteHeader(500)
				return
			}
			sourceReads.Add(1)
			json.NewEncoder(w).Encode(map[string]any{"mode": "TRANSCODE", "media": map[string]any{"duration": 120.25, "streams": append(audioStreams(1), map[string]any{"id": "stream:0", "kind": "video", "codec": "hevc"})}, "reasons": []any{"VIDEO_CODEC"}})
			return
		}
		if path == "executions" {
			var body map[string]any
			_ = json.NewDecoder(r.Body).Decode(&body)
			id := str(body, "id")
			state := map[string]any{"id": id, "state": "RUNNING", "ready": true, "offset": 0, "trackMapping": []any{map[string]any{"kind": "video"}}, "purpose": str(obj(body["target"]), "purpose")}
			mu.Lock()
			remote[id] = state
			mu.Unlock()
			json.NewEncoder(w).Encode(state)
			return
		}
		parts := strings.Split(path, "/")
		if len(parts) < 2 || parts[0] != "executions" {
			w.WriteHeader(404)
			return
		}
		id := parts[1]
		mu.Lock()
		state := copyMap(remote[id])
		mu.Unlock()
		if len(state) == 0 {
			w.WriteHeader(404)
			return
		}
		if len(parts) == 2 || parts[2] == "lease" {
			if complete.Load() {
				state["state"] = "SUCCEEDED"
			}
			if str(state, "purpose") == "OFFLINE" && complete.Load() {
				sum := sha256.Sum256(files["output.mp4"])
				state["output"] = map[string]any{"size": len(files["output.mp4"]), "sha256": hex.EncodeToString(sum[:])}
			}
			json.NewEncoder(w).Encode(state)
			return
		}
		switch parts[2] {
		case "export":
			json.NewEncoder(w).Encode(map[string]any{"artifacts": manifest})
		case "acknowledge":
			ack.Add(1)
			io.WriteString(w, `{}`)
		case "cancel":
			io.WriteString(w, `{}`)
		case "artifacts":
			if len(parts) != 4 || files[parts[3]] == nil {
				w.WriteHeader(404)
				return
			}
			http.ServeContent(w, r, parts[3], time.Time{}, bytes.NewReader(files[parts[3]]))
		default:
			w.WriteHeader(404)
		}
	}))
	defer node.Close()
	f = fixture(node.URL)
	s := f.s
	body := map[string]any{"client": map[string]any{"hls": true, "video": []any{"h264"}, "audio": []any{"aac"}, "containers": []any{"mp4"}}}
	resource, err := s.Negotiate(t.Context(), f.song, "tv:one", body, "/original", "/api/v1/tv/transcoding")
	if err != nil || str(resource, "mode") != "HLS" || str(resource, "status") != "READY" || sourceReads.Load() != 1 {
		t.Fatal(resource, err, sourceReads.Load())
	}
	id := str(resource, "id")
	if _, err = s.TranscodeView(t.Context(), id, "tv:two", "/prefix"); err == nil {
		t.Fatal("device read other device lease")
	}
	complete.Store(true)
	s.tickTranscodes(t.Context())
	row, err := s.one(t.Context(), s.db, "transcode-find", map[string]any{"id": id})
	if err != nil || !boolv(decoded(row["result_json"])["computeReleased"]) || ack.Load() != 1 {
		t.Fatal(row, err, ack.Load())
	}
	out := httptest.NewRecorder()
	request := httptest.NewRequest("GET", "/artifact", nil)
	request.Header.Set("Range", "bytes=2-5")
	if err = s.Artifact(t.Context(), out, request, id, "tv:one", "stream_0_00001.ts"); err != nil || out.Code != 206 || !bytes.Equal(out.Body.Bytes(), files["stream_0_00001.ts"][2:6]) {
		t.Fatal(out.Code, err)
	}
	offline, err := s.Offline(t.Context(), map[string]any{"songIds": []any{f.song}})
	if err != nil || len(offline) != 1 {
		t.Fatal(offline, err)
	}
	s.tickTranscodes(t.Context())
	s.tickTranscodes(t.Context())
	versionID := offline[0]
	row, err = s.one(t.Context(), s.db, "transcode-find", map[string]any{"id": versionID})
	if err != nil || str(row, "state") != "SUCCEEDED" {
		t.Fatal(row, err)
	}
	data, err := os.ReadFile(filepath.Join(s.cfg.TranscodePath, versionID+".mp4"))
	if err != nil || !bytes.Equal(data, files["output.mp4"]) {
		t.Fatal("offline artifact", err)
	}
	out = httptest.NewRecorder()
	request = httptest.NewRequest("HEAD", "/version", nil)
	if err = s.Version(t.Context(), out, request, versionID, f.song); err != nil || out.Body.Len() != 0 || out.Header().Get("Content-Length") != stringInt(len(data)) {
		t.Fatal(out.Code, out.Header(), err)
	}
	settings, err := f.compute.View(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	if _, err = f.compute.Save(t.Context(), compute.Save{Mode: "DISABLED", Revision: settings.Revision}); err != nil {
		t.Fatal(err)
	}
	cached, err := s.Negotiate(t.Context(), f.song, "tv:two", body, "/original", "/api/v1/tv/transcoding")
	if err != nil || !boolv(obj(cached["execution"])["cacheHit"]) {
		t.Fatal("disabled compute cache reuse", cached, err)
	}
	if err = s.CancelTranscode(t.Context(), id, "tv:one"); err != nil {
		t.Fatal(err)
	}
	out = httptest.NewRecorder()
	request = httptest.NewRequest("GET", "/artifact", nil)
	if err = s.Artifact(t.Context(), out, request, id, "tv:one", "master.m3u8"); err == nil {
		t.Fatal("cancelled lease streamed")
	}
}

func TestLocalMetadataGrantsBoundReadsAndRevoke(t *testing.T) {
	var f *execFixture
	var reference string
	node := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/internal/v1/media/remote-tags" || r.Header.Get("Authorization") != "Bearer "+testToken {
			w.WriteHeader(404)
			return
		}
		var body map[string]any
		_ = json.NewDecoder(r.Body).Decode(&body)
		if body["path"] != nil || strings.Contains(str(body, "filename"), "/") {
			w.WriteHeader(400)
			return
		}
		reference = str(body, "reference")
		request := httptest.NewRequest("GET", "/api/v1/internal/metadata-input/"+reference, nil)
		request.Header.Set("Authorization", "Bearer "+testToken)
		request.Header.Set("Range", "bytes=1-4")
		out := httptest.NewRecorder()
		f.s.ServeLocalInput(ginContext(out, request, reference))
		if out.Code != 206 || out.Body.String() != "1234" {
			w.WriteHeader(500)
			return
		}
		request.Header.Set("Range", "bytes=0-1048576")
		out = httptest.NewRecorder()
		f.s.ServeLocalInput(ginContext(out, request, reference))
		if out.Code != 400 {
			w.WriteHeader(500)
			return
		}
		json.NewEncoder(w).Encode(map[string]any{"tags": map[string]any{"title": "歌曲", "artists": []any{"歌手"}}, "lyrics": map[string]any{"text": "歌词"}})
	}))
	defer node.Close()
	f = executionFixture(t, node.URL)
	source, err := f.s.song(t.Context(), f.db, f.song)
	if err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(str(source, "location"), "歌曲.mp4")
	tags, err := f.s.LocalTags(t.Context(), path)
	if err != nil || str(tags, "title") != "歌曲" || str(obj(tags["lyrics"]), "text") != "歌词" {
		t.Fatal(tags, err)
	}
	if f.s.HasLocalInput(reference) {
		t.Fatal("local scan grant retained")
	}
	request := httptest.NewRequest("GET", "/input", nil)
	request.Header.Set("Authorization", "Bearer "+testToken)
	out := httptest.NewRecorder()
	f.s.ServeLocalInput(ginContext(out, request, reference))
	if out.Code != 401 {
		t.Fatal("revoked local grant", out.Code)
	}
}

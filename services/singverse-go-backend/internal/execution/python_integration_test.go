package execution

import (
	"bufio"
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"slices"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/compute"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/metadata"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/testutil"
)

// This opt-in test crosses the real Go/Python HTTP boundary and executes the
// installed FFmpeg binaries. No compute endpoint or artifact is simulated.
func TestRealPythonComputeRoundTrip(t *testing.T) {
	if os.Getenv("SINGVERSE_TEST_REAL_COMPUTE") != "1" || testing.Short() {
		t.Skip("run make go-compute-check for the real isolated Python/FFmpeg round trip")
	}
	pythonRoot := computeProject(t)
	python := filepath.Join(pythonRoot, ".venv", "bin", "python")
	if _, err := os.Stat(python); err != nil {
		t.Fatal("compute virtual environment missing; run make compute-setup")
	}
	ffmpeg := requireProgram(t, "ffmpeg")
	ffprobe := requireProgram(t, "ffprobe")
	node := startRealPython(t, pythonRoot, python, ffmpeg, ffprobe)
	ctx, cancel := context.WithTimeout(t.Context(), 150*time.Second)
	defer cancel()

	var fixture *execFixture
	business := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		fixture.router.ServeHTTP(w, r)
	}))
	t.Cleanup(business.Close)
	fixture = executionFixtureDB(t, "", testutil.Database(t), "sqlite", business.URL)
	f := fixture
	s := f.s
	f.compute.RegisterPublic(f.router)
	metadataCfg := f.cfg
	metadataCfg.MetadataPath, metadataCfg.MetadataCachePath = t.TempDir(), t.TempDir()
	tags, err := metadata.New(f.db, "sqlite", metadataCfg, fixtureCipher{}, f.library, slog.New(slog.NewTextHandler(io.Discard, nil)))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = tags.Close() })
	tags.SetLocalInput(s.HasLocalInput, s.ServeLocalInputWith)
	tags.RegisterInternal(f.router.Group("/api/v1"))

	port, err := strconv.Atoi(strings.TrimPrefix(node, "http://127.0.0.1:"))
	if err != nil {
		t.Fatal(err)
	}
	discovered, err := f.compute.Discover(ctx, compute.Scan{Network: "127.0.0.1/32", Port: &port})
	if err != nil || len(discovered.Items) != 1 || !discovered.Items[0].PairingAvailable {
		t.Fatal("real Python discovery failed", discovered, err)
	}
	paired, err := f.compute.Pair(ctx, compute.Pair{DiscoveryID: discovered.Items[0].ID})
	if err != nil || !paired.HasToken || paired.Endpoint != node {
		t.Fatal("real RSA3072 pairing or authenticated callback failed", err)
	}
	c, err := f.compute.Snapshot(ctx)
	if err != nil || c.Token != testToken {
		t.Fatal("paired token does not match isolated Python credential", err)
	}
	check, err := f.compute.Probe(ctx, c)
	if err != nil || check.Status != "AVAILABLE" || !slices.Contains(check.Capabilities, "media-remote-tags") || !slices.Contains(check.Capabilities, "transcode-hls") {
		t.Fatal("real compute readiness or callback check failed", check, err)
	}
	serialized, err := json.Marshal(paired)
	if err != nil || bytes.Contains(serialized, []byte(testToken)) {
		t.Fatal("internal credential escaped into node settings")
	}
	t.Log("real Python discovery, RSA3072 pairing and authenticated callback passed")

	song, err := s.song(ctx, f.db, f.song)
	if err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(str(song, "location"), "歌曲.mp4")
	mediaCommand(t, ctx, ffmpeg, "-v", "error", "-y", "-f", "lavfi", "-i", "testsrc2=s=160x90:r=12:d=2", "-f", "lavfi", "-i", "sine=frequency=400:duration=2", "-f", "lavfi", "-i", "sine=frequency=800:duration=2", "-map", "0:v", "-map", "1:a", "-map", "2:a", "-c:v", "mpeg4", "-c:a", "aac", "-metadata", "title=真实集成歌曲", "-metadata", "artist=集成歌手", "-metadata", "album=集成专辑", "-metadata:s:a:0", "title=原唱", "-metadata:s:a:1", "title=伴奏", path)
	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = f.db.ExecContext(ctx, "UPDATE media_song SET file_size=?,modified_at=? WHERE id=?", info.Size(), info.ModTime().UTC().Format(time.RFC3339Nano), f.song); err != nil {
		t.Fatal(err)
	}
	original, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	originalDigest := sha256.Sum256(original)
	local, err := s.LocalTags(ctx, path)
	if err != nil || str(local, "title") != "真实集成歌曲" || !contains(local["artists"], "集成歌手") {
		t.Fatal("local tags through real Python range callback failed", local, err)
	}
	s.mu.Lock()
	remainingGrants := len(s.localInputs)
	s.mu.Unlock()
	if remainingGrants != 0 {
		t.Fatal("completed local tag input grant retained")
	}
	realRemoteTags(t, ctx, f, tags, c, business.URL, original)
	t.Log("real local and WebDAV tag extraction, bounded callbacks and grant revocation passed")

	probe, err := s.SubmitAudio(ctx, f.song, "PROBE", "", nil)
	if err != nil {
		t.Fatal(err)
	}
	probeID := str(probe, "id")
	probeRow := waitExecution(t, ctx, 35*time.Second, func() (map[string]any, error) {
		s.tickAudio(ctx)
		return s.one(ctx, f.db, "audio-job", map[string]any{"id": probeID})
	}, func(row map[string]any) bool { return str(row, "state") == "SUCCEEDED" })
	streams := array(decoded(probeRow["result_json"])["streams"])
	if len(streams) != 2 || str(obj(streams[0]), "codec") != "aac" || str(obj(streams[0]), "id") != "stream:1" || str(obj(streams[1]), "id") != "stream:2" {
		t.Fatal("real ffprobe stream IDs or codecs differ", streams)
	}
	request, _ := http.NewRequestWithContext(ctx, "GET", business.URL+"/api/v1/internal/audio-input/"+probeID, nil)
	request.Header.Set("Authorization", "Bearer "+testToken)
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	response.Body.Close()
	if response.StatusCode != 404 {
		t.Fatal("completed real audio input authorization retained", response.StatusCode)
	}
	t.Log("real audio probe and completed task input revocation passed")

	client := map[string]any{"family": "android-tv", "hls": true, "fmp4": true, "video": []any{"h264"}, "audio": []any{"aac"}, "containers": []any{"mp4"}, "max_width": 160, "max_height": 90, "max_fps": 12, "max_bitrate": 400000}
	hls, err := s.Negotiate(ctx, f.song, "tv:television", map[string]any{"client": client, "force": true}, "/original", "/api/v1/tv/transcoding")
	if err != nil || str(hls, "mode") != "HLS" {
		t.Fatal("real HLS negotiation or launch failed", hls, err)
	}
	hlsID := str(hls, "id")
	hlsRow := waitExecution(t, ctx, 40*time.Second, func() (map[string]any, error) {
		s.tickTranscodes(ctx)
		return s.one(ctx, f.db, "transcode-find", map[string]any{"id": hlsID})
	}, func(row map[string]any) bool { return boolv(decoded(row["result_json"])["computeReleased"]) })
	cacheID := str(decoded(hlsRow["result_json"]), "cacheId")
	cache, err := s.one(ctx, f.db, "cache-find", map[string]any{"id": cacheID})
	if err != nil || !s.validCache(cache) {
		t.Fatal("real HLS export did not create a verified local cache", err)
	}
	localHLS := t.TempDir()
	for name, raw := range decoded(cache["artifacts_json"]) {
		data := realMediaGET(t, ctx, business.URL+"/api/v1/tv/transcoding/"+hlsID+"/artifacts/"+name, "tv-test", "", 200)
		manifest := obj(raw)
		digest := sha256.Sum256(data)
		if int64(len(data)) != integer(manifest["size"]) || hex.EncodeToString(digest[:]) != str(manifest, "sha256") {
			t.Fatal("Go HLS download differs from the signed-size/checksum manifest", name)
		}
		if err = os.WriteFile(filepath.Join(localHLS, name), data, 0600); err != nil {
			t.Fatal(err)
		}
	}
	assertRealOutput(t, ctx, ffprobe, filepath.Join(localHLS, "master.m3u8"))
	t.Log("real HLS transcode, authenticated download, SHA256 export, local replay and release passed")

	offline, err := s.Offline(ctx, map[string]any{"songIds": []any{f.song}, "client": client})
	if err != nil || len(offline) != 1 {
		t.Fatal("real offline task launch failed", err)
	}
	versionID := offline[0]
	version := waitExecution(t, ctx, 40*time.Second, func() (map[string]any, error) {
		s.tickTranscodes(ctx)
		return s.one(ctx, f.db, "transcode-find", map[string]any{"id": versionID})
	}, func(row map[string]any) bool { return str(row, "state") == "SUCCEEDED" })
	output := obj(decoded(version["result_json"])["output"])
	data := realMediaGET(t, ctx, business.URL+"/api/v1/tv/transcoding/versions/"+versionID+"/"+f.song, "tv-test", "", 200)
	digest := sha256.Sum256(data)
	if int64(len(data)) != integer(output["size"]) || hex.EncodeToString(digest[:]) != str(output, "sha256") {
		t.Fatal("real offline download checksum or size differs")
	}
	partial := realMediaGET(t, ctx, business.URL+"/api/v1/tv/transcoding/versions/"+versionID+"/"+f.song, "tv-test", "bytes=4-31", 206)
	if !bytes.Equal(partial, data[4:32]) {
		t.Fatal("real offline ranged download differs")
	}
	assertRealOutput(t, ctx, ffprobe, filepath.Join(s.cfg.TranscodePath, versionID+".mp4"))
	after, err := os.ReadFile(path)
	if err != nil || sha256.Sum256(after) != originalDigest {
		t.Fatal("compute modified the original media", err)
	}
	t.Log("real offline conversion, verified import, authenticated byte ranges and original read-only check passed")
}

func realRemoteTags(t *testing.T, ctx context.Context, f *execFixture, tags *metadata.Service, c *compute.Connection, callback string, media []byte) {
	t.Helper()
	var reads int
	var mu sync.Mutex
	dav := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/dav/remote.mp4" || r.Header.Get("Authorization") != "" || r.Header.Get("Range") == "" {
			w.WriteHeader(403)
			return
		}
		mu.Lock()
		reads++
		mu.Unlock()
		http.ServeContent(w, r, "remote.mp4", time.Unix(1700000000, 0), bytes.NewReader(media))
	}))
	defer dav.Close()
	sourceID, songID, taskID := uuid(), uuid(), uuid()
	location := dav.URL + "/dav/"
	binding := hash(location)
	stamp := time.Now().UTC().Format(time.RFC3339Nano)
	for _, query := range []struct {
		text string
		args []any
	}{
		{"INSERT INTO media_source(id,name,source_type,location,location_hash,username,created_at,updated_at)VALUES(?,'remote test','WEBDAV',?,?,'',?,?)", []any{sourceID, location, binding, stamp, stamp}},
		{"INSERT INTO media_song(id,source_id,relative_path,path_hash,file_size,scanned_at,parser_profile_id,parser_revision,result_json)VALUES(?,?,'remote.mp4',?,?,?,'default',1,?)", []any{songID, sourceID, hash("remote.mp4"), len(media), stamp, `{"title":"Remote","artists":[],"status":"MATCHED"}`}},
		{"INSERT INTO webdav_metadata_source(source_id,binding,enabled,paused,access_group,private_origins)VALUES(?,?,1,0,?,'[]')", []any{sourceID, binding, hash(dav.URL)}},
		{"INSERT INTO webdav_resource(song_id,source_id,binding,relative_path,path_hash,task_id,generation,last_seen_at,file_size)VALUES(?,?,?,'remote.mp4',?,?,1,?,?)", []any{songID, sourceID, binding, hash("remote.mp4"), taskID, stamp, len(media)}},
	} {
		if _, err := f.db.ExecContext(ctx, query.text, query.args...); err != nil {
			t.Fatal(err)
		}
	}
	task, err := store.Map(ctx, f.db, "SELECT r.*,s.access_group,s.private_origins FROM webdav_resource r JOIN webdav_metadata_source s ON s.source_id=r.source_id WHERE r.song_id=?", songID)
	if err != nil {
		t.Fatal(err)
	}
	task["id"] = taskID
	reference, revoke, err := tags.AuthorizeInput(ctx, task, c.Token, false)
	if err != nil {
		t.Fatal(err)
	}
	defer revoke()
	result, err := f.compute.Call(ctx, c, "/internal/v1/media/remote-tags", map[string]any{"reference": reference, "callbackOrigin": callback, "filename": "remote.mp4", "size": len(media)}, 70*time.Second, 4<<20)
	revoke()
	mu.Lock()
	count := reads
	mu.Unlock()
	if err != nil || str(obj(result["tags"]), "title") != "真实集成歌曲" || integer(result["bytesRead"]) < 1 || count < 1 {
		t.Fatal("real WebDAV extraction failed", result, err)
	}
	realMediaGET(t, ctx, callback+"/api/v1/internal/metadata-input/"+reference, c.Token, "bytes=0-3", 401)
}

func waitExecution(t *testing.T, ctx context.Context, limit time.Duration, read func() (map[string]any, error), ready func(map[string]any) bool) map[string]any {
	t.Helper()
	deadline := time.Now().Add(limit)
	for {
		row, err := read()
		if err != nil {
			t.Fatal(err)
		}
		if ready(row) {
			return row
		}
		if oneOf(str(row, "state"), "FAILED", "CANCELLED") || time.Now().After(deadline) || ctx.Err() != nil {
			t.Fatalf("real execution did not complete: state=%s, error=%s, result=%s", str(row, "state"), str(row, "error"), encoded(decoded(row["result_json"])))
		}
		timer := time.NewTimer(100 * time.Millisecond)
		select {
		case <-ctx.Done():
			timer.Stop()
			t.Fatal(ctx.Err())
		case <-timer.C:
		}
	}
}

func realMediaGET(t *testing.T, ctx context.Context, url, token, byteRange string, status int) []byte {
	t.Helper()
	req, err := http.NewRequestWithContext(ctx, "GET", url, nil)
	if err != nil {
		t.Fatal(err)
	}
	req.Header.Set("Authorization", "Bearer "+token)
	if byteRange != "" {
		req.Header.Set("Range", byteRange)
	}
	response, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	data, err := io.ReadAll(io.LimitReader(response.Body, (16<<20)+1))
	if err != nil || len(data) > 16<<20 || response.StatusCode != status {
		t.Fatal("real media HTTP download failed", response.StatusCode, err)
	}
	return data
}

func assertRealOutput(t *testing.T, ctx context.Context, ffprobe, path string) {
	t.Helper()
	data := mediaCommand(t, ctx, ffprobe, "-v", "error", "-show_streams", "-show_format", "-of", "json", path)
	var media map[string]any
	if err := json.Unmarshal(data, &media); err != nil {
		t.Fatal(err)
	}
	video, audio := 0, 0
	for _, raw := range array(media["streams"]) {
		stream := obj(raw)
		switch str(stream, "codec_type") {
		case "video":
			video++
			if str(stream, "codec_name") != "h264" || integer(stream["width"]) != 160 || integer(stream["height"]) != 90 {
				t.Fatal("real video output has the wrong codec or dimensions", stream)
			}
		case "audio":
			audio++
			if str(stream, "codec_name") != "aac" {
				t.Fatal("real output audio was not AAC")
			}
		}
	}
	if video < 1 || audio < 2 {
		t.Fatal("real output lost video or one of the original audio tracks", video, audio)
	}
}

func computeProject(t *testing.T) string {
	t.Helper()
	dir, err := os.Getwd()
	if err != nil {
		t.Fatal(err)
	}
	for {
		candidate := filepath.Join(dir, "services", "singverse-compute")
		if info, err := os.Stat(filepath.Join(candidate, "singverse_compute", "app.py")); err == nil && info.Mode().IsRegular() {
			return candidate
		}
		parent := filepath.Dir(dir)
		if parent == dir {
			t.Fatal("compute project not found")
		}
		dir = parent
	}
}

func requireProgram(t *testing.T, name string) string {
	t.Helper()
	path, err := exec.LookPath(name)
	if err != nil {
		t.Fatalf("%s is required by the real integration test", name)
	}
	return path
}

func mediaCommand(t *testing.T, ctx context.Context, program string, args ...string) []byte {
	t.Helper()
	command := exec.CommandContext(ctx, program, args...)
	var errors bytes.Buffer
	command.Stderr = &errors
	data, err := command.Output()
	if err != nil {
		t.Fatalf("real media command %s failed: %v: %.2048s", filepath.Base(program), err, errors.String())
	}
	return data
}

type boundedProcessLog struct {
	mu sync.Mutex
	b  []byte
}

func (b *boundedProcessLog) Write(data []byte) (int, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.b = append(b.b, data...)
	if len(b.b) > 8192 {
		b.b = b.b[len(b.b)-8192:]
	}
	return len(data), nil
}

func (b *boundedProcessLog) String() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	return strings.ReplaceAll(string(b.b), testToken, "[redacted]")
}

func startRealPython(t *testing.T, root, python, ffmpeg, ffprobe string) string {
	t.Helper()
	dir := t.TempDir()
	tokenPath := filepath.Join(dir, "node.token")
	if err := os.WriteFile(tokenPath, []byte(testToken), 0600); err != nil {
		t.Fatal(err)
	}
	code := `import socket, uvicorn
from singverse_compute.bootstrap import create_app
app = create_app()
listener = socket.socket()
listener.bind(('127.0.0.1', 0))
print('SINGVERSE_TEST_COMPUTE_PORT=' + str(listener.getsockname()[1]), flush=True)
uvicorn.Server(uvicorn.Config(app, log_level='error', access_log=False)).run(sockets=[listener])
`
	cmd := exec.Command(python, "-u", "-c", code)
	cmd.Dir = root
	cmd.Env = []string{"PATH=" + os.Getenv("PATH"), "LANG=C.UTF-8", "PYTHONDONTWRITEBYTECODE=1", "COMPUTE_AUTH_TOKEN_FILE=" + tokenPath, "COMPUTE_TRANSCODE_ROOT=" + filepath.Join(dir, "transcode"), "COMPUTE_AUDIO_MODEL_REPO=" + filepath.Join(dir, "models"), "COMPUTE_FFMPEG=" + ffmpeg, "COMPUTE_FFPROBE=" + ffprobe, "COMPUTE_HARDWARE_BACKEND=software", "COMPUTE_MINIMUM_FREE_BYTES=0", "COMPUTE_CACHE_BYTES=67108864", "COMPUTE_PROCESS_MEMORY_BYTES=1073741824", "COMPUTE_LAN_PAIRING_ENABLED=true"}
	var logs boundedProcessLog
	cmd.Stderr = &logs
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		t.Fatal(err)
	}
	if err = cmd.Start(); err != nil {
		t.Fatal(err)
	}
	done := make(chan error, 1)
	go func() { done <- cmd.Wait() }()
	t.Cleanup(func() {
		_ = cmd.Process.Signal(os.Interrupt)
		select {
		case <-done:
		case <-time.After(5 * time.Second):
			_ = cmd.Process.Kill()
			<-done
		}
	})
	port := make(chan string, 1)
	go func() {
		scanner := bufio.NewScanner(stdout)
		for scanner.Scan() {
			line := scanner.Text()
			if strings.HasPrefix(line, "SINGVERSE_TEST_COMPUTE_PORT=") {
				select {
				case port <- strings.TrimPrefix(line, "SINGVERSE_TEST_COMPUTE_PORT="):
				default:
				}
			}
		}
	}()
	var endpoint string
	select {
	case value := <-port:
		parsed, err := strconv.Atoi(value)
		if err != nil || parsed < 1 || parsed > 65535 {
			t.Fatal("real Python supplied an invalid bound port")
		}
		endpoint = fmt.Sprintf("http://127.0.0.1:%d", parsed)
	case err := <-done:
		done <- err
		t.Fatal("real Python exited before startup", err, logs.String())
	case <-time.After(35 * time.Second):
		t.Fatal("real Python startup timed out", logs.String())
	}
	for range 100 {
		ctx, cancel := context.WithTimeout(t.Context(), time.Second)
		req, _ := http.NewRequestWithContext(ctx, "GET", endpoint+"/health", nil)
		response, err := http.DefaultClient.Do(req)
		cancel()
		if err == nil {
			response.Body.Close()
			if response.StatusCode == 200 {
				// The node creates its persistent RSA identity lazily. Finish that
				// one-time operation before exercising the intentionally short
				// production LAN scan timeout on a heavily loaded test host.
				readyCtx, stop := context.WithTimeout(t.Context(), 15*time.Second)
				readyRequest, _ := http.NewRequestWithContext(readyCtx, "GET", endpoint+"/internal/v1/lan/discovery", nil)
				ready, readyErr := http.DefaultClient.Do(readyRequest)
				if readyErr == nil {
					io.Copy(io.Discard, io.LimitReader(ready.Body, 8192))
					ready.Body.Close()
				}
				stop()
				if readyErr != nil || ready.StatusCode != 200 {
					t.Fatal("real Python pairing identity did not become ready", readyErr, logs.String())
				}
				return endpoint
			}
		}
		time.Sleep(50 * time.Millisecond)
	}
	t.Fatal("real Python did not become healthy", logs.String())
	return ""
}

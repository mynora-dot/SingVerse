package execution

import (
	"context"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"errors"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"

	"github.com/jmoiron/sqlx"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/compute"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
	"golang.org/x/sys/unix"
)

func (s *Service) dirtyMetadata(ctx context.Context, tx store.DBTX, song string) error {
	_, err := s.exec(ctx, tx, "metadata-dirty", map[string]any{"song": song})
	return err
}
func (s *Service) SubmitAudio(ctx context.Context, songID, kind, backend string, options map[string]any) (map[string]any, error) {
	return s.submitAudio(ctx, songID, kind, backend, options, nil, false)
}
func (s *Service) submitAudioOrigins(ctx context.Context, songID, kind, backend string, options map[string]any, origins []string) (map[string]any, error) {
	return s.submitAudio(ctx, songID, kind, backend, options, origins, false)
}
func (s *Service) submitAudio(ctx context.Context, songID, kind, backend string, options map[string]any, origins []string, automatic bool) (map[string]any, error) {
	spec, err := recipe(options)
	if err != nil {
		return nil, err
	}
	if backend == "auto" {
		backend = ""
	}
	if err = spec.validateBackend(backend); err != nil {
		return nil, err
	}
	if !oneOf(kind, "PROBE", "FAST", "ANALYZE", "SEPARATE") {
		return nil, fail(400, "未知音轨任务")
	}
	if kind != "SEPARATE" && !spec.legacy() {
		return nil, fail(400, "伴奏方案仅用于生成伴奏")
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	song, err := s.song(ctx, s.db, songID)
	if err != nil {
		return nil, err
	}
	fp := contentFingerprint(song)
	if kind == "SEPARATE" {
		role, err := s.optional(ctx, s.db, "audio-role", map[string]any{"song": songID})
		if err != nil {
			return nil, err
		}
		backing := ""
		if str(role, "fingerprint") == fp {
			backing = str(role, "backing_id")
		}
		if spec.source != "" && spec.source != backing {
			if err = s.validateAudioSource(ctx, songID, fp, spec.source); err != nil {
				return nil, err
			}
		}
		if spec.source == "" && backing != "" {
			spec.source = backing
		}
	}
	history, err := s.maps(ctx, s.db, "audio-for-song", map[string]any{"song": songID, "fingerprint": fp})
	if err != nil {
		return nil, err
	}
	analysisSeen := false
	for _, row := range history {
		if str(row, "kind") != kind {
			continue
		}
		prior := storedRecipe(row)
		same := kind != "SEPARATE" || (prior.legacy() && spec.legacy()) || (encoded(prior.view()) == encoded(spec.view()) && str(row, "backend") == backend)
		if !same {
			continue
		}
		state := str(row, "state")
		latestAnalysis := !analysisSeen
		if oneOf(kind, "FAST", "ANALYZE") {
			analysisSeen = true
		}
		reuse := oneOf(state, "QUEUED", "RUNNING")
		if state == "SUCCEEDED" {
			if oneOf(kind, "FAST", "ANALYZE") {
				reuse = latestAnalysis && str(decoded(row["result_json"]), "state") == "CONFIDENT"
			} else {
				reuse = kind != "SEPARATE" || regular(s.audioPath(row))
			}
		}
		if reuse {
			if err = s.transaction(ctx, "AUDIO_JOB", str(row, "id"), func(tx *sqlx.Tx) error { return s.recordAudio(ctx, tx, row, origins, automatic, false) }); err != nil {
				return nil, err
			}
			return audioJobView(row), nil
		}
	}
	connection, err := s.connection(ctx, "")
	if err != nil {
		return nil, err
	}
	id := uuid()
	stamp := now()
	values := map[string]any{"id": id, "song_id": songID, "kind": kind, "state": "QUEUED", "fingerprint": fp, "node_origin": connection.Origin, "backend": backend, "recipe_json": encoded(spec.view()), "created_at": stamp, "updated_at": stamp}
	err = s.transaction(ctx, "AUDIO_JOB", id, func(tx *sqlx.Tx) error {
		current, e := s.song(ctx, tx, songID)
		if e != nil {
			return e
		}
		if contentFingerprint(current) != fp {
			return fail(409, "源媒体已变化，请重试")
		}
		if _, e = s.exec(ctx, tx, "audio-insert-job", values); e != nil {
			return e
		}
		return s.recordAudio(ctx, tx, values, origins, automatic, true)
	})
	if err != nil {
		return nil, err
	}
	return audioJobView(values), nil
}
func audioJobView(row map[string]any) map[string]any {
	return map[string]any{"id": str(row, "id"), "kind": str(row, "kind"), "state": str(row, "state")}
}
func (s *Service) validateAudioSource(ctx context.Context, song, fp, source string) error {
	parts := strings.Split(source, ":")
	base := parts[0] + ":" + parts[1]
	history, err := s.maps(ctx, s.db, "audio-for-song", map[string]any{"song": song, "fingerprint": fp})
	if err != nil {
		return err
	}
	for _, row := range history {
		for _, stream := range array(decoded(row["result_json"])["streams"]) {
			v := obj(stream)
			if str(v, "id") == base && (len(parts) == 2 || integer(v["channels"]) == 2) {
				return nil
			}
		}
	}
	return fail(422, "请先读取音轨，再试听确认所选伴奏音轨或声道")
}
func (s *Service) updateAudio(ctx context.Context, id, state string, result map[string]any, detail string) error {
	return s.transaction(ctx, "AUDIO_JOB", id, func(tx *sqlx.Tx) error {
		_, err := s.exec(ctx, tx, "audio-update-job", map[string]any{"id": id, "state": state, "result": encoded(result), "error": detail, "now": now()})
		return err
	})
}
func (s *Service) audioInput(ctx context.Context, row map[string]any, c *compute.Connection) (map[string]any, error) {
	body := map[string]any{"id": str(row, "id"), "mode": str(row, "kind"), "reference": str(row, "id")}
	if backend := str(row, "backend"); backend != "" {
		body["backend"] = backend
	}
	spec := storedRecipe(row)
	if str(row, "kind") == "SEPARATE" && !spec.legacy() {
		node, err := s.call(ctx, c, "/internal/v1/audio-roles/models", "GET", nil)
		if err != nil {
			return nil, err
		}
		if integer(obj(node["separation"])["version"]) < 1 {
			return nil, fail(422, "计算节点尚不支持伴奏方案，请升级节点")
		}
		if !spec.ready(node, str(row, "backend")) {
			return nil, fail(429, "等待伴奏模型与独立 CPU 运行环境就绪")
		}
		for key, value := range spec.view() {
			if !oneOf(key, "recipeVersion", "precisionPolicy", "modelDigest", "dspFilterVersion") {
				body[key] = value
			}
		}
	}
	return body, nil
}
func (s *Service) tickAudio(ctx context.Context) {
	if !s.audioWorking.CompareAndSwap(false, true) {
		return
	}
	defer s.audioWorking.Store(false)
	pending, err := s.maps(ctx, s.db, "audio-pending", nil)
	if err != nil {
		return
	}
	running := false
	for _, row := range pending {
		if str(row, "state") == "RUNNING" {
			running = true
		}
	}
	for _, row := range pending {
		if ctx.Err() != nil {
			return
		}
		if running && str(row, "state") == "QUEUED" {
			continue
		}
		id := str(row, "id")
		err = s.advanceAudio(ctx, row)
		if err == nil {
			if str(row, "state") == "QUEUED" {
				break
			}
			continue
		}
		var f *failure
		if errors.As(err, &f) {
			switch f.status {
			case 404:
				if str(row, "state") == "RUNNING" {
					_ = s.updateAudio(ctx, id, "QUEUED", map[string]any{}, "节点重启后重新排队")
				} else {
					_ = s.updateAudio(ctx, id, "FAILED", map[string]any{}, "媒体文件不可读取")
				}
			case 409:
				_ = s.updateAudio(ctx, id, "FAILED", map[string]any{}, "节点配置或任务状态已变化，请重试")
			case 400, 403, 413, 422:
				_ = s.updateAudio(ctx, id, "FAILED", map[string]any{}, f.detail)
			default:
				_ = s.updateAudio(ctx, id, str(row, "state"), decoded(row["result_json"]), "等待计算节点或可用空间")
			}
			if oneOfInt(f.status, 429, 502, 503) {
				break
			}
		} else {
			_ = s.updateAudio(ctx, id, str(row, "state"), decoded(row["result_json"]), "等待计算节点")
		}
	}
}
func (s *Service) advanceAudio(ctx context.Context, row map[string]any) error {
	id, songID := str(row, "id"), str(row, "song_id")
	song, err := s.song(ctx, s.db, songID)
	if err != nil {
		return err
	}
	if contentFingerprint(song) != str(row, "fingerprint") {
		s.cancelRemoteAudio(ctx, row)
		return fail(409, "源媒体已变化")
	}
	c, err := s.connection(ctx, str(row, "node_origin"))
	if err != nil {
		return err
	}
	if str(row, "state") == "QUEUED" {
		body, err := s.audioInput(ctx, row, c)
		if err != nil {
			return err
		}
		execution := map[string]any{"timingsMs": map[string]any{"queue": max(int64(0), now()-integer(row["created_at"]))}}
		if err = s.updateAudio(ctx, id, "RUNNING", execution, ""); err != nil {
			return err
		}
		_, err = s.call(ctx, c, "/internal/v1/audio-roles/tasks", "POST", body)
		latest, _ := s.optional(ctx, s.db, "audio-job", map[string]any{"id": id})
		if str(latest, "state") == "CANCELLED" {
			s.cancelRemoteAudio(ctx, latest)
		}
		if err != nil {
			_ = s.updateAudio(ctx, id, "QUEUED", map[string]any{}, "等待计算节点")
		}
		return err
	}
	state, err := s.call(ctx, c, "/internal/v1/audio-roles/tasks/"+id, "GET", nil)
	if err != nil {
		return err
	}
	result := obj(state["result"])
	previous := decoded(row["result_json"])
	if queue, ok := obj(previous["timingsMs"])["queue"]; ok {
		timings := obj(result["timingsMs"])
		timings["queue"] = queue
		result["timingsMs"] = timings
	}
	phase := str(state, "state")
	switch phase {
	case "SUCCEEDED":
		if str(row, "kind") == "SEPARATE" {
			if err = s.receiveAudio(ctx, row, c, result); err != nil {
				return err
			}
		}
		s.mu.Lock()
		defer s.mu.Unlock()
		err = s.transaction(ctx, "AUDIO_JOB", id, func(tx *sqlx.Tx) error {
			latest, e := s.one(ctx, tx, "audio-job", map[string]any{"id": id})
			if e != nil {
				return e
			}
			if str(latest, "state") == "CANCELLED" {
				return fail(409, "任务已取消")
			}
			song, e := s.song(ctx, tx, songID)
			if e != nil {
				return e
			}
			if contentFingerprint(song) != str(row, "fingerprint") {
				_ = os.Remove(s.audioPath(row))
				return fail(409, "源媒体已变化")
			}
			if e = s.saveFormalProbe(ctx, tx, songID, result); e != nil {
				return e
			}
			if oneOf(str(row, "kind"), "FAST", "ANALYZE") {
				if e = s.applySuggestion(ctx, tx, songID, str(row, "fingerprint"), result, false); e != nil {
					return e
				}
			}
			if e = s.verifyImported(ctx, tx, songID, str(row, "fingerprint"), result); e != nil {
				return e
			}
			timings := obj(result["timingsMs"])
			timings["endToEnd"] = max(int64(0), now()-integer(row["created_at"]))
			result["timingsMs"] = timings
			if str(row, "kind") == "SEPARATE" && storedRecipe(row).playable() {
				if e = s.preserveSelection(ctx, tx, row); e != nil {
					return e
				}
			}
			_, e = s.exec(ctx, tx, "audio-update-job", map[string]any{"id": id, "state": "SUCCEEDED", "result": encoded(result), "error": "", "now": now()})
			return e
		})
		if err == nil && str(row, "kind") == "SEPARATE" {
			_, _ = s.call(ctx, c, "/internal/v1/audio-roles/tasks/"+id+"/release", "POST", nil)
		}
		return err
	case "FAILED", "CANCELLED":
		detail := str(state, "error")
		if detail == "REALTIME_PREEMPTED" {
			phase = "FAILED"
		}
		return s.updateAudio(ctx, id, phase, result, detail)
	case "RUNNING":
		return s.updateAudio(ctx, id, "RUNNING", result, "")
	default:
		return fail(502, "计算节点任务状态无效")
	}
}
func availableBytes(root string) (int64, error) {
	var stat unix.Statfs_t
	if err := unix.Statfs(root, &stat); err != nil {
		return 0, err
	}
	return int64(stat.Bavail) * int64(stat.Bsize), nil
}
func (s *Service) receiveAudio(ctx context.Context, row map[string]any, c *compute.Connection, result map[string]any) error {
	start := time.Now()
	id := str(row, "id")
	spec := storedRecipe(row)
	if str(result, "artifact") != id+"."+spec.extension() {
		return fail(422, "伴奏产物无效")
	}
	if !spec.legacy() && (str(result, "separationProfile") != spec.profile || str(result, "modelId") != spec.model || str(result, "outputFormat") != spec.format || result["preview"] != spec.preview) {
		return fail(422, "伴奏产物与任务方案不一致")
	}
	guard := func() error {
		latest, err := s.one(ctx, s.db, "audio-job", map[string]any{"id": id})
		if err != nil {
			return err
		}
		if str(latest, "state") == "CANCELLED" {
			return fail(409, "伴奏任务已取消")
		}
		return nil
	}
	if err := s.downloadVerified(ctx, id, c, "/internal/v1/audio-roles/tasks/"+id+"/artifact", s.cfg.AudioDerivedPath, id+"."+spec.extension(), integer(result["size"]), str(result, "sha256"), 1<<30, 1024, guard); err != nil {
		return err
	}
	timings := obj(result["timingsMs"])
	timings["transfer"] = float64(time.Since(start).Microseconds()) / 1000
	result["timingsMs"] = timings
	return nil
}
func (s *Service) downloadVerified(ctx context.Context, id string, c *compute.Connection, remote, root, name string, expected int64, checksum string, maximum, minimum int64, guard func() error) error {
	if expected < minimum || expected > maximum || !hex64Pattern.MatchString(checksum) {
		return fail(422, "媒体产物清单无效")
	}
	free, err := availableBytes(root)
	if err != nil {
		return err
	}
	if free < max(int64(2<<30), expected) {
		return fail(507, "媒体产物存储空间不足")
	}
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	s.mu.Lock()
	s.active[id] = cancel
	s.mu.Unlock()
	defer func() { s.mu.Lock(); delete(s.active, id); s.mu.Unlock() }()
	response, err := s.artifactResponse(ctx, c, remote, "")
	if err != nil {
		return err
	}
	defer response.Body.Close()
	if response.StatusCode != 200 {
		return fail(502, "媒体产物读取失败")
	}
	if response.ContentLength >= 0 && response.ContentLength != expected {
		return fail(422, "媒体产物长度不一致")
	}
	part := id + ".part"
	dir, err := os.OpenRoot(root)
	if err != nil {
		return err
	}
	defer dir.Close()
	file, err := dir.OpenFile(part, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
	if errors.Is(err, os.ErrExist) {
		_ = dir.Remove(part)
		file, err = dir.OpenFile(part, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
	}
	if err != nil {
		return err
	}
	defer dir.Remove(part)
	hash := sha256.New()
	buffer := make([]byte, 65536)
	size := int64(0)
	for {
		if err = guard(); err != nil {
			file.Close()
			return err
		}
		n, readErr := response.Body.Read(buffer)
		if n > 0 {
			size += int64(n)
			if size > expected || size > maximum {
				file.Close()
				return fail(413, "媒体产物超过大小预算")
			}
			if _, err = file.Write(buffer[:n]); err != nil {
				file.Close()
				return err
			}
			_, _ = hash.Write(buffer[:n])
		}
		if readErr == io.EOF {
			break
		}
		if readErr != nil {
			file.Close()
			return fail(502, "媒体产物下载中断")
		}
	}
	if size != expected || hex.EncodeToString(hash.Sum(nil)) != checksum {
		file.Close()
		return fail(422, "媒体产物校验失败")
	}
	if err = file.Sync(); err != nil {
		file.Close()
		return err
	}
	if err = file.Close(); err != nil {
		return err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if err = guard(); err != nil {
		return err
	}
	return dir.Rename(part, name)
}

var hex64Pattern = regexpMustHex()

func regexpMustHex() *regexp.Regexp { return regexp.MustCompile(`^[a-f0-9]{64}$`) }
func (s *Service) preserveSelection(ctx context.Context, tx store.DBTX, completed map[string]any) error {
	song, fp := str(completed, "song_id"), str(completed, "fingerprint")
	selection, err := s.optional(ctx, tx, "audio-selection", map[string]any{"song": song})
	if err != nil {
		return err
	}
	if str(selection, "fingerprint") == fp {
		return nil
	}
	chosen := str(completed, "id")
	history, err := s.maps(ctx, tx, "audio-for-song", map[string]any{"song": song, "fingerprint": fp})
	if err != nil {
		return err
	}
	for _, prior := range history {
		if str(prior, "kind") == "SEPARATE" && str(prior, "state") == "SUCCEEDED" && storedRecipe(prior).playable() && regular(s.audioPath(prior)) {
			chosen = str(prior, "id")
			break
		}
	}
	name := "audio-update-selection"
	if selection == nil {
		name = "audio-insert-selection"
	}
	_, err = s.exec(ctx, tx, name, map[string]any{"song": song, "fingerprint": fp, "job": chosen, "now": now()})
	return err
}
func (s *Service) activateAudio(ctx context.Context, song, id string) (map[string]any, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	err := s.transaction(ctx, "", "", func(tx *sqlx.Tx) error {
		row, err := s.requireAudio(ctx, tx, song, id)
		if err != nil {
			return err
		}
		media, err := s.song(ctx, tx, song)
		if err != nil {
			return err
		}
		if str(row, "state") != "SUCCEEDED" || str(row, "kind") != "SEPARATE" || str(row, "fingerprint") != contentFingerprint(media) || !storedRecipe(row).playable() || !regular(s.audioPath(row)) {
			return fail(422, "请选择完整 AAC 伴奏；试听片段和 FLAC 导出不能设为播放版本")
		}
		prior, err := s.optional(ctx, tx, "audio-selection", map[string]any{"song": song})
		if err != nil {
			return err
		}
		name := "audio-update-selection"
		if prior == nil {
			name = "audio-insert-selection"
		}
		_, err = s.exec(ctx, tx, name, map[string]any{"song": song, "fingerprint": str(row, "fingerprint"), "job": id, "now": now()})
		return err
	})
	if err != nil {
		return nil, err
	}
	return s.audioStatus(ctx, song)
}
func (s *Service) requireAudio(ctx context.Context, db store.DBTX, song, id string) (map[string]any, error) {
	if !idPattern.MatchString(id) {
		return nil, fail(404, "任务不存在")
	}
	row, err := s.optional(ctx, db, "audio-job", map[string]any{"id": id})
	if err != nil {
		return nil, err
	}
	if row == nil || str(row, "song_id") != song {
		return nil, fail(404, "任务不存在")
	}
	return row, nil
}
func (s *Service) CancelAudio(ctx context.Context, song, id string) error {
	s.mu.Lock()
	row, err := s.requireAudio(ctx, s.db, song, id)
	if err != nil {
		s.mu.Unlock()
		return err
	}
	if oneOf(str(row, "state"), "SUCCEEDED", "FAILED", "CANCELLED") {
		s.mu.Unlock()
		return nil
	}
	err = s.updateAudio(ctx, id, "CANCELLED", map[string]any{}, "")
	if cancel := s.active[id]; cancel != nil {
		cancel()
	}
	_ = os.Remove(s.audioPath(row))
	s.mu.Unlock()
	if err != nil {
		return err
	}
	s.cancelRemoteAudio(ctx, row)
	return nil
}
func (s *Service) cancelRemoteAudio(ctx context.Context, row map[string]any) {
	if str(row, "state") != "RUNNING" {
		return
	}
	if c, err := s.connection(ctx, str(row, "node_origin")); err == nil {
		_, _ = s.call(ctx, c, "/internal/v1/audio-roles/tasks/"+str(row, "id")+"/cancel", "POST", nil)
	}
}
func (s *Service) sourceAudio(ctx context.Context, w http.ResponseWriter, r *http.Request, id string) error {
	guard := func() (map[string]any, error) {
		row, err := s.optional(ctx, s.db, "audio-job", map[string]any{"id": id})
		if err != nil {
			return nil, err
		}
		if row == nil || str(row, "state") != "RUNNING" {
			return nil, fail(404, "媒体授权不存在")
		}
		c, err := s.connection(ctx, str(row, "node_origin"))
		if err != nil {
			return nil, err
		}
		if subtle.ConstantTimeCompare([]byte(r.Header.Get("Authorization")), []byte("Bearer "+c.Token)) != 1 {
			return nil, fail(401, "内部认证失败")
		}
		song, err := s.song(ctx, s.db, str(row, "song_id"))
		if err != nil {
			return nil, err
		}
		if contentFingerprint(song) != str(row, "fingerprint") {
			return nil, fail(409, "源媒体已变化")
		}
		return row, nil
	}
	row, err := guard()
	if err != nil {
		return err
	}
	return s.library.ServeOriginalGuard(ctx, w, r, str(row, "song_id"), func() error { _, err := guard(); return err })
}
func (s *Service) accompaniment(ctx context.Context, w http.ResponseWriter, r *http.Request, song, id string) error {
	row, err := s.requireAudio(ctx, s.db, song, id)
	if err != nil {
		return err
	}
	media, err := s.song(ctx, s.db, song)
	if err != nil {
		return err
	}
	if str(row, "state") != "SUCCEEDED" || str(row, "kind") != "SEPARATE" || str(row, "fingerprint") != contentFingerprint(media) {
		return fail(404, "伴奏版本已失效")
	}
	return streamFile(w, r, s.cfg.AudioDerivedPath, filepath.Base(s.audioPath(row)))
}

func (s *Service) observeAudio(ctx context.Context, songID string, media map[string]any) error {
	streams, ok := media["streams"].([]any)
	if !ok || media["duration"] == nil {
		return nil
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.transaction(ctx, "", "", func(tx *sqlx.Tx) error {
		song, err := s.song(ctx, tx, songID)
		if err != nil {
			return err
		}
		fp := contentFingerprint(song)
		history, err := s.maps(ctx, tx, "audio-for-song", map[string]any{"song": songID, "fingerprint": fp})
		if err != nil {
			return err
		}
		for _, job := range history {
			if str(job, "kind") == "PROBE" && str(job, "state") == "SUCCEEDED" {
				return nil
			}
		}
		id := uuid()
		stamp := now()
		values := map[string]any{"id": id, "song_id": songID, "kind": "PROBE", "state": "RUNNING", "fingerprint": fp, "node_origin": "", "created_at": stamp, "updated_at": stamp, "backend": "", "recipe_json": "{}"}
		if _, err = s.exec(ctx, tx, "audio-insert-job", values); err != nil {
			return err
		}
		audio := []any{}
		for _, item := range streams {
			if str(obj(item), "kind") == "audio" {
				audio = append(audio, item)
			}
		}
		result := map[string]any{"duration": media["duration"], "streams": audio}
		if _, err = s.exec(ctx, tx, "audio-update-job", map[string]any{"id": id, "state": "SUCCEEDED", "result": encoded(result), "error": "", "now": stamp}); err != nil {
			return err
		}
		if err = s.saveFormalProbe(ctx, tx, songID, media); err != nil {
			return err
		}
		if err = s.verifyImported(ctx, tx, songID, fp, result); err != nil {
			return err
		}
		return s.projectWith(ctx, tx, "AUDIO_JOB", id)
	})
}
func (s *Service) discoverAudio(ctx context.Context) {
	scopes := s.audioScopes()
	snapshot, err := s.compute.Snapshot(ctx)
	if err != nil {
		if scopes != nil {
			_ = scopes.WaitingForAudioNode(ctx)
		}
		return
	}
	if snapshot == nil || snapshot.Token == "" {
		if scopes != nil {
			_ = scopes.SkipPendingAudio(ctx, "计算节点未配置或已停用")
		}
		return
	}
	capability, err := s.call(ctx, snapshot, "/internal/v1/status", "GET", nil)
	if err != nil {
		if scopes != nil {
			_ = scopes.WaitingForAudioNode(ctx)
		}
		return
	}
	if !contains(capability["capabilities"], "media-probe") {
		if scopes != nil {
			_ = scopes.SkipPendingAudio(ctx, "节点未启用音轨探测阶段")
		}
		return
	}
	candidates, err := s.maps(ctx, s.db, "audio-candidates", map[string]any{"cursor": s.scanCursor})
	if err != nil {
		return
	}
	work := []string{}
	seen := map[string]bool{}
	for _, candidate := range candidates {
		id := str(candidate, "id")
		work = append(work, id)
		seen[id] = true
		s.scanCursor = id
	}
	if scopes != nil {
		pending, e := scopes.PendingScopes(ctx, s.scopeBatchCursor, s.scopeSongCursor)
		if e != nil {
			return
		}
		if len(pending) == 0 {
			s.scopeBatchCursor, s.scopeSongCursor = "", ""
		}
		for _, scope := range pending {
			s.scopeBatchCursor, s.scopeSongCursor = str(scope, "batch_id"), str(scope, "song_id")
			song, e := s.song(ctx, s.db, s.scopeSongCursor)
			if e != nil || contentFingerprint(song) != str(scope, "fingerprint") {
				_ = s.resolveDiscovery(ctx, s.scopeSongCursor, str(scope, "fingerprint"), "INTERRUPTED", "扫描后的源媒体已变化或歌曲已删除")
				continue
			}
			if !seen[s.scopeSongCursor] {
				work = append(work, s.scopeSongCursor)
				seen[s.scopeSongCursor] = true
			}
		}
	}
	for _, song := range work {
		if ctx.Err() != nil {
			return
		}
		_ = s.discoverSong(ctx, song, capability)
	}
	if len(candidates) == 0 {
		s.scanCursor = ""
		if scopes != nil {
			_ = scopes.FinishDiscoveryRound(ctx, s.discoveryRound)
		}
		s.discoveryRound = uuid()
	}
}
func (s *Service) resolveDiscovery(ctx context.Context, song, fp, state, reason string) error {
	scopes := s.audioScopes()
	if scopes == nil {
		return nil
	}
	return s.transaction(ctx, "", "", func(tx *sqlx.Tx) error { return scopes.ResolveAudioScopes(ctx, tx, song, fp, state, reason) })
}
func (s *Service) attachDiscovery(ctx context.Context, job map[string]any, origins []string) error {
	if len(origins) == 0 {
		return nil
	}
	return s.transaction(ctx, "AUDIO_JOB", str(job, "id"), func(tx *sqlx.Tx) error { return s.recordAudio(ctx, tx, job, origins, false, false) })
}
func (s *Service) discoverSong(ctx context.Context, songID string, capability map[string]any) error {
	song, err := s.song(ctx, s.db, songID)
	if err != nil {
		return err
	}
	fp := contentFingerprint(song)
	history, err := s.maps(ctx, s.db, "audio-for-song", map[string]any{"song": songID, "fingerprint": fp})
	if err != nil {
		return err
	}
	origins := []string{}
	if scopes := s.audioScopes(); scopes != nil {
		origins, err = scopes.AudioOrigins(ctx, s.db, songID, fp)
		if err != nil {
			return err
		}
	}
	if len(history) == 0 {
		_, err = s.submitAudio(ctx, songID, "PROBE", "", nil, origins, true)
		return err
	}
	var probe, fast map[string]any
	for _, job := range history {
		if str(job, "kind") == "PROBE" && probe == nil {
			probe = job
		}
		if str(job, "kind") == "FAST" && fast == nil {
			fast = job
		}
	}
	if probe != nil {
		if err = s.attachDiscovery(ctx, probe, origins); err != nil {
			return err
		}
		if oneOf(str(probe, "state"), "QUEUED", "RUNNING") {
			return nil
		}
	}
	if !contains(capability["capabilities"], "audio-role-fast") {
		return s.resolveDiscovery(ctx, songID, fp, "SKIPPED", "未启用快速识别阶段")
	}
	status, err := s.audioStatus(ctx, songID)
	if err != nil {
		return err
	}
	if str(status, "state") == "CONFIRMED" {
		return s.resolveDiscovery(ctx, songID, fp, "SKIPPED", "音轨角色已确认，无需快速识别")
	}
	if fast != nil {
		prior := decoded(fast["result_json"])
		retry := str(fast, "state") == "SUCCEEDED" && ((str(prior, "reason") == "FAST_MODEL_MISSING" && contains(capability["capabilities"], "audio-role-fast-classifier")) || (contains(capability["capabilities"], "audio-role-fast-v2") && str(prior, "state") == "UNKNOWN" && str(obj(prior["evidence"]), "analysisVersion") != "fast-vocal-v2"))
		if !retry {
			return s.attachDiscovery(ctx, fast, origins)
		}
	}
	if probe != nil && str(probe, "state") == "SUCCEEDED" {
		if len(array(decoded(probe["result_json"])["streams"])) == 2 {
			_, err = s.submitAudio(ctx, songID, "FAST", "", nil, origins, true)
			return err
		}
		return s.resolveDiscovery(ctx, songID, fp, "SKIPPED", "非双音轨，无需快速识别")
	}
	state, reason := "SKIPPED", "无需自动音轨处理"
	if probe != nil {
		state, reason = "FAILED", "音轨探测未成功"
	}
	return s.resolveDiscovery(ctx, songID, fp, state, reason)
}
func (s *Service) collectAudio(ctx context.Context) {
	entries, err := os.ReadDir(s.cfg.AudioDerivedPath)
	if err != nil {
		return
	}
	for _, entry := range entries {
		name := entry.Name()
		if len(name) < 40 || !idPattern.MatchString(name[:36]) || !oneOf(filepath.Ext(name), ".m4a", ".flac", ".part") {
			continue
		}
		info, err := entry.Info()
		if err != nil || info.ModTime().After(time.Now().Add(-24*time.Hour)) {
			continue
		}
		row, err := s.optional(ctx, s.db, "audio-job", map[string]any{"id": name[:36]})
		if err != nil {
			continue
		}
		remove := filepath.Ext(name) == ".part" || row == nil || str(row, "state") != "SUCCEEDED"
		if !remove {
			song, err := s.song(ctx, s.db, str(row, "song_id"))
			remove = err != nil || contentFingerprint(song) != str(row, "fingerprint")
		}
		if remove {
			_ = os.Remove(filepath.Join(s.cfg.AudioDerivedPath, name))
		}
	}
}
func (s *Service) PortableAudio(ctx context.Context, songID string) (map[string]any, error) {
	v, err := s.audioStatus(ctx, songID)
	if err != nil {
		return nil, err
	}
	for _, key := range []string{"songId", "fingerprint", "revision", "jobs", "versions", "backingResource"} {
		delete(v, key)
	}
	v["separationPolicy"] = "GENERATE_LOCALLY"
	return v, nil
}
func (s *Service) ImportAudioClaim(ctx context.Context, songID string, claim map[string]any) error {
	return s.transaction(ctx, "", "", func(tx *sqlx.Tx) error { return s.ImportAudioClaimWith(ctx, tx, songID, claim) })
}
func (s *Service) ImportAudioClaimWith(ctx context.Context, tx store.DBTX, songID string, claim map[string]any) error {
	if len(encoded(claim)) > 16384 {
		return nil
	}
	prior, err := s.optional(ctx, tx, "audio-claim", map[string]any{"song": songID})
	if err != nil || prior != nil {
		return err
	}
	song, err := s.song(ctx, tx, songID)
	if err != nil {
		return err
	}
	_, err = s.exec(ctx, tx, "audio-insert-claim", map[string]any{"song": songID, "claim": encoded(map[string]any{"fingerprint": contentFingerprint(song), "claim": claim})})
	return err
}

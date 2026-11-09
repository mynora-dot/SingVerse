package execution

import (
	"context"
	"crypto/subtle"
	"errors"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"time"

	"github.com/jmoiron/sqlx"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/compute"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

var artifactPattern = regexp.MustCompile(`^(?:master\.m3u8|stream_[a-zA-Z0-9]+\.m3u8|stream_[a-zA-Z0-9]+_[0-9]+\.(?:ts|m4s)|init_[a-zA-Z0-9]+\.mp4|output\.mp4|subtitle\.vtt)$`)

func (s *Service) requireTranscode(ctx context.Context, db store.DBTX, id, owner string) (map[string]any, error) {
	if !idPattern.MatchString(id) {
		return nil, fail(404, "播放资源不存在")
	}
	row, err := s.optional(ctx, db, "transcode-find", map[string]any{"id": id})
	if err != nil {
		return nil, err
	}
	if row == nil || (owner != "admin" && str(row, "owner_id") != owner) {
		return nil, fail(404, "播放资源不存在")
	}
	return row, nil
}

func (s *Service) insertTranscode(ctx context.Context, id, songID, owner, purpose string, target map[string]any, c *compute.Connection) error {
	return s.transaction(ctx, "TRANSCODE", id, func(tx *sqlx.Tx) error {
		song, err := s.song(ctx, tx, songID)
		if err != nil {
			return err
		}
		state := "STARTING"
		if purpose == "OFFLINE" {
			state = "QUEUED"
		}
		origin := ""
		if c != nil {
			origin = c.Origin
		}
		_, err = s.exec(ctx, tx, "transcode-insert", map[string]any{"id": id, "song_id": songID, "owner_id": owner, "purpose": purpose, "state": state, "fingerprint": playbackFingerprint(song), "node_origin": origin, "input_json": encoded(map[string]any{"reference": id}), "target_json": encoded(target), "now": now()})
		return err
	})
}

func (s *Service) updateTranscode(ctx context.Context, id, state string, result map[string]any, detail string) error {
	return s.transaction(ctx, "TRANSCODE", id, func(tx *sqlx.Tx) error {
		_, err := s.exec(ctx, tx, "transcode-update", map[string]any{"id": id, "state": state, "result": encoded(result), "error": detail, "now": now()})
		return err
	})
}

func direct(url, reason string) map[string]any {
	return map[string]any{"mode": "DIRECT", "status": "READY", "url": url, "reason": reason, "offset": 0}
}

func cacheKey(song, fp string, target, client map[string]any, force, explicit bool) string {
	return hash(encoded(map[string]any{"version": 2, "songId": song, "fingerprint": fp, "target": target, "client": client, "force": force, "explicitCodec": explicit}))
}

// Negotiate keeps device-owned playback leases separate even when an immutable
// cached version can be shared. Owner must be derived from the authenticated TV.
func (s *Service) Negotiate(ctx context.Context, songID, owner string, body map[string]any, directURL, prefix string) (map[string]any, error) {
	audio, err := s.audioStatus(ctx, songID)
	if err != nil {
		return nil, err
	}
	request := copyMap(body)
	requested := str(body, "audioRole")
	if oneOf(requested, "original", "backing") {
		key := "originalId"
		if requested == "backing" {
			key = "backingId"
		}
		if selected := str(audio, key); selected != "" {
			request["audio_id"] = selected
		}
		delete(request, "audioRole")
	}
	v, err := s.negotiateMedia(ctx, songID, owner, request, directURL, prefix)
	if err != nil {
		return nil, err
	}
	v["audioRoles"], _ = s.audioStatus(ctx, songID)
	if requested == "backing" && audio["backingResource"] != nil {
		base := "/api/v1/tv/audio-roles/songs/"
		if owner == "admin" {
			base = "/api/v1/media-songs/"
		}
		v["backingUrl"] = base + songID + "/accompaniment/" + str(obj(audio["backingResource"]), "jobId")
	}
	return v, nil
}

func (s *Service) negotiateMedia(ctx context.Context, songID, owner string, body map[string]any, directURL, prefix string) (map[string]any, error) {
	song, err := s.song(ctx, s.db, songID)
	if err != nil {
		return nil, err
	}
	selected, err := target(body, "REALTIME")
	if err != nil {
		return nil, err
	}
	client := obj(body["client"])
	fp := playbackFingerprint(song)
	key := cacheKey(songID, fp, selected, client, boolv(body["force"]), body["codec"] != nil)
	session := uuid()
	cachedTarget := selected
	var cached map[string]any
	if number(selected["start"]) > 0 {
		whole := copyMap(selected)
		whole["start"] = 0.0
		wholeKey := cacheKey(songID, fp, whole, client, boolv(body["force"]), body["codec"] != nil)
		cached, err = s.acquireCache(ctx, wholeKey, session)
		if err != nil {
			return nil, err
		}
		if cached != nil && number(cached["offset"]) != 0 {
			s.releaseCache(ctx, session)
			cached = nil
		}
		if cached != nil {
			key, cachedTarget = wholeKey, whole
		}
	}
	if cached == nil {
		cached, err = s.acquireCache(ctx, key, session)
		if err != nil {
			return nil, err
		}
	}
	if cached != nil {
		if err = s.insertTranscode(ctx, session, songID, owner, "REALTIME", cachedTarget, nil); err != nil {
			s.releaseCache(ctx, session)
			return nil, err
		}
		cached["id"], cached["cacheKey"], cached["cacheHit"], cached["computeReleased"] = session, key, true, true
		if err = s.saveActiveCache(ctx, session, cached); err != nil {
			s.releaseCache(ctx, session)
			return nil, err
		}
		return s.TranscodeView(ctx, session, owner, prefix)
	}
	var version map[string]any
	if !boolv(body["force"]) && body["audio_id"] == nil && body["subtitle_id"] == nil && !boolv(body["burn_subtitle"]) {
		versions, err := s.maps(ctx, s.db, "transcode-versions", map[string]any{"song": songID, "fingerprint": fp})
		if err != nil {
			return nil, err
		}
		for _, row := range versions {
			t := decoded(row["target_json"])
			compatible := contains(client["video"], defaultText(t, "codec", "h264")) && contains(client["audio"], "aac") && contains(client["containers"], "mp4") && number(t["width"]) <= number(selected["width"]) && number(t["height"]) <= number(selected["height"]) && number(t["bitrate"]) <= number(selected["bitrate"]) && versionVideoCompatible(t, decoded(row["result_json"]), client, selected)
			if compatible && regular(filepath.Join(s.cfg.TranscodePath, str(row, "id")+".mp4")) {
				version = map[string]any{"mode": "VERSION", "status": "READY", "url": prefix + "/versions/" + str(row, "id") + "/" + songID, "offset": 0}
				break
			}
		}
	}
	c, err := s.connection(ctx, "")
	if err != nil {
		if version != nil {
			return version, nil
		}
		if boolv(body["force"]) {
			return nil, err
		}
		return direct(directURL, "COMPUTE_UNAVAILABLE"), nil
	}
	id := uuid()
	if err = s.insertTranscode(ctx, id, songID, owner, "REALTIME", selected, c); err != nil {
		return nil, err
	}
	v, err := s.startRealtime(ctx, id, songID, owner, fp, key, directURL, prefix, body, selected, client, c, version)
	if err == nil {
		return v, nil
	}
	_ = s.updateTranscode(ctx, id, "FAILED", map[string]any{}, safeFailure(err))
	var f *failure
	if errors.As(err, &f) && oneOfInt(f.status, 502, 503) && !boolv(body["force"]) && body["audio_id"] == nil && body["subtitle_id"] == nil {
		if version != nil {
			return version, nil
		}
		return direct(directURL, "COMPUTE_UNAVAILABLE"), nil
	}
	return nil, err
}

func (s *Service) startRealtime(ctx context.Context, id, songID, owner, fp, key, directURL, prefix string, body, selected, client map[string]any, c *compute.Connection, version map[string]any) (map[string]any, error) {
	effective := copyMap(client)
	effective["max_width"], effective["max_height"], effective["max_bitrate"], effective["max_fps"] = selected["width"], selected["height"], selected["bitrate"], selected["fps"]
	plan, err := s.call(ctx, c, "/internal/v1/transcoding/negotiate", "POST", map[string]any{"reference": id, "client": effective, "fingerprint": fp, "burn_subtitle": boolv(body["burn_subtitle"])})
	if err != nil {
		return nil, err
	}
	_ = s.observeAudio(ctx, songID, obj(plan["media"]))
	if str(plan, "mode") == "DIRECT" && !boolv(body["force"]) && !boolv(body["burn_subtitle"]) && body["audio_id"] == nil && body["subtitle_id"] == nil && body["codec"] == nil {
		_ = s.updateTranscode(ctx, id, "DIRECT", plan, "")
		v := direct(directURL, "")
		v["media"] = plan["media"]
		return v, nil
	}
	if version != nil && !boolv(body["force"]) {
		_ = s.updateTranscode(ctx, id, "DIRECT", plan, "")
		return version, nil
	}
	if client["hls"] == false {
		return nil, fail(422, "当前终端需要转换，但不支持 HLS；请先生成离线兼容版本")
	}
	if !boolv(body["force"]) {
		mode := str(plan, "mode")
		if mode == "DIRECT" {
			mode = "REMUX"
		}
		if !oneOf(mode, "REMUX", "TRANSCODE", "AUDIO_TRANSCODE") {
			return nil, fail(502, "计算节点协商模式无效")
		}
		selected["mode"] = mode
	}
	for _, item := range array(obj(plan["media"])["streams"]) {
		stream := obj(item)
		if str(stream, "kind") == "audio" && str(selected, "mode") == "REMUX" && !oneOf(str(stream, "codec"), "aac", "mp3", "ac3", "eac3") {
			selected["mode"] = "AUDIO_TRANSCODE"
		}
		if str(stream, "kind") == "video" {
			if str(selected, "mode") != "TRANSCODE" && !oneOf(str(stream, "codec"), "h264", "hevc") {
				selected["mode"] = "TRANSCODE"
			}
			if body["codec"] != nil && str(stream, "codec") != str(selected, "codec") {
				selected["mode"] = "TRANSCODE"
			}
			if str(stream, "hdr") == "DOVI" && !boolv(selected["fmp4"]) {
				selected["mode"] = "TRANSCODE"
			}
		}
	}
	if _, err = s.exec(ctx, s.db, "transcode-plan", map[string]any{"id": id, "target": encoded(selected)}); err != nil {
		return nil, err
	}
	if err = s.assertCapacity(ctx, 1); err != nil {
		return nil, err
	}
	start := map[string]any{"reference": id, "id": id, "fingerprint": fp, "target": selected}
	var execution map[string]any
	for attempt := 0; attempt < 20; attempt++ {
		execution, err = s.call(ctx, c, "/internal/v1/transcoding/executions", "POST", start)
		if err == nil {
			break
		}
		var f *failure
		if !errors.As(err, &f) || f.status != 429 || attempt == 19 {
			return nil, err
		}
		timer := time.NewTimer(250 * time.Millisecond)
		select {
		case <-ctx.Done():
			timer.Stop()
			return nil, fail(503, "播放准备已中断")
		case <-timer.C:
		}
	}
	execution["media"], execution["reasons"], execution["cacheKey"] = plan["media"], plan["reasons"], key
	if err = s.updateTranscode(ctx, id, "RUNNING", execution, ""); err != nil {
		return nil, err
	}
	return s.TranscodeView(ctx, id, owner, prefix)
}

func (s *Service) saveActiveCache(ctx context.Context, id string, result map[string]any) error {
	return s.transaction(ctx, "TRANSCODE", id, func(tx *sqlx.Tx) error {
		saved, err := s.exec(ctx, tx, "transcode-save-active-cache", map[string]any{"id": id, "result": encoded(result), "now": now()})
		if err != nil {
			return err
		}
		count, err := saved.RowsAffected()
		if err != nil {
			return err
		}
		if count != 1 {
			return fail(410, "播放资源已结束")
		}
		return nil
	})
}

func (s *Service) TranscodeView(ctx context.Context, id, owner, prefix string) (map[string]any, error) {
	row, err := s.requireTranscode(ctx, s.db, id, owner)
	if err != nil {
		return nil, err
	}
	song, err := s.song(ctx, s.db, str(row, "song_id"))
	if err != nil {
		return nil, err
	}
	if str(row, "fingerprint") != playbackFingerprint(song) {
		return nil, fail(409, "媒体已变化，请重建播放资源")
	}
	result := decoded(row["result_json"])
	if oneOf(str(row, "state"), "STARTING", "RUNNING") {
		var local map[string]any
		if str(row, "purpose") == "REALTIME" {
			local, err = s.renewCache(ctx, id)
			if err != nil {
				return nil, err
			}
		}
		if local != nil {
			hit := boolv(result["cacheHit"])
			for key, value := range local {
				result[key] = value
			}
			result["id"], result["cacheHit"] = id, hit
			if err = s.saveActiveCache(ctx, id, result); err != nil {
				s.releaseCache(ctx, id)
				return nil, err
			}
		} else {
			if result["cacheId"] != nil {
				return nil, fail(410, "缓存已清理，请重新开始播放")
			}
			c, err := s.connection(ctx, str(row, "node_origin"))
			if err != nil {
				return nil, err
			}
			remote, err := s.call(ctx, c, "/internal/v1/transcoding/executions/"+str(row, "execution_id")+"/lease", "POST", nil)
			if err != nil {
				return nil, err
			}
			for key, value := range remote {
				result[key] = value
			}
		}
		if _, err = s.exec(ctx, s.db, "transcode-renew", map[string]any{"id": id, "now": now()}); err != nil {
			return nil, err
		}
	}
	if oneOf(str(row, "state"), "FAILED", "CANCELLED") {
		result["ready"], result["state"] = false, str(row, "state")
	}
	mode, name := "HLS", "master.m3u8"
	if str(row, "purpose") == "OFFLINE" {
		mode, name = "VERSION", "output.mp4"
	}
	status := str(row, "state")
	if boolv(result["ready"]) {
		status = "READY"
	}
	out := map[string]any{"id": id, "songId": str(row, "song_id"), "mode": mode, "status": status, "state": defaultText(result, "state", str(row, "state")), "offset": number(result["offset"]), "error": defaultText(result, "error", str(row, "error")), "execution": result, "url": prefix + "/" + id + "/artifacts/" + name}
	if boolv(result["subtitle"]) {
		out["subtitleUrl"] = prefix + "/" + id + "/artifacts/subtitle.vtt"
	}
	return out, nil
}

func (s *Service) offlineTranscodes(ctx context.Context) ([]any, error) {
	rows, err := s.maps(ctx, s.db, "transcode-list", nil)
	if err != nil {
		return nil, err
	}
	items := []any{}
	for _, row := range rows {
		if str(row, "purpose") == "OFFLINE" {
			items = append(items, map[string]any{
				"id": str(row, "id"), "songId": str(row, "song_id"),
				"state": str(row, "state"), "error": str(row, "error"),
				"execution": decoded(row["result_json"]),
			})
		}
	}
	return items, nil
}

func (s *Service) Offline(ctx context.Context, body map[string]any) ([]string, error) {
	ids := array(body["songIds"])
	if len(ids) < 1 || len(ids) > 100 {
		return nil, fail(400, "每批请选择 1～100 首歌曲")
	}
	c, err := s.connection(ctx, "")
	if err != nil {
		return nil, err
	}
	selected, err := target(body, "OFFLINE")
	if err != nil {
		return nil, err
	}
	songs := make([]map[string]any, 0, len(ids))
	for _, value := range ids {
		id, ok := value.(string)
		if !ok {
			return nil, fail(400, "歌曲标识无效")
		}
		song, err := s.song(ctx, s.db, id)
		if err != nil {
			return nil, err
		}
		songs = append(songs, song)
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	history, err := s.maps(ctx, s.db, "transcode-list", nil)
	if err != nil {
		return nil, err
	}
	result := make([]string, 0, len(ids))
	for _, song := range songs {
		songID := str(song, "id")
		fp := playbackFingerprint(song)
		chosen := ""
		for _, row := range history {
			if str(row, "song_id") == songID && str(row, "fingerprint") == fp && str(row, "purpose") == "OFFLINE" && str(row, "target_json") == encoded(selected) && str(row, "node_origin") == c.Origin && str(row, "pipeline_version") == "2" && oneOf(str(row, "state"), "QUEUED", "RUNNING", "STARTING", "SUCCEEDED") {
				chosen = str(row, "id")
				break
			}
		}
		if chosen == "" {
			chosen = uuid()
			if err = s.insertTranscode(ctx, chosen, songID, "admin", "OFFLINE", selected, c); err != nil {
				return nil, err
			}
		}
		result = append(result, chosen)
	}
	return result, nil
}

func safeFailure(err error) string {
	var f *failure
	if errors.As(err, &f) {
		return f.detail
	}
	return "媒体处理失败"
}

func (s *Service) CancelTranscode(ctx context.Context, id, owner string) error {
	s.mu.Lock()
	row, err := s.requireTranscode(ctx, s.db, id, owner)
	if err != nil {
		s.mu.Unlock()
		return err
	}
	err = s.updateTranscode(ctx, id, "CANCELLED", decoded(row["result_json"]), "")
	if cancel := s.active[id]; cancel != nil {
		cancel()
	}
	s.mu.Unlock()
	s.releaseCache(ctx, id)
	if err != nil {
		return err
	}
	if str(row, "node_origin") != "" && !boolv(decoded(row["result_json"])["computeReleased"]) {
		if c, e := s.connection(ctx, str(row, "node_origin")); e == nil {
			_, _ = s.call(ctx, c, "/internal/v1/transcoding/executions/"+str(row, "execution_id")+"/cancel", "POST", nil)
		}
	}
	return nil
}

func (s *Service) deleteTranscode(ctx context.Context, id string) error {
	row, err := s.requireTranscode(ctx, s.db, id, "admin")
	if err != nil {
		return err
	}
	if !oneOf(str(row, "state"), "SUCCEEDED", "FAILED", "CANCELLED") {
		return fail(409, "请先取消转换任务")
	}
	if now()-integer(row["renewed_at"]) < 120000 {
		return fail(409, "版本正在使用，请稍后删除")
	}
	if err = os.Remove(filepath.Join(s.cfg.TranscodePath, id+".mp4")); err != nil && !errors.Is(err, os.ErrNotExist) {
		return fail(500, "版本删除失败")
	}
	return s.transaction(ctx, "TRANSCODE", id, func(tx *sqlx.Tx) error {
		_, err := s.exec(ctx, tx, "transcode-delete", map[string]any{"id": id})
		return err
	})
}

func (s *Service) Version(ctx context.Context, w http.ResponseWriter, r *http.Request, id, songID string) error {
	row, err := s.requireTranscode(ctx, s.db, id, "admin")
	if err != nil {
		return err
	}
	song, err := s.song(ctx, s.db, songID)
	if err != nil {
		return err
	}
	if str(row, "song_id") != songID || str(row, "purpose") != "OFFLINE" || str(row, "state") != "SUCCEEDED" || str(row, "fingerprint") != playbackFingerprint(song) {
		return fail(404, "兼容版本已失效")
	}
	_, err = s.exec(ctx, s.db, "transcode-renew", map[string]any{"id": id, "now": now()})
	if err != nil {
		return err
	}
	return streamFile(w, r, s.cfg.TranscodePath, id+".mp4")
}

func (s *Service) Artifact(ctx context.Context, w http.ResponseWriter, r *http.Request, id, owner, name string) error {
	if !artifactPattern.MatchString(name) {
		return fail(404, "播放资源不存在")
	}
	row, err := s.requireTranscode(ctx, s.db, id, owner)
	if err != nil {
		return err
	}
	if oneOf(str(row, "state"), "CANCELLED", "FAILED") {
		return fail(410, "播放资源已结束")
	}
	song, err := s.song(ctx, s.db, str(row, "song_id"))
	if err != nil {
		return err
	}
	if str(row, "fingerprint") != playbackFingerprint(song) {
		return fail(409, "源媒体已变化")
	}
	_, err = s.exec(ctx, s.db, "transcode-renew", map[string]any{"id": id, "now": now()})
	if err != nil {
		return err
	}
	if str(row, "purpose") == "OFFLINE" && name == "output.mp4" && str(row, "state") == "SUCCEEDED" {
		return s.Version(ctx, w, r, id, str(row, "song_id"))
	}
	served, err := s.serveCache(ctx, w, r, id, name)
	if served || err != nil {
		return err
	}
	if decoded(row["result_json"])["cacheId"] != nil {
		return fail(410, "缓存已清理，请重新开始播放")
	}
	c, err := s.connection(ctx, str(row, "node_origin"))
	if err != nil {
		return err
	}
	return s.proxyArtifact(ctx, w, r, c, "/internal/v1/transcoding/executions/"+str(row, "execution_id")+"/artifacts/"+name)
}

func (s *Service) sourceTranscode(ctx context.Context, w http.ResponseWriter, r *http.Request, id string) error {
	guard := func() (map[string]any, error) {
		c, err := s.connection(ctx, "")
		if err != nil {
			return nil, err
		}
		if subtle.ConstantTimeCompare([]byte(r.Header.Get("Authorization")), []byte("Bearer "+c.Token)) != 1 {
			return nil, fail(401, "内部认证失败")
		}
		row, err := s.requireTranscode(ctx, s.db, id, "admin")
		if err != nil {
			return nil, err
		}
		if !oneOf(str(row, "state"), "STARTING", "RUNNING", "QUEUED") || str(row, "node_origin") != c.Origin {
			return nil, fail(410, "媒体读取授权已结束")
		}
		song, err := s.song(ctx, s.db, str(row, "song_id"))
		if err != nil {
			return nil, err
		}
		if str(row, "fingerprint") != playbackFingerprint(song) {
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

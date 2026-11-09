package execution

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"io"
	"math"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"time"

	"github.com/jmoiron/sqlx"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/compute"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

const cacheOwnerFile = ".singverse-cache-owner"
const cacheOwner = "SingVerse song cache v1\n"
const day = int64(86400000)

func (s *Service) cacheSettings(ctx context.Context) (map[string]any, error) {
	row, err := s.one(ctx, s.db, "cache-settings", nil)
	if err != nil {
		return nil, err
	}
	return map[string]any{"maxBytes": integer(row["max_bytes"]), "policy": str(row, "policy"), "ttlHours": integer(row["ttl_hours"]), "highWatermarkPercent": integer(row["high_watermark_percent"]), "lowWatermarkPercent": integer(row["low_watermark_percent"]), "revision": integer(row["revision"])}, nil
}
func (s *Service) cacheFolder(id string) (string, error) {
	if !idPattern.MatchString(id) {
		return "", fail(404, "缓存不存在")
	}
	return filepath.Join(s.cfg.SongCachePath, id), nil
}
func ownedFolder(path string) bool {
	info, err := os.Lstat(path)
	if err != nil || !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
		return false
	}
	marker := filepath.Join(path, cacheOwnerFile)
	info, err = os.Lstat(marker)
	if err != nil || !info.Mode().IsRegular() || info.Size() != int64(len(cacheOwner)) {
		return false
	}
	b, err := os.ReadFile(marker)
	return err == nil && string(b) == cacheOwner
}
func (s *Service) validCache(row map[string]any) bool {
	if row == nil || str(row, "state") == "DELETING" || str(decoded(row["target_json"]), "mode") == "ORIGINAL" {
		return false
	}
	folder, err := s.cacheFolder(str(row, "id"))
	if err != nil || !ownedFolder(folder) {
		return false
	}
	artifacts := decoded(row["artifacts_json"])
	if len(artifacts) > 20000 || artifacts["master.m3u8"] == nil {
		return false
	}
	for name, value := range artifacts {
		if !artifactPattern.MatchString(name) {
			return false
		}
		info, err := os.Lstat(filepath.Join(folder, name))
		if err != nil || !info.Mode().IsRegular() || info.Size() != integer(obj(value)["size"]) {
			return false
		}
	}
	return true
}
func cacheSnapshot(row map[string]any, hit bool) map[string]any {
	v := decoded(row["execution_json"])
	v["cacheId"], v["cacheHit"], v["state"], v["ready"] = str(row, "id"), hit, "SUCCEEDED", true
	return v
}
func (s *Service) bindCache(ctx context.Context, tx store.DBTX, session string, row map[string]any, hit bool) error {
	if !idPattern.MatchString(session) {
		return fail(400, "播放会话无效")
	}
	lease, err := s.optional(ctx, tx, "cache-lease", map[string]any{"session": session})
	if err != nil {
		return err
	}
	if lease != nil && str(lease, "cache_id") != str(row, "id") {
		return fail(409, "播放会话已绑定其他缓存")
	}
	name := "cache-renew"
	if lease == nil {
		name = "cache-bind"
	}
	if _, err = s.exec(ctx, tx, name, map[string]any{"session": session, "id": str(row, "id"), "now": now()}); err != nil {
		return err
	}
	name = "cache-touch"
	if hit && lease == nil {
		name = "cache-hit"
	}
	_, err = s.exec(ctx, tx, name, map[string]any{"id": str(row, "id"), "now": now()})
	return err
}
func (s *Service) acquireCache(ctx context.Context, key, session string) (map[string]any, error) {
	s.cacheMu.Lock()
	defer s.cacheMu.Unlock()
	row, err := s.optional(ctx, s.db, "cache-by-key", map[string]any{"key": key})
	if err != nil {
		return nil, err
	}
	if !s.validCache(row) {
		return nil, nil
	}
	err = s.transaction(ctx, "", "", func(tx *sqlx.Tx) error { return s.bindCache(ctx, tx, session, row, true) })
	if err != nil {
		return nil, err
	}
	return cacheSnapshot(row, true), nil
}
func (s *Service) renewCache(ctx context.Context, session string) (map[string]any, error) {
	s.cacheMu.Lock()
	defer s.cacheMu.Unlock()
	lease, err := s.optional(ctx, s.db, "cache-lease", map[string]any{"session": session})
	if err != nil || lease == nil {
		return nil, err
	}
	row, err := s.optional(ctx, s.db, "cache-find", map[string]any{"id": str(lease, "cache_id")})
	if err != nil {
		return nil, err
	}
	if !s.validCache(row) {
		return nil, nil
	}
	err = s.transaction(ctx, "", "", func(tx *sqlx.Tx) error { return s.bindCache(ctx, tx, session, row, false) })
	if err != nil {
		return nil, err
	}
	return cacheSnapshot(row, false), nil
}
func (s *Service) releaseCache(ctx context.Context, session string) {
	s.cacheMu.Lock()
	defer s.cacheMu.Unlock()
	_, _ = s.exec(ctx, s.db, "cache-release", map[string]any{"session": session})
}
func (s *Service) serveCache(ctx context.Context, w http.ResponseWriter, r *http.Request, session, name string) (bool, error) {
	s.cacheMu.Lock()
	lease, err := s.optional(ctx, s.db, "cache-lease", map[string]any{"session": session})
	if err != nil || lease == nil {
		s.cacheMu.Unlock()
		return false, err
	}
	row, err := s.optional(ctx, s.db, "cache-find", map[string]any{"id": str(lease, "cache_id")})
	if err != nil {
		s.cacheMu.Unlock()
		return false, err
	}
	if !s.validCache(row) {
		s.cacheMu.Unlock()
		return false, nil
	}
	manifest := decoded(row["artifacts_json"])
	if manifest[name] == nil {
		s.cacheMu.Unlock()
		return true, fail(404, "缓存资源不存在")
	}
	id := str(row, "id")
	folder, _ := s.cacheFolder(id)
	s.cacheReaders[id]++
	s.cacheMu.Unlock()
	defer func() { s.cacheMu.Lock(); s.cacheReaders[id]--; s.cacheMu.Unlock() }()
	if _, err = s.exec(ctx, s.db, "cache-renew", map[string]any{"session": session, "now": now()}); err != nil {
		return true, err
	}
	return true, streamFile(w, r, folder, name)
}
func (s *Service) usage(ctx context.Context) (int64, error) {
	totals, err := s.one(ctx, s.db, "cache-totals", nil)
	if err != nil {
		return 0, err
	}
	return integer(totals["media_bytes"]) + integer(totals["entry_count"])*int64(len(cacheOwner)) + s.orphanCacheBytes.Load(), nil
}
func (s *Service) reserved() int64 {
	var total int64
	for _, n := range s.cacheReservations {
		total += n
	}
	return total
}
func (s *Service) assertCapacity(ctx context.Context, needed int64) error {
	s.cacheMu.Lock()
	settings, err := s.cacheSettings(ctx)
	if err != nil {
		s.cacheMu.Unlock()
		return err
	}
	usage, err := s.usage(ctx)
	reserved := s.reserved()
	s.cacheMu.Unlock()
	if err != nil {
		return err
	}
	free, err := availableBytes(s.cfg.SongCachePath)
	if err != nil {
		return err
	}
	if needed > integer(settings["maxBytes"])-usage-reserved || free-needed < s.cfg.SongCacheMinimumFreeBytes {
		if str(settings, "policy") != "MANUAL" {
			_, _ = s.submitCleanup(ctx, "POLICY", "", false)
		}
		return fail(507, "歌曲缓存容量或磁盘空间不足")
	}
	return nil
}
func (s *Service) captureCache(ctx context.Context, row map[string]any, key string, c *compute.Connection, execution map[string]any) (map[string]any, error) {
	session := str(row, "id")
	s.cacheMu.Lock()
	if s.cacheCaptures[session] {
		s.cacheMu.Unlock()
		return nil, fail(409, "缓存正在传输")
	}
	s.cacheCaptures[session] = true
	s.cacheMu.Unlock()
	defer func() { s.cacheMu.Lock(); delete(s.cacheCaptures, session); s.cacheMu.Unlock() }()
	if cached, err := s.acquireCache(ctx, key, session); err != nil || cached != nil {
		return cached, err
	}
	remote := str(row, "execution_id")
	if !idPattern.MatchString(remote) {
		return nil, fail(400, "缓存执行引用无效")
	}
	exported, err := s.call(ctx, c, "/internal/v1/transcoding/executions/"+remote+"/export", "GET", nil)
	if err != nil {
		return nil, err
	}
	artifacts := obj(exported["artifacts"])
	if len(artifacts) > 20000 || artifacts["master.m3u8"] == nil {
		return nil, fail(502, "计算节点缓存清单无效")
	}
	total := int64(0)
	for name, value := range artifacts {
		metadata := obj(value)
		size := integer(metadata["size"])
		if !artifactPattern.MatchString(name) || name == "output.mp4" || size < 0 || number(metadata["size"]) != float64(size) || !hex64Pattern.MatchString(str(metadata, "sha256")) || size > math.MaxInt64-total {
			return nil, fail(502, "计算节点缓存文件清单无效")
		}
		total += size
	}
	if total <= 0 {
		return nil, fail(502, "计算节点缓存产物为空")
	}
	if err = s.assertCapacity(ctx, total+int64(len(cacheOwner))); err != nil {
		return nil, err
	}
	id := uuid()
	stagingName := ".partial-" + id
	staging := filepath.Join(s.cfg.SongCachePath, stagingName)
	if err = os.Mkdir(staging, 0700); err != nil {
		return nil, err
	}
	if err = os.WriteFile(filepath.Join(staging, cacheOwnerFile), []byte(cacheOwner), 0600); err != nil {
		return nil, err
	}
	if err = s.reserveCache(ctx, staging, total+int64(len(cacheOwner))); err != nil {
		if ownedFolder(staging) {
			_ = os.RemoveAll(staging)
		}
		return nil, err
	}
	defer func() {
		s.cacheMu.Lock()
		delete(s.cacheReservations, staging)
		s.cacheMu.Unlock()
		if ownedFolder(staging) {
			_ = os.RemoveAll(staging)
		}
	}()
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	s.mu.Lock()
	s.active[session] = cancel
	s.mu.Unlock()
	defer func() { s.mu.Lock(); delete(s.active, session); s.mu.Unlock() }()
	guard := func() error {
		if err := ctx.Err(); err != nil {
			return err
		}
		latest, err := s.requireTranscode(ctx, s.db, session, "admin")
		if err != nil {
			return err
		}
		if !oneOf(str(latest, "state"), "STARTING", "RUNNING") {
			return fail(410, "播放会话已结束")
		}
		return nil
	}
	for name, value := range artifacts {
		if err = guard(); err != nil {
			return nil, err
		}
		if err = s.downloadCacheFile(ctx, c, remote, name, obj(value), staging, guard); err != nil {
			return nil, err
		}
	}
	if err = validatePlaylists(staging, artifacts); err != nil {
		return nil, err
	}
	s.cacheMu.Lock()
	defer s.cacheMu.Unlock()
	if err = guard(); err != nil {
		return nil, err
	}
	prior, err := s.optional(ctx, s.db, "cache-by-key", map[string]any{"key": key})
	if err != nil {
		return nil, err
	}
	if s.validCache(prior) {
		if err = s.transaction(ctx, "", "", func(tx *sqlx.Tx) error { return s.bindCache(ctx, tx, session, prior, false) }); err != nil {
			return nil, err
		}
		return cacheSnapshot(prior, false), nil
	}
	if prior != nil {
		return nil, fail(409, "旧缓存需要清理，继续使用当前播放资源")
	}
	folder, _ := s.cacheFolder(id)
	if err = os.Rename(staging, folder); err != nil {
		return nil, fail(500, "歌曲缓存原子发布失败")
	}
	snapshot := copyMap(execution)
	snapshot["cacheId"], snapshot["cacheHit"], snapshot["state"], snapshot["ready"] = id, false, "SUCCEEDED", true
	actualTarget := decoded(row["target_json"])
	if execution["effectiveMode"] != nil {
		actualTarget["mode"] = execution["effectiveMode"]
	}
	mediaType := "AUDIO"
	for _, track := range array(execution["trackMapping"]) {
		if str(obj(track), "kind") == "video" {
			mediaType = "VIDEO"
		}
	}
	saved := map[string]any{"id": id, "cache_key": key, "song_id": str(row, "song_id"), "execution_json": encoded(snapshot), "target_json": encoded(actualTarget), "artifacts_json": encoded(artifacts), "media_type": mediaType, "bytes": total, "created_at": now()}
	err = s.transaction(ctx, "", "", func(tx *sqlx.Tx) error {
		if _, err := s.exec(ctx, tx, "cache-insert", saved); err != nil {
			return err
		}
		return s.bindCache(ctx, tx, session, saved, false)
	})
	if err != nil {
		if ownedFolder(folder) {
			_ = os.RemoveAll(folder)
		}
		return nil, err
	}
	return snapshot, nil
}
func (s *Service) downloadCacheFile(ctx context.Context, c *compute.Connection, remote, name string, metadata map[string]any, staging string, guard func() error) error {
	response, err := s.artifactResponse(ctx, c, "/internal/v1/transcoding/executions/"+remote+"/artifacts/"+name, "")
	if err != nil {
		return err
	}
	defer response.Body.Close()
	expected := integer(metadata["size"])
	if response.StatusCode != 200 || response.ContentLength != expected {
		return fail(502, "缓存文件长度与清单不符")
	}
	dir, err := os.OpenRoot(staging)
	if err != nil {
		return err
	}
	defer dir.Close()
	file, err := dir.OpenFile(name, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
	if err != nil {
		return err
	}
	defer file.Close()
	digest := sha256.New()
	received := int64(0)
	buffer := make([]byte, 65536)
	for {
		if err = guard(); err != nil {
			return err
		}
		n, readErr := response.Body.Read(buffer)
		if n > 0 {
			received += int64(n)
			if received > expected {
				return fail(502, "缓存文件超过清单长度")
			}
			if _, err = file.Write(buffer[:n]); err != nil {
				return err
			}
			_, _ = digest.Write(buffer[:n])
			if received%(2<<20) < 65536 {
				free, err := availableBytes(s.cfg.SongCachePath)
				if err != nil || free < s.cfg.SongCacheMinimumFreeBytes {
					return fail(507, "缓存磁盘剩余空间不足")
				}
			}
		}
		if readErr == io.EOF {
			break
		}
		if readErr != nil {
			return fail(502, "缓存文件下载中断")
		}
	}
	if received != expected || hex.EncodeToString(digest.Sum(nil)) != str(metadata, "sha256") {
		return fail(502, "缓存文件摘要或长度校验失败")
	}
	return file.Sync()
}

var playlistURI = regexp.MustCompile(`URI="([^"]+)"`)

func validatePlaylists(staging string, artifacts map[string]any) error {
	playlists, segments := 0, false
	for name := range artifacts {
		if strings.HasSuffix(name, ".ts") || strings.HasSuffix(name, ".m4s") {
			segments = true
		}
		if !strings.HasSuffix(name, ".m3u8") {
			continue
		}
		playlists++
		info, err := os.Stat(filepath.Join(staging, name))
		if err != nil || info.Size() > 4<<20 {
			return fail(502, "缓存播放列表过大或无法读取")
		}
		b, err := os.ReadFile(filepath.Join(staging, name))
		if err != nil {
			return err
		}
		content := string(b)
		if !strings.HasPrefix(content, "#EXTM3U") || (name != "master.m3u8" && !strings.Contains(content, "#EXT-X-ENDLIST")) {
			return fail(502, "缓存播放列表尚未完整")
		}
		refs := []string{}
		for _, line := range strings.Split(content, "\n") {
			line = strings.TrimSpace(line)
			if line != "" && !strings.HasPrefix(line, "#") {
				refs = append(refs, line)
			}
		}
		for _, match := range playlistURI.FindAllStringSubmatch(content, -1) {
			refs = append(refs, match[1])
		}
		if len(refs) == 0 {
			return fail(502, "缓存播放列表缺少分片引用")
		}
		for _, ref := range refs {
			if artifacts[ref] == nil || !artifactPattern.MatchString(ref) {
				return fail(502, "缓存播放列表包含清单外引用")
			}
		}
	}
	if playlists < 2 || !segments {
		return fail(502, "缓存音视频分片不完整")
	}
	return nil
}

func score(row map[string]any, at int64) map[string]any {
	age := func(v any) float64 {
		if v == nil {
			return math.Inf(1)
		}
		return float64(max(int64(0), at-integer(v)))
	}
	h := bounded(number(row["heat"]), 0, 1) * math.Pow(2, -age(row["heat_updated_at"])/(14*float64(day)))
	r := math.Pow(2, -age(row["last_played_at"])/(7*float64(day)))
	f := min(1, math.Log1p(max(0, number(row["plays_7d"])))/math.Log(21))
	base := bounded(.4*h+.4*r+.2*f, 0, 1)
	bonus := .15 * bounded((3*float64(day)-age(row["last_played_at"]))/(2*float64(day)), 0, 1)
	priority := bounded(base+(1-base)*bonus, 0, 1)
	reference := row["last_played_at"]
	if reference == nil {
		reference = row["first_cached_at"]
		if reference == nil {
			reference = row["created_at"]
		}
	}
	elapsed := age(reference)
	lifecycle := "EVICT"
	if row["last_played_at"] != nil && elapsed < 7*float64(day) && integer(row["plays_7d"]) >= 3 {
		lifecycle = "HOT"
	} else if elapsed < 30*float64(day) {
		lifecycle = "WARM"
	} else if elapsed < 60*float64(day) {
		lifecycle = "COLD"
	}
	return map[string]any{"H": h, "R": r, "F": f, "score": base, "priority": priority, "lifecycle": lifecycle}
}
func rankCandidates(rows []map[string]any, at int64) {
	order := map[string]int{"EVICT": 0, "COLD": 1, "WARM": 2, "HOT": 3}
	sort.SliceStable(rows, func(i, j int) bool {
		a, b := score(rows[i], at), score(rows[j], at)
		if order[str(a, "lifecycle")] != order[str(b, "lifecycle")] {
			return order[str(a, "lifecycle")] < order[str(b, "lifecycle")]
		}
		if number(a["priority"]) != number(b["priority"]) {
			return number(a["priority"]) < number(b["priority"])
		}
		return integer(rows[i]["last_access_at"]) < integer(rows[j]["last_access_at"])
	})
}
func timestamp(v any) any {
	if v == nil {
		return nil
	}
	return time.UnixMilli(integer(v)).UTC().Format(time.RFC3339Nano)
}

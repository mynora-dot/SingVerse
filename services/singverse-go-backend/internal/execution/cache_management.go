package execution

import (
	"context"
	"encoding/json"
	"errors"
	"math"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/jmoiron/sqlx"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

func intInput(v any) (int64, bool) {
	switch n := v.(type) {
	case json.Number:
		i, err := n.Int64()
		return i, err == nil
	case int:
		return int64(n), true
	case int64:
		return n, true
	case float64:
		if math.IsNaN(n) || math.IsInf(n, 0) || math.Trunc(n) != n || n >= math.MaxInt64 || n < math.MinInt64 {
			return 0, false
		}
		return int64(n), true
	default:
		return 0, false
	}
}

func (s *Service) saveCacheSettings(ctx context.Context, body map[string]any) (map[string]any, error) {
	for key := range body {
		if !oneOf(key, "maxBytes", "policy", "ttlHours", "revision", "highWatermarkPercent", "lowWatermarkPercent") {
			return nil, fail(400, "未知缓存设置")
		}
	}
	maximum, ok1 := intInput(body["maxBytes"])
	ttl, ok2 := intInput(body["ttlHours"])
	revision, ok3 := intInput(body["revision"])
	if !ok1 || !ok2 || !ok3 || maximum < 64<<20 || maximum > 1<<50 || ttl < 1 || ttl > 8760 || revision < 0 || !oneOf(str(body, "policy"), "HYBRID", "LRU", "LFU", "TTL", "MANUAL") {
		return nil, fail(400, "缓存容量、保留时间或清理策略无效")
	}
	current, err := s.cacheSettings(ctx)
	if err != nil {
		return nil, err
	}
	high, low := integer(current["highWatermarkPercent"]), integer(current["lowWatermarkPercent"])
	if body["highWatermarkPercent"] != nil {
		var ok bool
		high, ok = intInput(body["highWatermarkPercent"])
		if !ok {
			return nil, fail(400, "缓存水位无效")
		}
	}
	if body["lowWatermarkPercent"] != nil {
		var ok bool
		low, ok = intInput(body["lowWatermarkPercent"])
		if !ok {
			return nil, fail(400, "缓存水位无效")
		}
	}
	if low < 1 || low >= high || high > 100 {
		return nil, fail(400, "水位必须为 1～100 的整数，低水位小于高水位")
	}
	policy := "HYBRID"
	if str(body, "policy") == "MANUAL" {
		policy = "MANUAL"
	}
	result, err := s.exec(ctx, s.db, "cache-save-settings", map[string]any{"maxBytes": maximum, "policy": policy, "ttlHours": ttl, "revision": revision, "highWatermarkPercent": high, "lowWatermarkPercent": low})
	if err != nil {
		return nil, err
	}
	count, err := result.RowsAffected()
	if err != nil {
		return nil, err
	}
	if count != 1 {
		return nil, fail(409, "缓存设置已变化，请重新读取")
	}
	return s.cacheSettings(ctx)
}

func (s *Service) cacheProtections(ctx context.Context, tx store.DBTX, row map[string]any, refresh bool) ([]string, error) {
	reasons := []string{}
	if str(row, "state") == "DELETING" {
		reasons = append(reasons, "DELETING")
	}
	song := str(row, "song_id")
	playing := s.cacheReaders[str(row, "id")] > 0 || integer(row["playing_count"]) > 0 || integer(row["lease_count"]) > 0
	if refresh {
		for _, name := range []string{"cache-playing", "cache-active-song"} {
			value, err := s.one(ctx, tx, name, map[string]any{"song": song, "now": now(), "since": now() - 120000})
			if err != nil {
				return nil, err
			}
			for _, n := range value {
				if integer(n) > 0 {
					playing = true
				}
			}
		}
	}
	if playing {
		reasons = append(reasons, "PLAYING")
	}
	if integer(row["pinned"]) != 0 {
		reasons = append(reasons, "PINNED")
	}
	if row["last_played_at"] != nil && now()-integer(row["last_played_at"]) < day {
		reasons = append(reasons, "RECENT_PLAY")
	}
	for _, value := range s.prefetchSongs {
		if value == song {
			reasons = append(reasons, "PREFETCH")
			break
		}
	}
	for id := range s.cacheCaptures {
		active, err := s.optional(ctx, tx, "transcode-find", map[string]any{"id": id})
		if err != nil {
			return nil, err
		}
		if str(active, "song_id") == song {
			reasons = append(reasons, "DOWNLOADING")
			break
		}
	}
	return reasons, nil
}

func titleArtist(song map[string]any) (string, string) {
	result := obj(song["result"])
	title := str(result, "title")
	artists := array(result["artists"])
	metadata := obj(song["metadata"])
	track := obj(metadata["track"])
	if !boolv(metadata["stale"]) {
		if contains(metadata["fields"], "title") {
			title = str(track, "title")
		}
		if contains(metadata["fields"], "artists") {
			artists = array(track["artists"])
		}
	}
	names := make([]string, 0, len(artists))
	for _, name := range artists {
		if text, ok := name.(string); ok {
			names = append(names, text)
		}
	}
	return title, strings.Join(names, " / ")
}

func (s *Service) cacheList(ctx context.Context, page, size int) (map[string]any, error) {
	if page < 0 || page > 1000000 || size < 1 || size > 100 {
		return nil, fail(400, "缓存分页参数无效")
	}
	settings, err := s.cacheSettings(ctx)
	if err != nil {
		return nil, err
	}
	totals, err := s.one(ctx, s.db, "cache-totals", nil)
	if err != nil {
		return nil, err
	}
	rows, err := s.maps(ctx, s.db, "cache-page", map[string]any{"size": size, "offset": int64(page) * int64(size), "since": now() - 7*day, "now": now()})
	if err != nil {
		return nil, err
	}
	ready, err := s.one(ctx, s.db, "cache-statistics-ready", nil)
	if err != nil {
		return nil, err
	}
	usage, err := s.usage(ctx)
	if err != nil {
		return nil, err
	}
	s.cacheMu.Lock()
	reserved := s.reserved()
	s.cacheMu.Unlock()
	items := []any{}
	for _, row := range rows {
		s.cacheMu.Lock()
		protections, e := s.cacheProtections(ctx, s.db, row, false)
		s.cacheMu.Unlock()
		if e != nil {
			return nil, e
		}
		item := map[string]any{"id": str(row, "id"), "songId": str(row, "song_id"), "mediaType": str(row, "media_type"), "bytes": integer(row["bytes"]), "residueBytes": integer(row["residue_bytes"]), "createdAt": timestamp(row["created_at"]), "lastAccessAt": timestamp(row["last_access_at"]), "hits": integer(row["hits"]), "leased": false, "pinned": integer(row["pinned"]) != 0, "pinRevision": integer(row["pin_revision"]), "state": str(row, "state"), "playCount7d": integer(row["plays_7d"]), "lastPlayedAt": timestamp(row["last_played_at"]), "protectionReasons": protections, "target": decoded(row["target_json"])}
		for _, reason := range protections {
			if oneOf(reason, "PLAYING", "DOWNLOADING", "PREFETCH") {
				item["leased"] = true
			}
		}
		for key, value := range score(row, now()) {
			item[key] = value
		}
		target := decoded(row["target_json"])
		if str(target, "mode") == "ORIGINAL" {
			item["cachedBytes"], item["sourceBytes"], item["complete"] = integer(row["bytes"]), integer(target["sourceBytes"]), integer(row["bytes"]) == integer(target["sourceBytes"])
		}
		if song, e := s.library.Song(ctx, str(row, "song_id")); e == nil {
			item["title"], item["artist"] = titleArtist(song)
		}
		items = append(items, item)
	}
	return map[string]any{"usageBytes": usage, "entryCount": integer(totals["entry_count"]), "page": page, "size": size, "totalElements": integer(totals["entry_count"]), "committedBytes": usage + reserved, "reservedBytes": reserved, "usageReady": true, "statisticsReady": integer(ready["ready"]) == 1, "cleanupGeneration": s.cacheGeneration.Load(), "settings": settings, "items": items}, nil
}

func (s *Service) pins(ctx context.Context, page, size int) (map[string]any, error) {
	if page < 0 || page > 1000000 || size < 1 || size > 100 {
		return nil, fail(400, "固定缓存分页参数无效")
	}
	count, err := s.one(ctx, s.db, "cache-pin-count", nil)
	if err != nil {
		return nil, err
	}
	rows, err := s.maps(ctx, s.db, "cache-pins", map[string]any{"size": size, "offset": int64(page) * int64(size)})
	if err != nil {
		return nil, err
	}
	var total int64
	for _, v := range count {
		total = integer(v)
	}
	items := []any{}
	for _, row := range rows {
		item := map[string]any{"songId": str(row, "song_id"), "pinned": true, "revision": integer(row["pin_revision"]), "hasCache": integer(row["cache_count"]) > 0}
		if song, e := s.library.Song(ctx, str(row, "song_id")); e == nil {
			item["title"], item["artist"] = titleArtist(song)
		}
		items = append(items, item)
	}
	return map[string]any{"page": page, "size": size, "totalElements": total, "items": items}, nil
}

func (s *Service) pin(ctx context.Context, song string, body map[string]any) (map[string]any, error) {
	s.cacheMu.Lock()
	defer s.cacheMu.Unlock()
	revision, ok := intInput(body["revision"])
	pinned, valid := body["pinned"].(bool)
	if len(body) != 2 || !ok || !valid || revision < 0 || song == "" || len(song) > 64 {
		return nil, fail(400, "固定缓存参数无效")
	}
	err := s.transaction(ctx, "", "", func(tx *sqlx.Tx) error {
		if _, err := s.exec(ctx, tx, "cache-ensure-profile", map[string]any{"song": song}); err != nil {
			return err
		}
		if _, err := s.exec(ctx, tx, "cache-lock-profile", map[string]any{"song": song}); err != nil {
			return err
		}
		if pinned {
			value, err := s.one(ctx, tx, "cache-deleting-for-song", map[string]any{"song": song})
			if err != nil {
				return err
			}
			for _, n := range value {
				if integer(n) > 0 {
					return fail(409, "该歌曲已有缓存正在删除，请待清理完成后重试固定")
				}
			}
		}
		value := 0
		if pinned {
			value = 1
		}
		result, err := s.exec(ctx, tx, "cache-pin", map[string]any{"song": song, "pinned": value, "revision": revision})
		if err != nil {
			return err
		}
		count, err := result.RowsAffected()
		if err != nil {
			return err
		}
		if count != 1 {
			return fail(409, "固定状态已变化，请重新读取")
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	return map[string]any{"songId": song, "pinned": pinned, "revision": revision + 1}, nil
}

func (s *Service) deleteCacheEntry(ctx context.Context, row map[string]any, confirm bool) (int64, error) {
	id, song := str(row, "id"), str(row, "song_id")
	s.cacheMu.Lock()
	defer s.cacheMu.Unlock()
	err := s.transaction(ctx, "", "", func(tx *sqlx.Tx) error {
		if _, err := s.exec(ctx, tx, "cache-ensure-profile", map[string]any{"song": song}); err != nil {
			return err
		}
		if _, err := s.exec(ctx, tx, "cache-lock-profile", map[string]any{"song": song}); err != nil {
			return err
		}
		current, err := s.optional(ctx, tx, "cache-find", map[string]any{"id": id})
		if err != nil {
			return err
		}
		if current == nil {
			return fail(404, "缓存不存在")
		}
		profile, err := s.optional(ctx, tx, "cache-profile", map[string]any{"song": song})
		if err != nil {
			return err
		}
		for key, value := range profile {
			current[key] = value
		}
		reasons, err := s.cacheProtections(ctx, tx, current, true)
		if err != nil {
			return err
		}
		for _, reason := range reasons {
			if reason == "DELETING" {
				continue
			}
			if confirm && reason == "RECENT_PLAY" {
				continue
			}
			return fail(409, "缓存正在使用、已固定或近期播放，暂不可删除")
		}
		if str(current, "state") != "DELETING" {
			_, err = s.exec(ctx, tx, "cache-claim-delete", map[string]any{"id": id, "token": uuid()})
		}
		return err
	})
	if err != nil {
		return 0, err
	}
	folder, err := s.cacheFolder(id)
	if err != nil {
		return 0, err
	}
	if _, e := os.Lstat(folder); errors.Is(e, os.ErrNotExist) {
	} else if !ownedFolder(folder) {
		_, _ = s.exec(ctx, s.db, "cache-failed-delete", map[string]any{"id": id, "retry": now() + 60000, "error": "缓存所有权标记无效，索引已保留"})
		return 0, fail(500, "缓存目录所有权校验失败")
	} else if err = os.RemoveAll(folder); err != nil {
		_, _ = s.exec(ctx, s.db, "cache-failed-delete", map[string]any{"id": id, "retry": now() + 60000, "error": "缓存文件删除失败，将自动重试"})
		return 0, err
	}
	err = s.transaction(ctx, "", "", func(tx *sqlx.Tx) error {
		if _, err := s.exec(ctx, tx, "cache-release-cache", map[string]any{"id": id}); err != nil {
			return err
		}
		_, err := s.exec(ctx, tx, "cache-delete", map[string]any{"id": id})
		return err
	})
	return integer(row["bytes"]) + integer(row["residue_bytes"]) + int64(len(cacheOwner)), err
}

func (s *Service) maintainCache(ctx context.Context) {
	s.cacheCensus(ctx)
	deleting, err := s.maps(ctx, s.db, "cache-pending-deletes", map[string]any{"now": now()})
	if err == nil {
		for _, row := range deleting {
			_, _ = s.deleteCacheEntry(ctx, row, false)
		}
	}
	settings, err := s.cacheSettings(ctx)
	if err == nil && str(settings, "policy") != "MANUAL" {
		usage, e := s.usage(ctx)
		if e == nil && usage > integer(settings["maxBytes"])*integer(settings["highWatermarkPercent"])/100 {
			_, _ = s.submitCleanup(ctx, "POLICY", "", false)
		}
	}
	entries, _ := os.ReadDir(s.cfg.SongCachePath)
	for _, entry := range entries {
		name := entry.Name()
		if !strings.HasPrefix(name, ".partial-") {
			continue
		}
		info, e := entry.Info()
		if e != nil || info.ModTime().After(time.Now().Add(-24*time.Hour)) {
			continue
		}
		path := filepath.Join(s.cfg.SongCachePath, name)
		s.cacheMu.Lock()
		_, active := s.cacheReservations[path]
		s.cacheMu.Unlock()
		if !active && ownedFolder(path) {
			_ = os.RemoveAll(path)
		}
	}
	_, _ = s.exec(ctx, s.db, "cache-expire", map[string]any{"since": now() - 120000})
	_, _ = s.exec(ctx, s.db, "cache-prune-jobs", map[string]any{"before": now() - 7*day})
	_ = s.transaction(ctx, "", "", func(tx *sqlx.Tx) error {
		for _, name := range []string{"batch-collect-preview-items", "batch-collect-previews"} {
			if _, err := s.exec(ctx, tx, name, map[string]any{"before": now() - day}); err != nil {
				return err
			}
		}
		return nil
	})
}

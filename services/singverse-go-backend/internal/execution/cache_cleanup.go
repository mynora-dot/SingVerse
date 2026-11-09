package execution

import (
	"context"
	"time"

	"github.com/jmoiron/sqlx"
)

func (s *Service) cacheJob(ctx context.Context, id string) (map[string]any, error) {
	if !idPattern.MatchString(id) {
		return nil, fail(404, "清理任务不存在")
	}
	row, err := s.optional(ctx, s.db, "cache-job", map[string]any{"id": id})
	if err != nil {
		return nil, err
	}
	if row == nil {
		return nil, fail(404, "清理任务不存在")
	}
	return decoded(row["result_json"]), nil
}

func (s *Service) saveCacheJob(ctx context.Context, id, state string, result map[string]any) error {
	result["state"] = state
	return s.transaction(ctx, "CACHE_CLEANUP", id, func(tx *sqlx.Tx) error {
		_, err := s.exec(ctx, tx, "cache-update-job", map[string]any{"id": id, "state": state, "now": now(), "result": encoded(result)})
		return err
	})
}

func (s *Service) submitCleanup(ctx context.Context, mode, entry string, confirm bool) (map[string]any, error) {
	if s.ctx.Err() != nil {
		return nil, fail(503, "缓存服务正在停止")
	}
	if !oneOf(mode, "POLICY", "CLEAR", "DELETE") || (mode == "DELETE" && !idPattern.MatchString(entry)) || (mode != "DELETE" && (entry != "" || confirm)) {
		return nil, fail(400, "清理目标或模式无效")
	}
	s.cacheMu.Lock()
	defer s.cacheMu.Unlock()
	if s.cleanupID != "" {
		if s.cleanupMode == mode && s.cleanupEntry == entry && s.cleanupConfirm == confirm {
			return s.cacheJob(ctx, s.cleanupID)
		}
		return nil, fail(409, "已有缓存清理任务正在执行")
	}
	if mode == "DELETE" {
		row, err := s.optional(ctx, s.db, "cache-find", map[string]any{"id": entry})
		if err != nil {
			return nil, err
		}
		if row == nil {
			return nil, fail(404, "缓存不存在")
		}
	}
	id := uuid()
	result := map[string]any{"id": id, "mode": mode, "state": "QUEUED", "removed": 0, "skipped": 0, "failed": 0, "freedBytes": 0, "usageBeforeBytes": 0, "usageAfterBytes": 0, "targetBytes": 0, "targetReached": false, "spaceInsufficient": false, "shortfallBytes": 0, "skippedByReason": map[string]any{}, "errors": []any{}}
	err := s.transaction(ctx, "CACHE_CLEANUP", id, func(tx *sqlx.Tx) error {
		_, err := s.exec(ctx, tx, "cache-insert-job", map[string]any{"id": id, "mode": mode, "state": "QUEUED", "now": now(), "result": encoded(result)})
		return err
	})
	if err != nil {
		return nil, err
	}
	jobCtx, cancel := context.WithCancel(s.ctx)
	s.cleanupID, s.cleanupMode, s.cleanupEntry, s.cleanupConfirm, s.cleanupCancel = id, mode, entry, confirm, cancel
	s.wg.Add(1)
	go func() {
		defer s.wg.Done()
		s.runCleanup(jobCtx, id, mode, entry, confirm, copyMap(result))
		cancel()
	}()
	return result, nil
}

func (s *Service) cancelCacheJob(ctx context.Context, id string) (map[string]any, error) {
	s.cacheMu.Lock()
	if s.cleanupID == id && s.cleanupCancel != nil {
		s.cleanupCancel()
	}
	s.cacheMu.Unlock()
	v, err := s.cacheJob(ctx, id)
	if err == nil && oneOf(str(v, "state"), "QUEUED", "RUNNING") {
		v["state"] = "CANCELLING"
	}
	return v, err
}

func (s *Service) runCleanup(ctx context.Context, id, mode, entry string, confirm bool, result map[string]any) {
	defer func() {
		s.cacheMu.Lock()
		if s.cleanupID == id {
			s.cleanupID = ""
			s.cleanupCancel = nil
		}
		s.cacheGeneration.Add(1)
		s.cacheMu.Unlock()
	}()
	background := context.WithoutCancel(ctx)
	before, _ := s.usage(background)
	result["usageBeforeBytes"] = before
	_ = s.saveCacheJob(background, id, "RUNNING", result)
	settings, err := s.cacheSettings(background)
	if err != nil {
		_ = s.saveCacheJob(background, id, "FAILED", result)
		return
	}
	target := integer(settings["maxBytes"]) * integer(settings["lowWatermarkPercent"]) / 100
	result["targetBytes"] = target
	skipped := func(reason string) {
		result["skipped"] = integer(result["skipped"]) + 1
		counts := obj(result["skippedByReason"])
		counts[reason] = integer(counts[reason]) + 1
		result["skippedByReason"] = counts
	}
	ready, err := s.one(background, s.db, "cache-statistics-ready", nil)
	if err != nil {
		_ = s.saveCacheJob(background, id, "FAILED", result)
		return
	}
	if (integer(ready["ready"]) != 1 && !(mode == "DELETE" && confirm)) || (mode == "POLICY" && str(settings, "policy") == "MANUAL") {
		reason := "STATISTICS_NOT_READY"
		if integer(ready["ready"]) == 1 {
			reason = "MANUAL"
		}
		skipped(reason)
		_ = s.saveCacheJob(background, id, "BLOCKED", result)
		return
	}
	rows, err := s.maps(background, s.db, "cache-candidates", map[string]any{"since": now() - 7*day, "now": now()})
	if err != nil {
		_ = s.saveCacheJob(background, id, "FAILED", result)
		return
	}
	if mode == "DELETE" {
		row, e := s.optional(background, s.db, "cache-find", map[string]any{"id": entry})
		if e != nil {
			_ = s.saveCacheJob(background, id, "FAILED", result)
			return
		}
		rows = nil
		if row != nil {
			profile, e := s.optional(background, s.db, "cache-profile", map[string]any{"song": str(row, "song_id")})
			if e == nil {
				for key, value := range profile {
					row[key] = value
				}
			}
			rows = []map[string]any{row}
		}
	}
	rankCandidates(rows, now())
	for _, row := range rows {
		if ctx.Err() != nil {
			break
		}
		usage, _ := s.usage(background)
		if mode == "POLICY" && usage <= target {
			break
		}
		s.cacheMu.Lock()
		reasons, e := s.cacheProtections(background, s.db, row, true)
		s.cacheMu.Unlock()
		if e != nil {
			continue
		}
		if confirm {
			filtered := reasons[:0]
			for _, r := range reasons {
				if r != "RECENT_PLAY" {
					filtered = append(filtered, r)
				}
			}
			reasons = filtered
		}
		if len(reasons) > 0 {
			skipped(reasons[0])
			continue
		}
		freed, e := s.deleteCacheEntry(background, row, confirm)
		if e != nil {
			result["failed"] = integer(result["failed"]) + 1
			errs := array(result["errors"])
			if len(errs) < 20 {
				result["errors"] = append(errs, map[string]any{"id": str(row, "id"), "message": "删除失败，索引已保留以供重试"})
			}
		} else {
			result["removed"] = integer(result["removed"]) + 1
			result["freedBytes"] = integer(result["freedBytes"]) + freed
		}
		_ = s.saveCacheJob(background, id, "RUNNING", result)
	}
	after, _ := s.usage(background)
	result["usageAfterBytes"] = after
	reached := integer(result["skipped"]) == 0 && integer(result["failed"]) == 0
	if mode == "POLICY" {
		reached = after <= target
	}
	result["targetReached"] = reached
	shortfall := max(int64(0), after-integer(settings["maxBytes"]))
	free, _ := availableBytes(s.cfg.SongCachePath)
	shortfall = max(shortfall, s.cfg.SongCacheMinimumFreeBytes-free)
	result["shortfallBytes"], result["spaceInsufficient"] = shortfall, shortfall > 0
	state := "SUCCEEDED"
	if ctx.Err() != nil {
		state = "CANCELLED"
	} else if !reached || integer(result["failed"]) > 0 {
		state = "PARTIAL"
		if integer(result["removed"]) == 0 && integer(result["failed"]) == 0 {
			state = "BLOCKED"
		}
	}
	_ = s.saveCacheJob(background, id, state, result)
}

func (s *Service) awaitCleanup(ctx context.Context, id string) (map[string]any, error) {
	ticker := time.NewTicker(25 * time.Millisecond)
	defer ticker.Stop()
	for {
		job, err := s.cacheJob(ctx, id)
		if err != nil {
			return nil, err
		}
		if !oneOf(str(job, "state"), "QUEUED", "RUNNING", "CANCELLING") {
			return job, nil
		}
		select {
		case <-ctx.Done():
			return nil, fail(503, "缓存清理等待已中断，任务将在后台继续")
		case <-ticker.C:
		}
	}
}

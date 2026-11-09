package execution

import (
	"context"
	"os"
	"path/filepath"
	"strings"
)

func (s *Service) recoverCache(ctx context.Context) {
	jobs, err := s.maps(ctx, s.db, "cache-interrupted-jobs", nil)
	if err == nil {
		for _, job := range jobs {
			result := decoded(job["result_json"])
			result["interrupted"] = true
			_ = s.saveCacheJob(ctx, str(job, "id"), "FAILED", result)
		}
	}
	s.cacheCensus(ctx)
}
func (s *Service) cacheCensus(ctx context.Context) {
	s.cacheMu.Lock()
	defer s.cacheMu.Unlock()
	rows, err := s.maps(ctx, s.db, "cache-paths", nil)
	if err != nil {
		return
	}
	indexed := map[string]bool{}
	for _, row := range rows {
		id := str(row, "id")
		indexed[id] = true
		current, e := s.optional(ctx, s.db, "cache-find", map[string]any{"id": id})
		if e == nil && current != nil {
			s.repairOriginal(ctx, current)
		}
	}
	entries, err := os.ReadDir(s.cfg.SongCachePath)
	if err != nil {
		return
	}
	var orphan int64
	for _, entry := range entries {
		if ctx.Err() != nil {
			return
		}
		name := entry.Name()
		if indexed[name] || (!idPattern.MatchString(name) && !(strings.HasPrefix(name, ".partial-") && idPattern.MatchString(strings.TrimPrefix(name, ".partial-")))) {
			continue
		}
		folder := filepath.Join(s.cfg.SongCachePath, name)
		if _, active := s.cacheReservations[folder]; active || !ownedFolder(folder) {
			continue
		}
		_ = filepath.WalkDir(folder, func(path string, d os.DirEntry, e error) error {
			if e != nil {
				return nil
			}
			if d.IsDir() {
				if path != folder {
					return filepath.SkipDir
				}
				return nil
			}
			if info, e := d.Info(); e == nil && info.Mode().IsRegular() {
				orphan += info.Size()
			}
			return nil
		})
	}
	s.orphanCacheBytes.Store(orphan)
}
func (s *Service) reserveCache(ctx context.Context, path string, needed int64) error {
	s.cacheMu.Lock()
	defer s.cacheMu.Unlock()
	settings, err := s.cacheSettings(ctx)
	if err != nil {
		return err
	}
	usage, err := s.usage(ctx)
	if err != nil {
		return err
	}
	free, err := availableBytes(s.cfg.SongCachePath)
	if err != nil {
		return err
	}
	if needed <= 0 || needed > integer(settings["maxBytes"])-usage-s.reserved() || free-needed < s.cfg.SongCacheMinimumFreeBytes {
		return fail(507, "歌曲缓存容量或磁盘空间不足")
	}
	s.cacheReservations[path] = needed
	return nil
}

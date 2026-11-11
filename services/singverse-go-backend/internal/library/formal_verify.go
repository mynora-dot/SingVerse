package library

import (
	"context"
	"path"
	"time"

	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

// The persisted PENDING location is the queue. Restarting a process loses no
// work, and a result only applies while the same binding is still current.
func (s *Service) verifyLocationLoop() {
	defer s.wg.Done()
	ticker := time.NewTicker(2 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-s.ctx.Done():
			return
		case <-ticker.C:
			s.verifyLocations(s.ctx)
		}
	}
}
func (s *Service) verifyLocations(ctx context.Context) {
	rows, e := store.Maps(ctx, s.db, s.q["formal-pending-locations"])
	if e != nil {
		return
	}
	for _, row := range rows {
		id, source, relative := row["id"].(string), row["source_id"].(string), row["relative_path"].(string)
		directory := path.Dir(relative)
		if directory == "." {
			directory = ""
		}
		files, missing, err := s.MediaDirectory(ctx, source, directory)
		if ctx.Err() != nil {
			return
		}
		state := "PRESENT"
		if missing {
			state = "MISSING"
		} else if err != nil {
			state = "UNREACHABLE"
		} else if size, ok := files[relative]; !ok {
			state = "MISSING"
		} else if old, ok := row["file_size"].(int64); ok && size != nil && old != *size {
			state = "MISMATCH"
		}
		_ = s.transaction(ctx, func(tx store.DBTX) error {
			r, e := tx.ExecContext(ctx, s.q["formal-verify-location"], state, id, source, relative)
			if e != nil {
				return e
			}
			n, e := r.RowsAffected()
			if e != nil || n == 0 {
				return e
			}
			if _, e = tx.ExecContext(ctx, s.q["formal-verify-shared"], state, now(), id); e != nil {
				return e
			}
			observation := map[string]any{"verifiedAt": now()}
			if size := files[relative]; size != nil {
				observation["size"] = *size
			}
			if err != nil {
				observation["error"] = "连接或读取目录失败，请检查来源诊断后重试"
			}
			return s.formal.Location(ctx, tx, id, source, relative, "", state, observation)
		})
	}
}

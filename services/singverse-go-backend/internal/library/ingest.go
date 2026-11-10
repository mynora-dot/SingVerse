package library

import (
	"context"
	"database/sql"
	"errors"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

type ingestRecord struct {
	ID         string  `db:"id" json:"id"`
	SourceID   string  `db:"source_id" json:"sourceId"`
	State      string  `db:"state" json:"state"`
	ScanTaskID *string `db:"scan_task_id" json:"scanTaskId"`
	Error      *string `db:"error" json:"error"`
	CreatedAt  string  `db:"created_at" json:"createdAt"`
	UpdatedAt  string  `db:"updated_at" json:"updatedAt"`
}

func (s *Service) enqueueIngest(ctx context.Context, tx store.DBTX, source string) (string, error) {
	var active int
	if e := tx.QueryRowContext(ctx, s.q["ingest-active"], source).Scan(&active); e != nil {
		return "", e
	}
	if active > 0 {
		return "", conflict("此来源已有扫描任务")
	}
	id := uuid()
	if _, e := tx.ExecContext(ctx, s.q["ingest-insert"], id, source, now(), now()); e != nil {
		return "", e
	}
	s.hookMu.RLock()
	fn := s.projector
	s.hookMu.RUnlock()
	if fn != nil {
		if e := fn(ctx, tx, "SOURCE_INGEST", id); e != nil {
			return "", e
		}
	}
	return id, nil
}
func (s *Service) latestIngest(c *gin.Context) {
	if _, e := s.findSource(c.Request.Context(), s.db, c.Param("id")); e != nil {
		s.fail(c, e)
		return
	}
	var r ingestRecord
	e := s.db.GetContext(c.Request.Context(), &r, s.q["ingest-latest"], c.Param("id"))
	if errors.Is(e, sql.ErrNoRows) {
		c.Status(204)
		return
	}
	if e != nil {
		s.fail(c, e)
		return
	}
	c.JSON(200, r)
}
func (s *Service) createIngest(c *gin.Context) {
	e := s.transaction(c.Request.Context(), func(tx store.DBTX) error {
		if _, e := s.findSource(c.Request.Context(), tx, c.Param("id")); e != nil {
			return e
		}
		_, e := s.enqueueIngest(c.Request.Context(), tx, c.Param("id"))
		return e
	})
	if e != nil {
		s.fail(c, e)
		return
	}
	s.latestIngest(c)
}
func (s *Service) ingestLoop() {
	defer s.wg.Done()
	timer := time.NewTicker(2 * time.Second)
	defer timer.Stop()
	for {
		select {
		case <-s.ctx.Done():
			return
		case <-timer.C:
			s.RunIngest(s.ctx)
		}
	}
}

// RunIngest resumes the durable start request. Local scans retain their existing
// bounded atomic publication; WebDAV execution uses the original scan queue.
func (s *Service) RunIngest(ctx context.Context) {
	s.ingestMu.Lock()
	defer s.ingestMu.Unlock()
	type pending struct {
		ID       string  `db:"id"`
		SourceID string  `db:"source_id"`
		State    string  `db:"state"`
		TaskID   *string `db:"scan_task_id"`
	}
	var jobs []pending
	if e := s.db.SelectContext(ctx, &jobs, s.q["ingest-pending"]); e != nil {
		return
	}
	for _, job := range jobs {
		if ctx.Err() != nil {
			return
		}
		src, e := s.findSource(ctx, s.db, job.SourceID)
		state := "RUNNING"
		var detail any
		taskID := job.TaskID
		if e == nil && src.Type == "LOCAL" {
			_, _ = s.db.ExecContext(ctx, s.q["ingest-state"], "RUNNING", taskID, nil, now(), job.ID)
			_, e = s.scanLocal(ctx, job.SourceID)
			state = "SUCCEEDED"
		} else if e == nil {
			if taskID == nil {
				task, err := s.startTask(ctx, job.SourceID)
				e = err
				if err == nil {
					taskID = &task.ID
				}
			} else {
				task, err := s.task(ctx, job.SourceID, *taskID)
				e = err
				if err == nil {
					switch task.Status {
					case "SUCCEEDED":
						state = "SUCCEEDED"
					case "FAILED", "CANCELLED":
						state = "FAILED"
						detail = task.Error
					case "INTERRUPTED":
						taskID = nil
						state = "QUEUED"
					}
				}
			}
		}
		if ctx.Err() != nil {
			return
		}
		if e != nil {
			state = "FAILED"
			detail = safeError(e)
		}
		_ = store.WithinTx(ctx, s.db, func(tx store.DBTX) error {
			if _, e := tx.ExecContext(ctx, s.q["ingest-state"], state, taskID, detail, now(), job.ID); e != nil {
				return e
			}
			s.hookMu.RLock()
			fn := s.projector
			s.hookMu.RUnlock()
			if fn != nil {
				return fn(ctx, tx, "SOURCE_INGEST", job.ID)
			}
			return nil
		})
	}
}

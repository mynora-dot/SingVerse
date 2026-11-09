package background

import (
	"context"
	"crypto/rand"
	"fmt"

	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/library"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

func uuid() string {
	var b [16]byte
	if _, e := rand.Read(b[:]); e != nil {
		panic("random source unavailable")
	}
	b[6] = (b[6] & 15) | 64
	b[8] = (b[8] & 63) | 128
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[:4], b[4:6], b[6:8], b[8:10], b[10:])
}
func (s *Service) StartLocal(ctx context.Context, source string) (id string, err error) {
	err = s.transact(ctx, func(tx store.DBTX) error {
		row, e := s.row(ctx, tx, "localSource", doc{"sourceId": source})
		if e != nil || len(row) == 0 {
			return e
		}
		business := uuid()
		id = ID("LOCAL_SCAN", business)
		r := base(id, "LOCAL_SCAN", business)
		r["source_id"] = source
		r["name"] = limit("本地扫描 · "+text(row, "name"), 512)
		r["subject_name"] = limit(text(row, "name"), 512)
		r["status"], r["business_status"], r["stage"] = "RUNNING", "RUNNING", "SCANNING"
		r["progress_unit"] = "SONGS"
		r["started_at"] = now()
		if e = s.exec(ctx, tx, "insert", r); e != nil {
			return e
		}
		if e = s.classify(ctx, tx, id, false); e != nil {
			return e
		}
		return s.changed(ctx, tx, id)
	})
	return
}
func (s *Service) LocalProgress(ctx context.Context, id, stage string, entries, songs int64, completed, total *int64) error {
	if id == "" {
		return nil
	}
	return s.transact(ctx, func(tx store.DBTX) error {
		r, e := s.row(ctx, tx, "find", doc{"id": id})
		if e != nil || text(r, "status") != "RUNNING" {
			return e
		}
		r["stage"] = stage
		r["updated_at"] = now()
		r["completed"], r["total"] = nil, nil
		if completed != nil {
			r["completed"] = *completed
		}
		if total != nil {
			r["total"] = *total
		}
		r["statistics_json"] = encode(doc{"statistics": doc{"entries": entries, "total_songs": songs}, "execution": doc{}})
		r["fingerprint"] = uuid()
		if e = s.exec(ctx, tx, "update", r); e != nil {
			return e
		}
		return s.changed(ctx, tx, id)
	})
}
func (s *Service) FinishLocal(ctx context.Context, id string, result *library.Scan, err error) error {
	if id == "" {
		return nil
	}
	return s.transact(ctx, func(tx store.DBTX) error {
		r, e := s.row(ctx, tx, "find", doc{"id": id})
		if e != nil || len(r) == 0 {
			return e
		}
		r["status"], r["business_status"], r["stage"] = "FAILED", "FAILED", "FAILED"
		r["error"] = ""
		if err != nil {
			r["error"] = Message(err.Error())
		}
		if result != nil {
			r["status"], r["business_status"], r["stage"] = "COMPLETED", "SUCCEEDED", "COMPLETED"
			r["completed"], r["total"] = int64(result.TotalSongs), int64(result.TotalSongs)
			r["statistics_json"] = encode(doc{"statistics": doc{"total_songs": result.TotalSongs, "matched_songs": result.MatchedSongs, "review_songs": result.NeedsReviewSongs, "skipped_files": result.SkippedFiles}, "execution": doc{}})
		}
		clock := now()
		r["updated_at"], r["finished_at"], r["expires_at"], r["fingerprint"] = clock, clock, clock+retention, uuid()
		if e = s.exec(ctx, tx, "update", r); e != nil {
			return e
		}
		if e = s.exec(ctx, tx, "sealScope", doc{"id": id}); e != nil {
			return e
		}
		return s.changed(ctx, tx, id)
	})
}
func (s *Service) AudioOrigins(ctx context.Context, tx store.DBTX, song, fingerprint string) ([]string, error) {
	rows, e := s.rows(ctx, tx, "origins", doc{"songId": song, "fingerprint": fingerprint})
	if e != nil {
		return nil, e
	}
	out := []string{}
	for _, r := range rows {
		out = append(out, text(r, "batch_id"))
	}
	return out, nil
}
func (s *Service) PendingScopes(ctx context.Context, batchCursor, songCursor string) ([]map[string]any, error) {
	return s.rows(ctx, s.db, "pendingScopes", doc{"batchCursor": batchCursor, "songCursor": songCursor})
}
func (s *Service) AutomaticOrigin(ctx context.Context, tx store.DBTX, round, song, fingerprint string) ([]string, error) {
	existing, e := s.AudioOrigins(ctx, tx, song, fingerprint)
	if e != nil || len(existing) > 0 {
		return existing, e
	}
	r, e := s.row(ctx, tx, "scopeSong", doc{"songId": song})
	if e != nil || len(r) == 0 {
		return []string{}, e
	}
	batch, e := s.synthetic(ctx, tx, "AUDIO_DISCOVERY", round+":"+text(r, "source_id"), "自动音轨识别", text(r, "source_id"), text(r, "source_name"), "AUTOMATIC", now())
	if e != nil {
		return nil, e
	}
	if e = s.exec(ctx, tx, "admitScope", doc{"batchId": batch, "songId": song, "fingerprint": fingerprint, "now": now()}); e != nil {
		return nil, e
	}
	e = s.aggregate(ctx, tx, batch)
	return []string{batch}, e
}
func (s *Service) AudioSubmitted(ctx context.Context, tx store.DBTX, job, kind, song, fingerprint string, origins []string, created bool) error {
	if len(origins) == 0 {
		return nil
	}
	task := ID("AUDIO_JOB", job)
	if e := s.Project(ctx, tx, "AUDIO_JOB", job); e != nil {
		return e
	}
	r, e := s.row(ctx, tx, "find", doc{"id": task})
	if e != nil || len(r) == 0 {
		return e
	}
	if e = s.exec(ctx, tx, "lockExpired", doc{"id": task}); e != nil {
		return e
	}
	state := text(r, "status")
	scope := state
	var jobID any
	if active(state) {
		scope = "FAST"
		if kind == "PROBE" {
			scope = "PROBING"
		}
		jobID = job
	} else if state == "COMPLETED" {
		scope = "DONE"
		if kind == "PROBE" {
			scope = "PENDING"
		}
	}
	uncertain := 0
	if text(object(read(r["statistics_json"]), "execution"), "resultState") == "UNKNOWN" {
		uncertain = 1
	}
	unique := map[string]bool{}
	for _, origin := range origins {
		if unique[origin] {
			continue
		}
		unique[origin] = true
		if e = s.link(ctx, tx, origin, task, created); e != nil {
			return e
		}
		if e = s.exec(ctx, tx, "resolveScope", doc{"batchId": origin, "songId": song, "fingerprint": fingerprint, "state": scope, "jobId": jobID, "reason": text(r, "error"), "uncertain": uncertain, "now": now()}); e != nil {
			return e
		}
		if e = s.aggregate(ctx, tx, origin); e != nil {
			return e
		}
	}
	return nil
}
func (s *Service) ResolveAudioScopes(ctx context.Context, tx store.DBTX, song, fingerprint, state, reason string) error {
	origins, e := s.AudioOrigins(ctx, tx, song, fingerprint)
	if e != nil {
		return e
	}
	for _, origin := range origins {
		if e = s.exec(ctx, tx, "resolveScope", doc{"batchId": origin, "songId": song, "fingerprint": fingerprint, "state": state, "jobId": nil, "reason": Message(reason), "uncertain": 0, "now": now()}); e != nil {
			return e
		}
		if e = s.aggregate(ctx, tx, origin); e != nil {
			return e
		}
	}
	return nil
}
func (s *Service) SkipPendingAudio(ctx context.Context, reason string) error {
	if e := s.transact(ctx, func(tx store.DBTX) error { return s.exec(ctx, tx, "sealAutomatic", doc{"round": nil}) }); e != nil {
		return e
	}
	cursor := ""
	for {
		rows, e := s.rows(ctx, s.db, "pendingScopeBatches", doc{"cursor": cursor})
		if e != nil {
			return e
		}
		if len(rows) == 0 {
			return nil
		}
		for _, r := range rows {
			e = s.transact(ctx, func(tx store.DBTX) error {
				id := text(r, "batch_id")
				if e := s.exec(ctx, tx, "skipBatchScopes", doc{"batchId": id, "reason": Message(reason), "now": now()}); e != nil {
					return e
				}
				return s.aggregate(ctx, tx, id)
			})
			if e != nil {
				return e
			}
		}
		cursor = text(rows[len(rows)-1], "batch_id")
	}
}
func (s *Service) WaitingForAudioNode(ctx context.Context) error {
	return s.exec(ctx, s.db, "waitingScopes", doc{"reason": "等待计算节点恢复后继续发现", "now": now()})
}
func (s *Service) FinishDiscoveryRound(ctx context.Context, round string) error {
	return s.transact(ctx, func(tx store.DBTX) error { return s.exec(ctx, tx, "sealAutomatic", doc{"round": round + ":%"}) })
}
func (s *Service) Maintain(ctx context.Context) error {
	for _, kind := range types {
		cursor := ""
		for {
			if e := ctx.Err(); e != nil {
				return e
			}
			rows, e := s.rows(ctx, s.db, "sourceKeys", doc{"type": kind, "cursor": cursor})
			if e != nil {
				return e
			}
			if len(rows) == 0 {
				break
			}
			for _, r := range rows {
				at := timestamp(r["updated_at"])
				if at == nil {
					at = timestamp(r["created_at"])
				}
				if sourceActive(text(r, "raw_status")) || (at != nil && stamp(at) >= now()-retention) {
					if e = s.transact(ctx, func(tx store.DBTX) error { return s.refresh(ctx, tx, kind, text(r, "id"), true) }); e != nil {
						return e
					}
				}
			}
			cursor = text(rows[len(rows)-1], "id")
		}
	}
	cursor := ""
	for {
		rows, e := s.rows(ctx, s.db, "batchKeys", doc{"cursor": cursor})
		if e != nil {
			return e
		}
		if len(rows) == 0 {
			break
		}
		for _, r := range rows {
			if e = s.transact(ctx, func(tx store.DBTX) error { return s.aggregate(ctx, tx, text(r, "id")) }); e != nil {
				return e
			}
		}
		cursor = text(rows[len(rows)-1], "id")
	}
	for {
		rows, e := s.rows(ctx, s.db, "expired", doc{"now": now()})
		if e != nil {
			return e
		}
		if len(rows) == 0 {
			return nil
		}
		for _, r := range rows {
			if e = s.transact(ctx, func(tx store.DBTX) error {
				v := doc{"id": r["id"], "now": now()}
				if e := s.exec(ctx, tx, "lockExpired", v); e != nil {
					return e
				}
				current, e := s.row(ctx, tx, "find", v)
				if e != nil {
					return e
				}
				expiry := current["expires_at"]
				if number(current, "is_batch") != 0 {
					expiry = current["batch_expires_at"]
				}
				if expiry == nil || stamp(expiry) > now() {
					return nil
				}
				members, e := s.rows(ctx, tx, "memberships", v)
				if e != nil || len(members) > 0 {
					return e
				}
				for _, q := range []string{"deleteRelations", "deleteScopes", "deleteItems", "deleteTask"} {
					if e = s.exec(ctx, tx, q, v); e != nil {
						return e
					}
				}
				return nil
			}); e != nil {
				return e
			}
		}
	}
}

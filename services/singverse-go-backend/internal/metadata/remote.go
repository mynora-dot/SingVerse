package metadata

import (
	"context"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
	"time"
)

func (s *Service) remoteLoop() {
	defer s.wg.Done()
	timer := time.NewTicker(5 * time.Second)
	defer timer.Stop()
	for {
		select {
		case <-s.ctx.Done():
			return
		case <-timer.C:
			if e := s.RunRemoteNext(s.ctx); e != nil && s.ctx.Err() == nil {
				s.log.Error("embedded metadata worker failed")
			}
		}
	}
}

// RunRemoteNext claims one durable extraction; upstream IO never holds a database connection.
func (s *Service) RunRemoteNext(ctx context.Context) error {
	s.hookMu.RLock()
	extract := s.remote
	s.hookMu.RUnlock()
	if extract == nil {
		return nil
	}
	tasks, e := s.rows(ctx, s.db, "dav-pending", map[string]any{"now": now(), "day": ""})
	if e != nil {
		return e
	}
	for _, task := range tasks {
		group := text(task["access_group"])
		budget, e := s.budget(ctx, s.db, group)
		if e != nil {
			return e
		}
		if until, e := time.Parse(time.RFC3339Nano, text(budget["blocked_until"])); e == nil && time.Now().Before(until) {
			continue
		}
		claimed := false
		e = s.transaction(ctx, func(tx store.DBTX) error {
			r, e := s.exec(ctx, tx, "dav-fileStart", map[string]any{"group": group, "after": time.Now().UTC().Add(10 * time.Second).Format(time.RFC3339Nano), "now": now()})
			if e != nil {
				return e
			}
			n, e := r.RowsAffected()
			if e != nil || n != 1 {
				return e
			}
			r, e = s.exec(ctx, tx, "dav-claim", map[string]any{"id": task["id"]})
			if e != nil {
				return e
			}
			n, e = r.RowsAffected()
			if e != nil {
				return e
			}
			if n != 1 {
				return conflict("提取任务已被领取")
			}
			if _, e = s.exec(ctx, tx, "dav-running", map[string]any{"songId": task["song_id"], "binding": task["binding"], "generation": task["generation"]}); e != nil {
				return e
			}
			claimed = true
			return nil
		})
		if e != nil {
			return e
		}
		if !claimed {
			continue
		}
		duration := 70 * time.Second
		if boolean(task["deep"]) {
			duration = 190 * time.Second
		}
		callCtx, cancel := context.WithTimeout(ctx, duration)
		result, callErr := extract(callCtx, task)
		cancel()
		if callErr != nil {
			s.SetRemoteAvailability("UNAVAILABLE", false, "计算节点暂不可用，元数据任务保留并稍后重试")
			return s.retryRemote(ctx, task, "NETWORK_ERROR")
		}
		s.SetRemoteAvailability("AVAILABLE", true, "计算节点支持远程元数据提取")
		return s.completeRemote(ctx, task, result)
	}
	return nil
}
func (s *Service) deferRemote(ctx context.Context, tx store.DBTX, task map[string]any) (bool, error) {
	rows, e := s.rows(ctx, tx, "dav-resource", map[string]any{"songId": task["song_id"]})
	if e != nil {
		return true, e
	}
	if len(rows) == 0 {
		return true, nil
	}
	r := rows[0]
	args := map[string]any{"id": task["id"], "songId": task["song_id"], "binding": task["binding"], "generation": task["generation"], "error": nil, "availableAt": now()}
	if r["archived_at"] != nil || number(r["generation"]) != number(task["generation"]) || text(r["binding"]) != text(task["binding"]) {
		args["state"] = "SUPERSEDED"
		_, e = s.exec(ctx, tx, "dav-finishTask", args)
		return true, e
	}
	if (boolean(r["enabled"]) || boolean(task["manual"])) && !boolean(r["paused"]) && text(r["access_group"]) == text(task["access_group"]) && text(r["private_origins"]) == text(task["private_origins"]) {
		return false, nil
	}
	args["state"], args["error"], args["availableAt"] = "QUEUED", "PAUSED", time.Now().UTC().Add(30*time.Second).Format(time.RFC3339Nano)
	if _, e = s.exec(ctx, tx, "dav-finishTask", args); e != nil {
		return true, e
	}
	args["state"] = "PENDING"
	_, e = s.exec(ctx, tx, "dav-resourceState", args)
	return true, e
}
func (s *Service) retryRemote(ctx context.Context, task map[string]any, reason string) error {
	if reason == "AUTH_REQUIRED" {
		if e := s.block(ctx, text(task["source_id"]), true, time.Now()); e != nil {
			return e
		}
	}
	if reason == "RATE_LIMITED" {
		if e := s.block(ctx, text(task["source_id"]), false, time.Now().Add(6*time.Hour)); e != nil {
			return e
		}
	}
	return s.transaction(ctx, func(tx store.DBTX) error {
		deferred, e := s.deferRemote(ctx, tx, task)
		if e != nil || deferred {
			return e
		}
		attempt := number(task["attempts"]) + 1
		again := attempt < 3 && reason != "UNSTABLE"
		delay := 30 * time.Minute
		if attempt > 1 {
			delay = 6 * time.Hour
		}
		state, resourceState := reason, reason
		if again {
			state, resourceState = "QUEUED", "PENDING"
		}
		a := map[string]any{"id": task["id"], "state": state, "error": reason, "availableAt": time.Now().UTC().Add(delay).Format(time.RFC3339Nano), "songId": task["song_id"], "binding": task["binding"], "generation": task["generation"]}
		if _, e = s.exec(ctx, tx, "dav-finishTask", a); e != nil {
			return e
		}
		a["state"] = resourceState
		_, e = s.exec(ctx, tx, "dav-resourceState", a)
		return e
	})
}
func (s *Service) completeRemote(ctx context.Context, task map[string]any, result map[string]any) error {
	state := text(result["state"])
	if contains([]string{"AUTH_REQUIRED", "RATE_LIMITED", "NETWORK_ERROR", "UNSTABLE"}, state) {
		return s.retryRemote(ctx, task, state)
	}
	if !contains([]string{"COMPLETE", "EMPTY", "PARTIAL", "BUDGET_EXCEEDED", "UNSUPPORTED", "RANGE_UNSUPPORTED"}, state) {
		return s.retryRemote(ctx, task, "NETWORK_ERROR")
	}
	if _, ok := result["fields"].(map[string]any); !ok {
		return s.retryRemote(ctx, task, "NETWORK_ERROR")
	}
	if e := validateSnapshot(result); e != nil {
		return s.retryRemote(ctx, task, "NETWORK_ERROR")
	}
	normalized := map[string]any{}
	for k, v := range result {
		normalized[k] = v
	}
	if raw := text(result["cover"]); raw != "" {
		key, e := s.InstallSharedCover(raw)
		if e != nil {
			normalized["cover"] = nil
			fields := map[string]any{}
			for k, v := range result["fields"].(map[string]any) {
				fields[k] = v
			}
			fields["cover"] = "UNKNOWN"
			normalized["fields"] = fields
			state = "PARTIAL"
		} else {
			normalized["cover"] = key
		}
	}
	if result["lyrics"] != nil {
		key, e := s.storeAsset([]byte(encode(result["lyrics"])), ".lyrics.json")
		if e != nil {
			return e
		}
		normalized["lyricsAsset"] = key
	}
	normalized["state"] = state
	return s.transaction(ctx, func(tx store.DBTX) error {
		if _, e := s.exec(ctx, tx, "dav-lockSource", map[string]any{"sourceId": task["source_id"]}); e != nil {
			return e
		}
		deferred, e := s.deferRemote(ctx, tx, task)
		if e != nil || deferred {
			return e
		}
		source, e := s.row(ctx, tx, "source", map[string]any{"id": task["source_id"]})
		if e != nil {
			return e
		}
		if hash(text(source["location"])) != text(task["binding"]) {
			_, e = s.exec(ctx, tx, "dav-finishTask", map[string]any{"id": task["id"], "state": "SUPERSEDED", "error": nil, "availableAt": now()})
			return e
		}
		a := map[string]any{"id": task["id"], "songId": task["song_id"], "binding": task["binding"], "generation": task["generation"], "state": state, "result": encode(normalized), "tags": encode(normalized["tags"]), "version": text(result["extractorVersion"]), "now": now(), "error": nil, "availableAt": now()}
		if a["version"] == "" {
			a["version"] = "remote-tags-1"
		}
		if _, e = s.exec(ctx, tx, "dav-finishResource", a); e != nil {
			return e
		}
		if _, e = s.exec(ctx, tx, "dav-applyTags", a); e != nil {
			return e
		}
		if _, e = s.exec(ctx, tx, "dav-finishTask", a); e != nil {
			return e
		}
		if e = s.Touch(ctx, tx, text(task["song_id"])); e != nil {
			return e
		}
		s.hookMu.RLock()
		hook := s.appliedHook
		s.hookMu.RUnlock()
		if hook != nil {
			return hook(ctx, tx, text(task["song_id"]))
		}
		return nil
	})
}

// SearchTracks exposes the same enabled-provider gate and request budget to artist matching.
func (s *Service) SearchTracks(ctx context.Context, provider, keyword string, limit int) ([]Track, error) {
	tracks, e := s.callProvider(ctx, provider, s.providers[provider], keyword)
	if len(tracks) > limit && limit > 0 {
		tracks = tracks[:limit]
	}
	return tracks, e
}

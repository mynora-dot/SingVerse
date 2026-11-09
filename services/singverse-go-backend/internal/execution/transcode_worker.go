package execution

import (
	"context"
	"errors"

	"github.com/jmoiron/sqlx"
)

func (s *Service) requeueTranscode(ctx context.Context, id string) error {
	return s.transaction(ctx, "TRANSCODE", id, func(tx *sqlx.Tx) error {
		_, err := s.exec(ctx, tx, "transcode-requeue", map[string]any{"id": id, "execution": uuid(), "now": now()})
		return err
	})
}
func (s *Service) tickTranscodes(ctx context.Context) {
	if !s.transcodeWorking.CompareAndSwap(false, true) {
		return
	}
	defer s.transcodeWorking.Store(false)
	pending, err := s.maps(ctx, s.db, "transcode-pending", nil)
	if err != nil {
		return
	}
	for _, row := range pending {
		if ctx.Err() != nil {
			return
		}
		err = s.advanceTranscode(ctx, row)
		if err == nil {
			continue
		}
		var f *failure
		if errors.As(err, &f) {
			if f.status == 404 && str(row, "purpose") == "OFFLINE" {
				_ = s.requeueTranscode(ctx, str(row, "id"))
			} else if oneOfInt(f.status, 400, 409, 413, 422) {
				_ = s.updateTranscode(ctx, str(row, "id"), "FAILED", map[string]any{}, f.detail)
			}
		} else {
			_ = s.updateTranscode(ctx, str(row, "id"), "FAILED", map[string]any{}, "转换或保存失败")
		}
	}
}
func (s *Service) advanceTranscode(ctx context.Context, row map[string]any) error {
	id := str(row, "id")
	song, err := s.song(ctx, s.db, str(row, "song_id"))
	if err != nil {
		return err
	}
	if str(row, "fingerprint") != playbackFingerprint(song) {
		return s.updateTranscode(ctx, id, "FAILED", map[string]any{}, "源媒体已变化")
	}
	if str(row, "pipeline_version") != "2" {
		if str(row, "purpose") == "REALTIME" {
			return s.updateTranscode(ctx, id, "FAILED", map[string]any{}, "转码流水线已升级，请重建播放会话")
		}
		return s.requeueTranscode(ctx, id)
	}
	if str(row, "purpose") == "REALTIME" {
		result := decoded(row["result_json"])
		if result["cacheId"] != nil {
			s.acknowledgeCached(ctx, row, result)
		} else if str(result, "cacheKey") != "" {
			c, err := s.connection(ctx, str(row, "node_origin"))
			if err != nil {
				return err
			}
			remote, err := s.call(ctx, c, "/internal/v1/transcoding/executions/"+str(row, "execution_id"), "GET", nil)
			if err != nil {
				return err
			}
			for key, value := range remote {
				result[key] = value
			}
			if str(result, "state") == "SUCCEEDED" {
				cached, captureErr := s.captureCache(ctx, row, str(result, "cacheKey"), c, result)
				if captureErr == nil {
					cached["id"], cached["cacheKey"], cached["cacheHit"], cached["computeReleased"] = id, str(result, "cacheKey"), false, false
					if err = s.saveActiveCache(ctx, id, cached); err != nil {
						s.releaseCache(ctx, id)
						return err
					}
					s.acknowledgeCached(ctx, row, cached)
				}
			}
		}
		resultSQL, err := s.exec(ctx, s.db, "transcode-expire-realtime", map[string]any{"id": id, "since": now() - 120000, "now": now()})
		if err != nil {
			return err
		}
		count, err := resultSQL.RowsAffected()
		if err != nil {
			return err
		}
		if count == 1 {
			return s.CancelTranscode(ctx, id, str(row, "owner_id"))
		}
		return nil
	}
	c, err := s.connection(ctx, str(row, "node_origin"))
	if err != nil {
		return err
	}
	var result map[string]any
	if str(row, "state") == "QUEUED" {
		body := decoded(row["input_json"])
		body["id"], body["fingerprint"], body["target"] = str(row, "execution_id"), str(row, "fingerprint"), decoded(row["target_json"])
		result, err = s.call(ctx, c, "/internal/v1/transcoding/executions", "POST", body)
		latest, _ := s.optional(ctx, s.db, "transcode-find", map[string]any{"id": id})
		if str(latest, "state") == "CANCELLED" {
			_, _ = s.call(ctx, c, "/internal/v1/transcoding/executions/"+str(row, "execution_id")+"/cancel", "POST", nil)
		}
	} else {
		result, err = s.call(ctx, c, "/internal/v1/transcoding/executions/"+str(row, "execution_id"), "GET", nil)
	}
	if err != nil {
		return err
	}
	state := str(result, "state")
	if state == "SUCCEEDED" {
		output := obj(result["output"])
		guard := func() error {
			latest, err := s.requireTranscode(ctx, s.db, id, "admin")
			if err != nil {
				return err
			}
			if oneOf(str(latest, "state"), "CANCELLED", "DELETING") || str(latest, "execution_id") != str(row, "execution_id") {
				return fail(409, "转换任务已取消或重建")
			}
			song, err := s.song(ctx, s.db, str(row, "song_id"))
			if err != nil {
				return err
			}
			if playbackFingerprint(song) != str(row, "fingerprint") {
				return fail(409, "源媒体已变化")
			}
			return nil
		}
		if err = s.downloadVerified(ctx, id, c, "/internal/v1/transcoding/executions/"+str(row, "execution_id")+"/artifacts/output.mp4", s.cfg.TranscodePath, id+".mp4", integer(output["size"]), str(output, "sha256"), 10<<30, 1, guard); err != nil {
			return err
		}
		_, err = s.call(ctx, c, "/internal/v1/transcoding/executions/"+str(row, "execution_id")+"/acknowledge", "POST", nil)
		if err != nil {
			return err
		}
	}
	if state == "CANCELLED" || str(result, "error") == "NODE_RESTARTED" {
		return s.requeueTranscode(ctx, id)
	}
	if state == "ACCEPTED" {
		state = "RUNNING"
	}
	if !oneOf(state, "QUEUED", "STARTING", "RUNNING", "SUCCEEDED", "FAILED") {
		return fail(502, "计算节点执行状态无效")
	}
	return s.updateTranscode(ctx, id, state, result, str(result, "error"))
}
func (s *Service) acknowledgeCached(ctx context.Context, row, result map[string]any) {
	if boolv(result["computeReleased"]) || str(row, "node_origin") == "" {
		return
	}
	c, err := s.connection(ctx, str(row, "node_origin"))
	if err != nil {
		return
	}
	if _, err = s.call(ctx, c, "/internal/v1/transcoding/executions/"+str(row, "execution_id")+"/acknowledge", "POST", nil); err != nil {
		return
	}
	result["computeReleased"] = true
	if s.saveActiveCache(ctx, str(row, "id"), result) != nil {
		s.releaseCache(ctx, str(row, "id"))
	}
}

package party

import (
	"context"
	"encoding/json"

	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

func integral(v any) (int64, bool) {
	switch n := v.(type) {
	case int64:
		return n, true
	case int:
		return int64(n), true
	case json.Number:
		parsed, err := n.Int64()
		return parsed, err == nil
	case float64:
		if float64(int64(n)) == n {
			return int64(n), true
		}
	}
	return 0, false
}

func (s *Service) recordPlaybackEvent(ctx context.Context, tx store.DBTX, actor document, sessionID string, input document) (document, error) {
	if str(actor, "kind") != "device" {
		return nil, fail(403, "仅电视可报告播放")
	}
	if err := s.lock(ctx, tx, sessionID); err != nil {
		return nil, err
	}
	session, err := s.get(ctx, tx, sessionID)
	if err != nil {
		return nil, err
	}
	if err = check(actor, session); err != nil {
		return nil, err
	}
	commandID := str(input, "commandId")
	commands := array(session, "commands")
	acknowledged := false
	if commandID != "" {
		for i, raw := range commands {
			if str(raw.(map[string]any), "id") == commandID {
				commands = commands[i+1:]
				acknowledged = true
				break
			}
		}
	}
	session["commands"] = commands
	if _, ok := input["attemptId"]; ok {
		playback, ok := session["playback"].(map[string]any)
		if !ok || str(playback, "attemptId") != str(input, "attemptId") {
			return nil, fail(409, "旧播放实例已失效")
		}
		sequence, ok := integral(input["eventSeq"])
		if !ok {
			sequence = -1
		}
		if sequence <= number(playback, "eventSeq") {
			if acknowledged {
				if err = s.save(ctx, tx, session); err != nil {
					return nil, err
				}
				s.prefetchChanged(session, false, false)
			}
			return snapshot(session, actor), nil
		}
		state, err := text(input, "state", 20)
		if err != nil {
			return nil, err
		}
		switch state {
		case "PREPARING", "BUFFERING", "PLAYING", "PAUSED", "ENDED", "ERROR", "IDLE":
		default:
			return nil, fail(400, "播放状态无效")
		}
		if raw, ok := input["durationMs"]; ok {
			duration, valid := integral(raw)
			if !valid || duration <= 0 || duration > 86400000 {
				return nil, fail(400, "播放总时长无效")
			}
			playback["durationMs"] = duration
		}
		if s.activity != nil {
			if err = s.activity.ReportTV(ctx, tx, sessionID, str(playback, "attemptId"), input); err != nil {
				return nil, err
			}
		}
		playback["state"] = state
		playback["eventSeq"] = sequence
		playback["positionMs"] = max(int64(0), number(input, "positionMs"))
		for _, raw := range array(session, "queue") {
			item := raw.(map[string]any)
			if str(item, "id") == str(playback, "itemId") {
				switch state {
				case "PLAYING":
					item["status"] = "PLAYING"
				case "ENDED":
					item["status"] = "COMPLETED"
				case "ERROR":
					item["status"] = "FAILED"
				case "IDLE":
					item["status"] = "SKIPPED"
				}
			}
		}
	}
	if err = s.save(ctx, tx, session); err != nil {
		return nil, err
	}
	_, progress := input["attemptId"]
	s.prefetchChanged(session, false, progress)
	return snapshot(session, actor), nil
}

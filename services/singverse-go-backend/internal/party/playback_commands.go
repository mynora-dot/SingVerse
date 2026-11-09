package party

import (
	"context"

	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

const maxPlaybackCommands = 100

func enqueuePlaybackCommand(session document, action string) error {
	commands := array(session, "commands")
	if len(commands) >= maxPlaybackCommands {
		return fail(409, "电视未响应，请等待连接恢复")
	}
	command := document{"id": id(), "action": action}
	if playback, ok := session["playback"].(map[string]any); ok {
		command["playback"] = clone(playback)
	}
	session["commands"] = append(commands, command)
	return nil
}

func firstQueueItem(queue []any, status string) document {
	for _, raw := range queue {
		item := raw.(map[string]any)
		if str(item, "status") == status {
			return item
		}
	}
	return nil
}

func (s *Service) advanceQueuePlayback(ctx context.Context, tx store.DBTX, session document, confirmed bool) error {
	queue := array(session, "queue")
	var candidate document
	if confirmed {
		candidate = firstQueueItem(queue, "HANDOFF")
		state := str(object(session, "playback"), "state")
		if state == "PLAYING" || state == "PAUSED" || state == "BUFFERING" {
			return fail(409, "当前歌曲尚未结束，请先切歌")
		}
	} else {
		if err := s.finishActivity(ctx, tx, session, "SKIPPED"); err != nil {
			return err
		}
		session["commands"] = []any{}
		for _, raw := range queue {
			item := raw.(map[string]any)
			if str(item, "status") == "HANDOFF" || str(item, "status") == "PLAYING" {
				item["status"] = "SKIPPED"
			}
		}
		if err := enqueuePlaybackCommand(session, "STOP"); err != nil {
			return err
		}
	}
	if candidate == nil {
		candidate = firstQueueItem(queue, "WAITING")
	}
	delete(session, "playback")
	if candidate == nil {
		return enqueuePlaybackCommand(session, "STOP")
	}

	candidate["status"] = "HANDOFF"
	session["playback"] = document{
		"itemId": candidate["id"], "songId": candidate["songId"], "attemptId": id(),
		"state": "PREPARING", "confirmed": confirmed,
		"eventSeq": int64(-1), "positionMs": int64(0),
	}
	if !confirmed {
		return nil
	}
	if err := s.startActivity(ctx, tx, session, candidate); err != nil {
		return err
	}
	return enqueuePlaybackCommand(session, "LOAD")
}

func (s *Service) controlQueuePlayback(ctx context.Context, tx store.DBTX, session document, action string) error {
	playback := object(session, "playback")
	if !boolean(playback, "confirmed") {
		return fail(409, "请先在电视确认开唱")
	}
	if action == "RESTART" {
		if err := s.finishActivity(ctx, tx, session, "STOPPED"); err != nil {
			return err
		}
		session["commands"] = []any{}
		playback["attemptId"] = id()
		playback["eventSeq"] = int64(-1)
		playback["state"] = "PREPARING"
		playback["positionMs"] = int64(0)
		delete(playback, "durationMs")
		if err := s.startActivity(ctx, tx, session, nil); err != nil {
			return err
		}
	}
	if action == "STOP" {
		if err := s.finishActivity(ctx, tx, session, "STOPPED"); err != nil {
			return err
		}
	}
	return enqueuePlaybackCommand(session, action)
}

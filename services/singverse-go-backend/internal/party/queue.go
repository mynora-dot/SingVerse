package party

import (
	"context"

	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

const (
	maxSessionQueueItems = 500
	maxSessionReceipts   = 1000
)

func (s *Service) changeQueue(ctx context.Context, tx store.DBTX, actor document, sessionID string, input document) (document, error) {
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
	requestID, err := text(input, "requestId", 80)
	if err != nil {
		return nil, err
	}

	// Check the receipt before the revision: retries return the current snapshot
	// without applying an already committed action a second time.
	signature := hash(encode(input))
	receiptKey := str(actor, "id") + ":" + requestID
	receipts := object(session, "receipts")
	if previous, ok := receipts[receiptKey]; ok {
		if previous != signature {
			return nil, fail(409, "请求标识已用于其他操作")
		}
		return snapshot(session, actor), nil
	}
	if str(session, "status") != "ACTIVE" {
		return nil, fail(409, "聚会已结束")
	}
	if _, ok := input["expectedRevision"]; !ok || number(input, "expectedRevision") != number(session, "queueRevision") {
		return nil, fail(409, "队列已更新，请核对后重试")
	}
	action, err := text(input, "action", 30)
	if err != nil {
		return nil, err
	}
	if err = s.applyQueueAction(ctx, tx, actor, session, input, action); err != nil {
		return nil, err
	}

	session["queue"] = array(session, "queue")
	session["queueRevision"] = number(session, "queueRevision") + 1
	receipts[receiptKey] = signature
	session["receipts"] = receipts
	if len(receipts) > maxSessionReceipts {
		return nil, fail(409, "本场操作数量已达上限，请结束后重新开场")
	}
	if err = s.save(ctx, tx, session); err != nil {
		return nil, err
	}
	s.prefetchChanged(session, action == "STOP", false)
	return snapshot(session, actor), nil
}

func (s *Service) applyQueueAction(ctx context.Context, tx store.DBTX, actor, session, input document, action string) error {
	switch action {
	case "ADD":
		return s.addQueueSong(ctx, tx, actor, session, input)
	case "REMOVE":
		return removeQueueSong(actor, session, str(input, "itemId"))
	}

	if !host(actor) {
		return fail(403, "需要主持权限")
	}
	switch action {
	case "TOP", "UP", "DOWN":
		return reorderQueue(session, str(input, "itemId"), action)
	case "LOCK", "UNLOCK", "CLEAR", "END", "HOST", "REVOKE":
		if str(actor, "kind") != "device" {
			return fail(403, "请在电视操作")
		}
		return s.applySessionAction(ctx, tx, session, input, action)
	case "START", "NEXT":
		if action == "START" && str(actor, "kind") != "device" {
			return fail(403, "请在电视确认开唱")
		}
		return s.advanceQueuePlayback(ctx, tx, session, action == "START")
	case "PAUSE", "RESUME", "RESTART", "STOP":
		return s.controlQueuePlayback(ctx, tx, session, action)
	default:
		return fail(400, "未知操作")
	}
}

func (s *Service) addQueueSong(ctx context.Context, tx store.DBTX, actor, session, input document) error {
	if !boolean(session, "acceptingRequests") {
		return fail(409, "本场已暂停接单")
	}
	queue := array(session, "queue")
	if len(queue) >= maxSessionQueueItems {
		return fail(409, "本场队列已满")
	}
	songID, err := text(input, "songId", 64)
	if err != nil {
		return err
	}
	song, err := s.library.SongWith(ctx, tx, songID)
	if err != nil {
		return err
	}
	if state := str(song, "mediaVerification"); state != "" && state != "PRESENT" {
		return fail(409, "此歌曲的媒体路径尚未核验，请管理员处理来源")
	}
	ownerName := str(actor, "name")
	if ownerName == "" {
		ownerName = "电视"
	}
	session["queue"] = append(queue, document{
		"id": id(), "songId": songID,
		"ownerId": actor["id"], "ownerName": ownerName,
		"status": "WAITING", "song": Projection(song),
	})
	return nil
}

func queueItem(queue []any, itemID string) (document, int) {
	for index, raw := range queue {
		item := raw.(map[string]any)
		if str(item, "id") == itemID {
			return item, index
		}
	}
	return nil, -1
}

func removeQueueSong(actor, session document, itemID string) error {
	item, _ := queueItem(array(session, "queue"), itemID)
	if item == nil || str(item, "status") != "WAITING" {
		return fail(409, "只能移除待唱歌曲")
	}
	if !host(actor) && str(item, "ownerId") != str(actor, "id") {
		return fail(403, "只能撤回自己点的歌")
	}
	item["status"] = "CANCELLED"
	return nil
}

func reorderQueue(session document, itemID, action string) error {
	queue := array(session, "queue")
	item, index := queueItem(queue, itemID)
	if item == nil || str(item, "status") != "WAITING" {
		return fail(409, "只能排序待唱歌曲")
	}
	destination := 0
	switch action {
	case "UP":
		destination = max(0, index-1)
	case "DOWN":
		destination = min(len(queue)-1, index+1)
	}
	queue = append(queue[:index], queue[index+1:]...)
	queue = append(queue, nil)
	copy(queue[destination+1:], queue[destination:])
	queue[destination] = item
	session["queue"] = queue
	return nil
}

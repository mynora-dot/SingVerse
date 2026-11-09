package downloads

import (
	"context"
	"strings"

	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/search"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

func (s *Service) Create(ctx context.Context, in BatchInput) (map[string]any, error) {
	if len(in.TrackIDs) == 0 || len(in.TrackIDs) > 1000 {
		return nil, bad("每批请选择 1–1000 首歌曲")
	}
	quality := in.Quality
	if quality == "" {
		quality = "auto"
	}
	if quality != "auto" && !contains(qualities, quality) {
		return nil, bad("音质无效")
	}
	// Filesystem probing stays outside the transaction. Recheck the binding under
	// its deployment row lock before persisting the immutable destination.
	destination, _, e := s.requireSource(ctx, s.db)
	if e != nil {
		return nil, e
	}
	root, e := writable(str(destination, "source_path"), true)
	if e != nil {
		return nil, e
	}
	var out map[string]any
	e = s.transaction(ctx, func(tx store.DBTX) error {
		if e := s.lock(ctx, tx); e != nil {
			return e
		}
		target, script, e := s.requireSource(ctx, tx)
		if e != nil {
			return e
		}
		if str(target, "source_path") != root || str(target, "source_id") != str(destination, "source_id") {
			return conflict("下载保存位置发生变化，请重试")
		}
		enabled, e := capabilities(script)
		if e != nil {
			return e
		}
		source := str(target, "source_id")
		tracks := []Track{}
		duplicates := []string{}
		seen, seenBatch := map[string]bool{}, map[string]bool{}
		for _, id := range in.TrackIDs {
			if seen[id] {
				continue
			}
			seen[id] = true
			row, e := s.cached(ctx, tx, id)
			if e != nil {
				return e
			}
			t, e := parseTrack(str(row, "track_json"))
			if e != nil {
				return e
			}
			if len(enabled[t.Platform]) == 0 || quality != "auto" && !contains(enabled[t.Platform], quality) {
				return bad("所选歌曲的平台或音质不受当前音源支持")
			}
			active, e := s.row(ctx, tx, "activeItem", map[string]any{"activeKey": activeKey(source, id, quality)})
			if e != nil {
				return e
			}
			if active != nil {
				batch := str(active, "batch_id")
				if !seenBatch[batch] {
					duplicates = append(duplicates, batch)
					seenBatch[batch] = true
				}
			} else {
				tracks = append(tracks, t)
			}
		}
		if len(tracks) == 0 {
			batch, e := s.batch(ctx, tx, duplicates[0])
			if e != nil {
				return e
			}
			out = map[string]any{"batch": batch, "merged": len(in.TrackIDs), "relatedBatchIds": duplicates}
			return nil
		}
		name := strings.TrimSpace(in.Name)
		if name == "" {
			name = "歌曲下载 · " + str(map[string]any{"n": len(tracks)}, "n") + " 首"
		}
		if search.UTF16Length(name) > 200 {
			return bad("批次名称最多 200 个字")
		}
		id := uuid()
		if _, e = s.exec(ctx, tx, "insertBatch", map[string]any{"id": id, "name": name, "sourceId": source, "path": root, "scriptId": script["id"], "quality": quality, "now": now()}); e != nil {
			return e
		}
		for _, t := range tracks {
			if _, e = s.exec(ctx, tx, "insertItem", map[string]any{"id": uuid(), "batchId": id, "trackKey": t.ID, "trackJson": encode(t), "quality": quality, "activeKey": activeKey(source, t.ID, quality), "status": "QUEUED", "message": "", "songId": nil, "now": now()}); e != nil {
				return e
			}
		}
		if e = s.settle(ctx, tx, id); e != nil {
			return e
		}
		batch, e := s.batch(ctx, tx, id)
		if e != nil {
			return e
		}
		out = map[string]any{"batch": batch, "merged": len(in.TrackIDs) - len(tracks), "relatedBatchIds": duplicates}
		return nil
	})
	return out, e
}
func (s *Service) batch(ctx context.Context, tx store.DBTX, id string) (map[string]any, error) {
	row, e := s.row(ctx, tx, "batch", map[string]any{"id": id})
	if e != nil {
		return nil, e
	}
	if row == nil {
		return nil, bad("下载记录不存在")
	}
	return s.batchView(ctx, tx, row)
}
func (s *Service) batchView(ctx context.Context, tx store.DBTX, row map[string]any) (map[string]any, error) {
	rows, e := s.rows(ctx, tx, "counts", map[string]any{"id": row["id"]})
	if e != nil {
		return nil, e
	}
	counts := map[string]int64{}
	total := int64(0)
	for _, c := range rows {
		n := number(c, "amount")
		counts[str(c, "status")] = n
		total += n
	}
	return map[string]any{"id": row["id"], "name": row["name"], "sourceId": row["source_id"], "path": row["source_path"], "scriptId": row["script_id"], "quality": row["quality"], "status": row["status"], "createdAt": row["created_at"], "updatedAt": row["updated_at"], "counts": counts, "total": total}, nil
}
func (s *Service) Batches(ctx context.Context, page, size int) (map[string]any, error) {
	if e := bounds(page, size); e != nil {
		return nil, e
	}
	rows, e := s.rows(ctx, s.db, "batches", map[string]any{"size": size, "offset": int64(page) * int64(size)})
	if e != nil {
		return nil, e
	}
	items := []map[string]any{}
	for _, r := range rows {
		v, e := s.batchView(ctx, s.db, r)
		if e != nil {
			return nil, e
		}
		items = append(items, v)
	}
	total, e := s.scalar(ctx, s.db, "batchCount", nil)
	return map[string]any{"items": items, "total": total, "page": page, "size": size}, e
}
func (s *Service) Items(ctx context.Context, id string, page, size int) (map[string]any, error) {
	if e := bounds(page, size); e != nil {
		return nil, e
	}
	if _, e := s.batch(ctx, s.db, id); e != nil {
		return nil, e
	}
	rows, e := s.rows(ctx, s.db, "items", map[string]any{"id": id, "size": size, "offset": int64(page) * int64(size)})
	if e != nil {
		return nil, e
	}
	items := []map[string]any{}
	for _, r := range rows {
		t, e := parseTrack(str(r, "track_json"))
		if e != nil {
			return nil, e
		}
		track, e := s.view(ctx, s.db, t)
		if e != nil {
			return nil, e
		}
		items = append(items, map[string]any{"id": r["id"], "track": track, "status": r["status"], "requestedQuality": r["requested_quality"], "resolvedQuality": r["resolved_quality"], "receivedBytes": r["received_bytes"], "totalBytes": r["total_bytes"], "songId": r["song_id"], "message": r["message"]})
	}
	total, e := s.scalar(ctx, s.db, "itemCount", map[string]any{"id": id})
	return map[string]any{"items": items, "total": total, "page": page, "size": size}, e
}
func (s *Service) Cancel(ctx context.Context, id string) error {
	e := s.transaction(ctx, func(tx store.DBTX) error {
		if e := s.lock(ctx, tx); e != nil {
			return e
		}
		if _, e := s.batch(ctx, tx, id); e != nil {
			return e
		}
		if _, e := s.exec(ctx, tx, "cancel", map[string]any{"id": id, "now": now()}); e != nil {
			return e
		}
		if _, e := s.exec(ctx, tx, "updateBatch", map[string]any{"id": id, "status": "CANCELLING", "now": now(), "started": nil, "finished": nil}); e != nil {
			return e
		}
		return s.settle(ctx, tx, id)
	})
	if e != nil {
		return e
	}
	s.jobsMu.Lock()
	jobs := make(map[string]context.CancelFunc, len(s.running))
	for id, cancel := range s.running {
		jobs[id] = cancel
	}
	s.jobsMu.Unlock()
	for item, cancel := range jobs {
		row, e := s.row(ctx, s.db, "item", map[string]any{"id": item})
		if e == nil && str(row, "batch_id") == id && str(row, "status") != "PUBLISHING" {
			cancel()
		}
	}
	return nil
}
func (s *Service) Retry(ctx context.Context, id string) (map[string]any, error) {
	row, e := s.row(ctx, s.db, "batch", map[string]any{"id": id})
	if e != nil {
		return nil, e
	}
	if row == nil {
		return nil, bad("下载记录不存在")
	}
	items, e := s.rows(ctx, s.db, "retryItems", map[string]any{"id": id})
	if e != nil {
		return nil, e
	}
	if len(items) == 0 {
		return nil, bad("此批次没有可重试的歌曲")
	}
	tracks := []Track{}
	ids := []string{}
	for _, r := range items {
		t, e := parseTrack(str(r, "track_json"))
		if e != nil {
			return nil, e
		}
		tracks = append(tracks, t)
		ids = append(ids, t.ID)
	}
	if e = s.remember(ctx, tracks); e != nil {
		return nil, e
	}
	name := str(row, "name")
	for search.UTF16Length(name) > 190 {
		r := []rune(name)
		name = string(r[:len(r)-1])
	}
	return s.Create(ctx, BatchInput{TrackIDs: ids, Quality: str(row, "quality"), Name: name + " · 重试"})
}
func (s *Service) finish(ctx context.Context, tx store.DBTX, id, state, quality, path, song, contentHash, message string) error {
	old, e := s.row(ctx, tx, "item", map[string]any{"id": id})
	if e != nil {
		return e
	}
	if old == nil || str(old, "status") == "CANCELLED" && state != "CANCELLED" {
		return nil
	}
	active := contains([]string{"PUBLISHING", "RESOLVING", "DOWNLOADING", "QUEUED"}, state)
	var key, resolved any
	if active {
		key = old["active_key"]
		resolved = old["resolved_active_key"]
	}
	_, e = s.exec(ctx, tx, "updateItem", map[string]any{"id": id, "status": state, "resolvedQuality": nullable(quality), "relativePath": nullable(path), "songId": nullable(song), "hash": nullable(contentHash), "message": clean(message), "now": now(), "activeKey": key, "resolvedActiveKey": resolved})
	if e != nil {
		return e
	}
	return s.project(ctx, tx, str(old, "batch_id"))
}
func (s *Service) settle(ctx context.Context, tx store.DBTX, id string) error {
	row, e := s.row(ctx, tx, "batch", map[string]any{"id": id})
	if e != nil || row == nil {
		return e
	}
	rows, e := s.rows(ctx, tx, "counts", map[string]any{"id": id})
	if e != nil {
		return e
	}
	counts := map[string]int64{}
	for _, r := range rows {
		counts[str(r, "status")] = number(r, "amount")
	}
	queued := counts["QUEUED"]
	active := counts["RESOLVING"] + counts["DOWNLOADING"] + counts["PUBLISHING"]
	success := counts["SUCCESS"] + counts["SKIPPED"]
	failed, interrupted, cancelled := counts["FAILED"], counts["INTERRUPTED"], counts["CANCELLED"]
	state := "CANCELLED"
	switch {
	case active > 0:
		state = "RUNNING"
		if str(row, "status") == "CANCELLING" {
			state = "CANCELLING"
		}
	case queued > 0:
		state = "QUEUED"
	case success > 0:
		state = "COMPLETED"
		if failed+interrupted+cancelled > 0 {
			state = "PARTIAL"
		}
	case failed > 0:
		state = "FAILED"
	case interrupted > 0:
		state = "INTERRUPTED"
	}
	var started, finished any
	if active > 0 {
		started = now()
	}
	if active+queued == 0 {
		finished = row["finished_at"]
		if finished == nil {
			finished = now()
		}
	}
	if str(row, "status") != state || row["started_at"] == nil && started != nil || row["finished_at"] == nil && finished != nil {
		if _, e = s.exec(ctx, tx, "updateBatch", map[string]any{"id": id, "status": state, "now": now(), "started": started, "finished": finished}); e != nil {
			return e
		}
	}
	return s.project(ctx, tx, id)
}

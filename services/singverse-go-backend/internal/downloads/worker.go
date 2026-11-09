package downloads

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/downloads/lxruntime"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

func (s *Service) recover(ctx context.Context) error {
	rows, e := s.rows(ctx, s.db, "transferring", nil)
	if e != nil {
		return e
	}
	for _, row := range rows {
		item, e := s.row(ctx, s.db, "item", map[string]any{"id": row["id"]})
		if e != nil {
			return e
		}
		root, e := s.checkedRoot(ctx, item, false)
		if e != nil {
			continue
		}
		temp, e := temporary(root, str(item, "id"))
		if e == nil {
			_ = os.Remove(temp)
		}
	}
	rows, e = s.rows(ctx, s.db, "recovering", nil)
	if e != nil {
		return e
	}
	for _, row := range rows {
		item, e := s.row(ctx, s.db, "item", map[string]any{"id": row["id"]})
		if e != nil {
			return e
		}
		e = s.library.Publication(ctx, str(item, "source_id"), func() error { return s.recoverFile(ctx, item) })
		if e == nil {
			e = s.finishAttachments(ctx, str(item, "id"))
		}
		if e != nil {
			s.failure(str(item, "id"), e)
		}
	}
	return s.transaction(ctx, func(tx store.DBTX) error {
		if e := s.lock(ctx, tx); e != nil {
			return e
		}
		if _, e := s.exec(ctx, tx, "interrupt", map[string]any{"now": now()}); e != nil {
			return e
		}
		rows, e := s.rows(ctx, tx, "activeBatches", nil)
		if e != nil {
			return e
		}
		for _, row := range rows {
			if e = s.settle(ctx, tx, str(row, "id")); e != nil {
				return e
			}
		}
		return nil
	})
}
func (s *Service) schedule() {
	defer s.wg.Done()
	timer := time.NewTimer(time.Second)
	defer timer.Stop()
	for {
		select {
		case <-s.ctx.Done():
			return
		case <-timer.C:
			if e := s.pump(); e != nil && s.ctx.Err() == nil {
				s.log.Warn("download scheduling deferred")
			}
			timer.Reset(time.Second)
		}
	}
}
func (s *Service) pump() error {
	rows, e := s.rows(s.ctx, s.db, "recovering", nil)
	if e != nil {
		return e
	}
	queued, e := s.rows(s.ctx, s.db, "queued", nil)
	if e != nil {
		return e
	}
	rows = append(rows, queued...)
	for _, candidate := range rows {
		id := str(candidate, "id")
		s.jobsMu.Lock()
		if len(s.running) >= 2 {
			s.jobsMu.Unlock()
			break
		}
		if s.running[id] != nil {
			s.jobsMu.Unlock()
			continue
		}
		ctx, cancel := context.WithCancel(s.ctx)
		s.running[id] = cancel
		s.jobsMu.Unlock()
		claimed := false
		e = s.transaction(ctx, func(tx store.DBTX) error {
			if e := s.lock(ctx, tx); e != nil {
				return e
			}
			item, e := s.row(ctx, tx, "item", map[string]any{"id": id})
			if e != nil || item == nil {
				return e
			}
			if str(item, "status") == "PUBLISHING" {
				claimed = true
				return nil
			}
			res, e := s.exec(ctx, tx, "claim", map[string]any{"id": id, "now": now()})
			if e != nil {
				return e
			}
			n, e := res.RowsAffected()
			if e != nil {
				return e
			}
			claimed = n == 1
			if claimed {
				return s.settle(ctx, tx, str(item, "batch_id"))
			}
			return nil
		})
		if e != nil || !claimed {
			cancel()
			s.jobsMu.Lock()
			delete(s.running, id)
			s.jobsMu.Unlock()
			if e != nil {
				return e
			}
			continue
		}
		s.wg.Add(1)
		go func() {
			defer s.wg.Done()
			defer cancel()
			defer func() { s.jobsMu.Lock(); delete(s.running, id); s.jobsMu.Unlock() }()
			item, e := s.row(ctx, s.db, "item", map[string]any{"id": id})
			if e == nil && item != nil {
				if str(item, "status") == "PUBLISHING" {
					e = s.library.Publication(ctx, str(item, "source_id"), func() error { return s.recoverFile(ctx, item) })
					if e == nil {
						e = s.finishAttachments(ctx, id)
					}
				} else {
					e = s.download(ctx, item)
				}
			}
			if e != nil {
				s.failure(id, e)
			}
			s.finalizeBatch(id)
		}()
	}
	return nil
}
func (s *Service) checkedRoot(ctx context.Context, item map[string]any, probe bool) (string, error) {
	source, e := s.row(ctx, s.db, "publication-source", map[string]any{"id": item["source_id"]})
	if e != nil {
		return "", e
	}
	if source == nil || str(source, "source_type") != "LOCAL" || str(source, "location") != str(item, "source_path") {
		return "", conflict("下载保存位置发生变化，请检查媒体库设置")
	}
	return writable(str(item, "source_path"), probe)
}
func (s *Service) download(ctx context.Context, item map[string]any) error {
	id := str(item, "id")
	track, e := parseTrack(str(item, "track_json"))
	if e != nil {
		return e
	}
	script, e := s.script(ctx, s.db, str(item, "script_id"))
	if e != nil {
		return e
	}
	root, e := s.checkedRoot(ctx, item, true)
	if e != nil {
		return e
	}
	temp, e := temporary(root, id)
	if e != nil {
		return e
	}
	defer func() {
		cleanupCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		row, e := s.row(cleanupCtx, s.db, "item", map[string]any{"id": id})
		if e == nil && str(row, "status") != "PUBLISHING" {
			_ = os.Remove(temp)
		}
	}()
	cap, e := capabilities(script)
	if e != nil {
		return e
	}
	available := []string{str(item, "requested_quality")}
	if available[0] == "auto" {
		available = cap[track.Platform]
	}
	body, e := s.body(script)
	if e != nil {
		return e
	}
	address, chosen := "", ""
	for _, quality := range available {
		if e = ctx.Err(); e != nil {
			return e
		}
		asset, e := s.row(ctx, s.db, "asset", map[string]any{"sourceId": item["source_id"], "trackKey": track.ID, "quality": quality})
		if e != nil {
			return e
		}
		if asset != nil && present(withPath(asset, root)) {
			return s.transaction(ctx, func(tx store.DBTX) error {
				if e := s.lock(ctx, tx); e != nil {
					return e
				}
				return s.finish(ctx, tx, id, "SKIPPED", quality, str(asset, "relative_path"), str(asset, "song_id"), str(asset, "content_hash"), "已在曲库，保留已有文件")
			})
		}
		address, e = s.runtime.Resolve(ctx, body, str(script, "environment"), track.Platform, quality, track.MusicInfo)
		if e == nil {
			chosen = quality
			break
		}
		if str(item, "requested_quality") != "auto" || !errors.Is(e, lxruntime.ErrQualityUnavailable) {
			return e
		}
	}
	if address == "" {
		return bad("音源未能提供这些音质，请更换音源或歌曲版本")
	}
	reserved, e := s.reserveQuality(ctx, item, chosen, root)
	if e != nil || !reserved {
		return e
	}
	if e = os.Remove(temp); e != nil && !os.IsNotExist(e) {
		return bad("下载临时文件无法清理")
	}
	file, e := s.network.Download(ctx, address, temp, func(received int64, total *int64) error {
		return s.transaction(ctx, func(tx store.DBTX) error {
			res, e := s.exec(ctx, tx, "progress", map[string]any{"id": id, "received": received, "total": total, "now": now()})
			if e != nil {
				return e
			}
			n, e := res.RowsAffected()
			if e != nil {
				return e
			}
			if n == 0 {
				return context.Canceled
			}
			return s.project(ctx, tx, str(item, "batch_id"))
		})
	})
	if e != nil {
		return e
	}
	if e = ctx.Err(); e != nil {
		return e
	}
	e = s.library.Publication(ctx, str(item, "source_id"), func() error { return s.publish(ctx, item, track, chosen, temp, file) })
	if e != nil {
		return e
	}
	return s.finishAttachments(ctx, id)
}

// Optional sidecar requests run after publication, outside its source gate and
// database transaction. Recovery and ordinary downloads share this finish path.
func (s *Service) finishAttachments(ctx context.Context, id string) error {
	current, e := s.row(ctx, s.db, "item", map[string]any{"id": id})
	if e != nil {
		return e
	}
	if str(current, "status") == "SUCCESS" {
		root, e := s.checkedRoot(ctx, current, false)
		if e != nil {
			return e
		}
		track, e := parseTrack(str(current, "track_json"))
		if e != nil {
			return e
		}
		path, e := safeFile(root, str(current, "relative_path"))
		if e != nil {
			return e
		}
		note := s.attachments(ctx, track, path)
		return s.transaction(ctx, func(tx store.DBTX) error {
			if e := s.lock(ctx, tx); e != nil {
				return e
			}
			if e := s.published(ctx, tx, str(current, "song_id")); e != nil {
				return e
			}
			return s.finish(ctx, tx, id, "SUCCESS", str(current, "resolved_quality"), str(current, "relative_path"), str(current, "song_id"), str(current, "content_hash"), note)
		})
	}
	return nil
}
func (s *Service) reserveQuality(ctx context.Context, item map[string]any, quality, root string) (bool, error) {
	ctx, cancel := context.WithTimeout(ctx, 6*time.Minute)
	defer cancel()
	id := str(item, "id")
	key := activeKey(str(item, "source_id"), str(item, "track_key"), quality)
	for {
		state := 0
		e := s.transaction(ctx, func(tx store.DBTX) error {
			if e := s.lock(ctx, tx); e != nil {
				return e
			}
			current, e := s.row(ctx, tx, "item", map[string]any{"id": id})
			if e != nil {
				return e
			}
			if str(current, "status") == "CANCELLED" {
				state = -1
				return nil
			}
			asset, e := s.row(ctx, tx, "asset", map[string]any{"sourceId": item["source_id"], "trackKey": item["track_key"], "quality": quality})
			if e != nil {
				return e
			}
			if asset != nil && present(withPath(asset, root)) {
				state = -1
				return s.finish(ctx, tx, id, "SKIPPED", quality, str(asset, "relative_path"), str(asset, "song_id"), str(asset, "content_hash"), "已在曲库，保留已有文件")
			}
			owner, e := s.row(ctx, tx, "qualityOwner", map[string]any{"key": key})
			if e != nil {
				return e
			}
			if owner != nil && str(owner, "id") != id {
				return nil
			}
			res, e := s.exec(ctx, tx, "reserveQuality", map[string]any{"id": id, "key": key, "quality": quality, "now": now()})
			if e != nil {
				return e
			}
			n, e := res.RowsAffected()
			state = int(n)
			return e
		})
		if e != nil {
			return false, e
		}
		if state != 0 {
			return state > 0, nil
		}
		timer := time.NewTimer(250 * time.Millisecond)
		select {
		case <-ctx.Done():
			timer.Stop()
			return false, ctx.Err()
		case <-timer.C:
		}
	}
}
func (s *Service) publish(ctx context.Context, item map[string]any, t Track, quality, temp string, file Download) error {
	root, e := s.checkedRoot(ctx, item, false)
	if e != nil {
		return e
	}
	id := str(item, "id")
	e = s.transaction(ctx, func(tx store.DBTX) error {
		if e := s.lock(ctx, tx); e != nil {
			return e
		}
		current, e := s.row(ctx, tx, "item", map[string]any{"id": id})
		if e != nil {
			return e
		}
		if str(current, "status") == "CANCELLED" {
			return nil
		}
		prior, e := s.row(ctx, tx, "asset", map[string]any{"sourceId": item["source_id"], "trackKey": t.ID, "quality": quality})
		if e != nil {
			return e
		}
		if prior != nil && present(withPath(prior, root)) {
			return s.finish(ctx, tx, id, "SKIPPED", quality, str(prior, "relative_path"), str(prior, "song_id"), str(prior, "content_hash"), "已在曲库，保留已有文件")
		}
		relative, e := filename(root, t, quality, file.Extension)
		if e != nil {
			return e
		}
		return s.finish(ctx, tx, id, "PUBLISHING", quality, relative, songID(str(item, "source_id"), relative), file.Hash, "正在加入曲库")
	})
	if e != nil {
		return e
	}
	current, e := s.row(ctx, s.db, "item", map[string]any{"id": id})
	if e != nil {
		return e
	}
	if str(current, "status") != "PUBLISHING" {
		return nil
	}
	path, e := safeFile(root, str(current, "relative_path"))
	if e != nil {
		return e
	}
	if e = publishFile(temp, path); e != nil {
		return e
	}
	return s.recoverFile(ctx, current)
}
func (s *Service) recoverFile(ctx context.Context, item map[string]any) error {
	id := str(item, "id")
	root, e := s.checkedRoot(ctx, item, false)
	if e != nil {
		return e
	}
	path, e := safeFile(root, str(item, "relative_path"))
	if e != nil {
		return e
	}
	temp, e := temporary(root, id)
	if e != nil {
		return e
	}
	if _, e = os.Lstat(path); os.IsNotExist(e) {
		if info, e := os.Lstat(temp); e == nil && info.Mode().IsRegular() {
			content, e := hashFile(ctx, temp)
			if e != nil {
				return e
			}
			if content != str(item, "content_hash") {
				return conflict("已发布文件不完整或不可读取，请检查挂载后重试")
			}
			if e = publishFile(temp, path); e != nil {
				return e
			}
		}
	}
	info, e := os.Lstat(path)
	if e != nil || !info.Mode().IsRegular() {
		return conflict("已发布文件不完整或不可读取，请检查挂载后重试")
	}
	real, e := filepath.EvalSymlinks(path)
	if e != nil || real != path {
		return bad("歌曲文件路径无效")
	}
	content, e := hashFile(ctx, path)
	if e != nil {
		return e
	}
	if content != str(item, "content_hash") {
		return conflict("已发布文件不完整或不可读取，请检查挂载后重试")
	}
	track, e := parseTrack(str(item, "track_json"))
	if e != nil {
		return e
	}
	e = s.transaction(ctx, func(tx store.DBTX) error {
		// Follow the same lock order as source/profile changes on MySQL.
		if _, e := s.exec(ctx, tx, "publication-lock", nil); e != nil {
			return e
		}
		if e := s.lock(ctx, tx); e != nil {
			return e
		}
		current, e := s.row(ctx, tx, "item", map[string]any{"id": id})
		if e != nil {
			return e
		}
		if str(current, "status") != "PUBLISHING" {
			return nil
		}
		source, e := s.row(ctx, tx, "publication-source", map[string]any{"id": item["source_id"]})
		if e != nil {
			return e
		}
		if source == nil || str(source, "source_type") != "LOCAL" || str(source, "location") != root {
			return conflict("媒体库路径已改变，请检查原下载文件")
		}
		profile, e := s.row(ctx, tx, "publication-parser", nil)
		if e != nil {
			return e
		}
		relative, song := str(item, "relative_path"), str(item, "song_id")
		assetID := hash(str(item, "source_id") + ":" + track.ID + ":" + str(item, "resolved_quality"))
		modified := info.ModTime().UTC().Format(time.RFC3339Nano)
		if _, e = s.exec(ctx, tx, "insertSong", map[string]any{"songId": song, "sourceId": item["source_id"], "relativePath": relative, "pathHash": hash(relative), "size": info.Size(), "modified": modified, "now": now(), "profileId": source["parser_profile_id"], "revision": number(profile, "revision"), "result": encode(seed(track, filepath.Base(path)))}); e != nil {
			return e
		}
		if e = s.published(ctx, tx, song); e != nil {
			return e
		}
		if _, e = s.exec(ctx, tx, "deleteAsset", map[string]any{"id": assetID}); e != nil {
			return e
		}
		if _, e = s.exec(ctx, tx, "insertAsset", map[string]any{"id": assetID, "sourceId": item["source_id"], "trackKey": track.ID, "quality": item["resolved_quality"], "relativePath": relative, "songId": song, "size": info.Size(), "modified": modified, "hash": item["content_hash"], "trackJson": item["track_json"], "now": now()}); e != nil {
			return e
		}
		return s.finish(ctx, tx, id, "SUCCESS", str(item, "resolved_quality"), relative, song, str(item, "content_hash"), "")
	})
	if e == nil {
		_ = os.Remove(temp)
		s.notify()
	}
	return e
}
func (s *Service) failure(id string, cause error) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_ = s.transaction(ctx, func(tx store.DBTX) error {
		if e := s.lock(ctx, tx); e != nil {
			return e
		}
		item, e := s.row(ctx, tx, "item", map[string]any{"id": id})
		if e != nil || item == nil {
			return e
		}
		if str(item, "status") == "CANCELLED" || str(item, "status") == "SUCCESS" {
			return nil
		}
		if str(item, "status") == "PUBLISHING" && (statusError(cause) == 500 || s.ctx.Err() != nil) {
			return nil
		}
		state, message := "FAILED", "下载失败，请检查音源、网络、挂载和剩余空间后重试"
		if statusError(cause) < 500 {
			message = cause.Error()
		}
		if errors.Is(cause, context.Canceled) || errors.Is(cause, context.DeadlineExceeded) {
			state = "INTERRUPTED"
			message = "下载已中断，重新尝试将从头下载"
		}
		return s.finish(ctx, tx, id, state, str(item, "resolved_quality"), str(item, "relative_path"), str(item, "song_id"), str(item, "content_hash"), message)
	})
}
func (s *Service) finalizeBatch(id string) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_ = s.transaction(ctx, func(tx store.DBTX) error {
		if e := s.lock(ctx, tx); e != nil {
			return e
		}
		item, e := s.row(ctx, tx, "item", map[string]any{"id": id})
		if e != nil || item == nil {
			return e
		}
		return s.settle(ctx, tx, str(item, "batch_id"))
	})
}
func (s *Service) attachments(ctx context.Context, t Track, path string) string {
	warnings := []string{}
	stem := strings.TrimSuffix(path, filepath.Ext(path))
	lyrics, e := s.catalog.Lyrics(ctx, t)
	if e != nil {
		warnings = append(warnings, "歌词获取失败，可稍后补充")
	} else if strings.TrimSpace(lyrics) == "" || len(lyrics) > 256*1024 {
		warnings = append(warnings, "未获取到歌词")
	} else if e = writeAttachment(stem+".lrc", []byte(lyrics)); e != nil {
		warnings = append(warnings, "歌词保存失败，可稍后补充")
	}
	if t.CoverURL == "" {
		warnings = append(warnings, "未获取到封面")
	} else {
		reply, e := s.network.Request(ctx, t.CoverURL, "GET", nil, nil, 2_000_000, false)
		extension := ""
		if e == nil && reply.Status == 200 {
			extension, e = validateCover(reply.Bytes)
		}
		if e != nil || extension == "" {
			warnings = append(warnings, "封面获取失败，可稍后补充")
		} else if e = writeAttachment(stem+"."+extension, reply.Bytes); e != nil {
			warnings = append(warnings, "封面保存失败，可稍后补充")
		}
	}
	return strings.Join(warnings, "；")
}
func writeAttachment(path string, data []byte) error {
	if _, e := os.Lstat(path); e == nil {
		return nil
	}
	file, e := os.CreateTemp(filepath.Dir(path), ".singverse-attachment-*.tmp")
	if e != nil {
		return e
	}
	defer os.Remove(file.Name())
	if _, e = file.Write(data); e != nil {
		file.Close()
		return e
	}
	if e = file.Sync(); e != nil {
		file.Close()
		return e
	}
	if e = file.Close(); e != nil {
		return e
	}
	if e = os.Link(file.Name(), path); os.IsExist(e) {
		return nil
	}
	return e
}

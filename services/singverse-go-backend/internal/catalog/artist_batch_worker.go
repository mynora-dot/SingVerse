package catalog

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"

	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/metadata"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

// Explicit batches share the single artwork worker and the existing generation barrier.
// The queue and item results survive navigation, disconnects and process restarts.
func (s *Service) processArtworkBatch(ctx context.Context) bool {
	var job map[string]any
	var titles []string
	refs := []metadata.ArtistRef{}
	claimed := false
	e := store.WithinTx(ctx, s.db, func(tx store.DBTX) error {
		if e := s.exec(ctx, tx, "a_lock"); e != nil {
			return e
		}
		var e error
		job, e = one(ctx, tx, s.q["ab_pending"])
		if errors.Is(e, sql.ErrNoRows) {
			return nil
		}
		if e != nil {
			return e
		}
		claimed = true
		artistID := str(job["artist_id"])
		groups, e := s.ensureArtists(ctx, tx)
		if e != nil {
			return e
		}
		blocked, e := s.artworkBlocked(ctx, tx, artistID, "PORTRAIT")
		if e != nil {
			return e
		}
		if len(groups[artistID]) == 0 || blocked {
			message := "保留已有封面或手动移除设置"
			if len(groups[artistID]) == 0 {
				message = "歌手已无关联歌曲，跳过获取"
			}
			if e = s.exec(ctx, tx, "ab_itemState", "SKIPPED", message, 0, now(), job["id"]); e != nil {
				return e
			}
			batch := str(job["batch_id"])
			job = nil
			return s.finishArtworkBatch(ctx, tx, batch)
		}
		for i, raw := range groups[artistID] {
			if i >= 3 {
				break
			}
			titles = append(titles, str(partyProjection(raw)["title"]))
			meta := obj(raw["metadata"])
			if meta["stale"] != true && str(obj(obj(meta["origins"])["artists"])["kind"]) == "PLATFORM" {
				b, _ := json.Marshal(obj(meta["track"])["artistRefs"])
				var found []metadata.ArtistRef
				if json.Unmarshal(b, &found) == nil {
					refs = append(refs, found...)
				}
			}
		}
		if e = s.closeArtworkReviews(ctx, tx, artistID, "PORTRAIT", "SKIPPED", "候选已由新的取图操作替代"); e != nil {
			return e
		}
		if e = s.exec(ctx, tx, "a_retryImage", artistID, "PORTRAIT"); e != nil {
			return e
		}
		fresh, e := one(ctx, tx, s.q["a_imageFetch"], artistID, "PORTRAIT")
		if e != nil {
			return e
		}
		job["generation"] = fresh["generation"]
		if e = s.exec(ctx, tx, "a_imageFetchState", "PROCESSING", 0, "", artistID, "PORTRAIT", job["generation"]); e != nil {
			return e
		}
		if e = s.exec(ctx, tx, "ab_itemState", "PROCESSING", "正在核对歌手及关联歌曲", job["generation"], now(), job["id"]); e != nil {
			return e
		}
		return s.finishArtworkBatch(ctx, tx, str(job["batch_id"]))
	})
	if e != nil || job == nil {
		return claimed
	}
	chosen, address, data, review, fetchError := s.lookupArtwork(ctx, str(job["artist_name"]), titles, refs, "PORTRAIT", str(job["provider"]))
	if ctx.Err() != nil {
		return true
	}
	_, e = s.tx(ctx, func(tx store.DBTX) (any, error) {
		if e := s.exec(ctx, tx, "a_lock"); e != nil {
			return nil, e
		}
		item, e := one(ctx, tx, s.q["ab_item"], job["id"])
		if e != nil {
			return nil, e
		}
		if str(item["status"]) != "PROCESSING" {
			return nil, nil
		}
		artistID := str(job["artist_id"])
		fresh, e := one(ctx, tx, s.q["a_imageFetch"], artistID, "PORTRAIT")
		if e != nil {
			return nil, e
		}
		blocked, e := s.artworkBlocked(ctx, tx, artistID, "PORTRAIT")
		if e != nil {
			return nil, e
		}
		state, message, fetchState := "NO_MATCH", "没有找到可靠匹配，可换个平台或上传封面", "NO_MATCH"
		changed := num(fresh["generation"]) != num(job["generation"]) || blocked
		switch {
		case changed:
			state, message = "SKIPPED", "获取期间图片或操作已变化，已保留最新结果"
		case fetchError != nil:
			state, message, fetchState = "FAILED", "平台请求或图片校验未成功，可换个平台重试", "FAILED"
		case len(review) > 0:
			state, message, fetchState = "REVIEW", "找到候选，请打开歌手详情核对后确认", "NEEDS_REVIEW"
			if e = s.exec(ctx, tx, "a_imageCandidates", encode(review), artistID, "PORTRAIT", job["generation"]); e != nil {
				return nil, e
			}
		case chosen != nil && data != nil:
			if e = s.storeArtwork(ctx, tx, artistID, "PORTRAIT", data, *chosen, address, "AUTO"); e != nil {
				return nil, e
			}
			state, message, fetchState = "APPLIED", "封面已保存到 NAS", "READY"
		}
		if !changed {
			if e = s.exec(ctx, tx, "a_imageFetchState", fetchState, 0, "", artistID, "PORTRAIT", job["generation"]); e != nil {
				return nil, e
			}
		}
		if e = s.exec(ctx, tx, "ab_itemState", state, message, job["generation"], now(), job["id"]); e != nil {
			return nil, e
		}
		return nil, s.finishArtworkBatch(ctx, tx, str(job["batch_id"]))
	})
	if e != nil {
		s.log.WarnContext(ctx, "artist artwork result deferred")
		_ = s.exec(ctx, s.db, "ab_release", now(), job["id"], job["generation"])
	}
	return true
}

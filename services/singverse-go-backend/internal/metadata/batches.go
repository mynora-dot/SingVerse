package metadata

import (
	"context"
	"database/sql"
	"errors"
	"github.com/gin-gonic/gin"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
	"math"
	"math/rand/v2"
	"regexp"
	"strconv"
	"strings"
	"time"
)

var idPattern = regexp.MustCompile(`^[A-Za-z0-9-]{1,36}$`)

func (s *Service) batchRow(ctx context.Context, db store.DBTX, id string) (map[string]any, error) {
	r, e := s.row(ctx, db, "meta-batch", map[string]any{"id": id})
	return r, absent(e, "刮削批次不存在")
}
func (s *Service) requireItem(ctx context.Context, db store.DBTX, batch, id string) (map[string]any, error) {
	r, e := s.row(ctx, db, "meta-item", map[string]any{"id": id})
	if errors.Is(e, sql.ErrNoRows) || e == nil && text(r["batch_id"]) != batch {
		return nil, missing("刮削明细不存在")
	}
	return r, e
}
func (s *Service) itemStatus(ctx context.Context, db store.DBTX, id, expected, status string, candidates, message any) (sql.Result, error) {
	return s.exec(ctx, db, "meta-itemStatus", map[string]any{"id": id, "expected": expected, "status": status, "candidates": candidates, "error": message, "now": now()})
}
func (s *Service) batchStatus(ctx context.Context, db store.DBTX, id, expected, status string) error {
	r, e := s.exec(ctx, db, "meta-batchStatus", map[string]any{"id": id, "expected": expected, "status": status, "now": now()})
	if e != nil {
		return e
	}
	n, e := r.RowsAffected()
	if e != nil {
		return e
	}
	if n != 1 {
		return conflict("批次状态已经变化，请刷新后重试")
	}
	return s.project(ctx, db, "METADATA", id)
}
func (s *Service) startHTTP(c *gin.Context) {
	var in StartInput
	if !s.decode(c, &in) {
		return
	}
	ids := []string{}
	for _, id := range in.SongIDs {
		if !idPattern.MatchString(id) {
			s.fail(c, bad("歌曲 ID 无效"))
			return
		}
		if !contains(ids, id) {
			ids = append(ids, id)
		}
	}
	if in.All && len(ids) > 0 || !in.All && (len(ids) == 0 || len(ids) > 500) {
		s.fail(c, bad("请选择 1～500 首歌曲，全量与指定歌曲不能同时提供"))
		return
	}
	if in.SourceID != nil && (!idPattern.MatchString(*in.SourceID) || !in.All || len(ids) > 0) {
		s.fail(c, bad("媒体来源只能用于指定来源的全量刮削"))
		return
	}
	ctx := c.Request.Context()
	id := uuid()
	e := s.transaction(ctx, func(tx store.DBTX) error {
		cfg, e := s.settings(ctx, tx, true)
		if e != nil {
			return e
		}
		active, e := s.rows(ctx, tx, "meta-active", nil)
		if e != nil {
			return e
		}
		if len(active) > 0 {
			return conflict("已有运行或暂停的批次，请先完成或恢复当前批次")
		}
		if in.SourceID != nil {
			if _, e = s.row(ctx, tx, "source", map[string]any{"id": *in.SourceID}); e != nil {
				return absent(e, "媒体来源不存在")
			}
		}
		songs, e := s.library.LibrarySongs(ctx, tx)
		if e != nil {
			return e
		}
		identities, e := s.library.LibraryIdentities(ctx, tx)
		if e != nil {
			return e
		}
		targets := []map[string]any{}
		pending := 0
		for _, song := range songs {
			if in.SourceID != nil && text(song["sourceId"]) != *in.SourceID {
				continue
			}
			if !in.All && !contains(ids, text(song["id"])) {
				continue
			}
			song["_fingerprint"] = identities[text(song["id"])]
			targets = append(targets, song)
			if !confirmed(song) {
				pending++
			}
			if len(targets) > 5000 {
				return bad("单批次最多 5000 首，请分批选择歌曲")
			}
		}
		if !in.All && len(targets) != len(ids) {
			return conflict("部分歌曲已移除，请刷新列表后重试")
		}
		if pending == 0 {
			return bad("没有待刮削歌曲；已应用的歌曲可从单曲入口重新匹配")
		}
		mode := "SELECTED"
		if in.All {
			mode = "ALL"
		}
		if in.SourceID != nil {
			mode = "SOURCE"
		}
		at := now()
		if _, e = s.exec(ctx, tx, "meta-insertBatch", map[string]any{"id": id, "mode": mode, "providers": encode(cfg.Providers), "autoApply": cfg.AutoApply, "threshold": cfg.Threshold, "skipped": len(targets) - pending, "now": at}); e != nil {
			return e
		}
		for _, song := range targets {
			itemID := uuid()
			values := current(song)
			if _, e = s.exec(ctx, tx, "meta-insertItem", map[string]any{"id": itemID, "batchId": id, "songId": song["id"], "fingerprint": fingerprint(song), "title": values["title"], "artists": encode(values["artists"]), "now": at}); e != nil {
				return e
			}
			if confirmed(song) {
				if _, e = s.itemStatus(ctx, tx, itemID, "PENDING", "SKIPPED_EXISTING", nil, "已有确认元数据且文件未变化，本批次保留原结果"); e != nil {
					return e
				}
			}
		}
		if _, e = s.exec(ctx, tx, "meta-pruneCandidates", map[string]any{"before": time.Now().UTC().Add(-7 * 24 * time.Hour).Format(time.RFC3339Nano)}); e != nil {
			return e
		}
		return s.project(ctx, tx, "METADATA", id)
	})
	if e != nil {
		s.fail(c, e)
		return
	}
	out, e := s.details(ctx, s.db, id, "", 0, 20, "", "DEFAULT")
	if e != nil {
		s.fail(c, e)
		return
	}
	c.JSON(201, out)
}
func nullable(s string) any {
	if s == "" {
		return nil
	}
	return s
}
func decimal(v any) float64 {
	n, e := strconv.ParseFloat(text(v), 64)
	if e != nil {
		return 0
	}
	return n
}
func (s *Service) details(ctx context.Context, db store.DBTX, id, filter string, page, size int, keyword, sort string) (map[string]any, error) {
	if page < 0 || page > 1_000_000 || size < 1 || size > 100 {
		return nil, bad("分页参数无效")
	}
	if filter != "" && !contains([]string{"PENDING", "PROCESSING", "REVIEW", "FAILED", "AUTO_APPLIED", "MANUAL_APPLIED", "SKIPPED_EXISTING", "APPLIED"}, filter) {
		return nil, bad("批次状态筛选无效")
	}
	if !contains([]string{"DEFAULT", "TITLE", "SCORE_DESC", "UPDATED_DESC"}, sort) {
		return nil, bad("排序参数无效")
	}
	keyword, e := clean(keyword, 200)
	if e != nil {
		return nil, e
	}
	pattern := nullable("")
	if keyword != "" {
		pattern = "%" + strings.NewReplacer("!", "!!", "%", "!%", "_", "!_").Replace(keyword) + "%"
	}
	row, e := s.batchRow(ctx, db, id)
	if e != nil {
		return nil, e
	}
	counts, e := s.rows(ctx, db, "meta-counts", map[string]any{"batchId": id})
	if e != nil {
		return nil, e
	}
	count := map[string]int64{}
	for _, r := range counts {
		count[text(r["status"])] = number(r["amount"])
	}
	pending, processing, review, applied, failed, skipped := count["PENDING"], count["PROCESSING"], count["REVIEW"], count["AUTO_APPLIED"]+count["MANUAL_APPLIED"], count["FAILED"], number(row["skipped_existing"])
	processable := pending + processing + review + applied + failed
	args := map[string]any{"batchId": id, "status": nullable(filter), "keyword": pattern, "sort": sort, "limit": size, "offset": int64(page) * int64(size)}
	rows, e := s.rows(ctx, db, "meta-viewItems", args)
	if e != nil {
		return nil, e
	}
	items := []map[string]any{}
	threshold := decimal(row["threshold"])
	for _, r := range rows {
		candidates := []Candidate{}
		if r["candidates_json"] != nil {
			if e = decode(r["candidates_json"], &candidates); e != nil {
				return nil, e
			}
		}
		confidence := "NONE"
		if len(candidates) > 0 {
			if candidates[0].Score >= threshold {
				confidence = "HIGH"
			} else if candidates[0].Score >= .8 {
				confidence = "LOW"
			}
		}
		cover := false
		if r["metadata_fields"] != nil && r["source_id"] != nil {
			var fields []string
			_ = decode(r["metadata_fields"], &fields)
			song := map[string]any{"id": r["song_id"], "fileSize": r["file_size"], "modifiedAt": r["modified_at"]}
			cover = contains(fields, "cover") && fingerprint(song) == text(r["metadata_fingerprint"])
		}
		items = append(items, map[string]any{"id": r["id"], "songId": r["song_id"], "title": r["title"], "artists": stringsFromJSON(r["artists_json"]), "status": r["status"], "candidates": candidates, "error": r["error_message"], "updatedAt": r["updated_at"], "confidence": confidence, "coverAvailable": cover, "sourceId": r["source_id"], "relativePath": r["relative_path"]})
	}
	total, e := s.row(ctx, db, "meta-viewCount", args)
	if e != nil {
		return nil, e
	}
	return map[string]any{"id": id, "mode": row["mode"], "status": row["status"], "total": processable + skipped, "completed": review + applied + failed + skipped, "pending": pending, "processing": processing, "review": review, "applied": applied, "failed": failed, "skippedExisting": skipped, "autoApply": boolean(row["auto_apply"]), "threshold": threshold, "createdAt": row["created_at"], "updatedAt": row["updated_at"], "items": items, "page": page, "size": size, "totalElements": number(total["count"]), "processableTotal": processable, "legacySkipped": max(int64(0), skipped-count["SKIPPED_EXISTING"]), "observedAt": now()}, nil
}
func stringsFromJSON(v any) []string { out := []string{}; _ = decode(v, &out); return out }
func (s *Service) latestHTTP(c *gin.Context) {
	ctx := c.Request.Context()
	rows, e := s.rows(ctx, s.db, "meta-latest", nil)
	if e != nil {
		s.fail(c, e)
		return
	}
	if len(rows) == 0 {
		c.Status(204)
		return
	}
	out, e := s.details(ctx, s.db, text(rows[0]["id"]), "", 0, 20, "", "DEFAULT")
	if e != nil {
		s.fail(c, e)
		return
	}
	c.JSON(200, out)
}
func (s *Service) batchHTTP(c *gin.Context) {
	page, e := strconv.Atoi(c.DefaultQuery("page", "0"))
	if e != nil {
		s.fail(c, bad("分页参数无效"))
		return
	}
	size, e := strconv.Atoi(c.DefaultQuery("size", "20"))
	if e != nil {
		s.fail(c, bad("分页参数无效"))
		return
	}
	out, e := s.details(c.Request.Context(), s.db, c.Param("id"), c.Query("status"), page, size, c.Query("q"), c.DefaultQuery("sort", "DEFAULT"))
	if e != nil {
		s.fail(c, e)
		return
	}
	c.JSON(200, out)
}
func (s *Service) batchAction(c *gin.Context) {
	ctx := c.Request.Context()
	id := c.Param("id")
	e := s.transaction(ctx, func(tx store.DBTX) error {
		row, e := s.batchRow(ctx, tx, id)
		if e != nil {
			return e
		}
		expected := text(row["status"])
		next := ""
		switch {
		case strings.HasSuffix(c.FullPath(), "/pause"):
			if expected != "RUNNING" {
				return conflict("当前批次不是运行状态")
			}
			next = "PAUSED"
		case strings.HasSuffix(c.FullPath(), "/resume"):
			cfg, e := s.settings(ctx, tx, true)
			if e != nil {
				return e
			}
			var platforms []string
			if e = decode(row["providers_json"], &platforms); e != nil {
				return e
			}
			for _, p := range platforms {
				if !contains(cfg.Providers, p) {
					return conflict("批次平台已停用，请先重新启用对应平台")
				}
			}
			if expected != "PAUSED" {
				return conflict("当前批次不是暂停状态")
			}
			next = "RUNNING"
		case strings.HasSuffix(c.FullPath(), "/cancel"):
			if expected != "RUNNING" && expected != "PAUSED" {
				return conflict("当前批次已经结束")
			}
			next = "CANCELLED"
		}
		return s.batchStatus(ctx, tx, id, expected, next)
	})
	if e != nil {
		s.fail(c, e)
		return
	}
	out, e := s.details(ctx, s.db, id, "", 0, 20, "", "DEFAULT")
	if e != nil {
		s.fail(c, e)
		return
	}
	c.JSON(200, out)
}
func (s *Service) retryHTTP(c *gin.Context) {
	ctx := c.Request.Context()
	id, itemID := c.Param("id"), c.Param("itemId")
	e := s.transaction(ctx, func(tx store.DBTX) error {
		if _, e := s.settings(ctx, tx, true); e != nil {
			return e
		}
		item, e := s.requireItem(ctx, tx, id, itemID)
		if e != nil {
			return e
		}
		previous := text(item["status"])
		allowed := previous == "REVIEW" || previous == "FAILED"
		if previous == "MANUAL_APPLIED" {
			song, e := s.song(ctx, tx, text(item["song_id"]))
			allowed = e == nil && !confirmed(song)
		}
		if !allowed {
			return conflict("仅待审核、失败或尚未保存确认元数据的项可以重试")
		}
		batch, e := s.batchRow(ctx, tx, id)
		if e != nil {
			return e
		}
		status := text(batch["status"])
		if status == "CANCELLED" {
			return conflict("已终止的批次不能重试，请新建批次")
		}
		if status == "COMPLETED" {
			active, e := s.rows(ctx, tx, "meta-active", nil)
			if e != nil {
				return e
			}
			if len(active) > 0 {
				return conflict("已有其他活动批次，请先完成当前批次")
			}
			if e = s.batchStatus(ctx, tx, id, "COMPLETED", "RUNNING"); e != nil {
				return e
			}
		}
		if _, e = s.itemStatus(ctx, tx, itemID, previous, "PENDING", nil, nil); e != nil {
			return e
		}
		return s.project(ctx, tx, "METADATA", id)
	})
	if e != nil {
		s.fail(c, e)
		return
	}
	out, e := s.details(ctx, s.db, id, "", 0, 20, "", "DEFAULT")
	if e != nil {
		s.fail(c, e)
		return
	}
	c.JSON(200, out)
}
func (s *Service) applyItemHTTP(c *gin.Context) {
	var in ApplyInput
	if !s.decode(c, &in) {
		return
	}
	ctx := c.Request.Context()
	var out Applied
	e := s.transaction(ctx, func(tx store.DBTX) error {
		item, e := s.requireItem(ctx, tx, c.Param("id"), c.Param("itemId"))
		if e != nil {
			return e
		}
		previous := text(item["status"])
		if previous != "REVIEW" && previous != "FAILED" {
			return conflict("该明细当前不能人工应用")
		}
		out, e = s.applyLocked(ctx, tx, text(item["song_id"]), in, text(item["fingerprint"]))
		if e != nil {
			return e
		}
		if _, e = s.itemStatus(ctx, tx, text(item["id"]), previous, "MANUAL_APPLIED", item["candidates_json"], nil); e != nil {
			return e
		}
		return s.project(ctx, tx, "METADATA", c.Param("id"))
	})
	if e != nil {
		s.fail(c, e)
		return
	}
	c.JSON(200, out)
}
func (s *Service) finishBatch(ctx context.Context, db store.DBTX, id string) error {
	for _, state := range []string{"PENDING", "PROCESSING"} {
		r, e := s.row(ctx, db, "meta-itemCount", map[string]any{"batchId": id, "status": state})
		if e != nil {
			return e
		}
		if number(r["count"]) > 0 {
			return s.project(ctx, db, "METADATA", id)
		}
	}
	batch, e := s.batchRow(ctx, db, id)
	if e != nil {
		return e
	}
	if text(batch["status"]) == "RUNNING" {
		return s.batchStatus(ctx, db, id, "RUNNING", "COMPLETED")
	}
	return s.project(ctx, db, "METADATA", id)
}

type work struct {
	batchID, itemID, fp string
	platforms           []string
	song                map[string]any
	auto                bool
	threshold           float64
}

func (s *Service) ProcessNext(ctx context.Context) error {
	var w *work
	e := s.transaction(ctx, func(tx store.DBTX) error {
		active, e := s.rows(ctx, tx, "meta-active", nil)
		if e != nil || len(active) == 0 {
			return e
		}
		id := text(active[0]["id"])
		batch, e := s.batchRow(ctx, tx, id)
		if e != nil {
			return e
		}
		if text(batch["status"]) != "RUNNING" {
			return nil
		}
		items, e := s.rows(ctx, tx, "meta-items", map[string]any{"batchId": id, "status": "PENDING", "limit": 1, "offset": 0})
		if e != nil {
			return e
		}
		if len(items) == 0 {
			return s.finishBatch(ctx, tx, id)
		}
		item := items[0]
		itemID := text(item["id"])
		if _, e = s.itemStatus(ctx, tx, itemID, "PENDING", "PROCESSING", nil, nil); e != nil {
			return e
		}
		song, e := s.song(ctx, tx, text(item["song_id"]))
		if e != nil || fingerprint(song) != text(item["fingerprint"]) {
			if _, e = s.itemStatus(ctx, tx, itemID, "PROCESSING", "FAILED", nil, "歌曲已移除或文件发生变化"); e != nil {
				return e
			}
			return s.finishBatch(ctx, tx, id)
		}
		w = &work{batchID: id, itemID: itemID, fp: text(item["fingerprint"]), song: song, auto: boolean(batch["auto_apply"]), threshold: decimal(batch["threshold"])}
		if e = decode(batch["providers_json"], &w.platforms); e != nil {
			return e
		}
		return s.project(ctx, tx, "METADATA", id)
	})
	if e != nil || w == nil {
		return e
	}
	result, fetchErr := s.search(ctx, w.song, SearchInput{}, w.platforms)
	e = s.transaction(ctx, func(tx store.DBTX) error {
		item, e := s.requireItem(ctx, tx, w.batchID, w.itemID)
		if e != nil {
			return e
		}
		if text(item["status"]) != "PROCESSING" {
			return nil
		}
		batch, e := s.batchRow(ctx, tx, w.batchID)
		if e != nil {
			return e
		}
		if text(batch["status"]) != "RUNNING" {
			_, e = s.itemStatus(ctx, tx, w.itemID, "PROCESSING", "PENDING", nil, nil)
			return e
		}
		state, message := "REVIEW", any("请人工选择候选并确认应用字段")
		candidates := result.Candidates
		if len(candidates) > 5 {
			candidates = candidates[:5]
		}
		if fetchErr != nil {
			state, message = "FAILED", "平台请求失败、服务已停用或歌曲不可用，请检查设置后重试"
		} else if len(candidates) == 0 {
			message = "没有找到候选，请修改关键词后从单曲入口重新匹配"
		} else if w.auto {
			cfg, e := s.settings(ctx, tx, false)
			if e != nil {
				return e
			}
			threshold := math.Inf(1)
			if cfg.Enabled && cfg.AutoApply {
				threshold = math.Max(w.threshold, cfg.Threshold)
			}
			if candidates[0].Score >= threshold {
				latest, e := s.song(ctx, tx, text(w.song["id"]))
				if e == nil && confirmed(latest) {
					state, message = "MANUAL_APPLIED", "查询期间歌曲已确认，保留已有元数据"
				} else {
					_, e = s.applyLocked(ctx, tx, text(w.song["id"]), ApplyInput{candidates[0].ID, allFields}, w.fp)
					if e == nil {
						state, message = "AUTO_APPLIED", nil
					} else if code(e) < 500 {
						message = "自动写入未执行，歌曲或设置已变化，请人工审核"
					} else {
						return e
					}
				}
			}
		}
		if len(result.Errors) > 0 && state != "AUTO_APPLIED" && state != "MANUAL_APPLIED" {
			v := text(message)
			for _, p := range result.Errors {
				v += "；部分平台失败：" + p.Provider + "：" + p.Message
			}
			if len([]rune(v)) > 500 {
				v = string([]rune(v)[:500])
			}
			message = v
		}
		if _, e = s.itemStatus(ctx, tx, w.itemID, "PROCESSING", state, encode(candidates), message); e != nil {
			return e
		}
		return s.finishBatch(ctx, tx, w.batchID)
	})
	if e != nil {
		cleanupCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = s.transaction(cleanupCtx, func(tx store.DBTX) error {
			if _, e := s.itemStatus(cleanupCtx, tx, w.itemID, "PROCESSING", "FAILED", nil, "保存失败，原元数据已保留，请重试"); e != nil {
				return e
			}
			return s.finishBatch(cleanupCtx, tx, w.batchID)
		})
	}
	return e
}
func (s *Service) worker() {
	defer s.wg.Done()
	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()
	next := time.Now().Add(time.Second)
	for {
		select {
		case <-s.ctx.Done():
			return
		case <-ticker.C:
			if time.Now().Before(next) {
				continue
			}
			if e := s.ProcessNext(s.ctx); e != nil && s.ctx.Err() == nil {
				s.log.Error("metadata batch worker failed")
			}
			cfg, e := s.settings(s.ctx, s.db, false)
			if e != nil {
				next = time.Now().Add(5 * time.Second)
				continue
			}
			lo, hi := *cfg.MinIntervalSeconds, *cfg.MaxIntervalSeconds
			next = time.Now().Add(time.Duration(lo+rand.IntN(hi-lo+1)) * time.Second)
		}
	}
}

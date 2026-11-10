package catalog

import (
	"context"
	"database/sql"
	"errors"

	"github.com/gin-gonic/gin"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/httpx"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

type ArtworkBatchStart struct {
	RequestID   string       `json:"requestId"`
	ArtistIDs   []string     `json:"artistIds"`
	AllMatching bool         `json:"allMatching"`
	Filter      ArtistFilter `json:"filter"`
	Provider    string       `json:"provider"`
}

func (s *Service) artworkPlatforms(ctx context.Context, tx store.DBTX) ([]string, error) {
	out := []string{"AUTO", "KUWO", "QISHUI", "THEAUDIODB"}
	if s.artistProvider != nil {
		enabled, e := s.artistProvider.ArtistArtworkProviders(ctx, tx)
		if e != nil {
			return nil, e
		}
		out = append(out, enabled...)
	}
	return out, nil
}

func (s *Service) startArtworkBatch(ctx context.Context, in ArtworkBatchStart) (any, error) {
	if !uuidPattern.MatchString(in.RequestID) || !has(in.Provider, "AUTO", "NETEASE", "QQ", "KUGOU", "KUWO", "QISHUI", "THEAUDIODB") || in.AllMatching && len(in.ArtistIDs) > 0 || !in.AllMatching && (len(in.ArtistIDs) == 0 || len(in.ArtistIDs) > 1000) {
		return nil, fail(400, "请选择 1～1000 位歌手、图片平台和有效请求标识")
	}
	if e := in.Filter.validate(); e != nil {
		return nil, e
	}
	batch := id()
	_, e := s.tx(ctx, func(tx store.DBTX) (any, error) {
		if e := s.exec(ctx, tx, "a_lock"); e != nil {
			return nil, e
		}
		prior, e := one(ctx, tx, s.q["ab_request"], in.RequestID)
		if e != nil && !errors.Is(e, sql.ErrNoRows) {
			return nil, e
		}
		fingerprint := sha(encode(in))
		if prior != nil {
			if str(prior["request_hash"]) != fingerprint {
				return nil, fail(409, "请求标识已用于其他取图操作，请重新建立批次")
			}
			batch = str(prior["id"])
			return nil, nil
		}
		platforms, e := s.artworkPlatforms(ctx, tx)
		if e != nil {
			return nil, e
		}
		if !has(in.Provider, platforms...) {
			return nil, fail(409, "所选平台尚未启用，请先在设置 → 音乐平台中启用")
		}
		filter := ArtistFilter{}
		if in.AllMatching {
			filter = in.Filter
		}
		available, e := s.filteredArtists(ctx, tx, filter)
		if e != nil {
			return nil, e
		}
		wanted := map[string]bool{}
		for _, artist := range in.ArtistIDs {
			if !uuidPattern.MatchString(artist) || wanted[artist] {
				return nil, fail(400, "歌手标识无效或重复")
			}
			wanted[artist] = true
		}
		targets := []map[string]any{}
		for _, artist := range available {
			if in.AllMatching || wanted[str(artist["id"])] {
				targets = append(targets, artist)
			}
		}
		if !in.AllMatching && len(targets) != len(wanted) {
			return nil, fail(409, "部分歌手已不在曲库中，请刷新后重新选择")
		}
		if len(targets) == 0 || len(targets) > 1000 {
			return nil, fail(400, "每批支持 1～1000 位歌手，请缩小筛选范围")
		}
		at := now()
		if e = s.exec(ctx, tx, "ab_insert", batch, in.RequestID, fingerprint, in.Provider, at, at); e != nil {
			return nil, e
		}
		for ordinal, artist := range targets {
			artistID := str(artist["id"])
			blocked, e := s.artworkBlocked(ctx, tx, artistID, "PORTRAIT")
			if e != nil {
				return nil, e
			}
			state, message := "PENDING", "等待获取封面"
			if blocked {
				state, message = "SKIPPED", "保留已有封面或手动移除设置"
			}
			if e = s.exec(ctx, tx, "ab_insertItem", id(), batch, artistID, artist["name"], ordinal, state, message, at); e != nil {
				return nil, e
			}
		}
		return nil, s.finishArtworkBatch(ctx, tx, batch)
	})
	if e != nil {
		return nil, e
	}
	return s.artworkBatch(ctx, batch, 0, 20)
}

func (s *Service) finishArtworkBatch(ctx context.Context, tx store.DBTX, batch string) error {
	if e := s.exec(ctx, tx, "ab_finish", now(), now(), batch); e != nil {
		return e
	}
	if s.project != nil {
		return s.project(ctx, tx, "ARTIST_ARTWORK", batch)
	}
	return nil
}

func (s *Service) closeArtworkReviews(ctx context.Context, tx store.DBTX, artist, kind, state, message string) error {
	if kind != "PORTRAIT" {
		return nil
	}
	rows, e := query(ctx, tx, s.q["ab_reviewBatches"], artist)
	if e != nil {
		return e
	}
	if len(rows) == 0 {
		return nil
	}
	if e = s.exec(ctx, tx, "ab_resolveReviews", state, message, now(), artist); e != nil {
		return e
	}
	for _, row := range rows {
		if e = s.finishArtworkBatch(ctx, tx, str(row["batch_id"])); e != nil {
			return e
		}
	}
	return nil
}

func (s *Service) artworkBatch(ctx context.Context, batch string, p, size int) (any, error) {
	if e := pages(p, size); e != nil {
		return nil, e
	}
	var out any
	e := store.WithinTx(ctx, s.db, func(tx store.DBTX) error {
		r, e := one(ctx, tx, s.q["ab_find"], batch)
		if errors.Is(e, sql.ErrNoRows) {
			return fail(404, "封面获取批次不存在")
		}
		if e != nil {
			return e
		}
		counts := map[string]int64{}
		for _, state := range []string{"PENDING", "PROCESSING", "APPLIED", "REVIEW", "NO_MATCH", "FAILED", "SKIPPED"} {
			counts[state] = 0
		}
		rows, e := query(ctx, tx, s.q["ab_counts"], batch)
		if e != nil {
			return e
		}
		var total int64
		for _, row := range rows {
			counts[str(row["status"])] = num(row["n"])
			total += num(row["n"])
		}
		rows, e = query(ctx, tx, s.q["ab_items"], batch, size, p*size)
		if e != nil {
			return e
		}
		items := []map[string]any{}
		for _, row := range rows {
			items = append(items, map[string]any{"id": row["id"], "artistId": row["artist_id"], "name": row["artist_name"], "status": row["status"], "message": row["message"]})
		}
		out = map[string]any{"id": r["id"], "provider": r["provider"], "status": r["status"], "createdAt": r["created_at"], "updatedAt": r["updated_at"], "counts": counts, "total": total, "completed": total - counts["PENDING"] - counts["PROCESSING"], "items": items, "page": p, "size": size, "totalElements": total}
		return nil
	})
	return out, e
}

type ArtistCategorySelection struct {
	ID       string `json:"id"`
	Revision int64  `json:"revision"`
}

func (s *Service) categorizeArtists(ctx context.Context, selected []ArtistCategorySelection, kind string) (any, error) {
	if len(selected) == 0 || len(selected) > 1000 || !has(kind, "MALE", "FEMALE", "GROUP", "UNKNOWN") {
		return nil, fail(400, "请选择 1～1000 位歌手和有效类型")
	}
	return s.tx(ctx, func(tx store.DBTX) (any, error) {
		if e := s.exec(ctx, tx, "a_lock"); e != nil {
			return nil, e
		}
		seen := map[string]bool{}
		for _, item := range selected {
			if !uuidPattern.MatchString(item.ID) || seen[item.ID] || item.Revision < 1 {
				return nil, fail(400, "歌手标识或版本无效")
			}
			seen[item.ID] = true
			if e := s.cas(ctx, tx, "a_category", kind, now(), item.ID, item.Revision); e != nil {
				return nil, e
			}
		}
		return map[string]int{"updated": len(selected)}, nil
	})
}

func (s *Service) registerArtworkBatches(r *gin.Engine, base string) {
	r.GET(base+"/artwork-platforms", func(c *gin.Context) {
		v, e := s.artworkPlatforms(c.Request.Context(), s.db)
		s.reply(c, 200, map[string]any{"enabled": v}, e)
	})
	r.POST(base+"/artwork-batches", func(c *gin.Context) {
		var in ArtworkBatchStart
		if !httpx.Decode(c, &in, 150000) {
			return
		}
		v, e := s.startArtworkBatch(c.Request.Context(), in)
		s.reply(c, 201, v, e)
	})
	r.GET(base+"/artwork-batches/latest", func(c *gin.Context) {
		row, e := one(c.Request.Context(), s.db, s.q["ab_latest"])
		if errors.Is(e, sql.ErrNoRows) {
			s.reply(c, 200, nil, nil)
			return
		}
		if e != nil {
			s.report(c, e)
			return
		}
		v, e := s.artworkBatch(c.Request.Context(), str(row["id"]), 0, 20)
		s.reply(c, 200, v, e)
	})
	r.GET(base+"/artwork-batches/:batch", func(c *gin.Context) {
		p, size, e := pageParams(c)
		if e != nil {
			s.report(c, e)
			return
		}
		v, e := s.artworkBatch(c.Request.Context(), c.Param("batch"), p, size)
		s.reply(c, 200, v, e)
	})
	r.POST(base+"/categories", func(c *gin.Context) {
		var in struct {
			Artists []ArtistCategorySelection `json:"artists"`
			Type    string                    `json:"type"`
		}
		if !httpx.Decode(c, &in, 150000) {
			return
		}
		v, e := s.categorizeArtists(c.Request.Context(), in.Artists, in.Type)
		s.reply(c, 200, v, e)
	})
}

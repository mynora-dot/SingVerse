package library

import (
	"context"
	"database/sql"
	"errors"
	"strings"

	"github.com/gin-gonic/gin"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/metastandard"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/metastore"

	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

func (s *Service) registerFormal(g *gin.RouterGroup) {
	g.POST("/metadata/assets", s.formalUploadAsset)
	g.GET("/metadata/assets/:assetId", s.formalReadAsset)
	g.GET("/metadata/statistics", s.formalStatistics)
	g.GET("/songs", s.formalPage)
	g.GET("/songs/:songId", s.formalGetSong)
	g.GET("/metadata/entities", s.formalPage)
	g.POST("/metadata/entities", s.formalCreate)
	g.GET("/metadata/entities/:entityId", s.formalGet)
	g.PATCH("/metadata/entities/:entityId", s.formalUpdate)
	g.POST("/metadata/entities/:entityId/association", s.formalUpdate)
	g.GET("/metadata/entities/:entityId/differences", s.formalDifferences)
	g.GET("/metadata/entities/:entityId/locations", s.formalLocations)
	g.GET("/metadata/entities/:entityId/lyrics", s.formalLyrics)
	g.POST("/metadata/entities/:entityId/location", s.formalRebindLocation)
	g.POST("/metadata/entities/:entityId/visibility", s.formalVisibility)
	g.POST("/metadata/entities/:entityId/split", s.formalSplit)
	g.POST("/metadata/entities/:entityId/replacement", s.formalReplaceMedia)
}
func (s *Service) formalFail(c *gin.Context, e error) {
	var formalProblem *metastore.Problem
	if errors.As(e, &formalProblem) {
		s.fail(c, &problem{status: formalProblem.Status, detail: formalProblem.Message})
		return
	}
	if errors.Is(e, sql.ErrNoRows) {
		e = missing("资料条目不存在")
	}
	s.fail(c, e)
}
func (s *Service) formalPage(c *gin.Context) {
	page, size, e := pageParams(c)
	if e != nil {
		s.fail(c, e)
		return
	}
	kind := c.DefaultQuery("type", "SONG")
	search := strings.TrimSpace(c.Query("search"))
	if len([]rune(search)) > 200 {
		s.fail(c, bad("搜索文本过长"))
		return
	}
	items, total, e := s.formal.PageSource(c.Request.Context(), s.db, kind, search, c.Query("sourceId"), page*size, size)
	if e != nil {
		s.formalFail(c, e)
		return
	}
	c.JSON(200, map[string]any{"items": items, "totalElements": total, "page": page, "size": size})
}
func (s *Service) formalGetSong(c *gin.Context) { s.formalGetID(c, c.Param("songId")) }
func (s *Service) formalGet(c *gin.Context)     { s.formalGetID(c, c.Param("entityId")) }
func (s *Service) formalGetID(c *gin.Context, id string) {
	ctx := c.Request.Context()
	page, size, e := pageParams(c)
	if e != nil {
		s.fail(c, e)
		return
	}
	var out map[string]any
	e = store.WithinTx(ctx, s.db, func(tx store.DBTX) error {
		d, e := s.formal.Read(ctx, tx, id)
		if e != nil {
			return e
		}
		children, total, e := s.formal.ChildrenPage(ctx, tx, id, page*size, size)
		if e != nil {
			return e
		}
		locations, e := store.Maps(ctx, tx, s.q["formal-child-locations"], id, id, size, page*size)
		if e != nil {
			return e
		}
		status, e := store.Map(ctx, tx, s.q["formal-save-status"], id, id, id)
		if e != nil {
			return e
		}
		out = map[string]any{"document": d, "children": children, "locations": locations, "saveStatus": status, "childrenPage": map[string]any{"page": page, "size": size, "totalElements": total}}
		return nil
	})
	if e != nil {
		s.formalFail(c, e)
		return
	}
	c.JSON(200, out)
}

func (s *Service) formalStatistics(c *gin.Context) {
	rows, e := store.Maps(c.Request.Context(), s.db, s.q["formal-stats"])
	if e != nil {
		s.formalFail(c, e)
		return
	}
	out := map[string]any{}
	for _, row := range rows {
		if kind, ok := row["entity_type"].(string); ok {
			out[kind] = row["total"]
		}
	}
	c.JSON(200, out)
}
func (s *Service) formalCreate(c *gin.Context) {
	var in struct {
		Type      string         `json:"type"`
		ParentID  string         `json:"parentId"`
		Fields    map[string]any `json:"fields"`
		RequestID string         `json:"requestId"`
	}
	if !decodeFormal(c, &in) {
		return
	}
	if !metastandard.UUID.MatchString(in.RequestID) {
		s.fail(c, bad("需要有效提交标识"))
		return
	}
	ctx := c.Request.Context()
	var d metastandard.Document
	e := s.transaction(ctx, func(tx store.DBTX) error {
		var e error
		d, e = s.formal.CreateRequested(ctx, tx, in.Type, in.RequestID, in.ParentID, in.Fields)
		return e
	})
	if e != nil {
		s.formalFail(c, e)
		return
	}
	d, e = s.formal.Read(ctx, s.db, in.RequestID)
	if e != nil {
		s.formalFail(c, e)
		return
	}
	c.JSON(201, d)
}
func (s *Service) formalUpdate(c *gin.Context) {
	var in metastore.Update
	if !decodeFormal(c, &in) {
		return
	}
	ctx := c.Request.Context()
	var d metastandard.Document
	e := s.transaction(ctx, func(tx store.DBTX) error {
		var e error
		d, e = s.formal.Update(ctx, tx, c.Param("entityId"), in)
		if e != nil {
			return e
		}
		return s.touchFormal(ctx, tx, d.ID)
	})
	if e != nil {
		s.formalFail(c, e)
		return
	}
	c.JSON(200, d)
}
func (s *Service) touchFormal(ctx context.Context, tx store.DBTX, id string) error {
	if _, e := tx.ExecContext(ctx, s.q["formal-touch-file"], id, id, id); e != nil {
		return e
	}
	_, e := tx.ExecContext(ctx, s.q["formal-enqueue-files"], id, id, id)
	return e
}
func (s *Service) formalDifferences(c *gin.Context) {
	rows, e := store.Maps(c.Request.Context(), s.db, s.q["formal-differences"], c.Param("entityId"))
	if e != nil {
		s.formalFail(c, e)
		return
	}
	c.JSON(200, map[string]any{"items": rows})
}
func (s *Service) formalLocations(c *gin.Context) {
	items, e := s.formal.Locations(c.Request.Context(), s.db, c.Param("entityId"))
	if e != nil {
		s.formalFail(c, e)
		return
	}
	c.JSON(200, map[string]any{"items": items})
}
func (s *Service) formalLyrics(c *gin.Context) {
	lyrics, e := s.FormalLyrics(c.Request.Context(), s.db, c.Param("entityId"))
	if e != nil {
		s.formalFail(c, e)
		return
	}
	c.JSON(200, map[string]any{"lyrics": lyrics})
}
func (s *Service) formalRebindLocation(c *gin.Context) {
	var in struct {
		SourceID         string `json:"sourceId"`
		RelativePath     string `json:"relativePath"`
		ExpectedRevision string `json:"expectedRevision"`
		RequestID        string `json:"requestId"`
	}
	if !decodeFormal(c, &in) {
		return
	}
	if !metastandard.ValidPath(in.RelativePath) || !supports(in.RelativePath) || !metastandard.UUID.MatchString(in.RequestID) {
		s.fail(c, bad("文件位置或提交标识无效"))
		return
	}
	ctx := c.Request.Context()
	id := c.Param("entityId")
	e := s.transaction(ctx, func(tx store.DBTX) error {
		var err error
		_, err = s.formal.Mutate(ctx, tx, id, in.ExpectedRevision, in.RequestID, in, func(d *metastandard.Document) error {
			if d.Type != "MEDIA" {
				return bad("只能为媒体文件绑定位置")
			}
			if _, e := s.findSource(ctx, tx, in.SourceID); e != nil {
				return e
			}
			if occupied, e := s.formal.PathMedia(ctx, tx, in.SourceID, in.RelativePath); e == nil && occupied != id {
				return conflict("此路径已绑定其他媒体，请先核对关联")
			} else if e != nil && !errors.Is(e, sql.ErrNoRows) {
				return e
			}
			if _, e := tx.ExecContext(ctx, s.q["song-rebind-location"], in.SourceID, in.RelativePath, hash(in.RelativePath), id); e != nil {
				return e
			}
			if e := s.formal.Location(ctx, tx, id, in.SourceID, in.RelativePath, "", "PENDING", map[string]any{}); e != nil {
				return e
			}
			if _, e := tx.ExecContext(ctx, s.q["formal-location-pending"], id); e != nil {
				return e
			}
			return s.touchFormal(ctx, tx, id)
		})
		return err
	})
	if e != nil {
		s.formalFail(c, e)
		return
	}
	c.JSON(200, map[string]any{"mediaId": id, "state": "PENDING"})
}
func (s *Service) formalVisibility(c *gin.Context) {
	var in struct {
		Suppressed       bool   `json:"suppressed"`
		ExpectedRevision string `json:"expectedRevision"`
		RequestID        string `json:"requestId"`
	}
	if !decodeFormal(c, &in) {
		return
	}
	ctx := c.Request.Context()
	id := c.Param("entityId")
	e := s.transaction(ctx, func(tx store.DBTX) error {
		_, e := s.formal.Mutate(ctx, tx, id, in.ExpectedRevision, in.RequestID, in, func(d *metastandard.Document) error {
			_, e := tx.ExecContext(ctx, s.q["formal-visibility"], in.Suppressed, id)
			return e
		})
		return e
	})
	if e != nil {
		s.formalFail(c, e)
		return
	}
	c.Status(204)
}
func (s *Service) formalSplit(c *gin.Context) {
	var in struct {
		MediaIDs         []string `json:"mediaIds"`
		ExpectedRevision string   `json:"expectedRevision"`
		RequestID        string   `json:"requestId"`
	}
	if !decodeFormal(c, &in) {
		return
	}
	if !metastandard.UUID.MatchString(in.RequestID) || len(in.MediaIDs) == 0 || len(in.MediaIDs) > 200 {
		s.fail(c, bad("请选择最多 200 个媒体文件并提供提交标识"))
		return
	}
	ctx := c.Request.Context()
	var out metastandard.Document
	e := s.transaction(ctx, func(tx store.DBTX) error {
		_, e := s.formal.Mutate(ctx, tx, c.Param("entityId"), in.ExpectedRevision, in.RequestID, in, func(v *metastandard.Document) error {
			if v.Type != "VARIANT" {
				return bad("只能拆分媒体版本")
			}
			var e error
			out, e = s.formal.Create(ctx, tx, "VARIANT", in.RequestID, v.ParentID)
			if e != nil {
				return e
			}
			seen := map[string]bool{}
			for _, id := range in.MediaIDs {
				if seen[id] {
					return bad("不能重复选择同一媒体文件")
				}
				seen[id] = true
				m, e := s.formal.Read(ctx, tx, id)
				if e != nil {
					return e
				}
				if m.Type != "MEDIA" || m.ParentID != v.ID {
					return bad("媒体文件不属于当前版本")
				}
				if _, e = s.formal.Link(ctx, tx, id, out.ID, m.Revision); e != nil {
					return e
				}
				if e = s.touchFormal(ctx, tx, id); e != nil {
					return e
				}
			}
			return nil
		})
		if e != nil {
			return e
		}
		out, e = s.formal.Read(ctx, tx, in.RequestID)
		return e
	})
	if e != nil {
		s.formalFail(c, e)
		return
	}
	c.JSON(201, out)
}

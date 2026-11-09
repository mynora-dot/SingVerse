package catalog

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"errors"
	"github.com/gin-gonic/gin"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/httpx"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
	"strconv"
)

func pageParams(c *gin.Context) (int, int, error) {
	p, e := strconv.Atoi(c.DefaultQuery("page", "0"))
	if e != nil {
		return 0, 0, fail(400, "分页参数无效")
	}
	size, e := strconv.Atoi(c.DefaultQuery("size", "20"))
	if e != nil {
		return 0, 0, fail(400, "分页参数无效")
	}
	return p, size, pages(p, size)
}
func (s *Service) Register(r *gin.Engine) {
	s.registerClassification(r)
	s.registerPlaylists(r)
	s.registerArtists(r)
}
func (s *Service) registerClassification(r *gin.Engine) {
	base := "/api/v1/classification"
	r.GET(base+"/settings", func(c *gin.Context) { v, e := s.settings(c.Request.Context()); s.reply(c, 200, v, e) })
	r.PUT(base+"/settings", func(c *gin.Context) {
		var in struct {
			Enabled         bool    `json:"enabled"`
			Endpoint        string  `json:"endpoint"`
			Model           string  `json:"model"`
			APIKey          *string `json:"apiKey"`
			ClearKey        bool    `json:"clearKey"`
			TimeoutSeconds  int     `json:"timeoutSeconds"`
			IntervalSeconds int     `json:"intervalSeconds"`
		}
		if !httpx.Decode(c, &in, 20000) {
			return
		}
		v, e := s.saveSettings(c.Request.Context(), in)
		s.reply(c, 200, v, e)
	})
	r.POST(base+"/settings/test", func(c *gin.Context) {
		cfg, e := s.modelConfig(c.Request.Context(), s.db, true)
		if e != nil {
			s.report(c, e)
			return
		}
		snap, e := s.ai.Snapshot(c.Request.Context(), *cfg.ServiceID)
		if e == nil {
			e = s.testClassification(c.Request.Context(), snap)
		}
		s.reply(c, 200, map[string]any{"success": true, "message": "连接与结构化输出验证通过；不代表分类准确率"}, e)
	})
	r.GET(base+"/tags", func(c *gin.Context) { v, e := s.tags(c.Request.Context(), s.db); s.reply(c, 200, v, e) })
	for _, create := range []bool{true, false} {
		path := base + "/tags"
		if !create {
			path += "/:id"
		}
		handler := func(c *gin.Context) {
			var in SaveTag
			if !httpx.Decode(c, &in, 20000) {
				return
			}
			v, e := s.saveTag(c.Request.Context(), c.Param("id"), in)
			status := 200
			if create {
				status = 201
			}
			s.reply(c, status, v, e)
		}
		if create {
			r.POST(path, handler)
		} else {
			r.PUT(path, handler)
		}
	}
	r.GET(base+"/tags/:id/cover", func(c *gin.Context) {
		v, e := one(c.Request.Context(), s.db, s.q["c_tagCover"], c.Param("id"))
		if errors.Is(e, sql.ErrNoRows) {
			e = fail(404, "此分类暂无可用封面")
		}
		if e != nil {
			s.report(c, e)
			return
		}
		c.Header("Cache-Control", "no-store")
		c.Header("X-Content-Type-Options", "nosniff")
		c.Data(200, "image/jpeg", v["image_bytes"].([]byte))
	})
	for _, remove := range []bool{false, true} {
		handler := func(c *gin.Context) {
			var b []byte
			var e error
			if _, present := c.GetQuery("version"); remove && !present {
				s.report(c, fail(400, "请提供图片版本"))
				return
			}
			if !remove {
				b, e = uploadedImage(c)
				if e != nil {
					s.report(c, e)
					return
				}
			}
			v, e := s.tagCover(c.Request.Context(), c.Param("id"), c.Query("version"), b, remove)
			s.reply(c, 200, v, e)
		}
		if remove {
			r.DELETE(base+"/tags/:id/cover", handler)
		} else {
			r.PUT(base+"/tags/:id/cover", handler)
		}
	}
	r.GET(base+"/songs/:id", func(c *gin.Context) {
		var v any
		e := store.WithinTx(c.Request.Context(), s.db, func(tx store.DBTX) error {
			var e error
			v, e = s.state(c.Request.Context(), tx, c.Param("id"))
			return e
		})
		s.reply(c, 200, v, e)
	})
	r.PUT(base+"/songs/:id", func(c *gin.Context) {
		var v Change
		if !httpx.Decode(c, &v, 50000) {
			return
		}
		o, e := s.change(c.Request.Context(), c.Param("id"), v)
		s.reply(c, 200, o, e)
	})
	r.GET(base+"/songs/:id/history", func(c *gin.Context) {
		p, size, e := pageParams(c)
		if e != nil {
			s.report(c, e)
			return
		}
		v, e := s.history(c.Request.Context(), c.Param("id"), p, size)
		s.reply(c, 200, v, e)
	})
	r.POST(base+"/history/:id/undo", func(c *gin.Context) {
		var v Undo
		if !httpx.Decode(c, &v, 10000) {
			return
		}
		o, e := s.undo(c.Request.Context(), c.Param("id"), v)
		s.reply(c, 200, o, e)
	})
	r.POST(base+"/preview", func(c *gin.Context) {
		var v Start
		if !httpx.Decode(c, &v, 1000000) {
			return
		}
		o, e := s.preview(c.Request.Context(), v)
		s.reply(c, 200, o, e)
	})
	r.POST(base+"/tasks", func(c *gin.Context) {
		var v Start
		if !httpx.Decode(c, &v, 1000000) {
			return
		}
		o, e := s.start(c.Request.Context(), v)
		s.reply(c, 201, o, e)
	})
	r.GET(base+"/tasks", func(c *gin.Context) {
		p, size, e := pageParams(c)
		if e != nil {
			s.report(c, e)
			return
		}
		v, e := s.tasks(c.Request.Context(), p, size)
		s.reply(c, 200, v, e)
	})
	r.GET(base+"/tasks/:id", func(c *gin.Context) {
		p, size, e := pageParams(c)
		if e != nil {
			s.report(c, e)
			return
		}
		v, e := s.task(c.Request.Context(), c.Param("id"), p, size, c.Query("status"))
		s.reply(c, 200, v, e)
	})
	for _, action := range []string{"pause", "resume", "cancel"} {
		r.POST(base+"/tasks/:id/"+action, func(c *gin.Context) {
			v, e := s.control(c.Request.Context(), c.Param("id"), action)
			s.reply(c, 200, v, e)
		})
	}
	r.POST(base+"/tasks/:id/items/:item/retry", func(c *gin.Context) {
		v, e := s.retry(c.Request.Context(), c.Param("id"), c.Param("item"))
		s.reply(c, 200, v, e)
	})
	r.POST(base+"/tasks/:id/items/:item/apply", func(c *gin.Context) {
		var v Review
		if !httpx.Decode(c, &v, 20000) {
			return
		}
		o, e := s.review(c.Request.Context(), c.Param("id"), c.Param("item"), v)
		s.reply(c, 200, o, e)
	})
}
func (s *Service) tagCover(ctx context.Context, id, expected string, b []byte, remove bool) (any, error) {
	return s.tx(ctx, func(tx store.DBTX) (any, error) {
		if e := s.lock(ctx, tx); e != nil {
			return nil, e
		}
		tags, e := s.tags(ctx, tx)
		if e != nil {
			return nil, e
		}
		found := false
		for _, t := range tags {
			if t.ID == id {
				found = true
			}
		}
		if !found {
			return nil, fail(404, "分类标签不存在")
		}
		old, e := one(ctx, tx, s.q["c_tagCover"], id)
		if e != nil && !errors.Is(e, sql.ErrNoRows) {
			return nil, e
		}
		var version string
		if !remove {
			sum := sha256.Sum256(b)
			version = hex.EncodeToString(sum[:])
		}
		prior := ""
		if old != nil {
			prior = str(old["version"])
		}
		if !remove && version == prior {
		} else if old != nil || !remove {
			if expected != prior {
				return nil, fail(409, "封面已被修改，请刷新分类后重试")
			}
			if remove {
				e = s.exec(ctx, tx, "c_deleteCover", id)
			} else if old == nil {
				e = s.exec(ctx, tx, "c_insertCover", id, version, b)
			} else {
				e = s.exec(ctx, tx, "c_updateCover", version, b, id)
			}
			if e != nil {
				return nil, e
			}
		}
		tags, e = s.tags(ctx, tx)
		if e != nil {
			return nil, e
		}
		for _, t := range tags {
			if t.ID == id {
				return t, nil
			}
		}
		return nil, fail(404, "分类标签不存在")
	})
}

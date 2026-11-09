package downloads

import (
	"errors"
	"strconv"

	"github.com/gin-gonic/gin"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/httpx"
)

func (s *Service) Register(group *gin.RouterGroup) {
	g := group.Group("/lx-downloads")
	g.GET("/status", func(c *gin.Context) { v, e := s.Status(c.Request.Context()); s.reply(c, v, e) })
	g.GET("/sources", func(c *gin.Context) { v, e := s.Scripts(c.Request.Context()); s.reply(c, v, e) })
	g.POST("/sources", func(c *gin.Context) {
		var in SourceInput
		if !httpx.Decode(c, &in, maxScript+65536) {
			return
		}
		v, e := s.Import(c.Request.Context(), in)
		s.reply(c, v, e)
	})
	g.POST("/sources/:id/activate", func(c *gin.Context) { v, e := s.Inspect(c.Request.Context(), c.Param("id"), true); s.reply(c, v, e) })
	g.POST("/sources/:id/test", func(c *gin.Context) { v, e := s.Inspect(c.Request.Context(), c.Param("id"), false); s.reply(c, v, e) })
	g.DELETE("/sources/:id", func(c *gin.Context) {
		e := s.DeleteScript(c.Request.Context(), c.Param("id"))
		if e != nil {
			s.fail(c, e)
			return
		}
		c.Status(204)
	})
	g.POST("/catalog/search", func(c *gin.Context) {
		var in struct {
			Query    string `json:"query"`
			Platform string `json:"platform"`
			Page     int    `json:"page"`
		}
		if !httpx.Decode(c, &in, 65536) {
			return
		}
		v, e := s.Search(c.Request.Context(), in.Query, in.Platform, in.Page)
		s.reply(c, v, e)
	})
	g.POST("/catalog/preview", func(c *gin.Context) {
		var in struct {
			URL  string `json:"url"`
			Page int    `json:"page"`
		}
		if !httpx.Decode(c, &in, 65536) {
			return
		}
		v, e := s.Preview(c.Request.Context(), in.URL, in.Page)
		s.reply(c, v, e)
	})
	g.GET("/catalog/tracks/:id", func(c *gin.Context) { v, e := s.Detail(c.Request.Context(), c.Param("id")); s.reply(c, v, e) })
	g.POST("/catalog/states", func(c *gin.Context) {
		var in struct {
			TrackIDs []string `json:"trackIds"`
		}
		if !httpx.Decode(c, &in, 128*1024) {
			return
		}
		v, e := s.TrackStates(c.Request.Context(), in.TrackIDs)
		s.reply(c, v, e)
	})
	g.GET("/batches", func(c *gin.Context) {
		page, size, ok := pagination(c)
		if !ok {
			return
		}
		v, e := s.Batches(c.Request.Context(), page, size)
		s.reply(c, v, e)
	})
	g.POST("/batches", func(c *gin.Context) {
		var in BatchInput
		if !httpx.Decode(c, &in, 256*1024) {
			return
		}
		v, e := s.Create(c.Request.Context(), in)
		s.reply(c, v, e)
	})
	g.GET("/batches/:id", func(c *gin.Context) { v, e := s.batch(c.Request.Context(), s.db, c.Param("id")); s.reply(c, v, e) })
	g.GET("/batches/:id/items", func(c *gin.Context) {
		page, size, ok := pagination(c)
		if !ok {
			return
		}
		v, e := s.Items(c.Request.Context(), c.Param("id"), page, size)
		s.reply(c, v, e)
	})
	g.POST("/batches/:id/cancel", func(c *gin.Context) {
		e := s.Cancel(c.Request.Context(), c.Param("id"))
		if e != nil {
			s.fail(c, e)
			return
		}
		c.Status(204)
	})
	g.POST("/batches/:id/retry", func(c *gin.Context) { v, e := s.Retry(c.Request.Context(), c.Param("id")); s.reply(c, v, e) })
	g.GET("/covers/:id", func(c *gin.Context) {
		bytes, mime, e := s.covers.Get(c.Request.Context(), c.Param("id"))
		if e != nil {
			s.fail(c, e)
			return
		}
		c.Header("Cache-Control", "private, max-age=300")
		c.Header("X-Content-Type-Options", "nosniff")
		c.Data(200, mime, bytes)
	})
}
func (s *Service) reply(c *gin.Context, value any, err error) {
	if err != nil {
		s.fail(c, err)
		return
	}
	c.JSON(200, value)
}
func (s *Service) fail(c *gin.Context, err error) {
	status := statusError(err)
	message := "操作失败，请稍后重试"
	if status >= 400 && status < 500 {
		message = clean(err.Error())
	} else {
		var p interface{ Error() string }
		if errors.As(err, &p) {
			s.log.Warn("download operation failed", "operation", c.FullPath())
		}
	}
	httpx.Error(c, status, "about:blank", message)
}
func pagination(c *gin.Context) (int, int, bool) {
	page, e1 := strconv.Atoi(c.DefaultQuery("page", "0"))
	size, e2 := strconv.Atoi(c.DefaultQuery("size", "20"))
	if e1 != nil || e2 != nil || bounds(page, size) != nil {
		httpx.Error(c, 400, "about:blank", "分页参数无效")
		return 0, 0, false
	}
	return page, size, true
}

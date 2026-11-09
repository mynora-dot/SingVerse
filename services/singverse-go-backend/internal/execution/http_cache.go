package execution

import (
	"context"

	"github.com/gin-gonic/gin"
)

func (s *Service) registerCacheRoutes(g *gin.RouterGroup) {
	g.GET("/transcoding/cache", s.listCacheHTTP)
	g.PUT("/transcoding/cache/settings", s.saveCacheSettingsHTTP)
	g.GET("/transcoding/cache/pins", s.listCachePinsHTTP)
	g.PUT("/transcoding/cache/songs/:songId/pin", s.pinCacheSongHTTP)
	g.POST("/transcoding/cache/cleanup-jobs", s.submitCacheCleanupHTTP)
	g.GET("/transcoding/cache/cleanup-jobs/:id", s.cacheCleanupHTTP)
	g.POST("/transcoding/cache/cleanup-jobs/:id/cancel", s.cancelCacheCleanupHTTP)
	g.POST("/transcoding/cache/clear", s.clearCacheHTTP)
	g.DELETE("/transcoding/cache/:id", s.deleteCacheHTTP)
}

func (s *Service) listCacheHTTP(c *gin.Context) {
	page, size, ok := pagination(c, 20)
	if !ok {
		return
	}
	s.respond(c, 200, func(ctx context.Context) (any, error) { return s.cacheList(ctx, page, size) })
}

func (s *Service) saveCacheSettingsHTTP(c *gin.Context) {
	body, ok := decodeInput(c)
	if !ok {
		return
	}
	s.respond(c, 200, func(ctx context.Context) (any, error) { return s.saveCacheSettings(ctx, body) })
}

func (s *Service) listCachePinsHTTP(c *gin.Context) {
	page, size, ok := pagination(c, 20)
	if !ok {
		return
	}
	s.respond(c, 200, func(ctx context.Context) (any, error) { return s.pins(ctx, page, size) })
}

func (s *Service) pinCacheSongHTTP(c *gin.Context) {
	body, ok := decodeInput(c)
	if !ok {
		return
	}
	s.respond(c, 200, func(ctx context.Context) (any, error) { return s.pin(ctx, c.Param("songId"), body) })
}

func (s *Service) submitCacheCleanupHTTP(c *gin.Context) {
	body, ok := decodeInput(c)
	if !ok {
		return
	}
	for key := range body {
		if !oneOf(key, "mode", "entryId", "confirmRecent") {
			s.report(c, fail(400, "未知清理参数"))
			return
		}
	}
	if body["confirmRecent"] != nil {
		if _, ok := body["confirmRecent"].(bool); !ok {
			s.report(c, fail(400, "清理参数无效"))
			return
		}
	}
	s.respond(c, 202, func(ctx context.Context) (any, error) {
		return s.submitCleanup(ctx, str(body, "mode"), str(body, "entryId"), boolv(body["confirmRecent"]))
	})
}

func (s *Service) cacheCleanupHTTP(c *gin.Context) {
	s.respond(c, 200, func(ctx context.Context) (any, error) { return s.cacheJob(ctx, c.Param("id")) })
}

func (s *Service) cancelCacheCleanupHTTP(c *gin.Context) {
	s.respond(c, 200, func(ctx context.Context) (any, error) { return s.cancelCacheJob(ctx, c.Param("id")) })
}

func (s *Service) clearCacheHTTP(c *gin.Context) {
	s.respond(c, 200, func(ctx context.Context) (any, error) {
		job, err := s.submitCleanup(ctx, "CLEAR", "", false)
		if err != nil {
			return nil, err
		}
		return s.awaitCleanup(ctx, str(job, "id"))
	})
}

func (s *Service) deleteCacheHTTP(c *gin.Context) {
	confirm := c.DefaultQuery("confirmRecent", "false")
	if !oneOf(confirm, "true", "false") {
		s.report(c, fail(400, "确认参数无效"))
		return
	}
	s.respond(c, 204, func(ctx context.Context) (any, error) {
		job, err := s.submitCleanup(ctx, "DELETE", c.Param("id"), confirm == "true")
		if err != nil {
			return nil, err
		}
		result, err := s.awaitCleanup(ctx, str(job, "id"))
		if err != nil {
			return nil, err
		}
		if integer(result["removed"]) == 0 {
			return nil, fail(409, "缓存状态已变化或仍受保护，请查看清理记录")
		}
		return nil, nil
	})
}

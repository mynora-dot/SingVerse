package execution

import (
	"context"

	"github.com/gin-gonic/gin"
)

func (s *Service) registerAudioBatchRoutes(g *gin.RouterGroup) {
	g.POST("/audio-processing/batches/preview", s.previewAudioBatchHTTP)
	g.GET("/audio-processing/batches", s.listAudioBatchesHTTP)
	g.GET("/audio-processing/batches/:id", s.audioBatchHTTP)
	g.POST("/audio-processing/batches/:id/submit", s.startAudioBatchHTTP)
	g.POST("/audio-processing/batches/:id/cancel", s.cancelAudioBatchHTTP)
	g.POST("/audio-processing/batches/:id/items/:song/cancel", s.cancelAudioBatchItemHTTP)
	g.POST("/audio-processing/batches/:id/retry", s.retryAudioBatchHTTP)
}

func (s *Service) previewAudioBatchHTTP(c *gin.Context) {
	body, ok := decodeInput(c)
	if !ok {
		return
	}
	s.respond(c, 202, func(ctx context.Context) (any, error) { return s.previewBatch(ctx, body) })
}

func (s *Service) listAudioBatchesHTTP(c *gin.Context) {
	page, size, ok := pagination(c, 20)
	if !ok {
		return
	}
	s.respond(c, 200, func(ctx context.Context) (any, error) { return s.batches(ctx, page, size) })
}

func (s *Service) audioBatchHTTP(c *gin.Context) {
	page, size, ok := pagination(c, 50)
	if !ok {
		return
	}
	s.respond(c, 200, func(ctx context.Context) (any, error) { return s.batchDetail(ctx, c.Param("id"), page, size) })
}

func (s *Service) startAudioBatchHTTP(c *gin.Context) {
	s.respond(c, 200, func(ctx context.Context) (any, error) { return s.startBatch(ctx, c.Param("id")) })
}

func (s *Service) cancelAudioBatchHTTP(c *gin.Context) {
	s.respond(c, 200, func(ctx context.Context) (any, error) { return s.cancelBatch(ctx, c.Param("id")) })
}

func (s *Service) cancelAudioBatchItemHTTP(c *gin.Context) {
	s.respond(c, 200, func(ctx context.Context) (any, error) {
		return nil, s.cancelBatchItem(ctx, c.Param("id"), c.Param("song"))
	})
}

func (s *Service) retryAudioBatchHTTP(c *gin.Context) {
	body := map[string]any{}
	if c.Request.ContentLength != 0 {
		var ok bool
		body, ok = decodeInput(c)
		if !ok {
			return
		}
	}
	s.respond(c, 202, func(ctx context.Context) (any, error) { return s.retryBatch(ctx, c.Param("id"), str(body, "backend")) })
}

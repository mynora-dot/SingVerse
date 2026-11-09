package execution

import (
	"context"

	"github.com/gin-gonic/gin"
)

func (s *Service) registerModelRoutes(g *gin.RouterGroup) {
	g.GET("/audio-processing/node", s.audioNodeHTTP)
	for _, action := range []string{"download", "cancel", "test", "select"} {
		g.POST("/audio-processing/model/"+action, s.modelActionHTTP(action))
	}
	g.POST("/audio-processing/model/import", s.importAudioModelHTTP)
}

func (s *Service) audioNodeHTTP(c *gin.Context) {
	path := "/internal/v1/audio-roles/models"
	if c.Query("refresh") == "true" {
		path += "?refresh=true"
	}
	s.respond(c, 200, func(ctx context.Context) (any, error) { return s.nodeCall(ctx, path, "GET", nil) })
}

func (s *Service) importAudioModelHTTP(c *gin.Context) {
	s.respond(c, 200, func(ctx context.Context) (any, error) {
		return s.uploadModel(ctx, c.Request, c.DefaultQuery("model", "htdemucs"))
	})
}

func (s *Service) modelActionHTTP(action string) gin.HandlerFunc {
	return func(c *gin.Context) {
		body := map[string]any(nil)
		if oneOf(action, "test", "select") {
			var ok bool
			body, ok = decodeInput(c)
			if !ok {
				return
			}
		}
		path := "/internal/v1/audio-roles/models/" + action
		if action == "download" {
			model := c.DefaultQuery("model", "htdemucs")
			if _, err := modelMaximum(model); err != nil {
				s.report(c, err)
				return
			}
			path += "?model=" + model
		}
		s.respond(c, 200, func(ctx context.Context) (any, error) { return s.nodeCall(ctx, path, "POST", body) })
	}
}

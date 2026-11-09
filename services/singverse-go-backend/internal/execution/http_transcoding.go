package execution

import (
	"context"
	"net/http"

	"github.com/gin-gonic/gin"
)

func (s *Service) registerTranscodingRoutes(g *gin.RouterGroup) {
	for _, endpoint := range []string{"capabilities", "hardware-settings"} {
		g.GET("/transcoding/"+endpoint, s.transcodingNodeHTTP(endpoint, http.MethodGet))
	}
	for _, endpoint := range []string{"hardware-settings", "hardware-settings/test"} {
		g.POST("/transcoding/"+endpoint, s.transcodingNodeHTTP(endpoint, http.MethodPost))
	}
	g.POST("/transcoding/playback/:song", s.negotiatePlaybackHTTP)
	s.registerTranscodeRoutes(g, "/transcoding/sessions", func(*gin.Context) string { return "admin" }, "/api/v1/transcoding/sessions", nil)
	g.GET("/transcoding/offline", s.listOfflineTranscodesHTTP)
	g.POST("/transcoding/offline", s.createOfflineTranscodesHTTP)
	g.POST("/transcoding/offline/:id/cancel", s.cancelOfflineTranscodeHTTP)
	g.DELETE("/transcoding/offline/:id", s.deleteOfflineTranscodeHTTP)
}

func (s *Service) transcodingNodeHTTP(endpoint, method string) gin.HandlerFunc {
	return func(c *gin.Context) {
		var body map[string]any
		if method == http.MethodPost {
			var ok bool
			body, ok = decodeInput(c)
			if !ok {
				return
			}
		}
		s.respond(c, 200, func(ctx context.Context) (any, error) {
			return s.nodeCall(ctx, "/internal/v1/transcoding/"+endpoint, method, body)
		})
	}
}

func (s *Service) negotiatePlaybackHTTP(c *gin.Context) {
	body, ok := decodeInput(c)
	if !ok {
		return
	}
	s.respond(c, 200, func(ctx context.Context) (any, error) {
		song := c.Param("song")
		return s.Negotiate(ctx, song, "admin", body, "/api/v1/media-songs/"+song+"/video", "/api/v1/transcoding/sessions")
	})
}

func (s *Service) listOfflineTranscodesHTTP(c *gin.Context) {
	s.respond(c, 200, func(ctx context.Context) (any, error) {
		return s.offlineTranscodes(ctx)
	})
}

func (s *Service) createOfflineTranscodesHTTP(c *gin.Context) {
	body, ok := decodeInput(c)
	if !ok {
		return
	}
	s.respond(c, 200, func(ctx context.Context) (any, error) { return s.Offline(ctx, body) })
}

func (s *Service) cancelOfflineTranscodeHTTP(c *gin.Context) {
	s.respond(c, 200, func(ctx context.Context) (any, error) { return nil, s.CancelTranscode(ctx, c.Param("id"), "admin") })
}

func (s *Service) deleteOfflineTranscodeHTTP(c *gin.Context) {
	s.respond(c, 200, func(ctx context.Context) (any, error) { return nil, s.deleteTranscode(ctx, c.Param("id")) })
}

func (s *Service) registerTranscodeRoutes(g *gin.RouterGroup, base string, owner func(*gin.Context) string, prefix string, requireVersion gin.HandlerFunc) {
	g.GET(base+"/:id", func(c *gin.Context) {
		s.respond(c, 200, func(ctx context.Context) (any, error) { return s.TranscodeView(ctx, c.Param("id"), owner(c), prefix) })
	})
	g.POST(base+"/:id/cancel", func(c *gin.Context) {
		s.respond(c, 200, func(ctx context.Context) (any, error) { return nil, s.CancelTranscode(ctx, c.Param("id"), owner(c)) })
	})
	versions := g
	if requireVersion != nil {
		versions = g.Group("", requireVersion)
	}
	for _, method := range []string{"GET", "HEAD"} {
		g.Handle(method, base+"/:id/artifacts/:name", func(c *gin.Context) {
			s.stream(c, func(ctx context.Context, w http.ResponseWriter, r *http.Request) error {
				return s.Artifact(ctx, w, r, c.Param("id"), owner(c), c.Param("name"))
			})
		})
		versions.Handle(method, base+"/versions/:id/:song", func(c *gin.Context) {
			s.stream(c, func(ctx context.Context, w http.ResponseWriter, r *http.Request) error {
				return s.Version(ctx, w, r, c.Param("id"), c.Param("song"))
			})
		})
	}
}

package party

import (
	"github.com/gin-gonic/gin"
)

func (s *Service) registerPlaybackRoutes(r *gin.Engine) {
	r.POST("/api/v1/tv/playback-resources", s.RequireActor(true), s.playbackResourceHTTP)
}

func (s *Service) playbackResourceHTTP(c *gin.Context) {
	in, ok := decode(c)
	if !ok {
		return
	}
	song, err := text(in, "songId", 64)
	if err != nil {
		s.report(c, err)
		return
	}
	raw, err := s.library.Song(c.Request.Context(), song)
	if err != nil {
		s.report(c, err)
		return
	}
	if s.negotiator == nil {
		s.report(c, fail(503, "播放服务暂不可用"))
		return
	}
	out, err := s.negotiator(c.Request.Context(), song, "tv:"+str(actorFrom(c), "id"), in, "/api/v1/tv/media/"+song, "/api/v1/tv/transcoding")
	if err != nil {
		s.report(c, err)
		return
	}
	out["revision"] = Projection(raw)["revision"]
	c.Header("Cache-Control", "no-store")
	c.JSON(200, out)
}

func (s *Service) media(c *gin.Context) {
	a := actorFrom(c)
	if str(a, "kind") != "device" {
		s.report(c, fail(403, "仅电视可取播放媒体"))
		return
	}
	if err := s.library.ServeOriginal(c.Request.Context(), c.Writer, c.Request, c.Param("id")); err != nil && !c.Writer.Written() {
		s.report(c, err)
	}
}

package execution

import (
	"context"
	"net/http"
	"strconv"

	"github.com/gin-gonic/gin"
)

func (s *Service) registerAudioRoutes(g *gin.RouterGroup) {
	for _, method := range []string{"GET", "PUT"} {
		g.Handle(method, "/media-songs/:id/audio-roles", s.songRolesHTTP)
	}
	g.POST("/media-songs/:id/audio-jobs", s.submitAudioJobHTTP)
	g.POST("/media-songs/:id/accompaniment/:job/activate", s.activateAudioHTTP)
	g.POST("/media-songs/:id/audio-jobs/:job/cancel", s.cancelAudioJobHTTP)
	for _, method := range []string{"GET", "HEAD"} {
		g.Handle(method, "/media-songs/:id/accompaniment/:job", s.accompanimentHTTP)
	}
	g.GET("/audio-processing/songs", s.listAudioSongsHTTP)
}

func (s *Service) submitAudioJobHTTP(c *gin.Context) {
	body, ok := decodeInput(c)
	if !ok {
		return
	}
	if body["sourceAudioId"] != nil && !boolv(body["sourceConfirmed"]) {
		s.report(c, fail(400, "请试听并明确确认所选音轨或声道是伴奏"))
		return
	}
	s.respond(c, 200, func(ctx context.Context) (any, error) {
		return s.SubmitAudio(ctx, c.Param("id"), str(body, "kind"), str(body, "backend"), body)
	})
}

func (s *Service) activateAudioHTTP(c *gin.Context) {
	s.respond(c, 200, func(ctx context.Context) (any, error) { return s.activateAudio(ctx, c.Param("id"), c.Param("job")) })
}

func (s *Service) cancelAudioJobHTTP(c *gin.Context) {
	s.respond(c, 200, func(ctx context.Context) (any, error) { return nil, s.CancelAudio(ctx, c.Param("id"), c.Param("job")) })
}

func (s *Service) listAudioSongsHTTP(c *gin.Context) {
	page, size, ok := pagination(c, 30)
	if !ok {
		return
	}
	filter := map[string]any{"title": c.Query("title"), "artist": c.Query("artist"), "sourceId": c.Query("sourceId"), "state": c.Query("state"), "songId": c.Query("songId")}
	if value := c.Query("streams"); value != "" {
		n, err := strconv.Atoi(value)
		if err != nil || n < 0 || n > 1000 {
			s.report(c, fail(400, "音轨数量无效"))
			return
		}
		filter["streams"] = n
	}
	s.respond(c, 200, func(ctx context.Context) (any, error) { return s.audioSongs(ctx, filter, page, size) })
}

func (s *Service) accompanimentHTTP(c *gin.Context) {
	s.stream(c, func(ctx context.Context, w http.ResponseWriter, r *http.Request) error {
		return s.accompaniment(ctx, w, r, c.Param("id"), c.Param("job"))
	})
}

func (s *Service) songRolesHTTP(c *gin.Context) { s.roleHTTP(c, c.Param("id")) }

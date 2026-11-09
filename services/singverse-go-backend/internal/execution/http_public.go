package execution

import (
	"context"
	"net/http"

	"github.com/gin-gonic/gin"
)

// RegisterPublic registers self-authenticated compute grants and explicit Bearer
// routes. Shared audio and owned leases accept party actors; original playback
// and offline-version resources retain their television-device boundary.
func (s *Service) RegisterPublic(engine *gin.Engine, requireTV, requireActor gin.HandlerFunc) {
	for _, kind := range []string{"audio", "transcode"} {
		for _, method := range []string{"GET", "HEAD"} {
			engine.Handle(method, "/api/v1/internal/"+kind+"-input/:id", func(c *gin.Context) {
				s.stream(c, func(ctx context.Context, w http.ResponseWriter, r *http.Request) error {
					if kind == "audio" {
						return s.sourceAudio(ctx, w, r, c.Param("id"))
					}
					return s.sourceTranscode(ctx, w, r, c.Param("id"))
				})
			})
		}
	}
	actors := engine.Group("/api/v1", requireActor)
	s.registerTranscodeRoutes(actors, "/tv/transcoding", actorTranscodeOwner, "/api/v1/tv/transcoding", requireTV)
	for _, base := range []string{"/party/audio-roles/songs", "/tv/audio-roles/songs"} {
		for _, method := range []string{"GET", "PUT"} {
			actors.Handle(method, base+"/:song", func(c *gin.Context) { s.roleHTTP(c, c.Param("song")) })
		}
	}
	for _, method := range []string{"GET", "HEAD"} {
		actors.Handle(method, "/tv/audio-roles/songs/:song/accompaniment/:id", func(c *gin.Context) {
			s.stream(c, func(ctx context.Context, w http.ResponseWriter, r *http.Request) error {
				return s.accompaniment(ctx, w, r, c.Param("song"), c.Param("id"))
			})
		})
	}
}

func actorTranscodeOwner(c *gin.Context) string {
	actor, _ := c.Get("partyActor")
	return "tv:" + str(obj(actor), "id")
}

package party

import (
	"context"

	"github.com/gin-gonic/gin"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

func (s *Service) registerSessionRoutes(r *gin.Engine) {
	r.POST("/api/v1/tv/sessions", s.createSessionHTTP)
	r.POST("/api/v1/tv/sessions/:id/recover", s.recoverSessionHTTP)
	for _, prefix := range []string{"tv", "party"} {
		r.GET("/api/v1/"+prefix+"/sessions/:id/snapshot", s.sessionSnapshotHTTP)
		r.POST("/api/v1/"+prefix+"/sessions/:id/commands", s.queueCommandHTTP)
	}
	r.POST("/api/v1/tv/sessions/:id/playback-events", s.playbackEventHTTP)
	r.POST("/api/v1/tv/sessions/:id/invitations", s.createInvitationHTTP)
	r.GET("/api/v1/tv/sessions/:id/participants", s.listParticipantsHTTP)
}

func (s *Service) createSessionHTTP(c *gin.Context) {
	s.runAsActor(c, func(ctx context.Context, tx store.DBTX, actor document) (any, error) {
		return s.session(ctx, tx, actor)
	})
}

func (s *Service) recoverSessionHTTP(c *gin.Context) {
	s.runAsActor(c, func(ctx context.Context, tx store.DBTX, actor document) (any, error) {
		return s.recover(ctx, tx, actor, c.Param("id"))
	})
}

func (s *Service) playbackEventHTTP(c *gin.Context) {
	input, ok := decode(c)
	if !ok {
		return
	}
	s.runAsActor(c, func(ctx context.Context, tx store.DBTX, actor document) (any, error) {
		return s.recordPlaybackEvent(ctx, tx, actor, c.Param("id"), input)
	})
}

func (s *Service) createInvitationHTTP(c *gin.Context) {
	s.runAsActor(c, func(ctx context.Context, tx store.DBTX, actor document) (any, error) {
		return s.invitation(ctx, tx, actor, c.Param("id"))
	})
}

func (s *Service) listParticipantsHTTP(c *gin.Context) {
	s.runAsActor(c, func(ctx context.Context, tx store.DBTX, actor document) (any, error) {
		session, err := s.get(ctx, tx, c.Param("id"))
		if err != nil {
			return nil, err
		}
		if err = check(actor, session); err != nil {
			return nil, err
		}
		if str(actor, "kind") != "device" {
			return nil, fail(403, "仅电视可查看参与设备")
		}
		participants, err := s.list(ctx, tx, "participant")
		if err != nil {
			return nil, err
		}
		participantsView := []document{}
		for _, participant := range participants {
			if !s.expired(participant) && str(participant, "sessionId") == c.Param("id") {
				delete(participant, "expiresAt")
				participantsView = append(participantsView, participant)
			}
		}
		return participantsView, nil
	})
}

func (s *Service) sessionSnapshotHTTP(c *gin.Context) {
	s.runAsActor(c, func(ctx context.Context, tx store.DBTX, actor document) (any, error) {
		session, err := s.get(ctx, tx, c.Param("id"))
		if err != nil {
			return nil, err
		}
		if err = check(actor, session); err != nil {
			return nil, err
		}
		return snapshot(session, actor), nil
	})
}

func (s *Service) queueCommandHTTP(c *gin.Context) {
	input, ok := decode(c)
	if !ok {
		return
	}
	s.runAsActor(c, func(ctx context.Context, tx store.DBTX, actor document) (any, error) {
		return s.changeQueue(ctx, tx, actor, c.Param("id"), input)
	})
}

package party

import (
	"github.com/gin-gonic/gin"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

func (s *Service) registerInvitationRoutes(r *gin.Engine) {
	r.POST("/api/v1/party/invitations/preview", s.previewInvitationHTTP)
	r.POST("/api/v1/party/join", s.joinPartyHTTP)
}

func (s *Service) previewInvitationHTTP(c *gin.Context) {
	in, ok := decode(c)
	if !ok {
		return
	}
	s.run(c, func(tx store.DBTX) (any, error) {
		secret, err := text(in, "invitation", 200)
		if err != nil {
			return nil, err
		}
		invite, err := s.invite(c.Request.Context(), tx, secret)
		if err != nil {
			return nil, err
		}
		session, err := s.get(c.Request.Context(), tx, str(invite, "sessionId"))
		if err != nil {
			return nil, err
		}
		device, err := s.get(c.Request.Context(), tx, str(session, "deviceId"))
		if err != nil {
			return nil, err
		}
		return document{"sessionId": session["id"], "deviceName": device["name"]}, nil
	})
}

func (s *Service) joinPartyHTTP(c *gin.Context) {
	in, ok := decode(c)
	if !ok {
		return
	}
	s.run(c, func(tx store.DBTX) (any, error) { return s.join(c.Request.Context(), tx, in) })
}

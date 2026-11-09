package party

import (
	"github.com/gin-gonic/gin"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

func (s *Service) registerDeviceRoutes(r *gin.Engine) {
	r.GET("/api/v1/server-info", s.serverInfoHTTP)
	r.POST("/api/v1/device-authorizations", s.requestDeviceAuthorizationHTTP)
	r.POST("/api/v1/device-authorizations/:id/poll", s.pollDeviceAuthorizationHTTP)
	r.GET("/api/v1/admin/tv-devices", s.listDevicesHTTP)
	r.POST("/api/v1/admin/device-authorizations/:id/approve", s.approveDeviceHTTP)
	r.DELETE("/api/v1/admin/device-authorizations/:id", s.denyDeviceHTTP)
	r.DELETE("/api/v1/admin/tv-devices/:id", s.revokeDeviceHTTP)
}

func (s *Service) serverInfoHTTP(c *gin.Context) {
	s.run(c, func(tx store.DBTX) (any, error) { return s.serverInfo(c.Request.Context(), tx) })
}

func (s *Service) requestDeviceAuthorizationHTTP(c *gin.Context) {
	in, ok := decode(c)
	if !ok {
		return
	}
	s.run(c, func(tx store.DBTX) (any, error) { return s.authorize(c.Request.Context(), tx, in) })
}

func (s *Service) pollDeviceAuthorizationHTTP(c *gin.Context) {
	in, ok := decode(c)
	if !ok {
		return
	}
	s.run(c, func(tx store.DBTX) (any, error) {
		secret, err := text(in, "pollSecret", 200)
		if err != nil {
			return nil, err
		}
		return s.exchange(c.Request.Context(), tx, c.Param("id"), secret)
	})
}

func (s *Service) listDevicesHTTP(c *gin.Context) {
	s.run(c, func(tx store.DBTX) (any, error) {
		devices, err := s.list(c.Request.Context(), tx, "device")
		if err != nil {
			return nil, err
		}
		pending, err := s.list(c.Request.Context(), tx, "authorization")
		if err != nil {
			return nil, err
		}
		out := []document{}
		for _, v := range pending {
			if !s.expired(v) && str(v, "status") == "PENDING" {
				out = append(out, v)
			}
		}
		return document{"devices": devices, "pending": out}, nil
	})
}

func (s *Service) approveDeviceHTTP(c *gin.Context) {
	in, ok := decode(c)
	if !ok {
		return
	}
	s.run(c, func(tx store.DBTX) (any, error) { return s.approve(c.Request.Context(), tx, c.Param("id"), in) })
}

func (s *Service) denyDeviceHTTP(c *gin.Context) {
	s.run(c, func(tx store.DBTX) (any, error) { return nil, s.deny(c.Request.Context(), tx, c.Param("id")) })
}

func (s *Service) revokeDeviceHTTP(c *gin.Context) {
	s.run(c, func(tx store.DBTX) (any, error) { return nil, s.revoke(c.Request.Context(), tx, c.Param("id")) })
}

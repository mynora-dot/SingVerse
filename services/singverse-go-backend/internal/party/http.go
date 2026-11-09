package party

import (
	"context"
	"errors"
	"fmt"
	"strings"

	"github.com/gin-gonic/gin"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/httpx"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

func (s *Service) report(c *gin.Context, err error) {
	var p *failure
	if errors.As(err, &p) {
		httpx.Error(c, p.status, "about:blank", p.detail)
		return
	}
	if status, ok := err.(interface{ StatusCode() int }); ok {
		httpx.Error(c, status.StatusCode(), "about:blank", err.Error())
		return
	}
	s.log.ErrorContext(c.Request.Context(), "party operation failed", "type", fmt.Sprintf("%T", err))
	httpx.Error(c, 500, "about:blank", "操作失败，请稍后重试")
}

func (s *Service) run(c *gin.Context, fn func(store.DBTX) (any, error)) {
	c.Header("Cache-Control", "no-store")
	v, err := s.transact(c.Request.Context(), fn)
	if err != nil {
		s.report(c, err)
		return
	}
	if v == nil {
		c.Status(200)
	} else {
		c.JSON(200, v)
	}
}

func (s *Service) actor(ctx context.Context, tx store.DBTX, c *gin.Context) (document, error) {
	h := c.GetHeader("Authorization")
	if !strings.HasPrefix(h, "Bearer ") {
		return nil, fail(401, "请先连接")
	}
	return s.authenticate(ctx, tx, strings.TrimPrefix(h, "Bearer "))
}

func (s *Service) RequireActor(deviceOnly bool) gin.HandlerFunc {
	return func(c *gin.Context) {
		c.Header("Cache-Control", "no-store")
		h := c.GetHeader("Authorization")
		if !strings.HasPrefix(h, "Bearer ") {
			s.report(c, fail(401, "请先连接"))
			return
		}
		a, err := s.Authenticate(c.Request.Context(), strings.TrimPrefix(h, "Bearer "))
		if err != nil {
			s.report(c, err)
			return
		}
		if deviceOnly && str(a, "kind") != "device" {
			s.report(c, fail(403, "需要电视设备授权"))
			return
		}
		c.Set("partyActor", a)
		c.Next()
	}
}

func actorFrom(c *gin.Context) document {
	v, _ := c.Get("partyActor")
	a, _ := v.(map[string]any)
	return a
}

func decode(c *gin.Context) (document, bool) {
	var v document
	if !httpx.Decode(c, &v, 16384) {
		return nil, false
	}
	if v == nil {
		httpx.Error(c, 400, "about:blank", "请求内容无效")
		return nil, false
	}
	return v, true
}

// runAsActor authenticates and mutates through the same transaction executor.
func (s *Service) runAsActor(c *gin.Context, operation func(context.Context, store.DBTX, document) (any, error)) {
	s.run(c, func(tx store.DBTX) (any, error) {
		ctx := c.Request.Context()
		actor, err := s.actor(ctx, tx, c)
		if err != nil {
			return nil, err
		}
		return operation(ctx, tx, actor)
	})
}

func (s *Service) Register(router *gin.Engine) {
	s.registerDeviceRoutes(router)
	s.registerInvitationRoutes(router)
	s.registerSessionRoutes(router)
	s.registerPlaybackRoutes(router)
	s.registerLibrary(router)
	s.registerHome(router)
}

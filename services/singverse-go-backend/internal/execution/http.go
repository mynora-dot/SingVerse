package execution

import (
	"context"
	"net/http"
	"strconv"

	"github.com/gin-gonic/gin"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/httpx"
)

func (s *Service) respond(c *gin.Context, status int, fn func(context.Context) (any, error)) {
	c.Header("Cache-Control", "no-store")
	v, err := fn(c.Request.Context())
	if err != nil {
		s.report(c, err)
		return
	}
	if v == nil {
		c.Status(status)
	} else {
		c.JSON(status, v)
	}
}

func decodeInput(c *gin.Context) (map[string]any, bool) {
	var body map[string]any
	if !httpx.Decode(c, &body, 2<<20) {
		return nil, false
	}
	if body == nil {
		httpx.Error(c, 400, "about:blank", "请求内容无效")
		return nil, false
	}
	return body, true
}

func pagination(c *gin.Context, defaultSize int) (int, int, bool) {
	page, e1 := strconv.Atoi(c.DefaultQuery("page", "0"))
	size, e2 := strconv.Atoi(c.DefaultQuery("size", strconv.Itoa(defaultSize)))
	if e1 != nil || e2 != nil || page < 0 || page > 1000000 || size < 1 || size > 100 {
		httpx.Error(c, 400, "about:blank", "分页参数无效")
		return 0, 0, false
	}
	return page, size, true
}

func (s *Service) roleHTTP(c *gin.Context, song string) {
	if c.Request.Method == "GET" {
		s.respond(c, 200, func(ctx context.Context) (any, error) { return s.audioStatus(ctx, song) })
		return
	}
	body, ok := decodeInput(c)
	if !ok {
		return
	}
	revision, ok := intInput(body["revision"])
	if !ok || revision < 0 {
		s.report(c, fail(400, "缺少音轨修订号"))
		return
	}
	s.respond(c, 200, func(ctx context.Context) (any, error) {
		return s.correctRoles(ctx, song, str(body, "originalId"), str(body, "backingId"), revision)
	})
}

func (s *Service) stream(c *gin.Context, fn func(context.Context, http.ResponseWriter, *http.Request) error) {
	if err := fn(c.Request.Context(), c.Writer, c.Request); err != nil && !c.Writer.Written() {
		s.report(c, err)
	}
}

// Register adds administrator/mobile endpoints to the already authenticated group.
func (s *Service) Register(group *gin.RouterGroup) {
	s.registerAudioRoutes(group)
	s.registerAudioBatchRoutes(group)
	s.registerModelRoutes(group)
	s.registerTranscodingRoutes(group)
	s.registerCacheRoutes(group)
}

package overview

import (
	"net/http"

	"github.com/gin-gonic/gin"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/httpx"
)

// Handler renders the admin overview snapshot. It is mounted inside the admin
// Cookie/CSRF boundary by the composition root. The response is never cached; a
// totally unavailable database yields 503 problem+json, while individual section
// failures are returned as null sections with a 200 and an errors map.
func (s *Service) Handler(c *gin.Context) {
	c.Header("Cache-Control", "no-store")
	out, err := s.Collect(c.Request.Context())
	if err != nil {
		httpx.Error(c, http.StatusServiceUnavailable, "about:blank", "数据库暂时不可用")
		return
	}
	c.JSON(http.StatusOK, out)
}

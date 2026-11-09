package compute

import (
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"errors"
	"fmt"
	"net/http"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/httpx"
)

func randomID() (string, error) {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		return "", err
	}
	b[6] = (b[6] & 15) | 64
	b[8] = (b[8] & 63) | 128
	v := hex.EncodeToString(b[:])
	return v[:8] + "-" + v[8:12] + "-" + v[12:16] + "-" + v[16:20] + "-" + v[20:], nil
}

// ObserveOrigin must only receive an origin derived from an authenticated admin
// request after the application's trusted-proxy and origin checks.
func (s *Service) ObserveOrigin(value string) {
	if s.configuredOrigin != "" {
		return
	}
	v, err := origin(value)
	if err != nil {
		return
	}
	s.mu.Lock()
	s.observed = v
	s.mu.Unlock()
}
func (s *Service) CallbackOrigin(connection *Connection) (string, error) {
	if s.configuredOrigin != "" {
		return s.configuredOrigin, nil
	}
	if connection != nil && connection.Origin == s.automaticEndpoint && s.automaticOrigin != "" {
		return s.automaticOrigin, nil
	}
	s.mu.Lock()
	value := s.observed
	s.mu.Unlock()
	if value == "" {
		return "", reject(503, "无法确定业务回源地址，请通过计算节点可达的地址打开管理页后测试连接")
	}
	return value, nil
}
func (s *Service) authorize(token string) (string, error) {
	id, err := randomID()
	if err != nil {
		return "", err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	now := time.Now()
	for key, value := range s.grants {
		if now.After(value.expires) {
			delete(s.grants, key)
		}
	}
	if len(s.grants) >= 256 {
		return "", reject(429, "正在检查节点回源，请稍后重试")
	}
	s.grants[id] = grant{token, now.Add(15 * time.Second)}
	return id, nil
}
func (s *Service) revoke(id string) { s.mu.Lock(); delete(s.grants, id); s.mu.Unlock() }
func (s *Service) verify(id, authorization string) bool {
	s.mu.Lock()
	value, ok := s.grants[id]
	s.mu.Unlock()
	return ok && time.Now().Before(value.expires) && subtle.ConstantTimeCompare([]byte("Bearer "+value.token), []byte(authorization)) == 1
}
func (s *Service) report(c *gin.Context, err error) {
	var f *failure
	if errors.As(err, &f) {
		httpx.Error(c, f.status, "about:blank", f.detail)
		return
	}
	s.log.ErrorContext(c.Request.Context(), "compute operation failed", "error_type", fmt.Sprintf("%T", err))
	httpx.Error(c, 500, "about:blank", "计算节点操作暂不可用")
}

// Register requires the administrator Cookie/Origin/CSRF middleware on group.
func (s *Service) Register(group *gin.RouterGroup) {
	group.GET("/compute-node", func(c *gin.Context) {
		v, err := s.View(c.Request.Context())
		if err != nil {
			s.report(c, err)
			return
		}
		c.JSON(200, v)
	})
	group.PUT("/compute-node", func(c *gin.Context) {
		var input Save
		if !httpx.Decode(c, &input, 65536) {
			return
		}
		v, err := s.Save(c.Request.Context(), input)
		if err != nil {
			s.report(c, err)
			return
		}
		c.JSON(200, v)
	})
	group.POST("/compute-node/test", func(c *gin.Context) {
		var input Save
		if !httpx.Decode(c, &input, 65536) {
			return
		}
		connection, err := s.Draft(c.Request.Context(), input)
		if err != nil {
			s.report(c, err)
			return
		}
		v, err := s.Probe(c.Request.Context(), connection)
		if err != nil {
			s.report(c, err)
			return
		}
		c.JSON(200, v)
	})
	group.POST("/compute-node/discovery", func(c *gin.Context) {
		var input Scan
		if c.Request.ContentLength != 0 && !httpx.Decode(c, &input, 4096) {
			return
		}
		v, err := s.Discover(c.Request.Context(), input)
		if err != nil {
			s.report(c, err)
			return
		}
		c.JSON(200, v)
	})
	group.POST("/compute-node/pair", func(c *gin.Context) {
		var input Pair
		if !httpx.Decode(c, &input, 4096) {
			return
		}
		v, err := s.Pair(c.Request.Context(), input)
		if err != nil {
			s.report(c, err)
			return
		}
		c.JSON(200, v)
	})
}
func (s *Service) RegisterPublic(engine *gin.Engine) {
	engine.GET("/api/v1/internal/compute-callback/:id", func(c *gin.Context) {
		c.Header("Cache-Control", "no-store")
		id := c.Param("id")
		if len(id) > 64 || !s.verify(id, c.GetHeader("Authorization")) {
			httpx.Error(c, 401, "about:blank", "业务回源认证失败")
			return
		}
		c.Data(http.StatusOK, "text/plain; charset=utf-8", []byte(id))
	})
}

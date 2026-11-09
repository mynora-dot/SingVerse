package auth

import (
	"crypto/subtle"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"strconv"
	"strings"

	"github.com/gin-gonic/gin"
	"github.com/jmoiron/sqlx"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/httpx"
)

const (
	SessionCookie   = "singverse_session"
	ChallengeCookie = "singverse_challenge"
	AnonymousCookie = "singverse_csrf"
	sessionRequired = "urn:singverse:auth:session-required"
)

type originPolicy struct {
	publicOrigin string
	proxies      []netip.Prefix
}

func newOriginPolicy(origin string, proxies []string) (originPolicy, error) {
	p := originPolicy{publicOrigin: origin}
	if origin != "" {
		u, err := url.Parse(origin)
		if err != nil || u.Scheme != "https" || u.Host == "" || u.User != nil || u.Path != "" || u.RawPath != "" || u.RawQuery != "" || u.ForceQuery || u.Fragment != "" {
			return p, errors.New("public origin must be an HTTPS origin without a path")
		}
	}
	for _, v := range proxies {
		prefix, err := netip.ParsePrefix(v)
		if err != nil {
			ip, e := netip.ParseAddr(v)
			if e != nil {
				return p, errors.New("invalid trusted proxy")
			}
			prefix = netip.PrefixFrom(ip, ip.BitLen())
		}
		if prefix.Bits() == 0 {
			return p, errors.New("wildcard proxy trust is forbidden")
		}
		p.proxies = append(p.proxies, prefix.Masked())
	}
	return p, nil
}
func remote(c *gin.Context) netip.Addr {
	host, _, err := net.SplitHostPort(c.Request.RemoteAddr)
	if err != nil {
		host = c.Request.RemoteAddr
	}
	ip, _ := netip.ParseAddr(host)
	return ip.Unmap()
}
func (p originPolicy) trusted(c *gin.Context) bool {
	ip := remote(c)
	for _, v := range p.proxies {
		if v.Contains(ip) {
			return true
		}
	}
	return false
}
func (p originPolicy) scheme(c *gin.Context) string {
	scheme := "http"
	if c.Request.TLS != nil {
		scheme = "https"
	}
	if p.trusted(c) && c.GetHeader("X-Forwarded-Proto") != "" {
		scheme = c.GetHeader("X-Forwarded-Proto")
	}
	return scheme
}
func (s *Service) Source(c *gin.Context) string {
	if s.policy.trusted(c) {
		if ip, err := netip.ParseAddr(c.GetHeader("X-Real-IP")); err == nil {
			return ip.String()
		}
	}
	return remote(c).String()
}
func (s *Service) CallbackOrigin(c *gin.Context) string {
	if s.policy.publicOrigin != "" {
		return s.policy.publicOrigin
	}
	host := c.Request.Host
	if s.policy.trusted(c) && c.GetHeader("X-Forwarded-Host") != "" {
		host = c.GetHeader("X-Forwarded-Host")
	}
	return s.policy.scheme(c) + "://" + host
}

func (s *Service) checkOrigin(c *gin.Context) error {
	actual := s.policy.scheme(c) + "://" + c.Request.Host
	expected := actual
	if s.policy.publicOrigin != "" {
		expected = s.policy.publicOrigin
		if actual != expected {
			return reject(400, "请求入口不符合 HTTPS 部署配置")
		}
	}
	if origin := c.GetHeader("Origin"); origin != "" && origin != expected {
		return reject(403, "不允许跨来源请求")
	}
	return nil
}
func cookie(c *gin.Context, name string) string { v, _ := c.Cookie(name); return v }
func (s *Service) setCookie(c *gin.Context, name, value string) {
	v := &http.Cookie{Name: name, Value: value, Path: "/", HttpOnly: true, Secure: s.policy.publicOrigin != "", SameSite: http.SameSiteLaxMode}
	if value == "" {
		v.MaxAge = -1
	}
	http.SetCookie(c.Writer, v)
}
func (s *Service) clear(c *gin.Context) {
	for _, name := range []string{SessionCookie, ChallengeCookie, AnonymousCookie} {
		s.setCookie(c, name, "")
	}
}
func (s *Service) report(c *gin.Context, err error) {
	var failure *Failure
	if errors.As(err, &failure) {
		if failure.RetryAfter > 0 {
			c.Header("Retry-After", strconv.Itoa(failure.RetryAfter))
		}
		httpx.Error(c, failure.Status, failure.Type, failure.Detail)
		return
	}
	s.log.ErrorContext(c.Request.Context(), "authentication operation failed", "error_type", fmt.Sprintf("%T", err))
	httpx.Error(c, 500, "about:blank", "服务暂时无法完成请求")
}

func safe(method string) bool {
	return method == http.MethodGet || method == http.MethodHead || method == http.MethodOptions
}
func public(path string) bool {
	return path == "/api/v1/auth/csrf" || path == "/api/v1/auth/login" || path == "/api/v1/auth/mfa/verify"
}

// RequireAdmin applies the Cookie/Origin/CSRF boundary. The application mounts
// other identity-specific route groups separately; this middleware never treats
// a device Bearer token as an administrator session.
func (s *Service) RequireAdmin() gin.HandlerFunc {
	return func(c *gin.Context) {
		if !strings.HasPrefix(c.Request.URL.Path, "/api/v1/") {
			c.Next()
			return
		}
		c.Header("Cache-Control", "no-store")
		if err := s.checkOrigin(c); err != nil {
			s.report(c, err)
			return
		}
		_, err := s.transaction(c.Request.Context(), func(tx *sqlx.Tx) (any, error) {
			ctx := c.Request.Context()
			session, err := s.lookup(ctx, tx, cookie(c, SessionCookie), "session")
			if err != nil {
				return nil, err
			}
			if session != nil {
				if _, err = exec(ctx, tx, "touch_token", s.now(), digest(cookie(c, SessionCookie))); err != nil {
					return nil, err
				}
				c.Set("admin", true)
			}
			var csrf *tokenRow
			for _, pair := range [][2]string{{SessionCookie, "session"}, {ChallengeCookie, "challenge"}, {AnonymousCookie, "anonymous"}} {
				csrf, err = s.lookup(ctx, tx, cookie(c, pair[0]), pair[1])
				if err != nil {
					return nil, err
				}
				if csrf != nil {
					break
				}
			}
			if !safe(c.Request.Method) {
				provided := c.GetHeader("X-CSRF-Token")
				if csrf == nil || provided == "" || subtle.ConstantTimeCompare([]byte(csrf.csrf), []byte(provided)) != 1 {
					return nil, reject(403, "请求未通过安全校验")
				}
			}
			if !public(c.Request.URL.Path) && session == nil {
				return nil, &Failure{Status: 401, Detail: "请先登录", Type: sessionRequired}
			}
			return nil, nil
		})
		if err != nil {
			s.report(c, err)
			return
		}
		c.Next()
	}
}

type credentials struct {
	Username    string `json:"username"`
	Password    string `json:"password"`
	Code        string `json:"code"`
	NewPassword string `json:"newPassword"`
	NewUsername string `json:"newUsername"`
}

func (s *Service) run(c *gin.Context, fn func(*sqlx.Tx) (any, error)) {
	result, err := s.transaction(c.Request.Context(), fn)
	if err != nil {
		s.report(c, err)
		return
	}
	if result != nil {
		c.JSON(200, result)
	} else {
		c.Status(200)
	}
}
func (s *Service) send(c *gin.Context, q dbtx, value issued) error {
	for _, name := range []string{SessionCookie, ChallengeCookie, AnonymousCookie} {
		if err := s.revoke(c.Request.Context(), q, cookie(c, name)); err != nil {
			return err
		}
	}
	s.clear(c)
	name := SessionCookie
	if value.kind == "challenge" {
		name = ChallengeCookie
	}
	s.setCookie(c, name, value.token)
	return nil
}

func (s *Service) Register(router *gin.Engine) {
	router.GET("/api/v1/auth/csrf", func(c *gin.Context) {
		s.run(c, func(tx *sqlx.Tx) (any, error) {
			for _, pair := range [][2]string{{SessionCookie, "session"}, {ChallengeCookie, "challenge"}, {AnonymousCookie, "anonymous"}} {
				row, err := s.lookup(c.Request.Context(), tx, cookie(c, pair[0]), pair[1])
				if err != nil {
					return nil, err
				}
				if row != nil {
					return gin.H{"token": row.csrf}, nil
				}
			}
			value, err := s.issue(c.Request.Context(), tx, "anonymous")
			if err != nil {
				return nil, err
			}
			row, err := s.lookup(c.Request.Context(), tx, value.token, "anonymous")
			if err != nil {
				return nil, err
			}
			s.setCookie(c, AnonymousCookie, value.token)
			return gin.H{"token": row.csrf}, nil
		})
	})
	router.POST("/api/v1/auth/login", func(c *gin.Context) {
		var in credentials
		if !httpx.Decode(c, &in, 16384) {
			return
		}
		s.run(c, func(tx *sqlx.Tx) (any, error) {
			value, err := s.login(c.Request.Context(), tx, in.Username, in.Password, s.Source(c))
			if err != nil {
				return nil, err
			}
			if err = s.send(c, tx, value); err != nil {
				return nil, err
			}
			state := "authenticated"
			if value.kind == "challenge" {
				state = "mfaRequired"
			}
			return gin.H{"state": state}, nil
		})
	})
	router.POST("/api/v1/auth/mfa/verify", func(c *gin.Context) {
		var in credentials
		if !httpx.Decode(c, &in, 16384) {
			return
		}
		s.run(c, func(tx *sqlx.Tx) (any, error) {
			value, err := s.complete(c.Request.Context(), tx, cookie(c, ChallengeCookie), in.Code, s.Source(c))
			if err != nil {
				return nil, err
			}
			if err = s.send(c, tx, value); err != nil {
				return nil, err
			}
			return gin.H{"state": "authenticated"}, nil
		})
	})
	for _, path := range []string{"/api/v1/auth/session", "/api/v1/system/parameters"} {
		router.GET(path, func(c *gin.Context) {
			s.run(c, func(tx *sqlx.Tx) (any, error) { return s.status(c.Request.Context(), tx) })
		})
	}
	router.POST("/api/v1/auth/logout", func(c *gin.Context) {
		s.run(c, func(tx *sqlx.Tx) (any, error) {
			err := s.revoke(c.Request.Context(), tx, cookie(c, SessionCookie))
			if err == nil {
				s.clear(c)
			}
			return nil, err
		})
	})
	for _, kind := range []string{"password", "username"} {
		router.POST("/api/v1/auth/"+kind, func(c *gin.Context) {
			var in credentials
			if !httpx.Decode(c, &in, 16384) {
				return
			}
			s.run(c, func(tx *sqlx.Tx) (any, error) {
				next := in.NewPassword
				if kind == "username" {
					next = in.NewUsername
				}
				err := s.change(c.Request.Context(), tx, kind, in.Password, in.Code, next, s.Source(c))
				if err == nil {
					s.clear(c)
				}
				return nil, err
			})
		})
	}
	router.POST("/api/v1/system/parameters/mfa/setup", func(c *gin.Context) {
		var in credentials
		if !httpx.Decode(c, &in, 16384) {
			return
		}
		s.run(c, func(tx *sqlx.Tx) (any, error) {
			return s.setup(c.Request.Context(), tx, in.Password, in.Code, s.Source(c))
		})
	})
	router.DELETE("/api/v1/system/parameters/mfa/setup", func(c *gin.Context) {
		s.run(c, func(tx *sqlx.Tx) (any, error) {
			_, err := exec(c.Request.Context(), tx, "cancel_setup")
			return nil, err
		})
	})
	router.POST("/api/v1/system/parameters/mfa/confirm", func(c *gin.Context) {
		var in credentials
		if !httpx.Decode(c, &in, 16384) {
			return
		}
		s.run(c, func(tx *sqlx.Tx) (any, error) {
			result, err := s.confirm(c.Request.Context(), tx, in.Code, s.Source(c))
			if err == nil {
				s.clear(c)
			}
			return result, err
		})
	})
	router.POST("/api/v1/system/parameters/mfa/disable", func(c *gin.Context) {
		var in credentials
		if !httpx.Decode(c, &in, 16384) {
			return
		}
		s.run(c, func(tx *sqlx.Tx) (any, error) {
			err := s.disable(c.Request.Context(), tx, in.Password, in.Code, s.Source(c))
			if err == nil {
				s.clear(c)
			}
			return nil, err
		})
	})
	router.POST("/api/v1/system/parameters/mfa/recovery-codes", func(c *gin.Context) {
		var in credentials
		if !httpx.Decode(c, &in, 16384) {
			return
		}
		s.run(c, func(tx *sqlx.Tx) (any, error) {
			result, err := s.regenerate(c.Request.Context(), tx, in.Password, in.Code, s.Source(c))
			if err == nil {
				s.clear(c)
			}
			return result, err
		})
	})
}

package ai

import (
	"errors"
	"fmt"
	"github.com/gin-gonic/gin"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/httpx"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/search"
	"strings"
	"time"
)

func (s *Service) report(c *gin.Context, e error) {
	var f *Failure
	if errors.As(e, &f) {
		httpx.Error(c, f.Status, "about:blank", f.Detail)
		return
	}
	s.log.ErrorContext(c.Request.Context(), "AI operation failed", "type", fmt.Sprintf("%T", e))
	httpx.Error(c, 500, "about:blank", "操作失败，请稍后重试")
}
func (s *Service) reply(c *gin.Context, status int, v any, e error) {
	c.Header("Cache-Control", "no-store")
	if e != nil {
		s.report(c, e)
		return
	}
	if status == 204 {
		c.Status(status)
	} else {
		c.JSON(status, v)
	}
}
func (s *Service) Register(r *gin.Engine) {
	r.GET("/api/v1/ai/providers", func(c *gin.Context) { s.reply(c, 200, Providers, nil) })
	for _, route := range []struct{ path, kind string }{{"models", "MODEL"}, {"search-services", "SEARCH"}} {
		p, k := "/api/v1/ai/"+route.path, route.kind
		r.GET(p, func(c *gin.Context) { v, e := s.List(c.Request.Context(), k); s.reply(c, 200, v, e) })
		r.POST(p, func(c *gin.Context) {
			var v ServiceInput
			if !httpx.Decode(c, &v, 20000) {
				return
			}
			o, e := s.Save(c.Request.Context(), k, "", v)
			s.reply(c, 201, o, e)
		})
		r.PUT(p+"/:id", func(c *gin.Context) {
			var v ServiceInput
			if !httpx.Decode(c, &v, 20000) {
				return
			}
			o, e := s.Save(c.Request.Context(), k, c.Param("id"), v)
			s.reply(c, 200, o, e)
		})
		r.DELETE(p+"/:id", func(c *gin.Context) { e := s.Remove(c.Request.Context(), k, c.Param("id")); s.reply(c, 204, nil, e) })
		r.POST(p+"/:id/test", func(c *gin.Context) {
			var v TestInput
			if !httpx.Decode(c, &v, 10000) {
				return
			}
			id := c.Param("id")
			old, e := s.required(c.Request.Context(), s.db, id)
			if e != nil {
				s.report(c, e)
				return
			}
			if old.Kind != k {
				s.report(c, failure(400, "服务类型不匹配"))
				return
			}
			s.mu.Lock()
			if time.Now().Before(s.cooldowns[id]) {
				s.mu.Unlock()
				s.report(c, failure(429, "服务仍在限流冷却，请稍后重试"))
				return
			}
			if s.testing[id] {
				s.mu.Unlock()
				s.report(c, failure(409, "该服务正在测试，请勿重复提交"))
				return
			}
			s.testing[id] = true
			s.mu.Unlock()
			defer func() { s.mu.Lock(); delete(s.testing, id); s.mu.Unlock() }()
			start := time.Now()
			snap, e := s.Snapshot(c.Request.Context(), id)
			out := TestResult{Success: true, Results: []SearchHit{}}
			if e == nil {
				if k == "SEARCH" {
					if strings.TrimSpace(v.Query) == "" || search.UTF16Length(v.Query) > 200 {
						e = failure(400, "搜索测试关键词应为 1～200 字符")
					} else {
						out.Results, e = s.Search(c.Request.Context(), snap, strings.TrimSpace(v.Query))
						out.Message = "查询成功，未找到结果"
						if len(out.Results) > 0 {
							out.Message = fmt.Sprintf("查询成功，返回 %d 条结果", len(out.Results))
						}
					}
				} else {
					switch v.Mode {
					case "generation":
						_, e = s.Generate(c.Request.Context(), snap, "这是服务连接测试。请简短回复连接成功。", "连接测试", nil)
						out.Message = "普通生成连接成功；分类能力需单独验证"
					case "classification":
						if s.tester == nil {
							e = failure(503, "分类能力暂不可用")
						} else {
							e = s.tester(c.Request.Context(), snap)
						}
						out.Message = "分类结构化输出验证通过；不代表分类准确率"
					default:
						e = failure(400, "未知模型测试类型")
					}
				}
			}
			if e != nil {
				var f *Failure
				if errors.As(e, &f) {
					status := 502
					if f.Status == 429 {
						status = 429
						s.mu.Lock()
						s.cooldowns[id] = time.Now().Add(time.Minute)
						s.mu.Unlock()
					} else if f.Status == 504 {
						status = 504
					} else if f.Status == 400 || f.Status == 503 {
						status = f.Status
					}
					e = failure(status, f.Detail)
				}
			}
			out.ElapsedMS = time.Since(start).Milliseconds()
			s.reply(c, 200, out, e)
		})
	}
	r.GET("/api/v1/ai/features", func(c *gin.Context) { v, e := s.Features(c.Request.Context()); s.reply(c, 200, v, e) })
	r.PUT("/api/v1/ai/features/:id", func(c *gin.Context) {
		var v Feature
		if !httpx.Decode(c, &v, 10000) {
			return
		}
		out, e := s.Bind(c.Request.Context(), c.Param("id"), v)
		s.reply(c, 200, out, e)
	})
}

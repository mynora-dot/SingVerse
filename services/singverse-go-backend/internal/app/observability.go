package app

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"log/slog"
	"net/http"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/httpx"
)

func (a *Application) observability(log *slog.Logger) gin.HandlerFunc {
	return func(c *gin.Context) {
		var id [16]byte
		_, _ = rand.Read(id[:])
		requestID := hex.EncodeToString(id[:])
		c.Header("X-Request-ID", requestID)
		c.Header("X-Content-Type-Options", "nosniff")
		if len(c.Request.URL.Path) >= 4 && c.Request.URL.Path[:4] == "/api" {
			c.Header("Cache-Control", "no-store")
		}
		start := time.Now()
		a.active.Add(1)
		defer func() {
			if recover() != nil {
				log.ErrorContext(c.Request.Context(), "request panic", "request_id", requestID, "route", c.FullPath())
				if !c.Writer.Written() {
					httpx.Error(c, 500, "about:blank", "服务暂时无法完成请求")
				}
				c.Abort()
			}
			a.active.Add(-1)
			a.requests.Add(1)
			elapsed := time.Since(start)
			a.nanos.Add(elapsed.Nanoseconds())
			log.InfoContext(c.Request.Context(), "http request", "request_id", requestID, "method", c.Request.Method, "route", c.FullPath(), "status", c.Writer.Status(), "duration_ms", elapsed.Milliseconds())
		}()
		c.Next()
	}
}

// Diagnostics exposes aggregate metrics only on the explicitly enabled loopback listener.
func (a *Application) Diagnostics() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /metrics", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
		stats := a.db.Stats()
		fmt.Fprintf(w, "singverse_http_requests_total %d\nsingverse_http_requests_active %d\nsingverse_http_request_duration_seconds_sum %.9f\nsingverse_database_connections_open %d\nsingverse_database_connections_in_use %d\nsingverse_database_wait_total %d\n", a.requests.Load(), a.active.Load(), float64(a.nanos.Load())/1e9, stats.OpenConnections, stats.InUse, stats.WaitCount)
		if a.tasks != nil {
			ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
			defer cancel()
			if metrics, err := a.tasks.Metrics(ctx); err == nil {
				for _, metric := range metrics {
					fmt.Fprintf(w, "singverse_background_tasks{type=%q,status=%q} %d\n", metric.Type, metric.Status, metric.Count)
				}
			}
		}
	})
	return mux
}

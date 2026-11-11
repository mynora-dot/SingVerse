package app

import (
	"context"
	"log/slog"
	"net/http"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/web"
)

func (a *Application) newRouter(services *applicationServices, log *slog.Logger) (*gin.Engine, error) {
	gin.SetMode(gin.ReleaseMode)
	router := gin.New()
	if err := router.SetTrustedProxies(nil); err != nil {
		return nil, err
	}
	router.Use(a.observability(log))
	router.GET("/api/health", a.health)

	// Gin snapshots middleware when each route is registered. Grants and Bearer
	// routes must be registered before the administrator Cookie/CSRF boundary.
	services.node.RegisterPublic(router)
	services.tags.RegisterInternal(router.Group("/api/v1"))
	services.processing.RegisterPublic(router, services.devices.RequireActor(true), services.devices.RequireActor(false))
	admin := services.accounts.RequireAdmin()
	router.Use(func(ctx *gin.Context) {
		if allowsNonAdministrator(ctx.Request) {
			ctx.Next()
			return
		}
		admin(ctx)
	})
	router.Use(func(ctx *gin.Context) {
		if value, ok := ctx.Get("admin"); ok && value == true {
			services.node.ObserveOrigin(services.accounts.CallbackOrigin(ctx))
		}
		ctx.Next()
	})

	services.accounts.Register(router)
	services.media.Register(router.Group("/api/v1"))
	services.activity.Register(router.Group("/api/v1"))
	services.missingSongs.Register(router.Group("/api/v1"))
	services.node.Register(router.Group("/api/v1"))
	services.devices.Register(router)
	services.models.Register(router)
	services.curation.Register(router)
	services.tags.Register(router.Group("/api/v1"))
	services.processing.Register(router.Group("/api/v1"))
	services.downloads.Register(router.Group("/api/v1"))
	router.GET("/api/v1/admin/overview", services.overview.Handler)
	router.GET("/api/v1/tv/library/songs/:id/cover", services.devices.RequireActor(false), services.tags.SongCoverHandler)
	services.tasks.Register(router.Group("/api/v1"))
	web.Register(router)
	return router, nil
}

func allowsNonAdministrator(request *http.Request) bool {
	path := request.URL.Path
	if strings.HasPrefix(path, "/api/v1/tv/") || strings.HasPrefix(path, "/api/v1/party/") {
		return true
	}
	if request.Method == http.MethodGet && path == "/api/v1/server-info" {
		return true
	}
	if request.Method != http.MethodPost {
		return false
	}
	return path == "/api/v1/device-authorizations" ||
		(strings.HasPrefix(path, "/api/v1/device-authorizations/") && strings.HasSuffix(path, "/poll"))
}

func (a *Application) health(ctx *gin.Context) {
	probe, cancel := context.WithTimeout(ctx.Request.Context(), 3*time.Second)
	defer cancel()
	status, code := "UP", http.StatusOK
	if a.db.PingContext(probe) != nil {
		status, code = "DOWN", http.StatusServiceUnavailable
	}
	ctx.Header("Cache-Control", "no-store")
	ctx.JSON(code, gin.H{"status": status})
}

func Server(address string, handler http.Handler) *http.Server {
	return &http.Server{
		Addr:              address,
		Handler:           handler,
		ReadHeaderTimeout: 5 * time.Second,
		IdleTimeout:       60 * time.Second,
		MaxHeaderBytes:    16 * 1024,
	}
}

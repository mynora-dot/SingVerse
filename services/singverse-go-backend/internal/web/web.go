// Package web serves the complete portable frontend embedded at build time.
package web

import (
	"embed"
	"github.com/gin-gonic/gin"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/httpx"
	"io/fs"
	"mime"
	"net/http"
	"path"
	"strings"
)

//go:embed all:dist
var assets embed.FS

func Register(router *gin.Engine) {
	files, _ := fs.Sub(assets, "dist")
	router.NoRoute(func(c *gin.Context) { Serve(c, files) })
}

func Serve(c *gin.Context, files fs.FS) {
	p := c.Request.URL.Path
	if p == "/api" || strings.HasPrefix(p, "/api/") {
		httpx.Error(c, http.StatusNotFound, "about:blank", "请求的接口不存在")
		return
	}
	if c.Request.Method != http.MethodGet && c.Request.Method != http.MethodHead {
		c.AbortWithStatus(404)
		return
	}
	if p == "/admin" || p == "/party" {
		c.Redirect(http.StatusMovedPermanently, p+"/")
		return
	}
	if p == "/" {
		p = "/admin/"
	}
	if p == "/admin/" || p == "/party/" {
		p += "index.html"
	}
	name := strings.TrimPrefix(p, "/")
	if !fs.ValidPath(name) || strings.HasPrefix(name, "api/") || name == "api" {
		c.AbortWithStatus(404)
		return
	}
	if !(strings.HasPrefix(name, "assets/") || strings.HasPrefix(name, "party/assets/") || name == "admin/index.html" || name == "party/index.html") {
		c.AbortWithStatus(404)
		return
	}
	data, err := fs.ReadFile(files, name)
	if err != nil {
		c.AbortWithStatus(404)
		return
	}
	contentType := mime.TypeByExtension(path.Ext(name))
	if contentType == "" {
		contentType = http.DetectContentType(data)
	}
	c.Header("X-Content-Type-Options", "nosniff")
	c.Header("Content-Length", fmtSize(len(data)))
	if strings.HasSuffix(name, ".html") {
		c.Header("Cache-Control", "no-cache")
	}
	c.Header("Content-Type", contentType)
	c.Status(200)
	if c.Request.Method == http.MethodGet {
		_, _ = c.Writer.Write(data)
	}
}

func fmtSize(n int) string {
	if n == 0 {
		return "0"
	}
	var b [20]byte
	i := len(b)
	for n > 0 {
		i--
		b[i] = byte(n%10) + '0'
		n /= 10
	}
	return string(b[i:])
}

package web

import (
	"encoding/json"
	"github.com/gin-gonic/gin"
	"io/fs"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"testing/fstest"
)

func TestHostingContract(t *testing.T) {
	gin.SetMode(gin.TestMode)
	files := fstest.MapFS{"admin/index.html": &fstest.MapFile{Data: []byte("admin")}, "party/index.html": &fstest.MapFile{Data: []byte("party")}, "assets/app.js": &fstest.MapFile{Data: []byte("js")}}
	r := gin.New()
	r.NoRoute(func(c *gin.Context) { Serve(c, fs.FS(files)) })
	for _, v := range []struct {
		method, path string
		status       int
		body         string
	}{{"GET", "/", 200, "admin"}, {"GET", "/admin/", 200, "admin"}, {"GET", "/party/index.html", 200, "party"}, {"GET", "/admin", 301, ""}, {"GET", "/assets/missing.js", 404, ""}, {"GET", "/assets/app.js", 200, "js"}, {"HEAD", "/assets/app.js", 200, ""}, {"POST", "/", 404, ""}, {"GET", "/assets/../admin/index.html", 404, ""}} {
		w := httptest.NewRecorder()
		r.ServeHTTP(w, httptest.NewRequest(v.method, v.path, nil))
		if v.status == http.StatusOK && (v.path == "/" || v.path == "/admin/" || v.path == "/party/index.html") {
			policy := w.Header().Get("Content-Security-Policy")
			for _, directive := range []string{"media-src 'self' blob:", "worker-src 'self' blob:", "frame-ancestors 'none'"} {
				if !strings.Contains(policy, directive) {
					t.Errorf("%s: security policy missing %s", v.path, directive)
				}
			}
			if w.Header().Get("Referrer-Policy") != "same-origin" {
				t.Errorf("%s: referrer policy missing", v.path)
			}
		}
		if w.Code != v.status {
			t.Errorf("%s %s: %d", v.method, v.path, w.Code)
		}
		if v.status != http.StatusMovedPermanently && w.Body.String() != v.body {
			t.Errorf("%s unexpected body", v.path)
		}
	}
}

func TestUnknownAPIUsesProblemDetails(t *testing.T) {
	gin.SetMode(gin.TestMode)
	router := gin.New()
	router.NoRoute(func(c *gin.Context) { Serve(c, fstest.MapFS{}) })
	for _, method := range []string{http.MethodGet, http.MethodPost} {
		w := httptest.NewRecorder()
		router.ServeHTTP(w, httptest.NewRequest(method, "/api/unknown", nil))
		if w.Code != http.StatusNotFound || w.Header().Get("Content-Type") != "application/problem+json" {
			t.Fatal(method, w.Code, w.Header())
		}
		var problem map[string]any
		if err := json.Unmarshal(w.Body.Bytes(), &problem); err != nil || problem["status"] != float64(404) || problem["type"] != "about:blank" || problem["title"] != "Not Found" || problem["instance"] != "/api/unknown" || problem["detail"] == "" {
			t.Fatal(method, problem, err)
		}
	}
}

// Package httpx implements the language-independent HTTP response contract.
package httpx

import (
	"encoding/json"
	"io"
	"net/http"

	"github.com/gin-gonic/gin"
)

type Problem struct {
	Type     string `json:"type"`
	Title    string `json:"title"`
	Status   int    `json:"status"`
	Detail   string `json:"detail,omitempty"`
	Instance string `json:"instance,omitempty"`
}

func Error(c *gin.Context, status int, kind, detail string) {
	if kind == "" {
		kind = "about:blank"
	}
	c.Header("Cache-Control", "no-store")
	c.Abort()
	c.Render(status, problemJSON{Problem{kind, http.StatusText(status), status, detail, c.Request.URL.Path}})
}

type problemJSON struct{ value Problem }

func (p problemJSON) Render(w http.ResponseWriter) error {
	p.WriteContentType(w)
	return json.NewEncoder(w).Encode(p.value)
}
func (p problemJSON) WriteContentType(w http.ResponseWriter) {
	w.Header().Set("Content-Type", "application/problem+json")
}

// Decode accepts exactly one JSON value and bounds input before allocating it.
// Unknown fields remain allowed for forward-compatible clients.
func Decode(c *gin.Context, value any, maxBytes int64) bool {
	c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, maxBytes)
	d := json.NewDecoder(c.Request.Body)
	d.UseNumber()
	if err := d.Decode(value); err != nil {
		Error(c, 400, "about:blank", "请求内容无效或超过限制")
		return false
	}
	if err := d.Decode(new(any)); err != io.EOF {
		Error(c, 400, "about:blank", "请求必须包含一个 JSON 对象")
		return false
	}
	return true
}

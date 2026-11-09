package httpx

import (
	"github.com/gin-gonic/gin"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestDecodeRejectsExtraAndOversizeJSON(t *testing.T) {
	gin.SetMode(gin.TestMode)
	r := gin.New()
	r.POST("/", func(c *gin.Context) {
		var value map[string]any
		if Decode(c, &value, 20) {
			c.Status(204)
		}
	})
	for _, v := range []struct {
		body   string
		status int
	}{{`{"a":1}`, 204}, {`{"a":1} {"a":2}`, 400}, {`{"a":"012345678901234567890"}`, 400}} {
		w := httptest.NewRecorder()
		r.ServeHTTP(w, httptest.NewRequest("POST", "/", strings.NewReader(v.body)))
		if w.Code != v.status {
			t.Fatal(v.body, w.Code)
		}
	}
}

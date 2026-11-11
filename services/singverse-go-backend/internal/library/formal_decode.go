package library

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"

	"github.com/gin-gonic/gin"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/metastandard"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/httpx"
)

func decodeFormal(c *gin.Context, out any) bool {
	data, e := io.ReadAll(http.MaxBytesReader(c.Writer, c.Request.Body, metastandard.MaxJSON))
	if e != nil {
		httpx.Error(c, 400, "about:blank", "资料请求超过限制")
		return false
	}
	if _, e = metastandard.Parse(data); e != nil {
		httpx.Error(c, 400, "about:blank", "资料必须为有效 UTF-8 JSON，且不能有重复键")
		return false
	}
	d := json.NewDecoder(bytes.NewReader(data))
	d.UseNumber()
	d.DisallowUnknownFields()
	if e = d.Decode(out); e != nil {
		httpx.Error(c, 400, "about:blank", "资料请求的字段或类型无效")
		return false
	}
	return true
}

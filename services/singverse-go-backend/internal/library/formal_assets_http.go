package library

import (
	"io"
	"mime"
	"net/http"
	"strings"

	"github.com/gin-gonic/gin"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/metastandard"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/metastore"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

func (s *Service) formalUploadAsset(c *gin.Context) {
	kind, _, e := mime.ParseMediaType(c.GetHeader("Content-Type"))
	if e != nil {
		s.fail(c, bad("附件 MIME 类型无效"))
		return
	}
	data, e := io.ReadAll(http.MaxBytesReader(c.Writer, c.Request.Body, metastandard.ImageLimit))
	if e != nil {
		s.fail(c, &problem{status: 413, detail: "附件超过 32 MiB"})
		return
	}
	var asset metastore.Asset
	e = s.transaction(c.Request.Context(), func(tx store.DBTX) error {
		var e error
		asset, e = s.formal.PutAsset(c.Request.Context(), tx, data, kind)
		if e != nil {
			return e
		}
		expiry := metastore.LeaseExpiry()
		return s.formal.References(c.Request.Context(), tx, "UPLOAD", asset.ID, []string{asset.ID}, &expiry)
	})
	if e != nil {
		s.formalFail(c, e)
		return
	}
	c.JSON(201, asset)
}
func (s *Service) formalReadAsset(c *gin.Context) {
	asset, data, e := s.formal.ReadAsset(c.Request.Context(), s.db, c.Param("assetId"))
	if e != nil {
		s.formalFail(c, e)
		return
	}
	c.Header("X-Content-Type-Options", "nosniff")
	c.Header("Cache-Control", "private, max-age=31536000, immutable")
	c.Header("ETag", "\""+asset.ID+"\"")
	for _, tag := range strings.Split(c.GetHeader("If-None-Match"), ",") {
		tag = strings.TrimSpace(strings.TrimPrefix(strings.TrimSpace(tag), "W/"))
		if tag == "*" || tag == "\""+asset.ID+"\"" {
			c.Status(http.StatusNotModified)
			return
		}
	}
	c.Data(200, asset.MIME, data)
}

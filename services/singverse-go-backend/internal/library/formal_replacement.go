package library

import (
	"github.com/gin-gonic/gin"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/metastandard"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

// Replacement is explicit: a changed timestamp alone cannot prove that the
// encoded content changed. Preserve the prior media and the song's decisions.
func (s *Service) formalReplaceMedia(c *gin.Context) {
	var in struct {
		ExpectedRevision string `json:"expectedRevision"`
		RequestID        string `json:"requestId"`
	}
	if !decodeFormal(c, &in) {
		return
	}
	if !metastandard.UUID.MatchString(in.RequestID) {
		s.fail(c, bad("提交标识无效"))
		return
	}
	ctx := c.Request.Context()
	id := c.Param("entityId")
	var out metastandard.Document
	e := s.transaction(ctx, func(tx store.DBTX) error {
		_, e := s.formal.Mutate(ctx, tx, id, in.ExpectedRevision, in.RequestID, in, func(old *metastandard.Document) error {
			if old.Type != "MEDIA" {
				return bad("只能替换媒体文件")
			}
			locations, e := s.formal.Locations(ctx, tx, id)
			if e != nil {
				return e
			}
			if len(locations) != 1 || locations[0].SourceID == nil {
				return bad("请先为媒体绑定唯一的当前位置")
			}
			loc := locations[0]
			if _, e = tx.ExecContext(ctx, s.q["song-retire-content"], hash("retired:"+id), id); e != nil {
				return e
			}
			if e = s.formal.Location(ctx, tx, id, "", loc.RelativePath, "", "REPLACED", map[string]any{}); e != nil {
				return e
			}
			if _, e = s.formal.Create(ctx, tx, "MEDIA", in.RequestID, old.ParentID); e != nil {
				return e
			}
			if _, e = tx.ExecContext(ctx, s.q["song-replace-content"], in.RequestID, hash(loc.RelativePath), now(), id); e != nil {
				return e
			}
			if e = s.SyncMetadata(ctx, tx, in.RequestID); e != nil {
				return e
			}
			return s.touchFormal(ctx, tx, in.RequestID)
		})
		if e != nil {
			return e
		}
		out, e = s.formal.Read(ctx, tx, in.RequestID)
		return e
	})
	if e != nil {
		s.formalFail(c, e)
		return
	}
	c.JSON(201, out)
}

package catalog

import (
	"database/sql"
	"errors"
	"github.com/gin-gonic/gin"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/httpx"
	"strconv"
)

func (s *Service) registerArtists(r *gin.Engine) {
	base := "/api/v1/artists"
	s.registerArtworkBatches(r, base)
	r.GET(base+"/artwork-settings", func(c *gin.Context) { v, e := s.artworkSettings(c.Request.Context()); s.reply(c, 200, v, e) })
	r.PUT(base+"/artwork-settings", func(c *gin.Context) {
		var in struct {
			FanartKey      *string `json:"fanartKey"`
			ClearFanartKey bool    `json:"clearFanartKey"`
		}
		if !httpx.Decode(c, &in, 10000) {
			return
		}
		v, e := s.saveArtworkSettings(c.Request.Context(), in.FanartKey, in.ClearFanartKey)
		s.reply(c, 200, v, e)
	})
	r.GET(base, func(c *gin.Context) {
		p, size, e := pageParams(c)
		if e != nil {
			s.report(c, e)
			return
		}
		var less *int
		if raw, ok := c.GetQuery("countLessThan"); ok {
			v, e := strconv.Atoi(raw)
			if e != nil {
				s.report(c, fail(400, "歌手数量筛选无效"))
				return
			}
			less = &v
		}
		v, e := s.artistList(c.Request.Context(), ArtistFilter{Q: c.Query("q"), Type: c.Query("type"), CountLessThan: less, Artwork: c.Query("artwork")}, p, size)
		s.reply(c, 200, v, e)
	})
	r.GET(base+"/:id", func(c *gin.Context) { v, e := s.artistGet(c.Request.Context(), c.Param("id")); s.reply(c, 200, v, e) })
	r.PUT(base+"/:id/category", func(c *gin.Context) {
		var in struct {
			Type     string `json:"type"`
			Revision int64  `json:"revision"`
		}
		if !httpx.Decode(c, &in, 10000) {
			return
		}
		v, e := s.category(c.Request.Context(), c.Param("id"), in.Type, in.Revision)
		s.reply(c, 200, v, e)
	})
	r.GET(base+"/:id/songs", func(c *gin.Context) {
		p, size, e := pageParams(c)
		if e != nil {
			s.report(c, e)
			return
		}
		v, e := s.artistSongs(c.Request.Context(), c.Param("id"), p, size)
		s.reply(c, 200, v, e)
	})
	r.POST(base+"/:id/images/retry", func(c *gin.Context) {
		e := s.retryArtwork(c.Request.Context(), c.Param("id"))
		s.reply(c, 200, map[string]bool{"success": true}, e)
	})
	r.GET(base+"/:id/images/:kind/candidates", func(c *gin.Context) {
		v, e := s.candidates(c.Request.Context(), s.db, c.Param("id"), c.Param("kind"))
		s.reply(c, 200, v, e)
	})
	r.POST(base+"/:id/images/:kind/selection", func(c *gin.Context) {
		var in struct {
			Provider   string `json:"provider"`
			ExternalID string `json:"externalId"`
		}
		if !httpx.Decode(c, &in, 10000) {
			return
		}
		v, e := s.selectArtwork(c.Request.Context(), c.Param("id"), c.Param("kind"), in.Provider, in.ExternalID)
		s.reply(c, 200, v, e)
	})
	r.GET(base+"/:id/images/:kind/candidates/:provider/:externalId/preview", func(c *gin.Context) {
		choice, e := s.candidate(c.Request.Context(), s.db, c.Param("id"), c.Param("kind"), c.Param("provider"), c.Param("externalId"))
		if e != nil {
			s.report(c, e)
			return
		}
		url := choice.Portrait
		if c.Param("kind") == "background" {
			url = choice.Background
		}
		if url == "" {
			s.report(c, fail(404, "候选没有此类图片"))
			return
		}
		b, e := downloadArtwork(c.Request.Context(), url)
		if e != nil {
			s.report(c, fail(502, "候选图片读取失败"))
			return
		}
		imageReply(c, b)
	})
	r.GET(base+"/song-overrides/:songId", func(c *gin.Context) {
		v, e := s.artistOverride(c.Request.Context(), c.Param("songId"), false, 0)
		s.reply(c, 200, v, e)
	})
	r.DELETE(base+"/song-overrides/:songId", func(c *gin.Context) {
		revision, e := strconv.ParseInt(c.Query("revision"), 10, 64)
		if e != nil {
			s.report(c, fail(400, "歌手订正修订号无效"))
			return
		}
		v, e := s.artistOverride(c.Request.Context(), c.Param("songId"), true, revision)
		s.reply(c, 200, v, e)
	})
	r.GET(base+"/:id/images/:kind", func(c *gin.Context) {
		kind, e := imageKind(c.Param("kind"))
		if e != nil {
			s.report(c, e)
			return
		}
		row, e := one(c.Request.Context(), s.db, s.q["a_image"], c.Param("id"), kind)
		if errors.Is(e, sql.ErrNoRows) {
			e = fail(404, "没有可用的歌手图片")
		}
		if e != nil {
			s.report(c, e)
			return
		}
		imageReply(c, row["image_bytes"].([]byte))
	})
	for _, remove := range []bool{false, true} {
		handler := func(c *gin.Context) {
			var b []byte
			var e error
			if _, present := c.GetQuery("version"); remove && !present {
				s.report(c, fail(400, "请提供图片版本"))
				return
			}
			if !remove {
				b, e = uploadedImage(c)
				if e != nil {
					s.report(c, e)
					return
				}
			}
			v, e := s.artistImage(c.Request.Context(), c.Param("id"), c.Param("kind"), c.Query("version"), b, remove)
			s.reply(c, 200, v, e)
		}
		if remove {
			r.DELETE(base+"/:id/images/:kind", handler)
		} else {
			r.PUT(base+"/:id/images/:kind", handler)
		}
	}
}
func imageReply(c *gin.Context, b []byte) {
	c.Header("Cache-Control", "no-store")
	c.Header("X-Content-Type-Options", "nosniff")
	c.Data(200, "image/jpeg", b)
}

package party

import (
	"bytes"
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"errors"
	"image"
	"image/color"
	"image/jpeg"
	_ "image/png"
	"io"
	"math"
	"net/http"
	"strconv"
	"strings"
	"unicode"

	"github.com/gin-gonic/gin"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/httpx"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/search"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

type Banner struct {
	ID        string `json:"id"`
	Label     string `json:"label"`
	Enabled   bool   `json:"enabled"`
	Version   string `json:"version"`
	ImagePath string `json:"imagePath"`
}
type Settings struct {
	Revision        int64    `json:"revision"`
	Headline        string   `json:"headline"`
	Subtitle        string   `json:"subtitle"`
	AutoRotate      bool     `json:"autoRotate"`
	RotationSeconds int      `json:"rotationSeconds"`
	Banners         []Banner `json:"banners"`
}

func (s *Service) settings(ctx context.Context, tx store.DBTX, tv bool) (Settings, error) {
	var v Settings
	err := tx.QueryRowContext(ctx, s.q["settings"]).Scan(&v.Revision, &v.Headline, &v.Subtitle, &v.AutoRotate, &v.RotationSeconds)
	if err != nil {
		return v, err
	}
	v.Banners = []Banner{}
	rows, err := tx.QueryContext(ctx, s.q["banners"])
	if err != nil {
		return v, err
	}
	defer rows.Close()
	for rows.Next() {
		var b Banner
		var order int
		if err = rows.Scan(&b.ID, &b.Label, &b.Enabled, &order, &b.Version); err != nil {
			return v, err
		}
		if tv && !b.Enabled {
			continue
		}
		prefix := "/api/v1/admin/tv-settings/banners/"
		if tv {
			prefix = "/api/v1/tv/home/banners/"
		}
		b.ImagePath = prefix + b.ID + "/image"
		v.Banners = append(v.Banners, b)
	}
	return v, rows.Err()
}
func (s *Service) checkSettings(ctx context.Context, tx store.DBTX, revision int64) (Settings, error) {
	if _, err := tx.ExecContext(ctx, s.q["settings_lock"]); err != nil {
		return Settings{}, err
	}
	prior, err := s.settings(ctx, tx, false)
	if err != nil {
		return prior, err
	}
	if prior.Revision != revision {
		return prior, fail(409, "电视设置已被修改，请重新加载后再试")
	}
	return prior, nil
}
func validLabel(v string, limit int, empty bool) bool {
	for _, r := range v {
		if unicode.IsControl(r) {
			return false
		}
	}
	return search.UTF16Length(v) <= limit && (empty || strings.TrimSpace(v) != "")
}
func (s *Service) saveSettings(ctx context.Context, tx store.DBTX, v Settings) (Settings, error) {
	if !validLabel(v.Headline, 80, false) || !validLabel(v.Subtitle, 160, true) || v.RotationSeconds < 5 || v.RotationSeconds > 60 || v.Banners == nil || len(v.Banners) > 8 {
		return Settings{}, fail(400, "标题、说明或轮播间隔无效（5至60秒，最多8张图片）")
	}
	ids := map[string]bool{}
	for _, b := range v.Banners {
		if b.ID == "" || ids[b.ID] || !validLabel(b.Label, 80, false) {
			return Settings{}, fail(400, "封面名称或列表无效")
		}
		ids[b.ID] = true
	}
	prior, err := s.checkSettings(ctx, tx, v.Revision)
	if err != nil {
		return prior, err
	}
	if len(prior.Banners) != len(v.Banners) {
		return prior, fail(409, "封面列表已变化，请重新加载")
	}
	for _, b := range prior.Banners {
		if !ids[b.ID] {
			return prior, fail(409, "封面列表已变化，请重新加载")
		}
	}
	r, err := tx.ExecContext(ctx, s.q["settings_save"], v.Headline, v.Subtitle, v.AutoRotate, v.RotationSeconds, v.Revision)
	if err != nil {
		return prior, err
	}
	changed, err := r.RowsAffected()
	if err != nil {
		return prior, err
	}
	if changed != 1 {
		return prior, fail(409, "电视设置已变化")
	}
	for i, b := range v.Banners {
		if _, err = tx.ExecContext(ctx, s.q["banner_update"], b.Label, b.Enabled, i, b.ID); err != nil {
			return prior, err
		}
	}
	return s.settings(ctx, tx, false)
}

// normalizeImage decodes only bounded JPEG/PNG pixels, strips metadata, flattens
// alpha, and resizes to the same 1280px delivery boundary used by the clients.
func normalizeImage(data []byte, contentType string) ([]byte, error) {
	if len(data) == 0 || len(data) > 2000000 || (contentType != "image/png" && contentType != "image/jpeg") {
		return nil, fail(400, "请选择2MB以内的JPEG或PNG图片")
	}
	cfg, format, err := image.DecodeConfig(bytes.NewReader(data))
	if err != nil || (format != "png" && format != "jpeg") || contentType != "image/"+format {
		return nil, fail(400, "图片类型与实际内容不一致")
	}
	if cfg.Width < 1 || cfg.Height < 1 || cfg.Width > 4096 || cfg.Height > 4096 {
		return nil, fail(400, "图片尺寸应在1至4096像素之间")
	}
	src, _, err := image.Decode(bytes.NewReader(data))
	if err != nil {
		return nil, fail(400, "图片内容无法识别")
	}
	scale := math.Min(1, 1280.0/float64(max(cfg.Width, cfg.Height)))
	w, h := max(1, int(float64(cfg.Width)*scale)), max(1, int(float64(cfg.Height)*scale))
	out := image.NewRGBA(image.Rect(0, 0, w, h))
	bounds := src.Bounds()
	for y := 0; y < h; y++ {
		sy := math.Max(0, (float64(y)+0.5)/scale-0.5)
		y0 := min(int(sy), cfg.Height-1)
		y1 := min(y0+1, cfg.Height-1)
		fy := sy - float64(y0)
		for x := 0; x < w; x++ {
			sx := math.Max(0, (float64(x)+0.5)/scale-0.5)
			x0 := min(int(sx), cfg.Width-1)
			x1 := min(x0+1, cfg.Width-1)
			fx := sx - float64(x0)
			corners := []color.Color{src.At(bounds.Min.X+x0, bounds.Min.Y+y0), src.At(bounds.Min.X+x1, bounds.Min.Y+y0), src.At(bounds.Min.X+x0, bounds.Min.Y+y1), src.At(bounds.Min.X+x1, bounds.Min.Y+y1)}
			weights := []float64{(1 - fx) * (1 - fy), fx * (1 - fy), (1 - fx) * fy, fx * fy}
			var rr, gg, bb float64
			for i, c := range corners {
				r, g, b, a := c.RGBA()
				alpha := float64(65535-a) / 65535
				rr += weights[i] * (float64(r)/257 + 25*alpha)
				gg += weights[i] * (float64(g)/257 + 27*alpha)
				bb += weights[i] * (float64(b)/257 + 32*alpha)
			}
			out.SetRGBA(x, y, color.RGBA{uint8(math.Min(255, math.Round(rr))), uint8(math.Min(255, math.Round(gg))), uint8(math.Min(255, math.Round(bb))), 255})
		}
	}
	var encoded bytes.Buffer
	if err = jpeg.Encode(&encoded, out, &jpeg.Options{Quality: 85}); err != nil {
		return nil, err
	}
	return encoded.Bytes(), nil
}

func (s *Service) registerHome(r *gin.Engine) {
	r.GET("/api/v1/admin/tv-settings", func(c *gin.Context) {
		s.run(c, func(tx store.DBTX) (any, error) { return s.settings(c.Request.Context(), tx, false) })
	})
	r.PUT("/api/v1/admin/tv-settings", func(c *gin.Context) {
		var in Settings
		if !httpx.Decode(c, &in, 16384) {
			return
		}
		s.run(c, func(tx store.DBTX) (any, error) { return s.saveSettings(c.Request.Context(), tx, in) })
	})
	r.POST("/api/v1/admin/tv-settings/banners", func(c *gin.Context) {
		revision, err := strconv.ParseInt(c.Query("revision"), 10, 64)
		if err != nil {
			s.report(c, fail(400, "设置修订号无效"))
			return
		}
		c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, 3000000)
		file, header, err := c.Request.FormFile("file")
		if err != nil {
			s.report(c, fail(400, "请选择2MB以内的JPEG或PNG图片"))
			return
		}
		defer file.Close()
		if c.Request.MultipartForm != nil {
			defer c.Request.MultipartForm.RemoveAll()
		}
		raw, err := io.ReadAll(io.LimitReader(file, 2000001))
		if err != nil {
			s.report(c, fail(400, "图片无法读取"))
			return
		}
		data, err := normalizeImage(raw, header.Header.Get("Content-Type"))
		if err != nil {
			s.report(c, err)
			return
		}
		sum := sha256.Sum256(data)
		s.run(c, func(tx store.DBTX) (any, error) {
			prior, err := s.checkSettings(c.Request.Context(), tx, revision)
			if err != nil {
				return nil, err
			}
			if len(prior.Banners) >= 8 {
				return nil, fail(400, "最多保存8张封面，请先移除不需要的图片")
			}
			label := "首页封面 " + strconv.Itoa(len(prior.Banners)+1)
			var order int
			if err = tx.QueryRowContext(c.Request.Context(), s.q["banner_next_order"]).Scan(&order); err != nil {
				return nil, err
			}
			if _, err = tx.ExecContext(c.Request.Context(), s.q["banner_insert"], id(), label, order, hex.EncodeToString(sum[:]), data); err != nil {
				return nil, err
			}
			if _, err = tx.ExecContext(c.Request.Context(), s.q["settings_bump"]); err != nil {
				return nil, err
			}
			return s.settings(c.Request.Context(), tx, false)
		})
	})
	r.DELETE("/api/v1/admin/tv-settings/banners/:id", func(c *gin.Context) {
		revision, err := strconv.ParseInt(c.Query("revision"), 10, 64)
		if err != nil {
			s.report(c, fail(400, "设置修订号无效"))
			return
		}
		s.run(c, func(tx store.DBTX) (any, error) {
			if _, err := s.checkSettings(c.Request.Context(), tx, revision); err != nil {
				return nil, err
			}
			result, err := tx.ExecContext(c.Request.Context(), s.q["banner_delete"], c.Param("id"))
			if err != nil {
				return nil, err
			}
			n, err := result.RowsAffected()
			if err != nil {
				return nil, err
			}
			if n != 1 {
				return nil, fail(404, "封面不存在或已移除")
			}
			if _, err = tx.ExecContext(c.Request.Context(), s.q["settings_bump"]); err != nil {
				return nil, err
			}
			return s.settings(c.Request.Context(), tx, false)
		})
	})
	imageHandler := func(tv bool) gin.HandlerFunc {
		return func(c *gin.Context) {
			var data []byte
			var enabled bool
			err := s.db.QueryRowContext(c.Request.Context(), s.q["banner_image"], c.Param("id")).Scan(&data, &enabled)
			if errors.Is(err, sql.ErrNoRows) {
				s.report(c, fail(404, "封面不存在或已停用"))
				return
			}
			if err != nil {
				s.report(c, err)
				return
			}
			if tv && !enabled {
				s.report(c, fail(404, "封面不存在或已停用"))
				return
			}
			c.Header("Cache-Control", "no-store")
			c.Header("X-Content-Type-Options", "nosniff")
			c.Data(200, "image/jpeg", data)
		}
	}
	r.GET("/api/v1/admin/tv-settings/banners/:id/image", imageHandler(false))
	r.GET("/api/v1/tv/home/banners/:id/image", s.RequireActor(true), imageHandler(true))
	r.GET("/api/v1/tv/home", s.RequireActor(true), func(c *gin.Context) {
		if err := rejectTVScope(c); err != nil {
			s.report(c, err)
			return
		}
		index, err := s.current(c.Request.Context())
		if err != nil {
			s.report(c, err)
			return
		}
		recent := []document{}
		for _, entry := range index.entries[:min(8, len(index.entries))] {
			recent = append(recent, clone(entry.song))
		}
		lists, err := s.playlists(c.Request.Context(), "")
		if err != nil {
			s.report(c, err)
			return
		}
		featured := []document{}
		for _, v := range lists[:min(4, len(lists))] {
			v = clone(v)
			delete(v, "songIds")
			delete(v, "songs")
			featured = append(featured, v)
		}
		s.runAsActor(c, func(ctx context.Context, tx store.DBTX, actor document) (any, error) {
			if str(actor, "kind") != "device" {
				return nil, fail(403, "需要电视设备授权")
			}
			server, err := s.serverInfo(c.Request.Context(), tx)
			if err != nil {
				return nil, err
			}
			appearance, err := s.settings(c.Request.Context(), tx, true)
			if err != nil {
				return nil, err
			}
			popular, _, err := s.popularSongs(ctx, tx, index, 0, 8)
			if err != nil {
				return nil, err
			}
			history, err := s.recentPlayedSongs(ctx, tx, actor, index)
			if err != nil {
				return nil, err
			}
			return document{"server": server, "totalSongs": len(index.entries), "recentSongs": recent, "popularSongs": popular, "recentlyPlayedSongs": history, "appearance": appearance, "featuredPlaylists": featured, "generatedAt": s.now().UTC().Format("2006-01-02T15:04:05.999999999Z")}, nil
		})
	})
	r.GET("/api/v1/tv/releases/current", s.RequireActor(true), func(c *gin.Context) {
		v, err := s.release()
		if err != nil {
			s.report(c, err)
			return
		}
		c.JSON(200, v)
	})
	r.GET("/api/v1/tv/releases/apk", s.RequireActor(true), s.apk)
}

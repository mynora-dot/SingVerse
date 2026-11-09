package catalog

import (
	"bytes"
	"github.com/gin-gonic/gin"
	"image"
	"image/color"
	"image/jpeg"
	_ "image/png"
	"io"
	"math"
	"mime"
	"net/http"
)

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

func uploadedImage(c *gin.Context) ([]byte, error) {
	contentType, _, _ := mime.ParseMediaType(c.GetHeader("Content-Type"))
	if contentType != "multipart/form-data" {
		return nil, fail(415, "请使用 multipart/form-data 上传图片")
	}
	c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, 3000000)
	f, h, e := c.Request.FormFile("file")
	if e != nil {
		return nil, fail(400, "请选择2MB以内的JPEG或PNG图片")
	}
	defer f.Close()
	if c.Request.MultipartForm != nil {
		defer c.Request.MultipartForm.RemoveAll()
	}
	b, e := io.ReadAll(io.LimitReader(f, 2000001))
	if e != nil {
		return nil, fail(400, "图片无法读取")
	}
	return normalizeImage(b, h.Header.Get("Content-Type"))
}

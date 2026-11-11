package metadata

import (
	"bytes"
	"image"
	"image/png"
	"path/filepath"

	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/metastandard"
	"golang.org/x/image/draw"
)

// A thumbnail is a disposable derivative of original bytes. It never replaces
// the persisted asset or participates in the portable asset's identity.
func (s *Service) thumbnail(original Cover) (Cover, error) {
	name := filepath.Join(s.cfg.MetadataCachePath, "thumbnails", metastandard.Digest(original.Bytes)+"-512.png")
	if cached, e := readImage(name); e == nil {
		return cached, nil
	}
	decoded, _, e := image.Decode(bytes.NewReader(original.Bytes))
	if e != nil {
		return Cover{}, bad("图片缩略图生成失败")
	}
	w, h := original.Width, original.Height
	if w > 512 || h > 512 {
		if w >= h {
			h = max(1, h*512/w)
			w = 512
		} else {
			w = max(1, w*512/h)
			h = 512
		}
	}
	result := image.NewNRGBA(image.Rect(0, 0, w, h))
	draw.CatmullRom.Scale(result, result.Bounds(), decoded, decoded.Bounds(), draw.Over, nil)
	var out bytes.Buffer
	if e = png.Encode(&out, result); e != nil {
		return Cover{}, e
	}
	if e = atomicWrite(name, out.Bytes()); e != nil {
		return Cover{}, e
	}
	return Cover{Bytes: out.Bytes(), MIME: "image/png", Width: w, Height: h}, nil
}

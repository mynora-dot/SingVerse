package metadata

import (
	"bytes"
	"image"
	"io"
	"net/url"
	"os"
	"regexp"
	"strings"

	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/metastandard"
)

// Keep platform originals byte-for-byte within the persistent asset limits.
// A display thumbnail is generated separately and never replaces the original.
const remoteCoverLimit = metastandard.ImageLimit

var kugouCoverSize = regexp.MustCompile(`(/(?:stdmusic|albumcover)/)([0-9]+|\{size\})(/)`)
var kuwoCoverSize = regexp.MustCompile(`(/star/albumcover/)[0-9]+(/)`)
var qqCoverSize = regexp.MustCompile(`T002(?:R[0-9]+x[0-9]+)?M000`)
var qishuiCoverSize = regexp.MustCompile(`-(?:image\.image|resize:[0-9]+:[0-9]+\.(?:png|jpg))$`)

func validCoverQuality(quality string) bool {
	return quality == "" || quality == "ORIGINAL" || quality == "HIGH"
}

// The host and path came from the stored provider candidate, never from the
// request. Each replacement is a known CDN image operation, with no redirect.
func coverVariant(provider, raw *string, quality string) *string {
	if provider == nil {
		return nil
	}
	checked := checkedCover(*provider, raw)
	if checked == nil {
		return nil
	}
	u, _ := url.Parse(*checked)
	high := quality == "HIGH"
	switch *provider {
	case "NETEASE":
		q := u.Query()
		q.Del("param")
		if high {
			q.Set("param", "2048y2048")
		}
		u.RawQuery = q.Encode()
	case "KUGOU":
		size := "0"
		if high {
			size = "2048"
		}
		u.Path = kugouCoverSize.ReplaceAllString(u.Path, "${1}"+size+"${3}")
	case "KUWO":
		size := "0"
		if high {
			size = "2048"
		}
		u.Path = kuwoCoverSize.ReplaceAllString(u.Path, "${1}"+size+"${2}")
	case "QQ":
		size := "T002M000"
		if high {
			size = "T002R1500x1500M000"
		}
		u.Path = qqCoverSize.ReplaceAllString(u.Path, size)
	case "QISHUI":
		size := "-image.image"
		if high {
			size = "-resize:2048:2048.png"
		}
		u.Path = qishuiCoverSize.ReplaceAllString(u.Path, size)
	}
	u.RawPath = ""
	return ptr(u.String())
}

func validateRemoteCover(b []byte) (Cover, error) {
	if len(b) > remoteCoverLimit {
		return Cover{}, &problem{422, "平台图片超过单次读取容量，请选择高清版本或其他封面"}
	}
	cfg, format, err := image.DecodeConfig(bytes.NewReader(b))
	if err != nil || !contains([]string{"jpeg", "png"}, format) || cfg.Width < 1 || cfg.Height < 1 {
		return Cover{}, &problem{422, "平台返回的内容不是可用的 JPEG 或 PNG 图片"}
	}
	if int64(cfg.Width)*int64(cfg.Height) > metastandard.MaxImagePixels {
		return Cover{}, &problem{422, "平台原图超过可保存的像素范围，请选择高清版本或其他封面"}
	}
	return Cover{Bytes: b, MIME: "image/" + format, Width: cfg.Width, Height: cfg.Height}, nil
}

func readRemoteCover(path string) (Cover, error) {
	st, err := os.Lstat(path)
	if err != nil || !st.Mode().IsRegular() || st.Size() > remoteCoverLimit {
		return Cover{}, missing("封面缓存不可用")
	}
	f, err := os.Open(path)
	if err != nil {
		return Cover{}, err
	}
	defer f.Close()
	b, err := io.ReadAll(io.LimitReader(f, remoteCoverLimit+1))
	if err != nil {
		return Cover{}, err
	}
	return validateRemoteCover(b)
}

// Retain this value in persisted metadata so a later read honours the selected
// quality. A frontend image element's CSS dimensions never affect the choice.
func canonicalCover(provider, raw *string) *string {
	if raw == nil || strings.TrimSpace(*raw) == "" {
		return nil
	}
	return coverVariant(provider, raw, "ORIGINAL")
}

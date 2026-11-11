package metastandard

import (
	"bytes"
	"errors"
	"image"
	_ "image/jpeg"
	_ "image/png"
	"unicode/utf8"

	_ "golang.org/x/image/webp"
)

const MaxImagePixels int64 = 67108864

// InspectAsset applies the same original-byte rules in storage and the
// standalone package validator. Text syntax is checked by its referencing field.
func InspectAsset(data []byte, mime string) (width, height int, err error) {
	if len(data) == 0 || len(data) > ImageLimit {
		return 0, 0, errors.New("资料附件大小必须为 1 字节至 32 MiB")
	}
	switch mime {
	case "image/jpeg", "image/png", "image/webp":
		cfg, format, e := image.DecodeConfig(bytes.NewReader(data))
		if e != nil || cfg.Width < 1 || cfg.Height < 1 || int64(cfg.Width)*int64(cfg.Height) > MaxImagePixels {
			return 0, 0, errors.New("图片尺寸或内容无效")
		}
		if mime != "image/"+format {
			return 0, 0, errors.New("图片内容与声明的 MIME 不一致")
		}
		return cfg.Width, cfg.Height, nil
	case "application/json", "text/plain", "application/x-subrip", "text/vtt":
		if !utf8.Valid(data) {
			return 0, 0, errors.New("资料文本必须采用 UTF-8 编码")
		}
		return 0, 0, nil
	default:
		return 0, 0, errors.New("不支持的资料附件类型")
	}
}

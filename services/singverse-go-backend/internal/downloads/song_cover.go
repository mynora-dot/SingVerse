package downloads

import (
	"context"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
)

// SongCover reads only a bounded sidecar beside a verified downloaded asset.
func (s *Service) SongCover(ctx context.Context, songID string) ([]byte, string, error) {
	row, e := s.row(ctx, s.db, "songAsset", map[string]any{"songId": songID})
	if e != nil {
		return nil, "", e
	}
	if row == nil {
		return nil, "", &problem{404, "下载封面不存在"}
	}
	source, e := s.row(ctx, s.db, "publication-source", map[string]any{"id": row["source_id"]})
	if e != nil {
		return nil, "", e
	}
	if str(source, "source_type") != "LOCAL" {
		return nil, "", &problem{404, "下载封面不存在"}
	}
	root, e := writable(str(source, "location"), false)
	if e != nil {
		return nil, "", e
	}
	relative := str(row, "relative_path")
	if !validRelative(relative) {
		return nil, "", bad("歌曲文件路径无效")
	}
	stem := strings.TrimSuffix(relative, filepath.Ext(relative))
	dir, e := os.OpenRoot(root)
	if e != nil {
		return nil, "", e
	}
	defer dir.Close()
	for _, extension := range []string{"jpg", "png", "webp"} {
		name := stem + "." + extension
		info, e := dir.Lstat(name)
		if e != nil || !info.Mode().IsRegular() || info.Size() > 2_000_000 {
			continue
		}
		file, e := dir.Open(name)
		if e != nil {
			continue
		}
		data, e := io.ReadAll(io.LimitReader(file, 2_000_001))
		file.Close()
		if e != nil || len(data) > 2_000_000 {
			continue
		}
		if _, e = validateCover(data); e != nil {
			continue
		}
		return data, http.DetectContentType(data), nil
	}
	return nil, "", &problem{404, "下载封面不存在"}
}

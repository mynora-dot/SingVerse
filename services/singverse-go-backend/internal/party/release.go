package party

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"io"
	"os"
	"path/filepath"
	"strings"

	"github.com/gin-gonic/gin"
)

func (s *Service) releasePath(name string) (string, error) {
	if s.cfg.TVReleaseDirectory == "" {
		return "", fail(404, "尚未发布TV安装包")
	}
	root, err := filepath.EvalSymlinks(s.cfg.TVReleaseDirectory)
	if err != nil {
		return "", fail(404, "TV发布文件不可用")
	}
	path := filepath.Join(root, name)
	info, err := os.Lstat(path)
	if err != nil || !info.Mode().IsRegular() {
		return "", fail(404, "TV发布文件不可用")
	}
	resolved, err := filepath.EvalSymlinks(path)
	if err != nil {
		return "", fail(404, "TV发布文件不可用")
	}
	relative, err := filepath.Rel(root, resolved)
	if err != nil || relative == ".." || strings.HasPrefix(relative, ".."+string(os.PathSeparator)) {
		return "", fail(404, "TV发布文件不可用")
	}
	return path, nil
}
func (s *Service) release() (document, error) {
	if s.cfg.TVReleaseDirectory == "" {
		return document{"available": false}, nil
	}
	invalid := fail(503, "TV发布目录内容不完整或无效")
	manifest, err := s.releasePath("release.json")
	if err != nil {
		return nil, invalid
	}
	f, err := os.Open(manifest)
	if err != nil {
		return nil, invalid
	}
	defer f.Close()
	raw, err := io.ReadAll(io.LimitReader(f, 8193))
	if err != nil || len(raw) > 8192 {
		return nil, invalid
	}
	var v struct {
		VersionCode int64  `json:"versionCode"`
		VersionName string `json:"versionName"`
	}
	if err = json.Unmarshal(raw, &v); err != nil || v.VersionCode < 1 || strings.TrimSpace(v.VersionName) == "" || len(v.VersionName) > 80 {
		return nil, invalid
	}
	path, err := s.releasePath("singverse-tv.apk")
	if err != nil {
		return nil, invalid
	}
	apk, err := os.Open(path)
	if err != nil {
		return nil, invalid
	}
	defer apk.Close()
	info, err := apk.Stat()
	if err != nil || info.Size() < 1 || info.Size() > 268435456 {
		return nil, invalid
	}
	digest := sha256.New()
	if _, err = io.Copy(digest, apk); err != nil {
		return nil, invalid
	}
	return document{"available": true, "versionCode": v.VersionCode, "versionName": v.VersionName, "size": info.Size(), "sha256": hex.EncodeToString(digest.Sum(nil)), "url": "/api/v1/tv/releases/apk"}, nil
}
func (s *Service) apk(c *gin.Context) {
	path, err := s.releasePath("singverse-tv.apk")
	if err != nil {
		s.report(c, err)
		return
	}
	c.Header("Cache-Control", "no-store")
	c.Header("Content-Type", "application/vnd.android.package-archive")
	c.File(path)
}

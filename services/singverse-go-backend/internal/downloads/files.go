package downloads

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"unicode"

	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/library"
)

func writable(location string, probe bool) (string, error) {
	fail := func() (string, error) {
		return "", conflict("下载目录不存在或不可写，请检查 NAS 挂载和目录权限")
	}
	if !filepath.IsAbs(location) || filepath.Clean(location) != location {
		return fail()
	}
	info, e := os.Lstat(location)
	if e != nil || !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
		return fail()
	}
	real, e := filepath.EvalSymlinks(location)
	if e != nil || real != location {
		return fail()
	}
	if probe {
		f, e := os.CreateTemp(location, ".singverse-write-check-*.tmp")
		if e != nil {
			return fail()
		}
		name := f.Name()
		e = f.Close()
		removeErr := os.Remove(name)
		if e != nil || removeErr != nil {
			return fail()
		}
	}
	return location, nil
}
func present(asset map[string]any) bool {
	root, e := writable(str(asset, "source_path"), false)
	if e != nil {
		return false
	}
	relative := str(asset, "relative_path")
	if !validRelative(relative) {
		return false
	}
	path := filepath.Join(root, relative)
	info, e := os.Lstat(path)
	if e != nil || !info.Mode().IsRegular() || info.Mode()&os.ModeSymlink != 0 {
		return false
	}
	real, e := filepath.EvalSymlinks(path)
	if e != nil || real != path {
		return false
	}
	return info.Size() == number(asset, "file_size") && info.ModTime().UTC().Format("2006-01-02T15:04:05.999999999Z07:00") == str(asset, "modified_at")
}
func validRelative(value string) bool {
	return value != "" && value != "." && value != ".." && !filepath.IsAbs(value) && !strings.ContainsAny(value, "/\\\x00") && filepath.Base(value) == value
}
func safeFile(root, relative string) (string, error) {
	if !validRelative(relative) {
		return "", bad("歌曲文件路径无效")
	}
	path := filepath.Join(root, relative)
	info, e := os.Lstat(path)
	if e == nil && info.Mode()&os.ModeSymlink != 0 {
		return "", bad("歌曲文件路径无效")
	}
	if e != nil && !os.IsNotExist(e) {
		return "", e
	}
	return path, nil
}

var itemIDPattern = regexp.MustCompile(`^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$`)

func temporary(root, id string) (string, error) {
	if !itemIDPattern.MatchString(id) {
		return "", bad("下载记录无效")
	}
	area := filepath.Join(root, ".singverse-downloads")
	if e := os.Mkdir(area, 0700); e != nil && !os.IsExist(e) {
		return "", conflict("下载临时目录不可写，请检查 NAS 挂载与目录权限")
	}
	info, e := os.Lstat(area)
	if e != nil || !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
		return "", conflict("下载临时目录不可写，请检查 NAS 挂载与目录权限")
	}
	real, e := filepath.EvalSymlinks(area)
	if e != nil || real != area {
		return "", conflict("下载临时目录不可写，请检查 NAS 挂载与目录权限")
	}
	return filepath.Join(area, id+".part"), nil
}
func safeName(v string) string {
	v = strings.Map(func(r rune) rune {
		if unicode.IsControl(r) || strings.ContainsRune(`\/:*?"<>|`, r) {
			return '_'
		}
		return r
	}, v)
	v = strings.TrimSpace(strings.TrimRight(v, ". "))
	for len(v) > 160 {
		r := []rune(v)
		v = string(r[:len(r)-1])
	}
	if v == "" {
		return "歌曲"
	}
	return v
}
func filename(root string, t Track, quality, extension string) (string, error) {
	base := safeName(strings.Join(t.Artists, "、") + " - " + t.Title)
	suffix := " [" + t.Platform + "-" + t.ID[:8] + "-" + quality + "]"
	for i := 0; i < 100000; i++ {
		name := base
		if i > 0 {
			name += suffix
		}
		if i > 1 {
			name += " (" + str(map[string]any{"n": i}, "n") + ")"
		}
		name += "." + extension
		_, e := os.Lstat(filepath.Join(root, name))
		if os.IsNotExist(e) {
			return name, nil
		}
		if e != nil {
			return "", e
		}
	}
	return "", conflict("同名文件过多，请清理下载目录")
}
func hashFile(ctx context.Context, path string) (string, error) {
	file, e := os.Open(path)
	if e != nil {
		return "", e
	}
	defer file.Close()
	digest := sha256.New()
	buffer := make([]byte, 65536)
	for {
		if e = ctx.Err(); e != nil {
			return "", e
		}
		n, readErr := file.Read(buffer)
		if n > 0 {
			_, _ = digest.Write(buffer[:n])
		}
		if readErr == io.EOF {
			break
		}
		if readErr != nil {
			return "", readErr
		}
	}
	return hex.EncodeToString(digest.Sum(nil)), nil
}

// Publication is atomic and never replaces an existing media file. Keeping the
// private hard link on an unlink error lets startup finish the same intent.
func publishFile(temporary, destination string) error {
	if e := os.Link(temporary, destination); e != nil {
		if e = renameNoReplace(temporary, destination); e != nil {
			return conflict("完整文件发布失败，请检查下载目录挂载与权限，已有文件已保留")
		}
	} else if e := os.Remove(temporary); e != nil {
		return e
	}
	return syncDirectory(filepath.Dir(destination))
}
func syncDirectory(path string) error {
	dir, e := os.Open(path)
	if e != nil {
		return e
	}
	defer dir.Close()
	return dir.Sync()
}
func seed(t Track, name string) library.ParseResult {
	extension := strings.TrimPrefix(strings.ToLower(filepath.Ext(name)), ".")
	title := t.Title
	return library.ParseResult{OriginalFilename: name, NormalizedFilename: name, Title: &title, Artists: t.Artists, Language: "UNKNOWN", VersionType: "UNKNOWN", Extension: &extension, QualityTags: []string{}, Confidence: 1, ParseStatus: "SUCCESS", ParserRule: "NONE", UnparsedTokens: []string{}, Diagnostics: []library.Diagnostic{}}
}
func withPath(asset map[string]any, root string) map[string]any {
	out := make(map[string]any, len(asset)+1)
	for k, v := range asset {
		out[k] = v
	}
	out["source_path"] = root
	return out
}

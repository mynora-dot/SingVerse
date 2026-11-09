package library

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
)

var byteRange = regexp.MustCompile(`^bytes=(?:[0-9]+-[0-9]*|-[0-9]+)$`)
var contentRange = regexp.MustCompile(`^bytes ([0-9]+)-([0-9]+)/([0-9]+)$`)
var unsatisfiableRange = regexp.MustCompile(`^bytes \*/[0-9]+$`)

func rangeBounds(value string, size int64) (int64, int64, bool) {
	if size == 0 {
		return 0, -1, false
	}
	parts := strings.Split(strings.TrimPrefix(value, "bytes="), "-")
	if len(parts) != 2 {
		return 0, 0, false
	}
	start, end := int64(0), size-1
	if parts[0] == "" {
		suffix, e := strconv.ParseInt(parts[1], 10, 64)
		if e != nil || suffix <= 0 {
			return 0, 0, false
		}
		if suffix < size {
			start = size - suffix
		}
	} else {
		var e error
		start, e = strconv.ParseInt(parts[0], 10, 64)
		if e != nil {
			return 0, 0, false
		}
		if parts[1] != "" {
			n, e := strconv.ParseInt(parts[1], 10, 64)
			if e != nil {
				return 0, 0, false
			}
			if n < end {
				end = n
			}
		}
	}
	return start, end, start >= 0 && start < size && end >= start
}
func validRelative(relative string) bool {
	if relative == "" || strings.HasPrefix(relative, "/") || strings.Contains(relative, `\`) || controls(relative) {
		return false
	}
	for _, part := range strings.Split(relative, "/") {
		if part == "" || part == "." || part == ".." {
			return false
		}
	}
	return true
}
func (s *Service) video(c *gin.Context) {
	if e := s.ServeOriginal(c.Request.Context(), c.Writer, c.Request, c.Param("id")); e != nil && !c.Writer.Written() {
		s.fail(c, e)
	}
}

// ServeOriginal reuses the caller's already checked authorization. Media remains bounded to its saved source.
func (s *Service) ServeOriginal(ctx context.Context, w http.ResponseWriter, r *http.Request, id string) error {
	return s.ServeOriginalGuard(ctx, w, r, id, nil)
}
func (s *Service) ServeOriginalGuard(ctx context.Context, w http.ResponseWriter, r *http.Request, id string, guard func() error) error {
	song, e := s.readSong(ctx, s.db, id)
	if e != nil {
		return e
	}
	src, e := s.findSource(ctx, s.db, song.SourceID)
	if e != nil {
		return e
	}
	if !validRelative(song.RelativePath) {
		return bad("歌曲路径无效，请重新扫描来源")
	}
	value := r.Header.Get("Range")
	if value != "" && !byteRange.MatchString(value) {
		return bad("仅支持单段字节范围")
	}
	if r.Method != "GET" && r.Method != "HEAD" {
		return &problem{status: 405, detail: "媒体仅支持 GET 或 HEAD"}
	}
	if guard != nil {
		if e = guard(); e != nil {
			return e
		}
	}
	kind := mediaTypes[strings.TrimPrefix(strings.ToLower(filepath.Ext(song.RelativePath)), ".")]
	if kind == "" {
		switch strings.ToLower(filepath.Ext(song.RelativePath)) {
		case ".m4v":
			kind = "video/mp4"
		case ".webm":
			kind = "video/webm"
		case ".m2ts":
			kind = "video/mp2t"
		default:
			kind = "application/octet-stream"
		}
	}
	headers := w.Header()
	headers.Set("Cache-Control", "private, no-store")
	headers.Set("X-Content-Type-Options", "nosniff")
	headers.Set("Content-Disposition", "inline")
	headers.Set("Content-Type", kind)
	if src.Type == "LOCAL" {
		return s.localOriginal(ctx, w, r, song, src, guard)
	}
	s.hookMu.RLock()
	cached := s.originalCache
	s.hookMu.RUnlock()
	if cached != nil {
		handled, e := cached(ctx, w, r, id, func(out http.ResponseWriter, req *http.Request) error {
			return s.remoteOriginal(ctx, out, req, song, src, guard)
		}, guard)
		if handled || e != nil {
			return e
		}
	}
	return s.remoteOriginal(ctx, w, r, song, src, guard)
}
func (s *Service) localOriginal(ctx context.Context, w http.ResponseWriter, r *http.Request, song Song, src Source, guard func() error) error {
	root, e := os.OpenRoot(src.Location)
	if e != nil {
		return missing("媒体文件不存在或无法读取")
	}
	defer root.Close()
	file, e := root.Open(filepath.FromSlash(song.RelativePath))
	if e != nil {
		return missing("媒体文件不存在或无法读取")
	}
	defer file.Close()
	info, e := file.Stat()
	if e != nil || !info.Mode().IsRegular() {
		return missing("媒体文件不可用，请重新扫描来源")
	}
	if guard != nil {
		if song.FileSize != nil && *song.FileSize != info.Size() {
			return conflict("源媒体已变化，请重新扫描来源")
		}
		if song.ModifiedAt != nil {
			stamp, e := time.Parse(time.RFC3339Nano, *song.ModifiedAt)
			if e != nil || !stamp.Equal(info.ModTime()) {
				return conflict("源媒体已变化，请重新扫描来源")
			}
		}
	}
	size := info.Size()
	start, end, status := int64(0), size-1, 200
	if v := r.Header.Get("Range"); v != "" {
		var ok bool
		start, end, ok = rangeBounds(v, size)
		if !ok {
			w.Header().Set("Content-Range", fmt.Sprintf("bytes */%d", size))
			w.Header().Set("Content-Length", "0")
			w.WriteHeader(416)
			return nil
		}
		status = 206
		w.Header().Set("Content-Range", fmt.Sprintf("bytes %d-%d/%d", start, end, size))
	}
	length := end - start + 1
	w.Header().Set("Accept-Ranges", "bytes")
	w.Header().Set("Content-Length", strconv.FormatInt(length, 10))
	if _, e = file.Seek(start, io.SeekStart); e != nil {
		return missing("媒体文件无法读取")
	}
	w.WriteHeader(status)
	if r.Method == "HEAD" {
		return nil
	}
	return copyMedia(ctx, w, file, length, guard, nil)
}
func (s *Service) remoteOriginal(ctx context.Context, w http.ResponseWriter, r *http.Request, song Song, src Source, guard func() error) error {
	target, e := url.Parse(src.Location)
	if e != nil {
		return bad("WebDAV 媒体地址无效")
	}
	target.Path += song.RelativePath
	target.RawPath = ""
	requestCtx, cancel := context.WithCancel(ctx)
	defer cancel()
	request, e := http.NewRequestWithContext(requestCtx, r.Method, target.String(), nil)
	if e != nil {
		return bad("WebDAV 媒体地址无效")
	}
	request.Header.Set("Accept-Encoding", "identity")
	rangeValue := r.Header.Get("Range")
	if rangeValue != "" {
		request.Header.Set("Range", rangeValue)
	}
	authorization, e := s.authorization(src)
	if e != nil {
		return e
	}
	if authorization != "" {
		request.Header.Set("Authorization", authorization)
	}
	response, e := s.client.Do(request)
	if e != nil {
		return &problem{status: 502, detail: "WebDAV 媒体读取失败或超时"}
	}
	defer response.Body.Close()
	fail := func(detail string) error { return &problem{status: 502, detail: detail} }
	if response.StatusCode == 416 {
		if value := response.Header.Get("Content-Range"); unsatisfiableRange.MatchString(value) {
			w.Header().Set("Content-Range", value)
		}
		w.Header().Set("Content-Length", "0")
		w.WriteHeader(416)
		return nil
	}
	if response.StatusCode != 200 && response.StatusCode != 206 {
		if response.StatusCode >= 300 && response.StatusCode < 400 {
			return fail("WebDAV 媒体请求发生重定向，请使用支持直接读取的 WebDAV 地址")
		}
		return fail("WebDAV 媒体读取失败，请检查地址、凭据和文件权限")
	}
	if encoding := response.Header.Get("Content-Encoding"); encoding != "" && !strings.EqualFold(encoding, "identity") {
		return fail("WebDAV 返回压缩的媒体响应，无法安全读取字节范围")
	}
	length := response.ContentLength
	if response.StatusCode == 206 {
		if rangeValue == "" {
			return fail("WebDAV 返回未请求的媒体范围")
		}
		parts := contentRange.FindStringSubmatch(response.Header.Get("Content-Range"))
		if len(parts) != 4 {
			return fail("WebDAV 返回无效的媒体范围")
		}
		start, e1 := strconv.ParseInt(parts[1], 10, 64)
		end, e2 := strconv.ParseInt(parts[2], 10, 64)
		size, e3 := strconv.ParseInt(parts[3], 10, 64)
		expectedStart, expectedEnd, ok := rangeBounds(rangeValue, size)
		if e1 != nil || e2 != nil || e3 != nil || !ok || start != expectedStart || end != expectedEnd {
			return fail("WebDAV 返回无效的媒体范围")
		}
		expectedLength := end - start + 1
		if length >= 0 && length != expectedLength {
			return fail("WebDAV 返回不一致的媒体长度")
		}
		length = expectedLength
		w.Header().Set("Content-Range", response.Header.Get("Content-Range"))
	} else if r.Method != "HEAD" && rangeValue != "" && !regexp.MustCompile(`^bytes=0+-[0-9]*$`).MatchString(rangeValue) {
		return fail("WebDAV 不支持媒体字节范围，无法定位播放，请检查来源设置")
	}
	if response.StatusCode == 206 || strings.EqualFold(response.Header.Get("Accept-Ranges"), "bytes") {
		w.Header().Set("Accept-Ranges", "bytes")
	}
	if length >= 0 {
		w.Header().Set("Content-Length", strconv.FormatInt(length, 10))
	}
	w.WriteHeader(response.StatusCode)
	if r.Method == "HEAD" {
		return nil
	}
	return copyMedia(ctx, w, response.Body, length, guard, cancel)
}
func copyMedia(ctx context.Context, w io.Writer, r io.Reader, remaining int64, guard func() error, cancel context.CancelFunc) error {
	buffer := make([]byte, 64*1024)
	next := time.Time{}
	for remaining != 0 {
		if e := ctx.Err(); e != nil {
			return e
		}
		if guard != nil && time.Now().After(next) {
			if e := guard(); e != nil {
				return e
			}
			next = time.Now().Add(250 * time.Millisecond)
		}
		var alarm *time.Timer
		if cancel != nil {
			alarm = time.AfterFunc(30*time.Second, cancel)
		}
		limit := len(buffer)
		if remaining > 0 && remaining < int64(limit) {
			limit = int(remaining)
		}
		count, e := r.Read(buffer[:limit])
		if alarm != nil {
			alarm.Stop()
		}
		if count > 0 {
			n, writeErr := w.Write(buffer[:count])
			if writeErr != nil {
				return writeErr
			}
			if n != count {
				return io.ErrShortWrite
			}
			if remaining > 0 {
				remaining -= int64(count)
			}
		}
		if e != nil {
			if e == io.EOF {
				if remaining > 0 {
					return io.ErrUnexpectedEOF
				}
				return nil
			}
			return e
		}
	}
	return nil
}

// LocalMediaPath resolves a current, regular, non-symlink local original for compute authorization.
func (s *Service) LocalMediaPath(ctx context.Context, sourceID, relative string) (string, error) {
	src, e := s.findSource(ctx, s.db, sourceID)
	if e != nil {
		return "", e
	}
	if src.Type != "LOCAL" {
		return "", bad("媒体来源不是本地目录")
	}
	if relative == "" || strings.HasPrefix(relative, "/") || strings.Contains(relative, "\\") || controls(relative) {
		return "", bad("媒体路径无效")
	}
	for _, part := range strings.Split(relative, "/") {
		if part == "" || part == "." || part == ".." {
			return "", bad("媒体路径无效")
		}
	}
	target := filepath.Join(src.Location, filepath.FromSlash(relative))
	real, e := filepath.EvalSymlinks(target)
	if e != nil || real != target || !within(src.Location, real) {
		return "", bad("媒体路径已变化或不可访问")
	}
	st, e := os.Lstat(real)
	if e != nil || !st.Mode().IsRegular() {
		return "", bad("媒体文件不可读")
	}
	return real, nil
}

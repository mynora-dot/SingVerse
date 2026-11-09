package execution

import (
	"context"
	"crypto/subtle"
	"fmt"
	"github.com/gin-gonic/gin"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"
)

type localInputGrant struct {
	mu                  sync.Mutex
	file                *os.File
	path, token, origin string
	info                os.FileInfo
	expires             time.Time
	requests            int
	bytes               int64
}

var closedRange = regexp.MustCompile(`^bytes=([0-9]+)-([0-9]+)$`)

// LocalTags reads tags through a bounded callback grant; the compute process
// receives no host filesystem path and the scan may precede the song row.
func (s *Service) LocalTags(ctx context.Context, path string) (map[string]any, error) {
	s.mu.Lock()
	if err := s.ctx.Err(); err != nil {
		s.mu.Unlock()
		return nil, err
	}
	s.wg.Add(1)
	s.mu.Unlock()
	defer s.wg.Done()
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	stop := context.AfterFunc(s.ctx, cancel)
	defer stop()
	c, err := s.connection(ctx, "")
	if err != nil {
		return nil, err
	}
	path, err = filepath.Abs(path)
	if err != nil {
		return nil, err
	}
	resolved, err := filepath.EvalSymlinks(path)
	if err != nil {
		return nil, err
	}
	f, err := os.Open(resolved)
	if err != nil {
		return nil, err
	}
	info, err := f.Stat()
	if err != nil || !info.Mode().IsRegular() {
		f.Close()
		return nil, fail(400, "媒体不是常规文件")
	}
	callback, err := s.compute.CallbackOrigin(c)
	if err != nil {
		f.Close()
		return nil, err
	}
	id := uuid()
	grant := &localInputGrant{file: f, path: resolved, token: c.Token, origin: c.Origin, info: info, expires: time.Now().Add(60 * time.Second)}
	s.mu.Lock()
	for key, v := range s.localInputs {
		if time.Now().After(v.expires) {
			delete(s.localInputs, key)
			v.file.Close()
		}
	}
	if len(s.localInputs) >= 64 {
		s.mu.Unlock()
		f.Close()
		return nil, fail(429, "本地媒体探测过多")
	}
	s.localInputs[id] = grant
	s.mu.Unlock()
	defer func() {
		s.mu.Lock()
		delete(s.localInputs, id)
		s.mu.Unlock()
		grant.mu.Lock()
		f.Close()
		grant.mu.Unlock()
	}()
	result, err := s.compute.Call(ctx, c, "/internal/v1/media/remote-tags", map[string]any{"reference": id, "callbackOrigin": callback, "filename": filepath.Base(resolved), "size": info.Size()}, 70*time.Second, 4<<20)
	if err != nil {
		return nil, err
	}
	tags := obj(result["tags"])
	if len([]rune(str(tags, "title"))) > 500 || len([]rune(str(tags, "album"))) > 500 || len([]rune(str(tags, "releaseDate"))) > 32 || len(array(tags["artists"])) > 32 {
		return nil, fail(502, "媒体标签超过限制")
	}
	for _, item := range array(tags["artists"]) {
		value, ok := item.(string)
		if !ok || strings.TrimSpace(value) == "" || len([]rune(value)) > 200 {
			return nil, fail(502, "媒体歌手标签无效")
		}
	}
	lyrics := obj(result["lyrics"])
	if len(encoded(lyrics)) <= 262144 && (strings.TrimSpace(str(lyrics, "text")) != "" || len(array(lyrics["lines"])) > 0) {
		tags["lyrics"] = lyrics
	}
	return tags, nil
}
func (s *Service) HasLocalInput(id string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	_, ok := s.localInputs[id]
	return ok
}
func (s *Service) ServeLocalInput(c *gin.Context) {
	if err := s.ServeLocalInputWith(c.Request.Context(), c.Writer, c.Request, c.Param("id")); err != nil && !c.Writer.Written() {
		s.report(c, err)
	}
}
func (s *Service) ServeLocalInputWith(ctx context.Context, w http.ResponseWriter, r *http.Request, id string) error {
	return s.serveLocalInput(ctx, w, r, id)
}
func (s *Service) serveLocalInput(ctx context.Context, w http.ResponseWriter, r *http.Request, id string) error {
	s.mu.Lock()
	grant := s.localInputs[id]
	s.mu.Unlock()
	if grant == nil || subtle.ConstantTimeCompare([]byte(r.Header.Get("Authorization")), []byte("Bearer "+grant.token)) != 1 {
		return fail(401, "媒体读取授权无效")
	}
	grant.mu.Lock()
	defer grant.mu.Unlock()
	if time.Now().After(grant.expires) {
		return fail(410, "媒体读取授权已结束")
	}
	current, err := s.connection(ctx, grant.origin)
	if err != nil || subtle.ConstantTimeCompare([]byte(current.Token), []byte(grant.token)) != 1 {
		return fail(410, "媒体读取授权已结束")
	}
	info, err := os.Lstat(grant.path)
	if err != nil || !info.Mode().IsRegular() || !os.SameFile(info, grant.info) || info.Size() != grant.info.Size() || !info.ModTime().Equal(grant.info.ModTime()) {
		return fail(409, "源媒体已变化")
	}
	match := closedRange.FindStringSubmatch(r.Header.Get("Range"))
	if len(match) != 3 {
		return fail(400, "必须指定有界单段字节范围")
	}
	start, e1 := strconv.ParseInt(match[1], 10, 64)
	end, e2 := strconv.ParseInt(match[2], 10, 64)
	if e1 != nil || e2 != nil || start < 0 || end < start || end-start >= 1<<20 {
		return fail(400, "字节范围超限")
	}
	grant.requests++
	if grant.requests > 16 || grant.bytes+end-start+1 > 16<<20 {
		return fail(413, "媒体读取预算已用尽")
	}
	if start >= info.Size() {
		w.Header().Set("Content-Range", fmt.Sprintf("bytes */%d", info.Size()))
		w.WriteHeader(416)
		return nil
	}
	end = min(end, info.Size()-1)
	length := end - start + 1
	data := make([]byte, length)
	if n, e := grant.file.ReadAt(data, start); n != len(data) || (e != nil && e != io.EOF) {
		return fail(409, "源媒体已变化")
	}
	if latest, e := grant.file.Stat(); e != nil || latest.Size() != grant.info.Size() || !latest.ModTime().Equal(grant.info.ModTime()) {
		return fail(409, "源媒体已变化")
	}
	w.Header().Set("Content-Type", "application/octet-stream")
	originalHeaders(w, info.Size(), start, end, 206)
	w.WriteHeader(206)
	if r.Method != "HEAD" {
		grant.bytes += length
		_, err = w.Write(data)
	}
	return err
}

package metadata

import (
	"context"
	"crypto/subtle"
	"encoding/base64"
	"fmt"
	"io"
	"math/rand/v2"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"
)

type grant struct {
	mu                           sync.Mutex
	task                         map[string]any
	token                        string
	ctx                          context.Context
	cancel                       context.CancelFunc
	deadline                     time.Time
	limit                        int64
	maxRequests                  int
	bytes                        int64
	requests                     int
	size                         *int64
	etag, etagResource, modified string
	started                      time.Time
}

func (s *Service) AuthorizeInput(ctx context.Context, task map[string]any, token string, deep bool) (string, func(), error) {
	id := text(task["id"])
	if !uuidPattern.MatchString(id) || token == "" {
		return "", nil, bad("媒体读取授权无效")
	}
	duration, limit, requests := time.Minute, int64(16*1024*1024), 16
	if deep {
		duration, limit, requests = 180*time.Second, 128*1024*1024, 64
	}
	grantCtx, cancel := context.WithTimeout(ctx, duration)
	g := &grant{task: map[string]any{}, token: token, ctx: grantCtx, cancel: cancel, deadline: time.Now().Add(duration), limit: limit, maxRequests: requests, started: time.Now()}
	for k, v := range task {
		g.task[k] = v
	}
	if task["file_size"] != nil {
		g.size = ptr(number(task["file_size"]))
	}
	s.grantMu.Lock()
	if old := s.grants[id]; old != nil {
		old.cancel()
	}
	s.grants[id] = g
	s.grantMu.Unlock()
	if e := s.validateGrant(grantCtx, id, g); e != nil {
		s.grantMu.Lock()
		delete(s.grants, id)
		s.grantMu.Unlock()
		cancel()
		return "", nil, e
	}
	var once sync.Once
	revoke := func() {
		once.Do(func() {
			cancel()
			s.grantMu.Lock()
			if s.grants[id] == g {
				delete(s.grants, id)
			}
			s.grantMu.Unlock()
			g.mu.Lock()
			b, r := g.bytes, g.requests
			g.mu.Unlock()
			finalCtx, end := context.WithTimeout(context.Background(), 5*time.Second)
			defer end()
			_, _ = s.exec(finalCtx, s.db, "dav-metrics", map[string]any{"songId": task["song_id"], "binding": task["binding"], "generation": task["generation"], "bytes": b, "requests": r, "elapsed": time.Since(g.started).Milliseconds()})
		})
	}
	return id, revoke, nil
}
func (s *Service) validateGrant(ctx context.Context, id string, g *grant) error {
	s.grantMu.Lock()
	same := s.grants[id] == g
	s.grantMu.Unlock()
	if !same || g.ctx.Err() != nil {
		return &problem{410, "媒体读取授权已结束"}
	}
	if !time.Now().Before(g.deadline) {
		return &problem{413, "媒体提取时间预算已用尽"}
	}
	r, e := s.row(ctx, s.db, "dav-resource", map[string]any{"songId": g.task["song_id"]})
	if e != nil {
		return &problem{410, "媒体读取授权已结束"}
	}
	source, e := s.row(ctx, s.db, "source", map[string]any{"id": g.task["source_id"]})
	if e != nil {
		return &problem{410, "媒体读取授权已结束"}
	}
	if number(r["generation"]) != number(g.task["generation"]) || text(r["binding"]) != text(g.task["binding"]) || r["archived_at"] != nil || (!boolean(r["enabled"]) && !boolean(g.task["manual"])) || boolean(r["paused"]) || text(r["access_group"]) != text(g.task["access_group"]) || text(r["private_origins"]) != text(g.task["private_origins"]) || hash(text(source["location"])) != text(g.task["binding"]) {
		return &problem{410, "媒体读取授权已结束"}
	}
	return nil
}

var boundedRange = regexp.MustCompile(`^bytes=([0-9]+)-([0-9]+)$`)
var contentRange = regexp.MustCompile(`^bytes ([0-9]+)-([0-9]+)/([0-9]+)$`)

// ServeInput publishes only an authenticated, budgeted, validated bounded block.
func (s *Service) ServeInput(ctx context.Context, w http.ResponseWriter, r *http.Request, id string) error {
	if r.Method != "GET" && r.Method != "HEAD" {
		return &problem{405, "仅支持 GET 或 HEAD"}
	}
	s.grantMu.Lock()
	g := s.grants[id]
	s.grantMu.Unlock()
	if g == nil || subtle.ConstantTimeCompare([]byte(r.Header.Get("Authorization")), []byte("Bearer "+g.token)) != 1 {
		return &problem{401, "媒体读取授权无效"}
	}
	raw := r.Header.Get("Range")
	if len(raw) > 100 {
		return bad("字节范围超限")
	}
	match := boundedRange.FindStringSubmatch(raw)
	if len(match) != 3 {
		return bad("仅支持有界单段字节范围")
	}
	start, e := strconv.ParseInt(match[1], 10, 64)
	if e != nil {
		return bad("字节范围超限")
	}
	end, e := strconv.ParseInt(match[2], 10, 64)
	if e != nil || end < start || end-start >= 1024*1024 {
		return bad("字节范围超限")
	}
	g.mu.Lock()
	defer g.mu.Unlock()
	if e = s.validateGrant(ctx, id, g); e != nil {
		return e
	}
	return s.Access(ctx, text(g.task["source_id"]), func() error { return s.transferBlock(ctx, w, r, id, g, start, end) })
}
func (s *Service) transferBlock(ctx context.Context, w http.ResponseWriter, in *http.Request, id string, g *grant, start, end int64) error {
	source, e := s.row(ctx, s.db, "source", map[string]any{"id": g.task["source_id"]})
	if e != nil {
		return &problem{410, "媒体来源已失效"}
	}
	relative := text(g.task["relative_path"])
	if relative == "" || strings.HasPrefix(relative, "/") || strings.Contains(relative, "\\") {
		return bad("媒体路径无效")
	}
	for _, p := range strings.Split(relative, "/") {
		if p == "" || p == "." || p == ".." {
			return bad("媒体路径无效")
		}
	}
	root, e := url.Parse(text(source["location"]))
	if e != nil {
		return bad("媒体路径无效")
	}
	target := *root
	target.Path = root.Path + relative
	target.RawPath = ""
	auth := ""
	username := text(source["username"])
	if username != "" {
		password := ""
		if source["password_encrypted"] != nil {
			password, e = s.cipher.Decrypt(text(source["password_encrypted"]), "singverse:webdav:v1")
			if e != nil {
				return &problem{503, "媒体凭据不可用"}
			}
		}
		auth = "Basic " + basicAuth(username, password)
	}
	allowed := stringsFromJSON(g.task["private_origins"])
	requestCtx, cancel := context.WithDeadline(ctx, g.deadline)
	defer cancel()
	stop := context.AfterFunc(g.ctx, cancel)
	defer stop()
	for redirects := 0; redirects <= 3; redirects++ {
		if e = s.validateGrant(requestCtx, id, g); e != nil {
			return e
		}
		if redirects > 0 {
			if e = s.waitAccess(requestCtx, text(g.task["access_group"]), s.requestInterval); e != nil {
				return e
			}
		}
		if g.requests >= g.maxRequests || end-start+1 > g.limit-g.bytes {
			return &problem{413, "单文件提取读取预算已用尽"}
		}
		g.requests++
		private := origin(&target) == origin(root) || contains(allowed, origin(&target))
		client, e := safeClient(requestCtx, &target, private)
		if e != nil {
			return e
		}
		req, _ := http.NewRequestWithContext(requestCtx, in.Method, target.String(), nil)
		req.Header.Set("Range", fmt.Sprintf("bytes=%d-%d", start, end))
		req.Header.Set("Accept-Encoding", "identity")
		if auth != "" {
			req.Header.Set("Authorization", auth)
		}
		resource := origin(&target) + target.EscapedPath()
		if g.etag != "" && g.etagResource == resource {
			req.Header.Set("If-Match", g.etag)
		}
		resp, e := client.Do(req)
		if e != nil {
			client.CloseIdleConnections()
			return &problem{502, "远端媒体读取失败"}
		}
		status := resp.StatusCode
		if containsInt([]int{301, 302, 303, 307, 308}, status) {
			location := resp.Header.Get("Location")
			resp.Body.Close()
			client.CloseIdleConnections()
			if redirects == 3 || location == "" || len(location) > 4096 {
				return &problem{502, "WebDAV 下载跳转过多或无效"}
			}
			next, e := target.Parse(location)
			if e != nil || next.Hostname() == "" || next.User != nil || next.Fragment != "" || (next.Scheme != "http" && next.Scheme != "https") || target.Scheme == "https" && next.Scheme != "https" {
				return &problem{502, "WebDAV 下载跳转被拒绝"}
			}
			if origin(&target) != origin(next) {
				auth = ""
			}
			target = *next
			continue
		}
		if status == 401 || status == 403 {
			resp.Body.Close()
			client.CloseIdleConnections()
			_ = s.block(requestCtx, text(g.task["source_id"]), true, time.Now())
			return &problem{403, "WebDAV 认证或权限异常，已暂停提取"}
		}
		if status == 429 {
			until := retryAfter(resp.Header.Get("Retry-After"))
			resp.Body.Close()
			client.CloseIdleConnections()
			_ = s.block(requestCtx, text(g.task["source_id"]), false, until)
			return &problem{429, "WebDAV 已进入访问冷却"}
		}
		if containsInt([]int{404, 409, 410, 412, 416}, status) {
			resp.Body.Close()
			client.CloseIdleConnections()
			return conflict("远端媒体在读取期间发生变化")
		}
		if status == 200 {
			resp.Body.Close()
			client.CloseIdleConnections()
			return &problem{422, "WebDAV 不支持受限范围读取"}
		}
		if status != 206 {
			resp.Body.Close()
			client.CloseIdleConnections()
			return &problem{502, "WebDAV 范围读取失败"}
		}
		if ce := resp.Header.Get("Content-Encoding"); ce != "" && !strings.EqualFold(ce, "identity") {
			resp.Body.Close()
			client.CloseIdleConnections()
			return conflict("WebDAV 返回了编码后的范围")
		}
		m := contentRange.FindStringSubmatch(resp.Header.Get("Content-Range"))
		if len(m) != 4 {
			resp.Body.Close()
			client.CloseIdleConnections()
			return conflict("WebDAV 返回无效范围")
		}
		actualStart, a := strconv.ParseInt(m[1], 10, 64)
		actualEnd, b := strconv.ParseInt(m[2], 10, 64)
		size, c := strconv.ParseInt(m[3], 10, 64)
		length := actualEnd - actualStart + 1
		if a != nil || b != nil || c != nil || size <= 0 || actualStart != start || actualEnd != min(end, size-1) || actualEnd < actualStart || g.size != nil && *g.size != size || resp.ContentLength >= 0 && resp.ContentLength != length {
			resp.Body.Close()
			client.CloseIdleConnections()
			return conflict("远端媒体长度发生变化")
		}
		g.size = &size
		etag, modified := resp.Header.Get("ETag"), resp.Header.Get("Last-Modified")
		if g.etag != "" && (g.etagResource != resource || g.etag != etag) || g.modified != "" && modified != "" && g.modified != modified {
			resp.Body.Close()
			client.CloseIdleConnections()
			return conflict("远端媒体版本发生变化")
		}
		if g.etag == "" && len(etag) <= 512 && strings.HasPrefix(etag, "\"") && strings.HasSuffix(etag, "\"") {
			g.etag, g.etagResource = etag, resource
		}
		if g.modified == "" {
			g.modified = modified
		}
		data := []byte{}
		if in.Method != "HEAD" {
			buf := make([]byte, 64*1024)
			left := length
			for left > 0 {
				if e = s.validateGrant(requestCtx, id, g); e != nil {
					break
				}
				n, readErr := resp.Body.Read(buf[:min(int64(len(buf)), left)])
				if n > 0 {
					data = append(data, buf[:n]...)
					left -= int64(n)
					g.bytes += int64(n)
					if _, dbErr := s.exec(requestCtx, s.db, "dav-bytes", map[string]any{"group": g.task["access_group"], "bytes": n}); dbErr != nil {
						e = dbErr
						break
					}
				}
				if readErr != nil {
					if readErr != io.EOF || left > 0 {
						e = &problem{502, "远端媒体读取不完整"}
					}
					break
				}
				if n == 0 {
					e = &problem{502, "远端媒体读取失败"}
					break
				}
			}
			if e == nil {
				var probe [1]byte
				n, readErr := resp.Body.Read(probe[:])
				if n > 0 || readErr != io.EOF {
					e = conflict("WebDAV 返回无效长度")
				}
			}
		}
		resp.Body.Close()
		client.CloseIdleConnections()
		if e != nil {
			return e
		}
		if e = s.validateGrant(requestCtx, id, g); e != nil {
			return e
		}
		w.Header().Set("Content-Range", fmt.Sprintf("bytes %d-%d/%d", start, actualEnd, size))
		w.Header().Set("Accept-Ranges", "bytes")
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("Content-Type", "application/octet-stream")
		w.Header().Set("Content-Length", strconv.FormatInt(length, 10))
		w.WriteHeader(206)
		if in.Method != "HEAD" {
			_, e = w.Write(data)
		}
		return e
	}
	return &problem{502, "WebDAV 下载跳转过多"}
}
func containsInt(v []int, n int) bool {
	for _, x := range v {
		if x == n {
			return true
		}
	}
	return false
}
func retryAfter(raw string) time.Time {
	minimum := time.Now().Add(6 * time.Hour)
	if seconds, e := strconv.ParseInt(raw, 10, 64); e == nil && seconds >= 0 && seconds < 86400*30 {
		if until := time.Now().Add(time.Duration(seconds) * time.Second); until.After(minimum) {
			return until
		}
	}
	if parsed, e := http.ParseTime(raw); e == nil && parsed.After(minimum) {
		return parsed
	}
	return minimum
}
func (s *Service) Access(ctx context.Context, sourceID string, action func() error) error {
	_, cfg, e := s.ensureSource(ctx, s.db, sourceID, true)
	if e != nil {
		return e
	}
	group := text(cfg["access_group"])
	s.accessMu.Lock()
	if s.accessGates == nil {
		s.accessGates = map[string]chan struct{}{}
	}
	gate := s.accessGates[group]
	if gate == nil {
		gate = make(chan struct{}, 1)
		s.accessGates[group] = gate
	}
	s.accessMu.Unlock()
	select {
	case gate <- struct{}{}:
	case <-ctx.Done():
		return ctx.Err()
	case <-s.ctx.Done():
		return context.Canceled
	}
	defer func() { <-gate }()
	if blocked, e := time.Parse(time.RFC3339Nano, text(cfg["blocked_until"])); e == nil && time.Now().Before(blocked) {
		return &problem{429, "WebDAV 来源处于访问冷却"}
	}
	budget, e := s.budget(ctx, s.db, group)
	if e != nil {
		return e
	}
	if blocked, e := time.Parse(time.RFC3339Nano, text(budget["blocked_until"])); e == nil && time.Now().Before(blocked) {
		return &problem{429, "WebDAV 访问冷却中"}
	}
	if next, e := time.Parse(time.RFC3339Nano, text(budget["next_request_at"])); e == nil && time.Now().Before(next) {
		if e = waitContext(ctx, time.Until(next)); e != nil {
			return e
		}
	}
	defer func() {
		endCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_, _ = s.exec(endCtx, s.db, "dav-nextRequest", map[string]any{"group": group, "after": time.Now().UTC().Add(s.requestInterval + s.accessJitter()).Format(time.RFC3339Nano)})
	}()
	return action()
}
func waitContext(ctx context.Context, wait time.Duration) error {
	if wait <= 0 {
		return nil
	}
	timer := time.NewTimer(wait)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-timer.C:
		return nil
	}
}
func (s *Service) waitAccess(ctx context.Context, group string, d time.Duration) error {
	after := time.Now().UTC().Add(d + s.accessJitter())
	if _, e := s.exec(ctx, s.db, "dav-nextRequest", map[string]any{"group": group, "after": after.Format(time.RFC3339Nano)}); e != nil {
		return e
	}
	return waitContext(ctx, time.Until(after))
}
func (s *Service) block(ctx context.Context, sourceID string, auth bool, until time.Time) error {
	_, cfg, e := s.ensureSource(ctx, s.db, sourceID, true)
	if e != nil {
		return e
	}
	reason := "RATE_LIMITED"
	if auth {
		until = time.Date(9999, 12, 31, 23, 59, 59, 0, time.UTC)
		reason = "AUTH_REQUIRED"
	}
	a := map[string]any{"sourceId": sourceID, "group": cfg["access_group"], "until": until.UTC().Format(time.RFC3339Nano), "reason": reason}
	if _, e = s.exec(ctx, s.db, "dav-blockSource", a); e != nil {
		return e
	}
	if !auth {
		_, e = s.exec(ctx, s.db, "dav-blockGroup", a)
	}
	return e
}

func basicAuth(user, password string) string {
	return base64.StdEncoding.EncodeToString([]byte(user + ":" + password))
}

func (s *Service) accessJitter() time.Duration {
	if s.requestInterval == 0 {
		return 0
	}
	return time.Duration(rand.IntN(501)) * time.Millisecond
}

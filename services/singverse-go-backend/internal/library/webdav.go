package library

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/base64"
	"encoding/xml"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"path"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

const propfindBody = `<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/><d:getetag/></d:prop></d:propfind>`

type davType struct {
	Collection *struct{} `xml:"DAV: collection"`
}
type davProp struct {
	Type     *davType `xml:"DAV: resourcetype"`
	Length   string   `xml:"DAV: getcontentlength"`
	Modified string   `xml:"DAV: getlastmodified"`
	ETag     string   `xml:"DAV: getetag"`
}
type davPropstat struct {
	Prop   davProp `xml:"DAV: prop"`
	Status string  `xml:"DAV: status"`
}
type davResponse struct {
	Hrefs     []string      `xml:"DAV: href"`
	Status    string        `xml:"DAV: status"`
	Propstats []davPropstat `xml:"DAV: propstat"`
}
type davEntry struct {
	path           string
	directory      bool
	size           *int64
	modified, etag *string
}

var successStatus = regexp.MustCompile(`^HTTP/[0-9](?:\.[0-9])? 2[0-9][0-9](?: .*)?$`)
var encodedSeparator = regexp.MustCompile(`(?i)%(?:2f|5c)`)

func cleanURLPath(value string) string {
	value = path.Clean("/" + strings.TrimPrefix(value, "/"))
	return value
}
func defaultPort(u *url.URL) string {
	if p := u.Port(); p != "" {
		return p
	}
	if u.Scheme == "https" {
		return "443"
	}
	return "80"
}
func davPath(root *url.URL, directory, href string, collection bool) (string, error) {
	fail := func() (string, error) {
		return "", bad("WebDAV 返回跨来源、越界或无效路径，旧索引已保留")
	}
	if len(href) > 32768 || controls(href) {
		return fail()
	}
	reference, e := url.Parse(strings.ReplaceAll(href, " ", "%20"))
	if e != nil || encodedSeparator.MatchString(reference.EscapedPath()) {
		return fail()
	}
	for _, p := range strings.Split(reference.Path, "/") {
		if p == "." || p == ".." || strings.Contains(p, `\`) {
			return fail()
		}
	}
	current := *root
	current.Path = directory
	current.RawPath = ""
	resolved := current.ResolveReference(reference)
	if resolved.Scheme != root.Scheme || !strings.EqualFold(root.Hostname(), resolved.Hostname()) || defaultPort(resolved) != defaultPort(root) || resolved.User != nil || resolved.RawQuery != "" || resolved.ForceQuery || resolved.Fragment != "" {
		return fail()
	}
	p := resolved.Path
	if controls(p) || strings.Contains(p, "//") {
		return fail()
	}
	if collection && !strings.HasSuffix(p, "/") {
		p += "/"
	}
	if p == directory {
		return p, nil
	}
	if !strings.HasPrefix(p, root.Path) || !strings.HasPrefix(p, directory) {
		return fail()
	}
	child := strings.TrimSuffix(strings.TrimPrefix(p, directory), "/")
	if child == "" || strings.Contains(child, "/") || len([]rune(strings.TrimPrefix(p, root.Path))) > 2048 {
		return fail()
	}
	return p, nil
}
func (s *Service) authorization(src Source) (string, error) {
	if src.Username == "" {
		return "", nil
	}
	password := ""
	if src.encrypted != nil {
		if s.cipher == nil {
			return "", errors.New("cipher unavailable")
		}
		var e error
		password, e = s.cipher.Decrypt(*src.encrypted, "singverse:webdav:v1")
		if e != nil {
			return "", e
		}
	}
	return "Basic " + base64.StdEncoding.EncodeToString([]byte(src.Username+":"+password)), nil
}
func (s *Service) listDAV(ctx context.Context, root *url.URL, directory, authorization string, accept func(davEntry) error) error {
	requestCtx, cancel := context.WithTimeout(ctx, 60*time.Second)
	defer cancel()
	target := *root
	target.Path = directory
	target.RawPath = ""
	req, e := http.NewRequestWithContext(requestCtx, "PROPFIND", target.String(), strings.NewReader(propfindBody))
	if e != nil {
		return e
	}
	req.Header.Set("Depth", "1")
	req.Header.Set("Content-Type", "application/xml; charset=utf-8")
	if authorization != "" {
		req.Header.Set("Authorization", authorization)
	}
	response, e := s.client.Do(req)
	if e != nil {
		return bad("WebDAV 目录读取失败或超时，旧索引已保留")
	}
	defer response.Body.Close()
	if response.StatusCode != 207 {
		switch response.StatusCode {
		case 401:
			return bad("WebDAV 认证失败，请检查用户名和密码")
		case 403:
			return bad("WebDAV 无目录读取权限")
		case 404:
			return bad("WebDAV 目录不存在，请检查 /dav/ 地址及挂载目录")
		case 301, 302, 303, 307, 308:
			return bad("WebDAV 地址发生重定向，请直接配置最终目录地址")
		default:
			return bad(fmt.Sprintf("WebDAV 目录请求失败（HTTP %d），旧索引已保留", response.StatusCode))
		}
	}
	data, e := io.ReadAll(io.LimitReader(response.Body, 64*1024*1024+1))
	if e != nil || len(data) > 64*1024*1024 {
		return bad("WebDAV 目录响应超过范围或读取失败")
	}
	upper := bytes.ToUpper(data)
	if bytes.Contains(upper, []byte("<!DOCTYPE")) || bytes.Contains(upper, []byte("<!ENTITY")) {
		return bad("WebDAV XML 不允许 DTD 或外部实体")
	}
	decoder := xml.NewDecoder(bytes.NewReader(data))
	rootSeen, self, closed := false, false, false
	for {
		if e = ctx.Err(); e != nil {
			return e
		}
		tok, e := decoder.Token()
		if e == io.EOF {
			break
		}
		if e != nil {
			return bad("WebDAV XML 响应无效，旧索引已保留")
		}
		switch t := tok.(type) {
		case xml.StartElement:
			if !rootSeen {
				if t.Name.Space != "DAV:" || t.Name.Local != "multistatus" {
					return bad("WebDAV 返回的不是目录列表")
				}
				rootSeen = true
			} else if closed {
				return bad("WebDAV XML 包含多个根元素")
			} else if t.Name.Space == "DAV:" && t.Name.Local == "response" {
				var item davResponse
				if e = decoder.DecodeElement(&item, &t); e != nil {
					return bad("WebDAV 条目格式无效")
				}
				if len(item.Hrefs) != 1 {
					return bad("WebDAV 条目缺少或重复路径")
				}
				if item.Status != "" && !successStatus.MatchString(strings.TrimSpace(item.Status)) {
					return bad("WebDAV 条目读取失败")
				}
				known, collection := false, false
				var size *int64
				var modified, etag *string
				for _, prop := range item.Propstats {
					if !successStatus.MatchString(strings.TrimSpace(prop.Status)) {
						continue
					}
					if prop.Prop.Type != nil {
						known = true
						collection = prop.Prop.Type.Collection != nil
					}
					if n, e := strconv.ParseInt(strings.TrimSpace(prop.Prop.Length), 10, 64); e == nil && n >= 0 {
						size = &n
					}
					if stamp, e := http.ParseTime(strings.TrimSpace(prop.Prop.Modified)); e == nil {
						v := stamp.UTC().Format(time.RFC3339Nano)
						modified = &v
					}
					if v := strings.TrimSpace(prop.Prop.ETag); v != "" && len(v) <= 512 && !controls(v) {
						etag = &v
					}
				}
				if !known {
					return bad("WebDAV 条目缺少目录类型")
				}
				entryPath, e := davPath(root, directory, item.Hrefs[0], collection)
				if e != nil {
					return e
				}
				if entryPath == directory {
					if !collection {
						return bad("WebDAV 地址不是目录")
					}
					self = true
				} else if e = accept(davEntry{entryPath, collection, size, modified, etag}); e != nil {
					return e
				}
			}
		case xml.EndElement:
			if t.Name.Space == "DAV:" && t.Name.Local == "multistatus" {
				closed = true
			}
		}
	}
	if !rootSeen || !closed || !self {
		return bad("WebDAV 目录响应缺少当前目录，旧索引已保留")
	}
	return nil
}
func (s *Service) traverse(work scanWork) error {
	ctx := work.ctx
	if e := ctx.Err(); e != nil {
		return e
	}
	if e := changed(s.db.ExecContext(ctx, s.q["scan-start"], now(), work.id)); e != nil {
		return e
	}
	root, e := url.Parse(work.source.Location)
	if e != nil {
		return e
	}
	if _, e = davPath(root, root.Path, root.String(), true); e != nil {
		return e
	}
	authorization, e := s.authorization(work.source)
	if e != nil {
		return e
	}
	if _, e = s.db.ExecContext(ctx, s.q["scan-directory"], work.id, hash(root.Path), root.Path, 0); e != nil {
		return e
	}
	t := ScanTask{ID: work.id, SourceID: work.source.ID}
	seen := map[string]bool{}
	scannedAt := now()
	batch := []Song{}
	type folder struct {
		path  string
		depth int
	}
	folders := []folder{}
	flush := func() error {
		return s.transaction(ctx, func(tx store.DBTX) error {
			for _, f := range folders {
				var count int
				if e := tx.QueryRowContext(ctx, s.q["scan-directory-exists"], work.id, hash(f.path)).Scan(&count); e != nil {
					return e
				}
				if count == 0 {
					if _, e := tx.ExecContext(ctx, s.q["scan-directory"], work.id, hash(f.path), f.path, f.depth); e != nil {
						return e
					}
				}
			}
			for _, song := range batch {
				if _, e := tx.ExecContext(ctx, s.q["scan-stage"], work.id, song.ID, song.SourceID, song.RelativePath, song.pathHash, song.FileSize, song.ModifiedAt, song.ScannedAt, song.ParserProfileID, song.ParserRevision, song.SelectedRuleID, encode(song.Result), song.Result.NeedsReview, song.etag); e != nil {
					return e
				}
			}
			_, e := tx.ExecContext(ctx, s.q["scan-progress"], t.Directories, t.Entries, t.TotalSongs, t.MatchedSongs, t.NeedsReviewSongs, t.SkippedFiles, work.id)
			if e == nil {
				batch = nil
				folders = nil
			}
			if e != nil {
				return e
			}
			return s.project(ctx, tx, work.id)
		})
	}
	for {
		if e = ctx.Err(); e != nil {
			return e
		}
		var directory, hashKey string
		var depth int
		e = s.db.QueryRowContext(ctx, s.q["scan-directory-next"], work.id).Scan(&directory, &hashKey, &depth)
		if errors.Is(e, sql.ErrNoRows) {
			break
		}
		if e != nil {
			return e
		}
		e = s.listDAVSource(ctx, work.source.ID, root, directory, authorization, func(entry davEntry) error {
			t.Entries++
			if t.Entries > 1000000 {
				return bad("WebDAV 超过 100 万个目录条目，请拆分来源")
			}
			if entry.directory {
				if depth >= 128 {
					return bad("WebDAV 目录超过 128 层，请拆分来源")
				}
				folders = append(folders, folder{entry.path, depth + 1})
			} else {
				relative := strings.TrimPrefix(entry.path, root.Path)
				filename := path.Base(relative)
				if !supports(filename) {
					t.SkippedFiles++
				} else {
					id, e := s.indexIdentity(ctx, s.db, work.source.ID, relative)
					if e != nil {
						return e
					}
					if !seen[relative] {
						if t.TotalSongs >= 100000 {
							return bad("WebDAV 单来源超过 10 万首媒体文件，请拆分来源")
						}
						parsed, e := Parse(filename, work.rules)
						if e != nil {
							return e
						}
						song := Song{ID: id, SourceID: work.source.ID, RelativePath: relative, pathHash: hash(relative), FileSize: entry.size, ModifiedAt: entry.modified, ScannedAt: scannedAt, ParserProfileID: work.profile.ID, ParserRevision: work.state.revision, Result: parsed.Result, etag: entry.etag}
						if parsed.SelectedRule != nil {
							song.SelectedRuleID = &parsed.SelectedRule.ID
							t.MatchedSongs++
						}
						if parsed.Result.NeedsReview {
							t.NeedsReviewSongs++
						}
						seen[relative] = true
						t.TotalSongs++
						batch = append(batch, song)
					}
				}
			}
			if t.Entries%100 == 0 {
				return flush()
			}
			return nil
		})
		if e != nil {
			return e
		}
		t.Directories++
		if e = flush(); e != nil {
			return e
		}
		if _, e = s.db.ExecContext(ctx, s.q["scan-directory-done"], work.id, hashKey); e != nil {
			return e
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(2 * time.Second):
		}
	}
	if e = flush(); e != nil {
		return e
	}
	if e = s.transaction(ctx, func(tx store.DBTX) error {
		if e := s.checkPublication(ctx, tx, work); e != nil {
			return e
		}
		if e := changed(tx.ExecContext(ctx, s.q["scan-committing"], work.id)); e != nil {
			return e
		}
		return s.project(ctx, tx, work.id)
	}); e != nil {
		return e
	}
	return s.transaction(ctx, func(tx store.DBTX) error {
		if e := s.checkPublication(ctx, tx, work); e != nil {
			return e
		}
		if e := changed(tx.ExecContext(ctx, s.q["source-touch"], now(), work.source.ID, work.source.UpdatedAt)); e != nil {
			return e
		}
		if e := s.publishDAV(ctx, tx, work, t.TotalSongs); e != nil {
			return e
		}
		if e := s.indexChanged(ctx, tx, work.source.ID); e != nil {
			return e
		}
		if _, e := tx.ExecContext(ctx, s.q["scan-finish"], "SUCCEEDED", nil, now(), work.id); e != nil {
			return e
		}
		if _, e := tx.ExecContext(ctx, s.q["source-result"], now(), "SUCCEEDED", nil, work.source.ID); e != nil {
			return e
		}
		for _, name := range []string{"scan-clear", "scan-clear-dirs"} {
			if _, e := tx.ExecContext(ctx, s.q[name], work.id); e != nil {
				return e
			}
		}
		return s.project(ctx, tx, work.id)
	})
}

var namedParameter = regexp.MustCompile(`#\{([A-Za-z]+)\}`)

func (s *Service) named(ctx context.Context, db store.DBTX, name string, values map[string]any) (sql.Result, error) {
	query, ok := s.q[name]
	if !ok {
		return nil, fmt.Errorf("missing query %s", name)
	}
	args := []any{}
	missing := false
	query = namedParameter.ReplaceAllStringFunc(query, func(match string) string {
		key := match[2 : len(match)-1]
		v, ok := values[key]
		if !ok {
			missing = true
		}
		args = append(args, v)
		return "?"
	})
	if missing {
		return nil, errors.New("query parameter missing")
	}
	return db.ExecContext(ctx, query, args...)
}
func (s *Service) publishDAV(ctx context.Context, tx store.DBTX, work scanWork, total int) error {
	root, _ := url.Parse(work.source.Location)
	a := map[string]any{"sourceId": work.source.ID, "taskId": work.id, "binding": hash(work.source.Location), "group": hash(root.Scheme + "://" + root.Host + "\x00" + work.source.Username), "now": now(), "missingBefore": time.Now().Add(-30 * time.Minute).UTC().Format(time.RFC3339Nano)}
	if _, e := s.named(ctx, tx, "dav-configInsert", a); e != nil {
		return e
	}
	var old int
	if e := tx.QueryRowContext(ctx, s.q["song-count"]+s.q["song-filter"], work.source.ID, work.source.ID, "", "", -1, -1, false).Scan(&old); e != nil {
		return e
	}
	if total == 0 && old > 0 {
		if _, e := s.named(ctx, tx, "dav-emptyScan", a); e != nil {
			return e
		}
		_, e := tx.ExecContext(ctx, s.q["scan-counters"], 0, 0, old, 0, work.id)
		return e
	}
	if _, e := s.named(ctx, tx, "dav-notEmpty", a); e != nil {
		return e
	}
	r, e := s.named(ctx, tx, "dav-newResources", a)
	if e != nil {
		return e
	}
	added, e := r.RowsAffected()
	if e != nil {
		return e
	}
	for _, name := range []string{"observed", "missing"} {
		if _, e = s.named(ctx, tx, "dav-"+name, a); e != nil {
			return e
		}
	}
	r, e = s.named(ctx, tx, "dav-archive", a)
	if e != nil {
		return e
	}
	removed, e := r.RowsAffected()
	if e != nil {
		return e
	}
	for _, name := range []string{"updateSongs", "insertSongs", "removeArchivedSongs", "restorePending", "queue"} {
		if _, e = s.named(ctx, tx, "dav-"+name, a); e != nil {
			return e
		}
	}
	if _, e = s.named(ctx, tx, "shared-observed", a); e != nil {
		return e
	}
	var missing, archived, changed int64
	query := namedParameter.ReplaceAllString(s.q["dav-presence"], "?")
	if e = tx.QueryRowContext(ctx, query, work.source.ID).Scan(&missing, &archived, &changed); e != nil {
		return e
	}
	_, e = tx.ExecContext(ctx, s.q["scan-counters"], added, int64(total)-added, missing, removed, work.id)
	return e
}

func (s *Service) listDAVSource(ctx context.Context, sourceID string, root *url.URL, directory, authorization string, visit func(davEntry) error) error {
	s.hookMu.RLock()
	gate := s.davAccess
	s.hookMu.RUnlock()
	action := func() error { return s.listDAV(ctx, root, directory, authorization, visit) }
	if gate != nil {
		return gate(ctx, sourceID, action)
	}
	return action()
}

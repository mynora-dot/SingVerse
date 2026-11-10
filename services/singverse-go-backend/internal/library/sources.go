package library

import (
	"context"
	"database/sql"
	"errors"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

func sourceRow(row scanner) (Source, error) {
	var stored sourceRecord
	err := row.StructScan(&stored)
	return stored.model(), err
}
func (s *Service) findSource(ctx context.Context, db store.DBTX, id string) (Source, error) {
	src, err := sourceRow(db.QueryRowxContext(ctx, s.q["source-find"], id))
	if errors.Is(err, sql.ErrNoRows) {
		return src, missing("媒体库不存在")
	}
	if err != nil {
		return src, err
	}
	var bound *string
	if err = db.QueryRowContext(ctx, s.q["lx-binding-read"]).Scan(&bound); err != nil && !errors.Is(err, sql.ErrNoRows) {
		return src, err
	}
	src.UseForLxDownloads = bound != nil && *bound == id
	return src, nil
}
func pageParams(c *gin.Context) (int, int, error) {
	page, e := strconv.Atoi(c.DefaultQuery("page", "0"))
	if e != nil {
		return 0, 0, bad("分页参数超出范围")
	}
	size, e := strconv.Atoi(c.DefaultQuery("size", "20"))
	if e != nil || page < 0 || page > 1000000 || size < 1 || size > 100 {
		return 0, 0, bad("分页参数超出范围")
	}
	return page, size, nil
}
func pattern(q string) string {
	return "%" + strings.NewReplacer("!", "!!", "%", "!%", "_", "!_").Replace(strings.TrimSpace(q)) + "%"
}
func (s *Service) listSources(c *gin.Context) {
	page, size, e := pageParams(c)
	if e != nil {
		s.fail(c, e)
		return
	}
	q, kind, profileID := c.Query("q"), c.Query("type"), c.Query("profileId")
	if len([]rune(q)) > 200 || controls(q) || (kind != "" && kind != "LOCAL" && kind != "WEBDAV") || (profileID != "" && (!validID.MatchString(profileID) || len(profileID) > 36)) {
		s.fail(c, bad("搜索条件无效"))
		return
	}
	args := []any{strings.TrimSpace(q), pattern(q), pattern(q), kind, kind, profileID, profileID}
	out := Page[Source]{Items: []Source{}, Page: page, Size: size}
	ctx := c.Request.Context()
	e = store.WithinTx(ctx, s.db, func(tx store.DBTX) error {
		if e := tx.QueryRowContext(ctx, s.q["source-count"]+s.q["source-filter"], args...).Scan(&out.TotalElements); e != nil {
			return e
		}
		var bound *string
		if e := tx.QueryRowContext(ctx, s.q["lx-binding-read"]).Scan(&bound); e != nil && !errors.Is(e, sql.ErrNoRows) {
			return e
		}
		rows, e := tx.QueryxContext(ctx, s.q["source-columns"]+s.q["source-filter"]+s.q["source-order"], append(args, size, page*size)...)
		if e != nil {
			return e
		}
		defer rows.Close()
		for rows.Next() {
			src, e := sourceRow(rows)
			if e != nil {
				return e
			}
			src.UseForLxDownloads = bound != nil && *bound == src.ID
			out.Items = append(out.Items, src)
		}
		return rows.Err()
	})
	if e != nil {
		s.fail(c, e)
		return
	}
	c.JSON(200, out)
}
func (s *Service) getSource(c *gin.Context) {
	src, e := s.findSource(c.Request.Context(), s.db, c.Param("id"))
	if e != nil {
		s.fail(c, e)
		return
	}
	c.JSON(200, src)
}
func (s *Service) createSource(c *gin.Context) { s.saveSource(c, true) }
func (s *Service) updateSource(c *gin.Context) { s.saveSource(c, false) }
func (s *Service) saveSource(c *gin.Context, create bool) {
	var in SourceInput
	if !s.decode(c, &in) {
		return
	}
	id := c.Param("id")
	if create {
		id = uuid()
	}
	var out Source
	ctx := c.Request.Context()
	e := s.transaction(ctx, func(tx store.DBTX) error {
		if _, e := tx.ExecContext(ctx, s.q["config-lock"]); e != nil {
			return e
		}
		if _, e := tx.ExecContext(ctx, s.q["lx-lock"]); e != nil {
			return e
		}
		var previous *Source
		if !create {
			old, e := s.findSource(ctx, tx, id)
			if e != nil {
				return e
			}
			previous = &old
		}
		src, e := s.validateSource(ctx, tx, id, in, previous)
		if e != nil {
			return e
		}
		if e = s.downloadBinding(ctx, tx, src, in.UseForLxDownloads, false, previous); e != nil {
			return e
		}
		if previous != nil && previous.Location != src.Location {
			if e = s.clearSource(ctx, tx, id); e != nil {
				return e
			}
		}
		if create {
			_, e = tx.ExecContext(ctx, s.q["source-insert"], src.ID, src.Name, src.Type, src.Location, hash(src.Type+":"+src.Location), src.Username, src.encrypted, src.ParserProfileID, src.CreatedAt, src.UpdatedAt, src.ScanEnabled, src.ScanCron, src.NextScanAt)
		} else {
			e = changed(tx.ExecContext(ctx, s.q["source-update"], src.Name, src.Location, hash(src.Type+":"+src.Location), src.Username, src.encrypted, src.ParserProfileID, src.UpdatedAt, src.ScanEnabled, src.ScanCron, src.NextScanAt, id))
		}
		if e != nil {
			if strings.Contains(strings.ToLower(e.Error()), "unique") || strings.Contains(strings.ToLower(e.Error()), "duplicate") {
				return conflict("此目录或 WebDAV 地址已添加")
			}
			return e
		}

		if in.AutoExtractEmbedded != nil {
			if src.Type != "WEBDAV" {
				return bad("自动读取远端信息仅适用于 WebDAV 来源")
			}
			u, _ := url.Parse(src.Location)
			if _, e = s.named(ctx, tx, "dav-configInsert", map[string]any{"sourceId": id, "binding": hash(src.Location), "group": hash(u.Scheme + "://" + u.Host + "\x00" + src.Username)}); e != nil {
				return e
			}
			if _, e = tx.ExecContext(ctx, s.q["ingest-enable"], *in.AutoExtractEmbedded, id); e != nil {
				return e
			}
		}
		if in.StartScan != nil && *in.StartScan {
			requestID, e := s.enqueueIngest(ctx, tx, id)
			if e != nil {
				return e
			}
			out.InitialTaskID = &requestID
		}
		taskID := out.InitialTaskID
		out, e = s.findSource(ctx, tx, id)
		out.InitialTaskID = taskID
		return e
	})
	if e != nil {
		s.fail(c, e)
		return
	}
	status := 200
	if create {
		status = 201
		c.Header("Location", "/api/v1/media-sources/"+id)
	}
	c.JSON(status, out)
}
func (s *Service) validateSource(ctx context.Context, tx store.DBTX, id string, in SourceInput, old *Source) (Source, error) {
	src := Source{ID: id, Name: strings.TrimSpace(in.Name), Type: in.Type, Location: strings.TrimSpace(in.Location), Username: strings.TrimSpace(in.Username), ParserProfileID: "default", CreatedAt: now(), UpdatedAt: now()}
	if !text(in.Name, 100) || !text(in.Location, 2048) {
		return src, bad("名称及目录或地址不能为空、过长或包含控制字符")
	}
	if src.Type != "LOCAL" && src.Type != "WEBDAV" {
		return src, bad("请选择有效的媒体库类型")
	}
	if old != nil {
		if src.Type != old.Type {
			return src, bad("媒体库类型不能修改")
		}
		src.CreatedAt = old.CreatedAt
		src.ParserProfileID = old.ParserProfileID
		src.ScanCron = old.ScanCron
		src.ScanEnabled = old.ScanEnabled
		src.NextScanAt = old.NextScanAt
		src.LastScanAt = old.LastScanAt
		src.LastScanStatus = old.LastScanStatus
		src.LastScanError = old.LastScanError
	}
	if len([]rune(src.Username)) > 256 || controls(src.Username) || (in.Password != nil && (len([]rune(*in.Password)) > 1024 || controls(*in.Password))) {
		return src, bad("WebDAV 认证信息格式无效")
	}
	if src.Type == "LOCAL" {
		if src.Username != "" || (in.Password != nil && *in.Password != "") {
			return src, bad("本地目录不接受远端认证信息")
		}
		if !filepath.IsAbs(src.Location) {
			return src, bad("请输入服务端可见的绝对目录路径")
		}
		real, e := filepath.EvalSymlinks(src.Location)
		if e != nil {
			return src, bad("目录不存在或服务端无法读取，请检查挂载和权限")
		}
		src.Location = filepath.Clean(real)
		dir, e := os.Open(src.Location)
		if e != nil {
			return src, bad("目录不存在或服务端无法读取")
		}
		info, e := dir.Stat()
		if e != nil || !info.IsDir() {
			dir.Close()
			return src, bad("目录不存在或服务端无法读取")
		}
		_, e = dir.Readdirnames(1)
		dir.Close()
		if e != nil && !errors.Is(e, os.ErrNotExist) && e.Error() != "EOF" {
			return src, bad("目录无法读取")
		}
	} else {
		u, e := url.Parse(src.Location)
		if e != nil || u.Hostname() == "" || (u.Scheme != "https" && u.Scheme != "http") || u.User != nil || u.RawQuery != "" || u.ForceQuery || u.Fragment != "" {
			return src, bad("WebDAV 地址须为 HTTP(S) 目录地址，不能包含凭据、查询或片段")
		}
		if port := u.Port(); port != "" {
			n, e := strconv.Atoi(port)
			if e != nil || n < 1 || n > 65535 {
				return src, bad("WebDAV 端口无效")
			}
		}
		u.Host = strings.ToLower(u.Host)
		u.Path = cleanURLPath(u.Path)
		if !strings.HasSuffix(u.Path, "/") {
			u.Path += "/"
		}
		u.RawPath = ""
		src.Location = u.String()
		same := old != nil && old.Location == src.Location && old.Username == src.Username
		if in.Password == nil && same {
			src.encrypted = old.encrypted
		} else if in.Password == nil && old != nil && old.encrypted != nil {
			return src, bad("更改地址或账号时，请重新输入密码，或明确清除密码")
		} else if in.Password != nil && *in.Password != "" {
			if s.cipher == nil {
				return src, errors.New("cipher unavailable")
			}
			v, e := s.cipher.Encrypt(*in.Password, "singverse:webdav:v1")
			if e != nil {
				return src, e
			}
			src.encrypted = &v
		}
		if src.Username == "" && src.encrypted != nil {
			return src, bad("设置密码时需要填写用户名")
		}
	}
	if in.ParserProfileID != nil {
		src.ParserProfileID = strings.TrimSpace(*in.ParserProfileID)
	}
	st, e := s.state(ctx, tx)
	if e != nil {
		return src, e
	}
	if _, e = profile(st, src.ParserProfileID); e != nil {
		return src, bad("解析方案不存在，请刷新后重新选择")
	}
	if in.ScanEnabled != nil {
		src.ScanEnabled = *in.ScanEnabled
	}
	if in.ScanCron != nil {
		cron := strings.TrimSpace(*in.ScanCron)
		src.ScanCron = nil
		if cron != "" {
			src.ScanCron = &cron
		}
	}
	if src.ScanEnabled && src.ScanCron == nil {
		return src, bad("启用定时扫描时必须填写五段 cron 表达式")
	}
	var next time.Time
	if src.ScanCron != nil {
		next, e = nextCron(*src.ScanCron, time.Now())
		if e != nil {
			return src, e
		}
	}
	if old == nil || in.ScanEnabled != nil || in.ScanCron != nil {
		src.NextScanAt = nil
		if src.ScanEnabled {
			v := next.UTC().Format(time.RFC3339Nano)
			src.NextScanAt = &v
		}
	}
	src.PasswordConfigured = src.encrypted != nil
	return src, nil
}
func (s *Service) downloadBinding(ctx context.Context, tx store.DBTX, src Source, requested *bool, removing bool, old *Source) error {
	var bound *string
	if e := tx.QueryRowContext(ctx, s.q["lx-binding-read"]).Scan(&bound); e != nil {
		return e
	}
	isBound := bound != nil && *bound == src.ID
	selected := isBound
	if requested != nil {
		selected = *requested
	}
	changing := isBound && (removing || !selected || (old != nil && old.Location != src.Location))
	checkActive := func(id string) error {
		var count int
		if e := tx.QueryRowContext(ctx, s.q["lx-active"], id).Scan(&count); e != nil {
			return e
		}
		if count > 0 {
			return conflict("下载仍在进行，请先结束或取消该媒体库的下载任务")
		}
		return nil
	}
	if changing {
		if e := checkActive(src.ID); e != nil {
			return e
		}
	}
	if selected && !removing {
		if src.Type != "LOCAL" {
			return bad("洛雪下载保存位置必须为本地目录")
		}
		temp, e := os.CreateTemp(src.Location, ".singverse-write-check-*")
		if e != nil {
			return conflict("下载目录不存在或不可写，请检查 NAS 挂载和目录权限")
		}
		name := temp.Name()
		temp.Close()
		if e = os.Remove(name); e != nil {
			return e
		}
		if bound != nil && !isBound {
			if e = checkActive(*bound); e != nil {
				return e
			}
		}
		_, e = tx.ExecContext(ctx, s.q["lx-binding-set"], src.ID)
		return e
	}
	if isBound {
		_, e := tx.ExecContext(ctx, s.q["lx-binding-set"], nil)
		return e
	}
	return nil
}
func (s *Service) deleteSource(c *gin.Context) {
	ctx := c.Request.Context()
	e := s.transaction(ctx, func(tx store.DBTX) error {
		if _, e := tx.ExecContext(ctx, s.q["config-lock"]); e != nil {
			return e
		}
		if _, e := tx.ExecContext(ctx, s.q["lx-lock"]); e != nil {
			return e
		}
		src, e := s.findSource(ctx, tx, c.Param("id"))
		if e != nil {
			return e
		}
		if e = s.downloadBinding(ctx, tx, src, nil, true, &src); e != nil {
			return e
		}
		if e = s.clearSource(ctx, tx, src.ID); e != nil {
			return e
		}
		return changed(tx.ExecContext(ctx, s.q["source-delete"], src.ID))
	})
	if e != nil {
		s.fail(c, e)
		return
	}
	c.Status(204)
}
func (s *Service) clearSource(ctx context.Context, tx store.DBTX, id string) error {
	for _, name := range []string{"source-clear-import-entries", "source-clear-imports", "source-clear-shared", "source-clear-shared-baseline", "source-clear-share-series", "source-clear-ingests", "source-clear-metadata", "source-clear-assignments", "source-clear-classification", "source-clear-artists", "source-clear-overrides", "source-clear-dav-tasks", "source-clear-webdav", "source-clear-dav-config", "song-delete-source"} {
		if _, e := tx.ExecContext(ctx, s.q[name], id); e != nil {
			return e
		}
	}
	return nil
}

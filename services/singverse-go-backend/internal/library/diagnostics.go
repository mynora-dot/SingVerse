package library

import (
	"context"
	"database/sql"
	"errors"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
)

// diagnose distinguishes directory access from a bounded, explicit media read.
// It never follows a download redirect or consumes an ignored Range response.
func (s *Service) diagnose(c *gin.Context) {
	ctx := c.Request.Context()
	src, err := s.findSource(ctx, s.db, c.Param("id"))
	if err != nil {
		s.fail(c, err)
		return
	}
	if src.Type != "WEBDAV" {
		s.fail(c, bad("连接诊断仅适用于 WebDAV"))
		return
	}
	out := map[string]any{"directory": "UNREACHABLE", "media": "NOT_TESTED", "directoryMessage": "无法读取目录", "mediaMessage": "目录可列出后才检查分段读取"}
	if _, _, err = s.MediaDirectory(ctx, src.ID, ""); err != nil {
		out["directoryMessage"] = safeError(err)
		c.JSON(200, out)
		return
	}
	out["directory"] = "PRESENT"
	out["directoryMessage"] = "可以列出目录"
	var relative string
	if err = s.db.GetContext(ctx, &relative, s.q["diagnostics-sample"], src.ID); err != nil {
		if !errors.Is(err, sql.ErrNoRows) {
			s.fail(c, err)
			return
		}
		out["mediaMessage"] = "暂无已入库歌曲可试读，请先导入或扫描"
		c.JSON(200, out)
		return
	}
	out["relativePath"] = relative
	address, err := MediaURL(src.Location, relative)
	if err != nil {
		s.fail(c, err)
		return
	}
	auth, err := s.authorization(src)
	if err != nil {
		s.fail(c, err)
		return
	}
	action := func() error {
		call, cancel := context.WithTimeout(ctx, 10*time.Second)
		defer cancel()
		req, err := http.NewRequestWithContext(call, "GET", address, nil)
		if err != nil {
			return err
		}
		req.Header.Set("Range", "bytes=0-0")
		req.Header.Set("Accept-Encoding", "identity")
		if auth != "" {
			req.Header.Set("Authorization", auth)
		}
		resp, err := s.client.Do(req)
		if err != nil {
			return bad("媒体读取失败或超时")
		}
		defer resp.Body.Close()
		switch resp.StatusCode {
		case 206:
			parts := contentRange.FindStringSubmatch(resp.Header.Get("Content-Range"))
			if len(parts) != 4 || parts[1] != "0" || parts[2] != "0" || parts[3] == "0" || resp.ContentLength > 1 || (resp.Header.Get("Content-Encoding") != "" && !strings.EqualFold(resp.Header.Get("Content-Encoding"), "identity")) {
				out["media"] = "RANGE_UNSUPPORTED"
				out["mediaMessage"] = "分段响应不兼容，请检查代理是否改写范围或压缩媒体"
				break
			}
			b, err := io.ReadAll(io.LimitReader(resp.Body, 2))
			if err != nil || len(b) != 1 {
				return bad("分段响应内容不完整")
			}
			out["media"] = "RANGE_READY"
			out["mediaMessage"] = "已成功读取一个字节；仍需实际播放验证编码兼容性"
		case 200:
			out["media"] = "RANGE_UNSUPPORTED"
			out["mediaMessage"] = "来源忽略分段请求，已停止读取；请检查 OpenList 代理与网盘读取方式"
		case 301, 302, 303, 307, 308:
			out["media"] = "REDIRECT"
			out["mediaMessage"] = "媒体下载返回跳转；请配置支持直接读取的 WebDAV 或调整 OpenList 代理方式"
		case 401, 403:
			out["media"] = "AUTH_REQUIRED"
			out["mediaMessage"] = "能列目录，但媒体读取权限不足，请检查来源账号及文件权限"
		case 404:
			out["media"] = "MISSING"
			out["mediaMessage"] = "能列目录，但试读文件不存在，请重新核验路径"
		case 429:
			out["media"] = "RATE_LIMITED"
			out["mediaMessage"] = "媒体访问被限流，请稍后重试"
		default:
			out["media"] = "UNREACHABLE"
			out["mediaMessage"] = "媒体读取失败，请检查来源及代理策略"
		}
		return nil
	}
	s.hookMu.RLock()
	gate := s.davAccess
	s.hookMu.RUnlock()
	if gate != nil {
		err = gate(ctx, src.ID, action)
	} else {
		err = action()
	}
	if err != nil {
		out["media"] = "UNREACHABLE"
		out["mediaMessage"] = "媒体试读失败或访问冷却中，请稍后重试"
	}
	c.JSON(200, out)
}

package metadata

import (
	"archive/zip"
	"context"
	"fmt"
	"github.com/gin-gonic/gin"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"
)

func (s *Service) shareFolder(id string) (string, error) {
	if !uuidPattern.MatchString(id) {
		return "", bad("分享任务标识无效")
	}
	return managedDirectory(s.cfg.MetadataPath, "shares", id)
}
func (s *Service) shareJob(ctx context.Context, db store.DBTX, source, id string) (map[string]any, error) {
	if _, e := s.row(ctx, db, "source", map[string]any{"id": source}); e != nil {
		return nil, absent(e, "媒体来源不存在")
	}
	if !uuidPattern.MatchString(id) {
		return nil, bad("分享任务标识无效")
	}
	r, e := s.row(ctx, db, "share-find", map[string]any{"sourceId": source, "id": id})
	if e != nil {
		return nil, absent(e, "分享任务不存在")
	}
	folder, folderErr := s.shareFolder(id)
	if folderErr != nil {
		return nil, folderErr
	}
	files, e := os.ReadDir(folder)
	count := 0
	if e == nil {
		for _, f := range files {
			if deletionName.MatchString(f.Name()) && f.Type()&os.ModeSymlink == 0 {
				count++
			}
		}
	}
	r["deletion_count"] = count
	return r, nil
}
func (s *Service) shareProgress(ctx context.Context, tx store.DBTX, job map[string]any, state, cursor string, processed int64, parts int64, message any) error {
	if _, e := s.exec(ctx, tx, "share-progress", map[string]any{"id": job["id"], "state": state, "cursor": cursor, "processed": processed, "parts": parts, "error": message}); e != nil {
		return e
	}
	return s.project(ctx, tx, "SHARE_EXPORT", text(job["id"]))
}
func scalar(rows []map[string]any) int64 {
	if len(rows) == 0 {
		return 0
	}
	for _, v := range rows[0] {
		return number(v)
	}
	return 0
}
func (s *Service) createShare(c *gin.Context) {
	var input struct {
		BaseID *string `json:"baseId"`
	}
	if c.Request.ContentLength != 0 && !s.decode(c, &input) {
		return
	}
	ctx := c.Request.Context()
	sourceID := c.Param("id")
	id := uuid()
	e := s.transaction(ctx, func(tx store.DBTX) error {
		if _, e := s.exec(ctx, tx, "share-lock", nil); e != nil {
			return e
		}
		source, e := s.row(ctx, tx, "source", map[string]any{"id": sourceID})
		if e != nil {
			return absent(e, "媒体来源不存在")
		}
		base := nullable("")
		if input.BaseID != nil && strings.TrimSpace(*input.BaseID) != "" {
			job, e := s.shareJob(ctx, tx, sourceID, *input.BaseID)
			if e != nil {
				return e
			}
			if text(job["binding"]) != hash(text(source["location"])) || text(job["state"]) != "SUCCEEDED" {
				return conflict("基准快照与当前媒体来源不兼容")
			}
			base = *input.BaseID
		}
		active, e := s.rows(ctx, tx, "share-active", map[string]any{"sourceId": sourceID})
		if e != nil {
			return e
		}
		if len(active) > 0 {
			return conflict("该来源已有分享导出任务")
		}
		active, e = s.rows(ctx, tx, "share-active", map[string]any{"sourceId": nil})
		if e != nil {
			return e
		}
		if len(active) >= 8 {
			return conflict("分享导出队列已满")
		}
		a := map[string]any{"id": id, "sourceId": sourceID, "binding": hash(text(source["location"])), "baseId": base, "now": now()}
		if _, e = s.exec(ctx, tx, "share-insert", a); e != nil {
			return e
		}
		if _, e = s.exec(ctx, tx, "files-enqueueSource", a); e != nil {
			return e
		}
		pending, e := s.rows(ctx, tx, "files-pendingSource", a)
		if e != nil {
			return e
		}
		if scalar(pending) > 0 {
			if _, e = s.exec(ctx, tx, "files-retrySource", a); e != nil {
				return e
			}
			if _, e = s.exec(ctx, tx, "share-sourceCount", a); e != nil {
				return e
			}
			return s.shareProgress(ctx, tx, a, "WAITING", "", 0, 0, nil)
		}
		if _, e = s.exec(ctx, tx, "share-snapshot", a); e != nil {
			return e
		}
		if _, e = s.exec(ctx, tx, "share-count", a); e != nil {
			return e
		}
		return s.project(ctx, tx, "SHARE_EXPORT", id)
	})
	if e != nil {
		s.fail(c, e)
		return
	}
	job, e := s.shareJob(ctx, s.db, sourceID, id)
	if e != nil {
		s.fail(c, e)
		return
	}
	c.JSON(202, job)
}
func (s *Service) getShare(c *gin.Context) {
	job, e := s.shareJob(c.Request.Context(), s.db, c.Param("id"), c.Param("jobId"))
	if e != nil {
		s.fail(c, e)
		return
	}
	c.JSON(200, job)
}
func (s *Service) latestShare(c *gin.Context) {
	ctx := c.Request.Context()
	id := c.Param("id")
	if _, e := s.row(ctx, s.db, "source", map[string]any{"id": id}); e != nil {
		s.fail(c, absent(e, "媒体来源不存在"))
		return
	}
	rows, e := s.rows(ctx, s.db, "share-latest", map[string]any{"sourceId": id})
	if e != nil {
		s.fail(c, e)
		return
	}
	if len(rows) == 0 {
		c.JSON(200, map[string]any{})
		return
	}
	job, e := s.shareJob(ctx, s.db, id, text(rows[0]["id"]))
	if e != nil {
		s.fail(c, e)
		return
	}
	c.JSON(200, job)
}
func (s *Service) resumeShare(c *gin.Context) {
	ctx := c.Request.Context()
	e := s.transaction(ctx, func(tx store.DBTX) error {
		if _, e := s.exec(ctx, tx, "share-lock", nil); e != nil {
			return e
		}
		job, e := s.shareJob(ctx, tx, c.Param("id"), c.Param("jobId"))
		if e != nil {
			return e
		}
		if text(job["state"]) == "SUCCEEDED" {
			return nil
		}
		rows, e := s.rows(ctx, tx, "share-snapshotCount", map[string]any{"id": job["id"]})
		if e != nil {
			return e
		}
		state := "QUEUED"
		if number(job["part_count"]) == 0 && scalar(rows) == 0 {
			state = "WAITING"
			if _, e = s.exec(ctx, tx, "files-retrySource", map[string]any{"sourceId": job["source_id"]}); e != nil {
				return e
			}
		}
		return s.shareProgress(ctx, tx, job, state, text(job["cursor_id"]), number(job["processed_count"]), number(job["part_count"]), nil)
	})
	if e != nil {
		s.fail(c, e)
		return
	}
	c.Status(200)
}
func (s *Service) shareIndex(ctx context.Context, source, id string) (map[string]any, error) {
	job, e := s.shareJob(ctx, s.db, source, id)
	if e != nil {
		return nil, e
	}
	folder, folderErr := s.shareFolder(id)
	if folderErr != nil {
		return nil, folderErr
	}
	files, e := os.ReadDir(folder)
	deletions := []string{}
	if e == nil {
		for _, f := range files {
			if deletionName.MatchString(f.Name()) && f.Type()&os.ModeSymlink == 0 {
				deletions = append(deletions, f.Name())
			}
		}
	}
	sort.Strings(deletions)
	parts := []string{}
	for i := int64(1); i <= number(job["part_count"]); i++ {
		parts = append(parts, fmt.Sprintf("part-%06d.zip", i))
	}
	return map[string]any{"format": "singverse-share-set", "version": 2, "id": id, "state": job["state"], "total": job["total_count"], "baseId": text(job["base_id"]), "deletions": deletions, "parts": parts}, nil
}
func (s *Service) indexShare(c *gin.Context) {
	index, e := s.shareIndex(c.Request.Context(), c.Param("id"), c.Param("jobId"))
	if e != nil {
		s.fail(c, e)
		return
	}
	c.Header("Content-Disposition", "attachment; filename=index.json")
	c.Header("Cache-Control", "no-store")
	c.JSON(200, index)
}
func (s *Service) shareFile(ctx context.Context, source, id string, part int, deletion bool) (string, error) {
	job, e := s.shareJob(ctx, s.db, source, id)
	if e != nil {
		return "", e
	}
	if part < 1 || part > 100000 || !deletion && int64(part) > number(job["part_count"]) {
		return "", missing("分享分片尚未生成")
	}
	folder, folderErr := s.shareFolder(id)
	if folderErr != nil {
		return "", folderErr
	}
	name := fmt.Sprintf("part-%06d.zip", part)
	if deletion {
		name = fmt.Sprintf("deletions-%06d.json", part)
	}
	target := filepath.Join(folder, name)
	st, e := os.Lstat(target)
	if e != nil || !st.Mode().IsRegular() {
		return "", missing("分享分片不可用")
	}
	return target, nil
}
func (s *Service) partShare(c *gin.Context) {
	part, e := strconv.Atoi(c.Param("part"))
	if e != nil {
		s.fail(c, bad("分享分片标识无效"))
		return
	}
	deletion := strings.Contains(c.FullPath(), "/deletions/")
	path, e := s.shareFile(c.Request.Context(), c.Param("id"), c.Param("jobId"), part, deletion)
	if e != nil {
		s.fail(c, e)
		return
	}
	f, e := os.Open(path)
	if e != nil {
		s.fail(c, missing("分享分片不可用"))
		return
	}
	defer f.Close()
	st, e := f.Stat()
	if e != nil {
		s.fail(c, e)
		return
	}
	c.Header("Cache-Control", "no-store")
	c.Header("X-Content-Type-Options", "nosniff")
	if deletion {
		c.Header("Content-Type", "application/json")
	} else {
		c.Header("Content-Type", "application/zip")
	}
	http.ServeContent(c.Writer, c.Request, filepath.Base(path), st.ModTime(), f)
}
func (s *Service) downloadShare(c *gin.Context) {
	ctx := c.Request.Context()
	source, id := c.Param("id"), c.Param("jobId")
	job, e := s.shareJob(ctx, s.db, source, id)
	if e != nil {
		s.fail(c, e)
		return
	}
	if text(job["state"]) != "SUCCEEDED" {
		s.fail(c, conflict("分享包仍在生成"))
		return
	}
	index, e := s.shareIndex(ctx, source, id)
	if e != nil {
		s.fail(c, e)
		return
	}
	paths := []string{}
	for i := int64(1); i <= number(job["part_count"]); i++ {
		path, e := s.shareFile(ctx, source, id, int(i), false)
		if e != nil {
			s.fail(c, e)
			return
		}
		paths = append(paths, path)
	}
	for i := int64(1); i <= number(job["deletion_count"]); i++ {
		path, e := s.shareFile(ctx, source, id, int(i), true)
		if e != nil {
			s.fail(c, e)
			return
		}
		paths = append(paths, path)
	}
	c.Header("Content-Type", "application/zip")
	c.Header("Content-Disposition", "attachment; filename=singverse-share-set.zip")
	c.Header("Cache-Control", "no-store")
	c.Status(200)
	zipWriter := zip.NewWriter(c.Writer)
	write := func(name string, r io.Reader) error {
		entry, e := zipWriter.CreateHeader(&zip.FileHeader{Name: name, Method: zip.Store})
		if e != nil {
			return e
		}
		_, e = io.Copy(entry, r)
		return e
	}
	if e = write("index.json", strings.NewReader(encode(index))); e == nil {
		for _, path := range paths {
			if ctx.Err() != nil {
				break
			}
			f, openErr := os.Open(path)
			if openErr != nil {
				e = openErr
				break
			}
			e = write(filepath.Base(path), f)
			f.Close()
			if e != nil {
				break
			}
		}
	}
	closeErr := zipWriter.Close()
	if e != nil || closeErr != nil {
		s.log.Error("share download interrupted")
	}
}
func (s *Service) shareLoop() {
	defer s.wg.Done()
	ticker := time.NewTicker(2 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-s.ctx.Done():
			return
		case <-ticker.C:
			if e := s.ProcessShare(s.ctx); e != nil && s.ctx.Err() == nil {
				s.log.Error("share export worker failed")
			}
		}
	}
}
func (s *Service) ProcessShare(ctx context.Context) error {
	s.shareMu.Lock()
	defer s.shareMu.Unlock()
	pending, e := s.rows(ctx, s.db, "share-pending", nil)
	if e != nil {
		return e
	}
	for _, job := range pending {
		if text(job["state"]) == "WAITING" {
			sourceID := text(job["source_id"])
			failures, e := s.rows(ctx, s.db, "files-failures", map[string]any{"sourceId": sourceID})
			if e != nil {
				return e
			}
			if scalar(failures) > 0 {
				if e = s.transaction(ctx, func(tx store.DBTX) error {
					return s.shareProgress(ctx, tx, job, "FAILED", "", 0, 0, "分享准备暂时不可用，请重试")
				}); e != nil {
					return e
				}
				continue
			}
			files, e := s.rows(ctx, s.db, "files-pendingSource", map[string]any{"sourceId": sourceID})
			if e != nil {
				return e
			}
			if scalar(files) > 0 {
				continue
			}
			ready := false
			e = s.transaction(ctx, func(tx store.DBTX) error {
				if _, e := s.exec(ctx, tx, "share-lock", nil); e != nil {
					return e
				}
				current, e := s.shareJob(ctx, tx, sourceID, text(job["id"]))
				if e != nil {
					return e
				}
				if text(current["state"]) != "WAITING" {
					ready = contains([]string{"QUEUED", "RUNNING"}, text(current["state"]))
					return nil
				}
				source, e := s.row(ctx, tx, "source", map[string]any{"id": sourceID})
				if e != nil {
					return e
				}
				if hash(text(source["location"])) != text(job["binding"]) {
					return s.shareProgress(ctx, tx, job, "FAILED", "", 0, 0, "媒体来源根目录已变化")
				}
				files, e := s.rows(ctx, tx, "files-pendingSource", map[string]any{"sourceId": sourceID})
				if e != nil || scalar(files) > 0 {
					return e
				}
				a := map[string]any{"id": job["id"], "sourceId": sourceID}
				if _, e = s.exec(ctx, tx, "share-snapshot", a); e != nil {
					return e
				}
				if _, e = s.exec(ctx, tx, "share-count", a); e != nil {
					return e
				}
				ready = true
				return s.shareProgress(ctx, tx, job, "QUEUED", "", 0, 0, nil)
			})
			if e != nil {
				return e
			}
			if !ready {
				continue
			}
			job, e = s.shareJob(ctx, s.db, sourceID, text(job["id"]))
			if e != nil {
				return e
			}
		}
		return s.exportShare(ctx, job)
	}
	return nil
}
func (s *Service) cleanupShares(ctx context.Context, before string) error {
	expired, e := s.rows(ctx, s.db, "share-expired", map[string]any{"before": before})
	if e != nil {
		return e
	}
	for _, job := range expired {
		if e = s.transaction(ctx, func(tx store.DBTX) error {
			if _, e := s.exec(ctx, tx, "share-lock", nil); e != nil {
				return e
			}
			eligible, e := s.rows(ctx, tx, "share-expired", map[string]any{"before": before})
			if e != nil {
				return e
			}
			found := false
			for _, j := range eligible {
				if j["id"] == job["id"] {
					found = true
				}
			}
			if !found {
				return nil
			}
			folder, e := s.shareFolder(text(job["id"]))
			if e != nil {
				return e
			}
			if st, e := os.Lstat(folder); e == nil && st.Mode()&os.ModeSymlink != 0 {
				return bad("分享目录是符号链接")
			}
			if e = os.RemoveAll(folder); e != nil {
				return e
			}
			for _, q := range []string{"share-deleteEntries", "share-deleteAssets", "share-deleteJob"} {
				if _, e = s.exec(ctx, tx, q, map[string]any{"id": job["id"]}); e != nil {
					return e
				}
			}
			return nil
		}); e != nil {
			return e
		}
	}
	return nil
}

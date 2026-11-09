package library

import (
	"context"
	"database/sql"
	"errors"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

func (s *Service) scanLocal(parent context.Context, sourceID string) (result Scan, err error) {
	s.mu.Lock()
	if s.ctx.Err() != nil {
		s.mu.Unlock()
		return result, conflict("服务正在停止")
	}
	if s.running[sourceID] {
		s.mu.Unlock()
		return result, conflict("该媒体来源正在扫描，请稍后重试")
	}
	s.running[sourceID] = true
	s.mu.Unlock()
	defer func() {
		s.mu.Lock()
		delete(s.running, sourceID)
		s.mu.Unlock()
		status, detail := "SUCCEEDED", any(nil)
		if err != nil {
			status = "FAILED"
			detail = safeError(err)
		}
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_, _ = s.db.ExecContext(ctx, s.q["source-result"], now(), status, detail, sourceID)
	}()
	ctx, cancel := context.WithTimeout(parent, 30*time.Second)
	defer cancel()
	stop := context.AfterFunc(s.ctx, cancel)
	defer stop()
	src, e := s.findSource(ctx, s.db, sourceID)
	if e != nil {
		return result, e
	}
	if src.Type != "LOCAL" {
		return result, bad("WebDAV 请使用后台扫描任务入口")
	}
	st, e := s.state(ctx, s.db)
	if e != nil {
		return result, e
	}
	p, e := profile(st, src.ParserProfileID)
	if e != nil {
		return result, e
	}
	rules, e := resolve(st.doc, p.Entries)
	if e != nil {
		return result, e
	}
	real, e := filepath.EvalSymlinks(src.Location)
	info, statErr := os.Lstat(src.Location)
	if e != nil || statErr != nil || !info.IsDir() || filepath.Clean(real) != src.Location {
		return result, bad("本地目录已离线或路径发生变化，请检查挂载")
	}
	release, e := s.acquirePublication(ctx, sourceID)
	if e != nil {
		return result, e
	}
	defer release()
	result = Scan{SourceID: sourceID, ParserProfileID: p.ID, ParserProfileName: p.Name, ParserRevision: st.revision, ScannedAt: now()}
	s.hookMu.RLock()
	start, progress, finish, scope := s.localStart, s.localProgress, s.localFinish, s.localScope
	s.hookMu.RUnlock()
	taskID := ""
	if start != nil {
		taskID, e = start(ctx, sourceID)
		if e != nil {
			return result, e
		}
	}
	if finish != nil && taskID != "" {
		defer func() {
			endCtx, endCancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer endCancel()
			if finishErr := finish(endCtx, taskID, &result, err); finishErr != nil {
				s.log.Error("local scan finalization failed")
			}
		}()
	}
	songs := []Song{}
	visited := 0
	e = filepath.WalkDir(src.Location, func(path string, entry fs.DirEntry, walkErr error) error {
		if walkErr != nil {
			return bad("扫描未完成：目录或文件不可读取，原歌曲数据已保留")
		}
		if ctx.Err() != nil {
			return bad("扫描已取消或超过 30 秒，原歌曲数据已保留")
		}
		visited++
		if progress != nil && taskID != "" && visited%100 == 0 {
			if e := progress(ctx, taskID, "SCANNING", int64(visited), int64(len(songs)), nil, nil); e != nil {
				return e
			}
		}
		if visited > 20000 {
			return bad("目录超过单次扫描范围（20000 个条目 / 30 秒）")
		}
		relative, err := filepath.Rel(src.Location, path)
		if err != nil {
			return err
		}
		if entry.IsDir() {
			if entry.Name() == ".singverse-downloads" {
				return filepath.SkipDir
			}
			if len(strings.Split(relative, string(filepath.Separator))) > 32 {
				return bad("目录超过 32 层，请拆分媒体来源")
			}
			return nil
		}
		if entry.Type()&os.ModeSymlink != 0 || !supports(entry.Name()) {
			result.SkippedFiles++
			return nil
		}
		info, err := entry.Info()
		if err != nil {
			return err
		}
		if !info.Mode().IsRegular() {
			result.SkippedFiles++
			return nil
		}
		if len(songs) >= 2000 {
			return bad("单次最多扫描 2000 个媒体文件，请拆分来源")
		}
		relative = filepath.ToSlash(relative)
		if len([]rune(relative)) > 2048 {
			return bad("歌曲相对路径过长")
		}
		real, err := filepath.EvalSymlinks(path)
		if err != nil || !within(src.Location, real) {
			return bad("歌曲文件不可读或路径发生变化")
		}
		file, err := os.Open(path)
		if err != nil {
			return bad("歌曲文件不可读")
		}
		file.Close()
		parsed, err := Parse(entry.Name(), rules)
		if err != nil {
			return err
		}
		song := Song{ID: songID(sourceID, relative), SourceID: sourceID, SourceName: src.Name, RelativePath: relative, FileSize: ptr(info.Size()), ModifiedAt: ptr(info.ModTime().UTC().Format(time.RFC3339Nano)), ScannedAt: result.ScannedAt, ParserProfileID: p.ID, ParserRevision: st.revision, Result: parsed.Result, pathHash: hash(relative)}
		if parsed.SelectedRule != nil {
			song.SelectedRuleID = &parsed.SelectedRule.ID
			result.MatchedSongs++
		}
		if song.Result.NeedsReview {
			result.NeedsReviewSongs++
		}
		songs = append(songs, song)
		return nil
	})
	if e != nil {
		return result, e
	}
	result.TotalSongs = len(songs)
	s.hookMu.RLock()
	readTags := s.localTagReader
	s.hookMu.RUnlock()
	if readTags != nil {
		total := int64(len(songs))
		for i := range songs {
			if e = ctx.Err(); e != nil {
				return result, bad("读取文件标签超出单次扫描范围，原歌曲索引保留")
			}
			tags, readErr := readTags(ctx, sourceID, songs[i].RelativePath)
			file := filepath.Join(src.Location, filepath.FromSlash(songs[i].RelativePath))
			after, statErr := os.Lstat(file)
			real, realErr := filepath.EvalSymlinks(file)
			if statErr != nil || realErr != nil || !after.Mode().IsRegular() || !within(src.Location, real) || songs[i].FileSize == nil || after.Size() != *songs[i].FileSize || songs[i].ModifiedAt == nil || after.ModTime().UTC().Format(time.RFC3339Nano) != *songs[i].ModifiedAt {
				return result, conflict("扫描期间文件已变化，原歌曲索引保留，请重试")
			}
			if readErr == nil && validEmbeddedTags(tags) {
				songs[i].embedded = ptr(encode(tags))
				if songs[i].Result.NeedsReview && embeddedIdentity(tags) {
					result.NeedsReviewSongs--
				}
			}
			if progress != nil && taskID != "" {
				done := int64(i + 1)
				if e = progress(ctx, taskID, "READING_TAGS", int64(visited), total, &done, &total); e != nil {
					return result, e
				}
			}
		}
	}
	if progress != nil && taskID != "" {
		total := int64(len(songs))
		if e = progress(ctx, taskID, "COMMITTING", int64(visited), total, &total, &total); e != nil {
			return result, e
		}
	}
	err = s.transaction(ctx, func(tx store.DBTX) error {
		if _, e := tx.ExecContext(ctx, s.q["config-lock"]); e != nil {
			return e
		}
		current, e := s.findSource(ctx, tx, sourceID)
		if e != nil {
			return e
		}
		latest, e := s.state(ctx, tx)
		if e != nil {
			return e
		}
		if current.UpdatedAt != src.UpdatedAt || current.Location != src.Location || current.ParserProfileID != src.ParserProfileID || latest.revision != st.revision {
			return conflict("媒体来源或解析方案已修改，本次扫描未保存，请重新扫描")
		}
		if e = changed(tx.ExecContext(ctx, s.q["source-touch"], now(), sourceID, src.UpdatedAt)); e != nil {
			return e
		}
		if _, e = tx.ExecContext(ctx, s.q["song-delete-source"], sourceID); e != nil {
			return e
		}
		for _, song := range songs {
			if _, e = tx.ExecContext(ctx, s.q["song-insert"], song.ID, song.SourceID, song.RelativePath, song.pathHash, song.FileSize, song.ModifiedAt, song.ScannedAt, song.ParserProfileID, song.ParserRevision, song.SelectedRuleID, encode(song.Result), song.Result.NeedsReview, song.embedded); e != nil {
				return e
			}
		}
		if _, e = tx.ExecContext(ctx, s.q["song-prune-metadata"], sourceID, sourceID); e != nil {
			return e
		}
		if e = s.indexChanged(ctx, tx, sourceID); e != nil {
			return e
		}
		if scope != nil && taskID != "" {
			return scope(ctx, tx, taskID, sourceID)
		}
		return nil
	})
	return result, err
}
func within(root, path string) bool {
	relative, e := filepath.Rel(root, path)
	return e == nil && relative != ".." && !strings.HasPrefix(relative, ".."+string(filepath.Separator)) && !filepath.IsAbs(relative)
}
func taskRow(row scanner) (ScanTask, error) {
	var stored scanRecord
	err := row.StructScan(&stored)
	return stored.model(), err
}
func (s *Service) task(ctx context.Context, sourceID, id string) (ScanTask, error) {
	t, e := taskRow(s.db.QueryRowxContext(ctx, s.q["scan-find"], sourceID, id))
	if e != nil && errors.Is(e, sql.ErrNoRows) {
		return t, missing("扫描任务不存在")
	}
	return t, e
}
func (s *Service) createTask(c *gin.Context) {
	t, e := s.startTask(c.Request.Context(), c.Param("id"))
	if e != nil {
		s.fail(c, e)
		return
	}
	c.Header("Location", "/api/v1/media-sources/"+t.SourceID+"/scan-tasks/"+t.ID)
	c.JSON(202, t)
}
func (s *Service) latestTask(c *gin.Context) {
	ctx := c.Request.Context()
	if _, e := s.findSource(ctx, s.db, c.Param("id")); e != nil {
		s.fail(c, e)
		return
	}
	t, e := taskRow(s.db.QueryRowxContext(ctx, s.q["scan-latest"], c.Param("id")))
	if errors.Is(e, sql.ErrNoRows) {
		c.Status(204)
		return
	}
	if e != nil {
		s.fail(c, e)
		return
	}
	c.JSON(200, t)
}
func (s *Service) getTask(c *gin.Context) {
	if _, e := s.findSource(c.Request.Context(), s.db, c.Param("id")); e != nil {
		s.fail(c, e)
		return
	}
	t, e := s.task(c.Request.Context(), c.Param("id"), c.Param("taskId"))
	if e != nil {
		s.fail(c, e)
		return
	}
	c.JSON(200, t)
}
func (s *Service) cancelTask(c *gin.Context) {
	ctx := c.Request.Context()
	sourceID, id := c.Param("id"), c.Param("taskId")
	if _, e := s.findSource(ctx, s.db, sourceID); e != nil {
		s.fail(c, e)
		return
	}
	if _, e := s.task(ctx, sourceID, id); e != nil {
		s.fail(c, e)
		return
	}
	s.mu.Lock()
	e := changed(s.db.ExecContext(ctx, s.q["scan-cancel"], id, sourceID))
	if e == nil {
		if cancel := s.tasks[id]; cancel != nil {
			cancel()
		}
	}
	s.mu.Unlock()
	if e != nil {
		s.fail(c, conflict("任务已经结束或正在提交索引，无法取消"))
		return
	}
	t, e := s.task(ctx, sourceID, id)
	if e != nil {
		s.fail(c, e)
		return
	}
	c.JSON(200, t)
}
func (s *Service) startTask(ctx context.Context, sourceID string) (ScanTask, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.ctx.Err() != nil {
		return ScanTask{}, conflict("服务正在停止，请稍后重试")
	}
	id := uuid()
	work := scanWork{id: id}
	var t ScanTask
	e := store.WithinTx(ctx, s.db, func(tx store.DBTX) error {
		src, e := s.findSource(ctx, tx, sourceID)
		if e != nil {
			return e
		}
		if src.Type != "WEBDAV" {
			return bad("本地目录请使用原扫描入口")
		}
		var active, total int
		if e = tx.QueryRowContext(ctx, s.q["scan-active"], sourceID).Scan(&active); e != nil {
			return e
		}
		if active > 0 {
			return conflict("该媒体来源正在扫描")
		}
		if e = tx.QueryRowContext(ctx, s.q["scan-count"]).Scan(&total); e != nil {
			return e
		}
		if total >= 34 {
			return conflict("后台扫描队列已满，请稍后重试")
		}
		st, e := s.state(ctx, tx)
		if e != nil {
			return e
		}
		p, e := profile(st, src.ParserProfileID)
		if e != nil {
			return e
		}
		rules, e := resolve(st.doc, p.Entries)
		if e != nil {
			return e
		}
		if _, e = tx.ExecContext(ctx, s.q["scan-insert"], id, sourceID, sourceID, now()); e != nil {
			return e
		}
		work.source = src
		work.state = st
		work.profile = p
		work.rules = rules
		t, e = taskRow(tx.QueryRowxContext(ctx, s.q["scan-find"], sourceID, id))
		if e != nil {
			return e
		}
		return s.project(ctx, tx, id)
	})
	if e != nil {
		return t, e
	}
	work.ctx, work.cancel = context.WithTimeout(s.ctx, 2*time.Hour)
	s.tasks[id] = work.cancel
	select {
	case s.queue <- work:
		return t, nil
	default:
		work.cancel()
		delete(s.tasks, id)
		_, _ = s.db.ExecContext(context.Background(), s.q["scan-finish"], "FAILED", "后台扫描队列已满，请重新扫描", now(), id)
		return t, conflict("后台扫描队列已满，请稍后重试")
	}
}
func (s *Service) worker() {
	defer s.wg.Done()
	for {
		select {
		case <-s.ctx.Done():
			return
		case work := <-s.queue:
			s.runWebDAV(work)
		}
	}
}
func (s *Service) finishTask(work scanWork, status string, err error) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	detail := any(nil)
	if err != nil {
		detail = safeError(err)
	}
	_ = store.WithinTx(ctx, s.db, func(tx store.DBTX) error {
		if _, e := tx.ExecContext(ctx, s.q["scan-finish"], status, detail, now(), work.id); e != nil {
			return e
		}
		if _, e := tx.ExecContext(ctx, s.q["source-result"], now(), status, detail, work.source.ID); e != nil {
			return e
		}
		for _, q := range []string{"scan-clear", "scan-clear-dirs"} {
			if _, e := tx.ExecContext(ctx, s.q[q], work.id); e != nil {
				return e
			}
		}
		return s.project(ctx, tx, work.id)
	})
}
func (s *Service) runWebDAV(work scanWork) {
	defer func() { work.cancel(); s.mu.Lock(); delete(s.tasks, work.id); s.mu.Unlock() }()
	err := s.traverse(work)
	if err == nil {
		return
	}
	status := "FAILED"
	if s.ctx.Err() != nil {
		status = "INTERRUPTED"
	} else if work.ctx.Err() != nil {
		if errors.Is(work.ctx.Err(), context.Canceled) {
			status = "CANCELLED"
			err = bad("扫描已取消，旧索引已保留")
		} else {
			err = bad("WebDAV 扫描超过两小时，请拆分来源")
		}
	}
	s.finishTask(work, status, err)
}
func (s *Service) checkPublication(ctx context.Context, tx store.DBTX, work scanWork) error {
	current, e := s.findSource(ctx, tx, work.source.ID)
	if e != nil {
		return e
	}
	latest, e := s.state(ctx, tx)
	if e != nil {
		return e
	}
	if current.UpdatedAt != work.source.UpdatedAt || current.Location != work.source.Location || current.ParserProfileID != work.source.ParserProfileID || latest.revision != work.state.revision {
		return conflict("媒体来源或解析方案已修改，本次扫描未保存，请重新扫描")
	}
	return nil
}

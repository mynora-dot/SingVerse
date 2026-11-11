package metadata

import (
	"context"
	"errors"
	"io"
	"os"
	pathpkg "path"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/library"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/metastandard"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

func (s *Service) importFolder(id string) (string, error) {
	if !uuidPattern.MatchString(id) {
		return "", bad("导入任务标识无效")
	}
	return managedDirectory(s.cfg.MetadataPath, "imports", id)
}
func (s *Service) uploadImport(c *gin.Context) {
	ctx := c.Request.Context()
	sourceID := c.Param("id")
	source, e := s.row(ctx, s.db, "source", map[string]any{"id": sourceID})
	if e != nil {
		s.fail(c, absent(e, "媒体来源不存在"))
		return
	}
	if text(source["source_type"]) != "WEBDAV" && text(source["source_type"]) != "LOCAL" {
		s.fail(c, bad("请选择自己的媒体来源"))
		return
	}
	id := uuid()
	folder, e := s.importFolder(id)
	if e != nil {
		s.fail(c, e)
		return
	}
	ready := false
	defer func() {
		if !ready {
			_ = os.RemoveAll(folder)
		}
	}()
	f, e := os.OpenFile(filepath.Join(folder, "upload.tmp"), os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
	if e != nil {
		s.fail(c, e)
		return
	}
	n, e := io.Copy(f, io.LimitReader(c.Request.Body, uploadLimit+1))
	if e == nil {
		e = f.Sync()
	}
	closeErr := f.Close()
	if e == nil {
		e = closeErr
	}
	if e != nil || ctx.Err() != nil {
		if e == nil {
			e = ctx.Err()
		}
		s.fail(c, e)
		return
	}
	if n > uploadLimit {
		s.fail(c, &problem{413, "分享包超过 8 GiB"})
		return
	}
	if e = os.Rename(filepath.Join(folder, "upload.tmp"), filepath.Join(folder, "bundle.zip")); e != nil {
		s.fail(c, e)
		return
	}
	e = s.transaction(ctx, func(tx store.DBTX) error {
		current, e := s.row(ctx, tx, "source", map[string]any{"id": sourceID})
		if e != nil {
			return e
		}
		if text(current["location"]) != text(source["location"]) {
			return conflict("上传期间媒体根目录已变化，请重新选择来源")
		}
		rows, e := s.rows(ctx, tx, "workflow-active", map[string]any{"sourceId": sourceID})
		if e != nil {
			return e
		}
		if scalar(rows) > 0 {
			return conflict("此来源已有导入任务，请先完成或取消")
		}
		if _, e = s.exec(ctx, tx, "workflow-insert", map[string]any{"id": id, "sourceId": sourceID, "binding": hash(text(source["location"])), "now": now()}); e != nil {
			return e
		}
		return s.project(ctx, tx, "SHARE_IMPORT", id)
	})
	if e != nil {
		s.fail(c, e)
		return
	}
	ready = true
	c.Header("Location", "/api/v1/media-sources/"+sourceID+"/embedded-metadata/import-jobs/"+id)
	s.respondImport(c, sourceID, id, 202)
}
func (s *Service) respondImport(c *gin.Context, sourceID, id string, status int) {
	job, e := s.importJob(c.Request.Context(), sourceID, id)
	if e != nil {
		s.fail(c, e)
		return
	}
	c.JSON(status, job)
}
func (s *Service) importJob(ctx context.Context, sourceID, id string) (map[string]any, error) {
	job, e := s.row(ctx, s.db, "workflow-read", map[string]any{"id": id, "sourceId": sourceID})
	if e != nil {
		return nil, absent(e, "导入任务不存在")
	}
	source, e := s.row(ctx, s.db, "source", map[string]any{"id": sourceID})
	if e != nil {
		return nil, absent(e, "媒体来源不存在")
	}
	samples, e := s.rows(ctx, s.db, "workflow-entries", map[string]any{"id": id, "cursor": 0})
	if e != nil {
		return nil, e
	}
	preview := []any{}
	for i, entry := range samples {
		if i >= 5 {
			break
		}
		url, e := library.MediaURL(text(source["location"]), text(entry["relative_path"]))
		if text(source["source_type"]) == "LOCAL" {
			url = filepath.Join(text(source["location"]), filepath.FromSlash(text(entry["relative_path"])))
			e = nil
		}
		if e != nil {
			return nil, e
		}
		sample := map[string]any{"originalPath": entry["original_path"], "relativePath": entry["relative_path"], "mediaURL": url}
		snapshot := map[string]any{}
		if e = decode(entry["snapshot_json"], &snapshot); e != nil {
			return nil, e
		}
		sample["incoming"] = snapshot["tags"]
		found, e := s.rows(ctx, s.db, "workflow-previewSong", map[string]any{"sourceId": sourceID, "hash": hash(text(entry["relative_path"])), "path": entry["relative_path"]})
		if e != nil {
			return nil, e
		}
		if len(found) > 0 {
			song, e := s.song(ctx, s.db, text(found[0]["id"]))
			if e != nil {
				return nil, e
			}
			sample["local"] = current(song)
		}
		preview = append(preview, sample)
	}
	job["samples"] = preview
	counts, e := s.row(ctx, s.db, "workflow-previewCounts", map[string]any{"id": id, "sourceId": sourceID})
	if e != nil {
		return nil, e
	}
	job["preview"] = counts
	// Credential and opaque binding digests are not useful in the product.
	delete(job, "binding")
	return importDTO(job), nil
}
func (s *Service) getImport(c *gin.Context) { s.respondImport(c, c.Param("id"), c.Param("jobId"), 200) }
func (s *Service) latestImport(c *gin.Context) {
	if _, e := s.row(c.Request.Context(), s.db, "source", map[string]any{"id": c.Param("id")}); e != nil {
		s.fail(c, absent(e, "媒体来源不存在"))
		return
	}
	rows, e := s.rows(c.Request.Context(), s.db, "workflow-latest", map[string]any{"sourceId": c.Param("id")})
	if e != nil {
		s.fail(c, e)
		return
	}
	if len(rows) == 0 {
		c.JSON(200, map[string]any{})
		return
	}
	s.respondImport(c, c.Param("id"), text(rows[0]["id"]), 200)
}
func (s *Service) importDifferences(c *gin.Context) {
	if _, e := s.row(c.Request.Context(), s.db, "workflow-read", map[string]any{"id": c.Param("jobId"), "sourceId": c.Param("id")}); e != nil {
		s.fail(c, absent(e, "导入任务不存在"))
		return
	}
	cursor := int64(0)
	if c.Query("cursor") != "" {
		var e error
		cursor, e = parseCursor(c.Query("cursor"))
		if e != nil {
			s.fail(c, e)
			return
		}
	}
	rows, e := s.rows(c.Request.Context(), s.db, "workflow-differences", map[string]any{"id": c.Param("jobId"), "cursor": cursor})
	if e != nil {
		s.fail(c, e)
		return
	}
	for _, row := range rows {
		var diff []string
		_ = decode(row["differences_json"], &diff)
		row["fields"] = diff
		delete(row, "differences_json")
		snapshot := map[string]any{}
		_ = decode(row["snapshot_json"], &snapshot)
		delete(row, "snapshot_json")
		row["incoming"] = snapshot["tags"]
		if song, e := s.song(c.Request.Context(), s.db, text(row["song_id"])); e == nil {
			row["local"] = current(song)
		}
	}
	next := cursor
	if len(rows) > 0 {
		next = number(rows[len(rows)-1]["ordinal"])
	}
	for i, row := range rows {
		rows[i] = importDTO(row)
	}
	c.JSON(200, map[string]any{"items": rows, "nextCursor": next, "hasMore": len(rows) == 100})
}
func (s *Service) mapImport(c *gin.Context) {
	var in struct {
		RemovePrefix string `json:"removePrefix"`
		AddPrefix    string `json:"addPrefix"`
	}
	if !s.decode(c, &in) {
		return
	}
	e := s.transaction(c.Request.Context(), func(tx store.DBTX) error {
		ctx := c.Request.Context()
		job, e := s.row(ctx, tx, "workflow-read", map[string]any{"id": c.Param("jobId"), "sourceId": c.Param("id")})
		if e != nil {
			return e
		}
		if text(job["state"]) != "PREVIEW" {
			return conflict("只有预览中的任务可以调整目录")
		}
		cursor := int64(0)
		matched := in.RemovePrefix == ""
		for {
			rows, e := s.rows(ctx, tx, "workflow-entries", map[string]any{"id": job["id"], "cursor": cursor})
			if e != nil {
				return e
			}
			if len(rows) == 0 {
				break
			}
			for _, entry := range rows {
				original := text(entry["original_path"])
				matched = matched || strings.HasPrefix(original, in.RemovePrefix+"/")
				relative, e := mapSharedPath(original, in.RemovePrefix, in.AddPrefix)
				if e != nil {
					return e
				}
				if _, e = s.exec(ctx, tx, "workflow-entryMap", map[string]any{"id": job["id"], "ordinal": entry["ordinal"], "path": relative, "hash": hash(relative)}); e != nil {
					return e
				}
				cursor = number(entry["ordinal"])
			}
		}
		if !matched {
			return bad("没有找到此目录前缀，请核对包内路径")
		}
		duplicates, e := s.rows(ctx, tx, "workflow-mappingDuplicates", map[string]any{"id": job["id"]})
		if e != nil {
			return e
		}
		if len(duplicates) > 0 {
			return bad("调整后有多个媒体指向同一路径，请修改目录前缀")
		}
		_, e = s.exec(ctx, tx, "workflow-mapping", map[string]any{"id": job["id"], "removePrefix": in.RemovePrefix, "addPrefix": in.AddPrefix, "now": now()})
		return e
	})
	if e != nil {
		s.fail(c, e)
		return
	}
	s.respondImport(c, c.Param("id"), c.Param("jobId"), 200)
}
func (s *Service) importAction(c *gin.Context) {
	ctx := c.Request.Context()
	action := c.Param("action")
	e := s.transaction(ctx, func(tx store.DBTX) error {
		job, e := s.row(ctx, tx, "workflow-read", map[string]any{"id": c.Param("jobId"), "sourceId": c.Param("id")})
		if e != nil {
			return absent(e, "导入任务不存在")
		}
		state := text(job["state"])
		phase := text(job["phase"])
		switch action {
		case "apply":
			if state != "PREVIEW" {
				return conflict("请等待解析完成并检查目录预览")
			}
			if e = s.validateImportBinding(ctx, tx, job); e != nil {
				return e
			}
			if e = s.checkSharedBaseline(ctx, tx, c.Param("id"), jobManifest(job)); e != nil {
				return e
			}
			state = "QUEUED"
			phase = "APPLYING"
		case "cancel":
			if state == "SUCCEEDED" || state == "CANCELLED" {
				return conflict("任务已经结束")
			}
			state = "CANCELLED"
		case "retry":
			others, e := s.rows(ctx, tx, "workflow-otherActive", map[string]any{"sourceId": c.Param("id"), "id": job["id"]})
			if e != nil {
				return e
			}
			if scalar(others) > 0 {
				return conflict("此来源已有其他导入任务，请先完成或取消")
			}
			if state != "FAILED" && state != "CANCELLED" && state != "SUCCEEDED" {
				return conflict("任务仍在处理")
			}
			if e = s.validateImportBinding(ctx, tx, job); e != nil {
				return e
			}
			if phase == "VERIFYING" {
				state = "VERIFYING"
				if _, e = s.exec(ctx, tx, "workflow-retryVerify", map[string]any{"id": job["id"]}); e != nil {
					return e
				}
			} else if phase == "APPLYING" {
				state = "QUEUED"
			} else {
				state = "PARSING"
				phase = "PARSING"
			}
		default:
			return bad("未知导入操作")
		}
		return s.setImportState(ctx, tx, text(job["id"]), state, phase, nil)
	})
	if e != nil {
		s.fail(c, e)
		return
	}
	s.respondImport(c, c.Param("id"), c.Param("jobId"), 200)
}
func jobManifest(job map[string]any) map[string]any {
	version := number(job["package_version"])
	if version == 0 {
		version = 3
	}
	rev := any(job["collection_revision"])
	if version == 4 {
		rev = strconv.FormatInt(number(job["collection_revision"]), 10)
	}
	return map[string]any{"version": version, "collectionId": job["collection_id"], "collectionRevision": rev, "setId": job["snapshot_id"], "baseId": job["base_id"], "contentDigest": job["content_digest"]}
}
func (s *Service) validateImportBinding(ctx context.Context, tx store.DBTX, job map[string]any) error {
	source, e := s.row(ctx, tx, "source", map[string]any{"id": job["source_id"]})
	if e != nil {
		return absent(e, "媒体来源不存在")
	}
	if hash(text(source["location"])) != text(job["binding"]) {
		return conflict("媒体根目录已变化，请重新创建导入任务")
	}
	return nil
}
func (s *Service) setImportState(ctx context.Context, tx store.DBTX, id, state, phase string, detail any) error {
	if _, e := s.exec(ctx, tx, "workflow-state", map[string]any{"id": id, "state": state, "phase": phase, "error": detail, "now": now()}); e != nil {
		return e
	}
	return s.project(ctx, tx, "SHARE_IMPORT", id)
}
func (s *Service) importLoop() {
	defer s.wg.Done()
	timer := time.NewTicker(2 * time.Second)
	defer timer.Stop()
	for {
		select {
		case <-s.ctx.Done():
			return
		case <-timer.C:
			if e := s.ProcessImports(s.ctx); e != nil && s.ctx.Err() == nil {
				s.log.Error("metadata import worker failed")
			}
		}
	}
}

// ProcessImports is restart-safe: previews may be regenerated; applied chunks
// and their outcomes commit together and are never replayed after a crash.
func (s *Service) ProcessImports(ctx context.Context) error {
	s.importMu.Lock()
	defer s.importMu.Unlock()
	jobs, e := s.rows(ctx, s.db, "workflow-pending", nil)
	if e != nil {
		return e
	}
	for _, job := range jobs {
		if e = ctx.Err(); e != nil {
			return e
		}
		switch text(job["state"]) {
		case "PARSING":
			e = s.prepareImport(ctx, job)
		case "QUEUED", "RUNNING":
			e = s.applyImport(ctx, job)
		case "VERIFYING":
			e = s.verifyImport(ctx, job)
		}
		if e != nil {
			if ctx.Err() != nil {
				return ctx.Err()
			}
			fresh, readErr := s.row(ctx, s.db, "workflow-read", map[string]any{"id": job["id"], "sourceId": job["source_id"]})
			if readErr != nil {
				return readErr
			}
			if text(fresh["state"]) != "CANCELLED" {
				detail := "资料处理失败，可重试，已完成的资料会保留"
				var p *problem
				if errors.As(e, &p) {
					detail = p.detail
				}
				if e = s.transaction(ctx, func(tx store.DBTX) error {
					return s.setImportState(ctx, tx, text(job["id"]), "FAILED", text(fresh["phase"]), detail)
				}); e != nil {
					return e
				}
			}
		}
	}
	return nil
}
func (s *Service) prepareImport(ctx context.Context, job map[string]any) error {
	if e := s.validateImportBinding(ctx, s.db, job); e != nil {
		return e
	}
	id := text(job["id"])
	folder, e := s.importFolder(id)
	if e != nil {
		return e
	}
	if _, e = s.exec(ctx, s.db, "workflow-clearEntries", map[string]any{"id": id}); e != nil {
		return e
	}
	total := int64(0)
	manifest := map[string]any{}
	batch := []map[string]any{}
	flush := func() error {
		return s.transaction(ctx, func(tx store.DBTX) error {
			fresh, e := s.row(ctx, tx, "workflow-read", map[string]any{"id": id, "sourceId": job["source_id"]})
			if e != nil {
				return e
			}
			if text(fresh["state"]) != "PARSING" {
				return conflict("导入任务已停止")
			}
			for _, a := range batch {
				if _, e = s.exec(ctx, tx, "workflow-entryInsert", a); e != nil {
					return bad("资料包包含重复或无效媒体路径")
				}
			}
			batch = nil
			_, e = s.exec(ctx, tx, "workflow-total", map[string]any{"id": id, "total": total})
			return e
		})
	}
	e = visitValidatedShareFile(filepath.Join(folder, "bundle.zip"), func(doc map[string]any) error {
		if e := ctx.Err(); e != nil {
			return e
		}
		doc["_leaseOwner"] = id
		if e := s.normalizeSharedAssets(doc); e != nil {
			return e
		}
		if len(manifest) == 0 {
			manifest = doc
		} else if text(manifest["collectionId"]) != text(doc["collectionId"]) || text(manifest["setId"]) != text(doc["setId"]) {
			return bad("资料分包不属于同一份快照")
		}
		for _, raw := range array(doc["entries"]) {
			item := raw.(map[string]any)
			size, e := shareSize(item["size"])
			if e != nil {
				return e
			}
			total++
			if total > 100000 {
				return bad("单次最多导入 10 万首歌曲")
			}
			batch = append(batch, map[string]any{"id": id, "ordinal": total, "path": item["path"], "hash": hash(text(item["path"])), "size": size, "snapshot": encode(item["metadata"])})
			if len(batch) >= 100 {
				if e = flush(); e != nil {
					return e
				}
			}
		}
		return nil
	})
	if e != nil {
		return e
	}
	if e = flush(); e != nil {
		return e
	}
	return s.transaction(ctx, func(tx store.DBTX) error {
		fresh, e := s.row(ctx, tx, "workflow-read", map[string]any{"id": id, "sourceId": job["source_id"]})
		if e != nil {
			return e
		}
		if text(fresh["state"]) != "PARSING" {
			return conflict("导入任务已停止")
		}
		if e = s.checkSharedBaseline(ctx, tx, text(job["source_id"]), manifest); e != nil {
			return e
		}
		collection, revision, e := sharedIdentity(manifest)
		if e != nil {
			return e
		}
		complete := 0
		if manifest["completeSnapshot"] == true {
			complete = 1
		}
		if _, err := s.exec(ctx, tx, "v4-workflow-version", map[string]any{"id": id, "version": number(manifest["version"]), "digest": text(manifest["contentDigest"])}); err != nil {
			return err
		}
		if _, e = s.exec(ctx, tx, "workflow-manifest", map[string]any{"id": id, "collectionId": collection, "revision": revision, "snapshotId": text(manifest["setId"]), "baseId": text(manifest["baseId"]), "deletions": number(manifest["deletionHints"]), "complete": complete}); e != nil {
			return e
		}
		return s.setImportState(ctx, tx, id, "PREVIEW", "PREVIEW", nil)
	})
}

func (s *Service) applyImport(ctx context.Context, job map[string]any) error {
	id := text(job["id"])
	for {
		done := false
		e := s.transaction(ctx, func(tx store.DBTX) error {
			current, e := s.row(ctx, tx, "workflow-read", map[string]any{"id": id, "sourceId": job["source_id"]})
			if e != nil {
				return e
			}
			if state := text(current["state"]); state != "QUEUED" && state != "RUNNING" {
				return conflict("导入任务已停止")
			}
			if e = s.validateImportBinding(ctx, tx, current); e != nil {
				return e
			}
			if e = s.checkSharedBaseline(ctx, tx, text(job["source_id"]), jobManifest(job)); e != nil {
				return e
			}
			entries, e := s.rows(ctx, tx, "workflow-unapplied", map[string]any{"id": id})
			if e != nil {
				return e
			}
			if len(entries) == 0 {
				done = true
				if number(current["complete_snapshot"]) != 0 {
					if e = s.saveSharedBaseline(ctx, tx, text(job["source_id"]), jobManifest(job)); e != nil {
						return e
					}
				}
				return s.setImportState(ctx, tx, id, "VERIFYING", "VERIFYING", nil)
			}
			for _, entry := range entries {
				var size *int64
				if entry["file_size"] != nil {
					n := number(entry["file_size"])
					size = &n
				}
				snapshot := map[string]any{}
				if e = decode(entry["snapshot_json"], &snapshot); e != nil {
					return e
				}
				preferred, e := s.preferredMedia(ctx, tx, text(job["source_id"]), text(job["collection_id"]), snapshot)
				if e != nil {
					return e
				}
				songID, added, e := s.library.ImportPortableSong(ctx, tx, text(job["source_id"]), text(entry["relative_path"]), size, preferred)
				if e != nil {
					return e
				}
				if !added {
					exists, e := s.rows(ctx, tx, "shared-songExists", map[string]any{"songId": songID})
					if e != nil {
						return e
					}
					if scalar(exists) == 0 {
						if _, e = s.exec(ctx, tx, "workflow-entryApplied", map[string]any{"id": id, "ordinal": entry["ordinal"], "songId": nil, "outcome": "UNCHANGED", "differences": "[]"}); e != nil {
							return e
						}
						if _, e = s.exec(ctx, tx, "workflow-entryVerified", map[string]any{"id": id, "ordinal": entry["ordinal"], "verification": "MISSING", "verificationError": "此路径已归档，保留原归档状态"}); e != nil {
							return e
						}
						continue
					}
				}
				outcome, diff, e := s.mergeShared(ctx, tx, songID, snapshot, text(job["collection_id"]), number(job["collection_revision"]), size)
				if e != nil {
					return e
				}
				if added {
					outcome = "ADDED"
				}
				if e = s.importAudioClaim(ctx, tx, songID, snapshot); e != nil {
					return e
				}
				if _, e = s.exec(ctx, tx, "workflow-entryApplied", map[string]any{"id": id, "ordinal": entry["ordinal"], "songId": songID, "outcome": outcome, "differences": encode(diff)}); e != nil {
					return e
				}
			}
			if e = s.importCounters(ctx, tx, id); e != nil {
				return e
			}
			return s.setImportState(ctx, tx, id, "RUNNING", "APPLYING", nil)
		})
		if e != nil {
			return e
		}
		if done {
			return nil
		}
	}
}
func (s *Service) importCounters(ctx context.Context, tx store.DBTX, id string) error {
	counts, e := s.row(ctx, tx, "workflow-counters", map[string]any{"id": id})
	if e != nil {
		return e
	}
	for k, v := range counts {
		if v == nil {
			counts[k] = int64(0)
		}
	}
	counts["id"] = id
	counts["now"] = now()
	_, e = s.exec(ctx, tx, "workflow-saveCounters", counts)
	return e
}
func (s *Service) verifyImport(ctx context.Context, job map[string]any) error {
	id := text(job["id"])
	entries, e := s.rows(ctx, s.db, "workflow-verifyAll", map[string]any{"id": id})
	if e != nil {
		return e
	}
	for offset := 0; offset < len(entries); {
		if e = s.validateImportBinding(ctx, s.db, job); e != nil {
			return e
		}
		folder := pathpkg.Dir(text(entries[offset]["relative_path"]))
		if folder == "." {
			folder = ""
		}
		end := offset + 1
		for end < len(entries) && pathpkg.Dir(text(entries[end]["relative_path"])) == pathpkg.Dir(text(entries[offset]["relative_path"])) {
			end++
		}
		fresh, e := s.row(ctx, s.db, "workflow-read", map[string]any{"id": id, "sourceId": job["source_id"]})
		if e != nil {
			return e
		}
		if text(fresh["state"]) != "VERIFYING" {
			return conflict("核验已停止")
		}
		observed, missing, listErr := s.library.MediaDirectory(ctx, text(job["source_id"]), folder)
		if ctx.Err() != nil {
			return ctx.Err()
		}
		for start := offset; start < end; {
			stop := start + 100
			if stop > end {
				stop = end
			}
			e = s.transaction(ctx, func(tx store.DBTX) error {
				fresh, e := s.row(ctx, tx, "workflow-read", map[string]any{"id": id, "sourceId": job["source_id"]})
				if e != nil {
					return e
				}
				if text(fresh["state"]) != "VERIFYING" {
					return conflict("核验已停止")
				}
				if e = s.validateImportBinding(ctx, tx, job); e != nil {
					return e
				}
				for _, entry := range entries[start:stop] {
					state := "PRESENT"
					size, found := observed[text(entry["relative_path"])]
					if listErr != nil {
						state = "UNREACHABLE"
						if missing {
							state = "MISSING"
						}
					} else if !found {
						state = "MISSING"
					} else if size != nil && entry["file_size"] != nil && *size != number(entry["file_size"]) {
						state = "MISMATCH"
					}
					a := map[string]any{"id": id, "ordinal": entry["ordinal"], "songId": entry["song_id"], "verification": state, "verificationError": verificationMessage(listErr), "now": now()}
					if _, e = s.exec(ctx, tx, "workflow-entryVerified", a); e != nil {
						return e
					}
					if _, e = s.exec(ctx, tx, "shared-verification", a); e != nil {
						return e
					}
					if e = s.library.Formal().Location(ctx, tx, text(entry["song_id"]), text(job["source_id"]), text(entry["relative_path"]), "", state, map[string]any{"size": size}); e != nil {
						return e
					}
				}
				if e = s.importCounters(ctx, tx, id); e != nil {
					return e
				}
				return s.project(ctx, tx, "SHARE_IMPORT", id)
			})
			if e != nil {
				return e
			}
			start = stop
		}
		offset = end
	}
	return s.transaction(ctx, func(tx store.DBTX) error {
		current, e := s.row(ctx, tx, "workflow-read", map[string]any{"id": id, "sourceId": job["source_id"]})
		if e != nil {
			return e
		}
		if text(current["state"]) != "VERIFYING" {
			return conflict("核验已停止")
		}
		if e = s.importCounters(ctx, tx, id); e != nil {
			return e
		}
		return s.setImportState(ctx, tx, id, "SUCCEEDED", "VERIFYING", nil)
	})
}

// visitSharePackage validates the outer container before processing bounded
// inner manifests. No filename from a ZIP is ever extracted as a local path.
func visitSharePackage(filename string, visit func(map[string]any) error) error {
	f, e := os.Open(filename)
	if e != nil {
		return e
	}
	defer f.Close()
	st, e := f.Stat()
	if e != nil {
		return e
	}
	if st.Size() > uploadLimit {
		return &problem{413, "分享包超过 8 GiB"}
	}
	z, e := checkedZip(f, st.Size(), 30001)
	if e != nil {
		return e
	}
	if len(z.File) == 0 {
		return bad("分享包为空")
	}
	if z.File[0].Name != "index.json" {
		if st.Size() > shareLimit {
			return &problem{413, "索引包最多 32 MiB"}
		}
		b, e := io.ReadAll(io.LimitReader(f, shareLimit+1))
		if e != nil {
			return e
		}
		doc, e := readManifest(b)
		if e != nil {
			return e
		}
		doc["completeSnapshot"] = doc["part"] == nil
		return visit(doc)
	}
	b, e := readZipFile(z.File[0], 1024*1024)
	if e != nil {
		return e
	}
	rawIndex, e := metastandard.Parse(b)
	if e != nil {
		return bad(e.Error())
	}
	index, ok := rawIndex.(map[string]any)
	if !ok || index["format"] != "singverse-share-set" || (number(index["version"]) != 2 && number(index["version"]) != 3 && number(index["version"]) != 4) {
		return bad("分享清单无效")
	}
	if number(index["version"]) == 4 {
		if e = checkV4Index(index); e != nil {
			return e
		}
	}
	if number(index["version"]) >= 3 && text(index["state"]) != "SUCCEEDED" {
		return bad("资料快照未完整导出，请重新获取完整 ZIP")
	}
	expected := []string{}
	known := map[string]bool{"index.json": true}
	advisory := map[string]bool{}
	for _, v := range array(index["parts"]) {
		name, ok := v.(string)
		if !ok || !partName.MatchString(name) || known[name] {
			return bad("分享分包清单无效")
		}
		known[name] = true
		expected = append(expected, name)
	}
	if _, ok := index["parts"].([]any); !ok {
		return bad("分享分包清单无效")
	}
	if len(expected) > 4096 || len(z.File) > 8193 {
		return bad("分享分包清单无效")
	}
	for _, v := range array(index["deletions"]) {
		name, ok := v.(string)
		if !ok || !deletionName.MatchString(name) || known[name] {
			return bad("删除提示无效")
		}
		known[name] = true
		advisory[name] = true
	}
	seen := map[string]bool{"index.json": true}
	cursor := 0
	expanded := uint64(len(b))
	for _, file := range z.File[1:] {
		if seen[file.Name] || !known[file.Name] || file.FileInfo().Mode()&os.ModeSymlink != 0 {
			return bad("分享包包含重复或未知文件")
		}
		seen[file.Name] = true
		if !advisory[file.Name] {
			if cursor >= len(expected) || expected[cursor] != file.Name {
				return bad("分享分包顺序错误")
			}
			cursor++
		}
		if file.UncompressedSize64 > shareLimit || expanded+file.UncompressedSize64 > uint64(uploadLimit) {
			return &problem{413, "分享包过大"}
		}
		expanded += file.UncompressedSize64
	}
	if cursor != len(expected) || len(seen) != len(known) {
		return bad("分享包不完整，请选择完整文件")
	}
	deletionCount := int64(0)
	for _, file := range z.File[1:] {
		if !advisory[file.Name] {
			continue
		}
		raw, e := readZipFile(file, shareLimit)
		if e != nil {
			return e
		}
		if number(index["version"]) == 4 {
			if e = checkV4Part(index, file.Name, raw); e != nil {
				return e
			}
		}
		parsedHint, parseErr := metastandard.Parse(raw)
		hint, validHint := parsedHint.(map[string]any)
		if parseErr != nil || !validHint || hint["advisoryOnly"] != true {
			return bad("删除提示无效")
		}
		paths, ok := hint["paths"].([]any)
		if !ok {
			return bad("删除提示无效")
		}
		for _, v := range paths {
			relative, ok := v.(string)
			if !ok || !validPortablePath(relative) {
				return bad("删除提示路径无效")
			}
			deletionCount++
			if deletionCount > 100000 {
				return bad("删除提示过多")
			}
		}
	}
	for _, file := range z.File[1:] {
		if advisory[file.Name] {
			continue
		}
		b, e := readZipFile(file, shareLimit)
		if e != nil {
			return e
		}
		if number(index["version"]) == 4 {
			if e = checkV4Part(index, file.Name, b); e != nil {
				return e
			}
		}
		doc, e := readManifest(b)
		if e != nil {
			return e
		}
		if number(index["version"]) != number(doc["version"]) {
			return bad("资料分包版本不一致")
		}
		if number(index["version"]) >= 3 && (text(index["collectionId"]) != text(doc["collectionId"]) || number(index["collectionRevision"]) != number(doc["collectionRevision"]) || text(index["id"]) != text(doc["setId"])) {
			return bad("资料分包与快照标识不匹配")
		}
		if number(index["version"]) == 4 {
			doc["contentDigest"] = index["contentDigest"]
		}
		doc["deletionHints"] = deletionCount
		doc["completeSnapshot"] = true
		if text(index["baseId"]) != text(doc["baseId"]) {
			return bad("资料分包基线不一致")
		}
		if e = visit(doc); e != nil {
			return e
		}
	}
	if len(expected) == 0 {
		return visit(map[string]any{"format": "singverse-media-index", "version": index["version"], "collectionId": index["collectionId"], "collectionRevision": index["collectionRevision"], "setId": index["id"], "baseId": index["baseId"], "entries": []any{}, "deletionHints": deletionCount, "completeSnapshot": true, "metadataSchema": index["metadataSchema"], "contentDigest": index["contentDigest"]})
	}
	return nil
}

func (s *Service) rebindSource(c *gin.Context) {
	var in library.SourceInput
	if !s.decode(c, &in) {
		return
	}
	id := uuid()
	ctx := c.Request.Context()
	e := s.transaction(ctx, func(tx store.DBTX) error {
		rows, e := s.rows(ctx, tx, "workflow-active", map[string]any{"sourceId": c.Param("id")})
		if e != nil {
			return e
		}
		if scalar(rows) > 0 {
			return conflict("请先完成或取消当前导入任务")
		}
		source, e := s.library.RebindWith(ctx, tx, c.Param("id"), in)
		if e != nil {
			return e
		}
		args := map[string]any{"id": id, "sourceId": source.ID, "binding": hash(source.Location), "now": now()}
		if _, e = s.exec(ctx, tx, "workflow-insert", args); e != nil {
			return e
		}
		if _, e = s.exec(ctx, tx, "workflow-rebindShared", args); e != nil {
			return e
		}
		if _, e = s.exec(ctx, tx, "workflow-rebindEntries", args); e != nil {
			return e
		}
		counts, e := s.row(ctx, tx, "workflow-counters", args)
		if e != nil {
			return e
		}
		if _, e = s.exec(ctx, tx, "workflow-total", map[string]any{"id": id, "total": counts["total_count"]}); e != nil {
			return e
		}
		return s.setImportState(ctx, tx, id, "VERIFYING", "VERIFYING", nil)
	})
	if e != nil {
		s.fail(c, e)
		return
	}
	s.respondImport(c, c.Param("id"), id, 202)
}

func parseCursor(raw string) (int64, error) {
	n, e := strconv.ParseInt(raw, 10, 64)
	if e != nil || n < 0 {
		return 0, bad("分页游标无效")
	}
	return n, nil
}

func verificationMessage(err error) any {
	if err == nil {
		return nil
	}
	// Library diagnostics are already sanitized and contain no upstream body.
	switch err.Error() {
	case "WebDAV 认证失败，请检查用户名和密码":
		return "来源权限错误，请检查账号和 WebDAV 读取权限"
	}
	message := err.Error()
	for _, pair := range [][2]string{{"无目录读取权限", "来源拒绝访问，请检查 WebDAV 读取权限"}, {"重定向", "目录地址发生重定向，请直接配置最终 WebDAV 目录"}, {"没有访问权限", "来源拒绝访问，请检查 WebDAV 读取权限"}, {"访问过于频繁", "来源限流，请稍后重试"}, {"429", "来源限流，请稍后重试"}, {"403", "来源拒绝访问，请检查 WebDAV 读取权限"}, {"401", "认证失败，请检查来源账号和密码"}, {"目录不存在", "媒体目录不存在"}} {
		if strings.Contains(message, pair[0]) {
			return pair[1]
		}
	}
	return "无法连接或读取目录，请检查来源连接后重试"
}
func (s *Service) importAudioClaim(ctx context.Context, tx store.DBTX, id string, snapshot map[string]any) error {
	claim, ok := snapshot["audioRoles"].(map[string]any)
	if !ok {
		return nil
	}
	s.hookMu.RLock()
	fn := s.audioClaim
	s.hookMu.RUnlock()
	if fn != nil {
		return fn(ctx, tx, id, claim)
	}
	return nil
}

func (s *Service) cleanupImports(ctx context.Context, before string) error {
	jobs, e := s.rows(ctx, s.db, "workflow-expired", map[string]any{"before": before})
	if e != nil {
		return e
	}
	for _, job := range jobs {
		e = s.transaction(ctx, func(tx store.DBTX) error {
			eligible, e := s.rows(ctx, tx, "workflow-expiredOne", map[string]any{"id": job["id"], "before": before})
			if e != nil {
				return e
			}
			if len(eligible) == 0 {
				return nil
			}
			folder, e := s.importFolder(text(job["id"]))
			if e != nil {
				return e
			}
			if e = os.RemoveAll(folder); e != nil {
				return e
			}
			if _, e = s.exec(ctx, tx, "workflow-clearEntries", map[string]any{"id": job["id"]}); e != nil {
				return e
			}
			if _, e = s.exec(ctx, tx, "v4-release-leases", map[string]any{"importId": job["id"], "exportId": ""}); e != nil {
				return e
			}
			_, e = s.exec(ctx, tx, "workflow-delete", map[string]any{"id": job["id"]})
			return e
		})
		if e != nil {
			return e
		}
	}
	// Source removal may leave a completed spool without a business row.
	folder, e := managedDirectory(s.cfg.MetadataPath, "imports")
	if e != nil {
		return e
	}
	children, e := os.ReadDir(folder)
	if e != nil {
		return e
	}
	cutoff, _ := time.Parse(time.RFC3339Nano, before)
	for i, child := range children {
		if i >= 1000 {
			break
		}
		if !uuidPattern.MatchString(child.Name()) || !child.IsDir() || child.Type()&os.ModeSymlink != 0 {
			continue
		}
		st, e := child.Info()
		if e != nil || st.ModTime().After(cutoff) {
			continue
		}
		rows, e := s.rows(ctx, s.db, "workflow-exists", map[string]any{"id": child.Name()})
		if e != nil {
			return e
		}
		if scalar(rows) == 0 {
			if e = os.RemoveAll(filepath.Join(folder, child.Name())); e != nil {
				return e
			}
		}
	}
	chunks, e := managedDirectory(s.cfg.MetadataPath, "import-chunks")
	if e != nil {
		return e
	}
	children, e = os.ReadDir(chunks)
	if e != nil {
		return e
	}
	for i, child := range children {
		if i >= 1000 {
			break
		}
		if !uuidPattern.MatchString(child.Name()) || !child.IsDir() || child.Type()&os.ModeSymlink != 0 {
			continue
		}
		st, e := child.Info()
		if e != nil || st.ModTime().After(cutoff) {
			continue
		}
		rows, e := s.rows(ctx, s.db, "v4-chunk-active", map[string]any{"snapshotId": child.Name()})
		if e != nil {
			return e
		}
		if scalar(rows) == 0 {
			if e = os.RemoveAll(filepath.Join(chunks, child.Name())); e != nil {
				return e
			}
		}
	}
	return nil
}

func importDTO(row map[string]any) map[string]any {
	out := make(map[string]any, len(row))
	for key, value := range row {
		parts := strings.Split(key, "_")
		for i := 1; i < len(parts); i++ {
			if parts[i] != "" {
				parts[i] = strings.ToUpper(parts[i][:1]) + parts[i][1:]
			}
		}
		out[strings.Join(parts, "")] = value
	}
	return out
}

package metadata

import (
	"archive/zip"
	"context"
	"encoding/base64"
	"encoding/json"
	"github.com/gin-gonic/gin"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/library"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
	"io"
	"os"
)

func shareSize(v any) (*int64, error) {
	if v == nil {
		return nil, nil
	}
	n, ok := v.(json.Number)
	if !ok {
		return nil, bad("文件大小无效")
	}
	size, e := n.Int64()
	if e != nil || size < 0 {
		return nil, bad("文件大小无效")
	}
	return &size, nil
}
func mediaPath(v string) bool { return library.SupportsMedia(v) }

// ImportPart commits bounded chunks with a durable receipt, so retries preserve prior work.
func (s *Service) ImportPart(ctx context.Context, sourceID string, data []byte) (map[string]int64, error) {
	document, e := readManifest(data)
	if e != nil {
		return nil, e
	}
	return s.importDocument(ctx, sourceID, document)
}
func (s *Service) importDocument(ctx context.Context, sourceID string, document map[string]any) (map[string]int64, error) {
	source, e := s.row(ctx, s.db, "source", map[string]any{"id": sourceID})
	if e != nil {
		return nil, absent(e, "媒体来源不存在")
	}
	if text(source["source_type"]) != "WEBDAV" && text(source["source_type"]) != "LOCAL" {
		return nil, bad("请选择 WebDAV 媒体库导入")
	}
	binding := hash(text(source["location"]))
	collection, revision, e := sharedIdentity(document)
	if e != nil {
		return nil, e
	}
	if e = s.checkSharedBaseline(ctx, s.db, sourceID, document); e != nil {
		return nil, e
	}
	if number(document["version"]) == 4 {
		if e = s.normalizeSharedAssets(document); e != nil {
			return nil, e
		}
	}
	entries := array(document["entries"])
	seen := map[string]bool{}
	if raw, ok := document["assets"]; ok && raw != nil {
		assets, ok := raw.(map[string]any)
		if !ok || len(assets) > 5000 {
			return nil, bad("共享资源清单无效")
		}
		for key, v := range assets {
			encoded, ok := v.(string)
			if !ok || !assetHash.MatchString(key) || key != hash(encoded) {
				return nil, bad("共享资源校验失败")
			}
			actual, e := s.InstallSharedCover(encoded)
			if e != nil || actual != key {
				return nil, bad("共享封面校验失败")
			}
		}
	}
	// Validate the whole part before writing its first receipt or song.
	for _, raw := range entries {
		item, ok := raw.(map[string]any)
		if !ok {
			return nil, bad("索引包条目无效")
		}
		relative, ok := item["path"].(string)
		if !ok || !validPortablePath(relative) || !mediaPath(relative) || seen[relative] {
			return nil, bad("歌曲路径重复或格式不支持")
		}
		seen[relative] = true
		if _, e = shareSize(item["size"]); e != nil {
			return nil, e
		}
		snapshot, ok := item["metadata"].(map[string]any)
		if !ok {
			return nil, bad("共享元数据无效")
		}
		if e = validateSnapshot(snapshot); e != nil {
			return nil, e
		}
		if ref := text(snapshot["coverRef"]); ref != "" {
			if !assetHash.MatchString(ref) {
				return nil, bad("共享封面标识无效")
			}
			cover, e := s.SharedCover(ref)
			if e != nil {
				return nil, bad("缺少共享封面资源，请按顺序导入分片")
			}
			snapshot["coverData"] = base64.StdEncoding.EncodeToString(cover)
		}
		if encoded := text(snapshot["coverData"]); encoded != "" {
			if _, e = s.InstallSharedCover(encoded); e != nil {
				return nil, e
			}
		}
	}
	key := map[string]any{"sourceId": sourceID, "binding": binding, "hash": hash(encode(document))}
	e = s.transaction(ctx, func(tx store.DBTX) error {
		if _, e := s.exec(ctx, tx, "share-lock", nil); e != nil {
			return e
		}
		if _, e := s.exec(ctx, tx, "share-receiptInsert", key); e != nil {
			return e
		}
		receipt, e := s.row(ctx, tx, "share-receipt", key)
		if e != nil {
			return e
		}
		if number(receipt["processed"]) < int64(len(entries)) {
			source, e := s.row(ctx, tx, "source", map[string]any{"id": sourceID})
			if e != nil {
				return e
			}
			if hash(text(source["location"])) != binding {
				return conflict("导入期间来源根目录已变化")
			}
			if text(source["source_type"]) == "LOCAL" {
				return nil
			}
			return s.prepare(ctx, tx, sourceID, true)
		}
		return nil
	})
	if e != nil {
		return nil, e
	}
	for {
		receipt, e := s.row(ctx, s.db, "share-receipt", key)
		if e != nil {
			return nil, e
		}
		cursor := int(number(receipt["processed"]))
		if cursor < 0 || cursor > len(entries) {
			return nil, conflict("分享导入进度无效")
		}
		if cursor == len(entries) {
			if document["part"] == nil {
				if e = s.saveSharedBaseline(ctx, s.db, sourceID, document); e != nil {
					return nil, e
				}
			}
			return map[string]int64{"imported": number(receipt["imported"]), "skipped": number(receipt["skipped"]), "processed": int64(cursor)}, nil
		}
		next := cursor
		used := 0
		for next < len(entries) && next-cursor < 100 {
			size := len(encode(entries[next]))
			if used+size > 4*1024*1024 && next > cursor {
				break
			}
			used += size
			next++
		}
		e = s.transaction(ctx, func(tx store.DBTX) error {
			if _, e := s.exec(ctx, tx, "share-receiptLock", key); e != nil {
				return e
			}
			current, e := s.row(ctx, tx, "share-receipt", key)
			if e != nil {
				return e
			}
			if number(current["processed"]) != int64(cursor) {
				return nil
			}
			source, e := s.row(ctx, tx, "source", map[string]any{"id": sourceID})
			if e != nil {
				return e
			}
			if (text(source["source_type"]) != "WEBDAV" && text(source["source_type"]) != "LOCAL") || hash(text(source["location"])) != binding {
				return conflict("导入期间来源根目录已变化")
			}
			imported, skipped := number(current["imported"]), number(current["skipped"])
			for _, raw := range entries[cursor:next] {
				item := raw.(map[string]any)
				size, e := shareSize(item["size"])
				if e != nil {
					return e
				}
				preferred, e := s.preferredMedia(ctx, tx, sourceID, collection, item["metadata"].(map[string]any))
				if e != nil {
					return e
				}
				id, added, e := s.library.ImportPortableSong(ctx, tx, sourceID, text(item["path"]), size, preferred)
				if e != nil {
					return e
				}

				if !added {
					exists, e := s.rows(ctx, tx, "shared-songExists", map[string]any{"songId": id})
					if e != nil {
						return e
					}
					if scalar(exists) == 0 {
						skipped++
						continue
					}
				}
				snapshot := item["metadata"].(map[string]any)
				outcome, _, mergeErr := s.mergeShared(ctx, tx, id, snapshot, collection, revision, size)
				if mergeErr != nil {
					e = mergeErr

					return e
				}
				if claim, ok := snapshot["audioRoles"].(map[string]any); ok {
					s.hookMu.RLock()
					fn := s.audioClaim
					s.hookMu.RUnlock()
					if fn != nil {
						if e = fn(ctx, tx, id, claim); e != nil {
							return e
						}
					}
				}
				if added || outcome == "UPDATED" {
					imported++
				} else {
					skipped++
				}
			}
			a := map[string]any{}
			for k, v := range key {
				a[k] = v
			}
			a["processed"], a["imported"], a["skipped"] = next, imported, skipped
			_, e = s.exec(ctx, tx, "share-receiptUpdate", a)
			return e
		})
		if e != nil {
			return nil, e
		}
	}
}

func readZipFile(file *zip.File, limit int64) ([]byte, error) {
	if file.UncompressedSize64 > uint64(limit) || file.FileInfo().Mode()&os.ModeSymlink != 0 {
		return nil, &problem{413, "分享包条目超过限制"}
	}
	r, e := file.Open()
	if e != nil {
		return nil, bad("分享包格式无效")
	}
	defer r.Close()
	b, e := io.ReadAll(io.LimitReader(r, limit+1))
	if e != nil {
		return nil, bad("分享包格式无效")
	}
	if int64(len(b)) > limit {
		return nil, &problem{413, "分享包条目超过限制"}
	}
	return b, nil
}

// ImportStream spools the upload to a managed temporary file and reads one bounded part at a time.
func (s *Service) ImportStream(ctx context.Context, sourceID string, input io.Reader) (map[string]int64, error) {
	source, e := s.row(ctx, s.db, "source", map[string]any{"id": sourceID})
	if e != nil {
		return nil, absent(e, "媒体来源不存在")
	}
	if text(source["source_type"]) != "WEBDAV" {
		return nil, bad("请选择 WebDAV 媒体库导入")
	}
	if e = os.MkdirAll(s.cfg.MetadataPath, 0700); e != nil {
		return nil, e
	}
	temp, e := os.CreateTemp(s.cfg.MetadataPath, "metadata-import-*.tmp")
	if e != nil {
		return nil, e
	}
	defer func() { temp.Close(); os.Remove(temp.Name()) }()
	size, e := io.Copy(temp, io.LimitReader(input, uploadLimit+1))
	if e != nil {
		return nil, e
	}
	if size > uploadLimit {
		return nil, &problem{413, "分享包超过 8 GiB"}
	}
	if e = ctx.Err(); e != nil {
		return nil, e
	}
	if e = temp.Sync(); e != nil {
		return nil, e
	}
	result := map[string]int64{"imported": 0, "skipped": 0, "processed": 0}
	var manifest map[string]any
	e = visitSharePackage(temp.Name(), func(doc map[string]any) error {
		if e := ctx.Err(); e != nil {
			return e
		}
		part, e := s.importDocument(ctx, sourceID, doc)
		if e != nil {
			return e
		}
		for k, v := range part {
			result[k] += v
		}
		manifest = doc
		return nil
	})
	if e != nil {
		return nil, e
	}
	if manifest != nil {
		e = s.transaction(ctx, func(tx store.DBTX) error {
			latest, e := s.row(ctx, tx, "source", map[string]any{"id": sourceID})
			if e != nil {
				return e
			}
			if text(latest["location"]) != text(source["location"]) {
				return conflict("导入期间来源根目录已变化")
			}
			return s.saveSharedBaseline(ctx, tx, sourceID, manifest)
		})
		if e != nil {
			return nil, e
		}
	}
	return result, nil
}

func (s *Service) importShare(c *gin.Context) {
	result, e := s.ImportStream(c.Request.Context(), c.Param("id"), c.Request.Body)
	if e != nil {
		s.fail(c, e)
		return
	}
	c.JSON(200, result)
}

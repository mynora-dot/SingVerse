package metadata

import (
	"context"
	"encoding/base64"
	"fmt"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
	"golang.org/x/sys/unix"
	"os"
	"path/filepath"
	"regexp"
)

var partName = regexp.MustCompile(`^part-[0-9]{6}\.zip$`)
var deletionName = regexp.MustCompile(`^deletions-[0-9]{6}\.json$`)

func (s *Service) exportShare(ctx context.Context, job map[string]any) (err error) {
	id := text(job["id"])
	sourceID := text(job["source_id"])
	defer func() {
		if err != nil && ctx.Err() == nil {
			saved, e := s.shareJob(ctx, s.db, sourceID, id)
			if e == nil {
				_ = s.transaction(ctx, func(tx store.DBTX) error {
					return s.shareProgress(ctx, tx, saved, "FAILED", text(saved["cursor_id"]), number(saved["processed_count"]), number(saved["part_count"]), "导出中断，请恢复重试")
				})
			}
		}
	}()
	folder, e := s.shareFolder(id)
	if e != nil {
		return e
	}
	if e = os.MkdirAll(folder, 0700); e != nil {
		return e
	}
	if st, e := os.Lstat(folder); e != nil || st.Mode()&os.ModeSymlink != 0 {
		return bad("分享目录无效")
	}
	cursor := text(job["cursor_id"])
	processed, parts := number(job["processed_count"]), number(job["part_count"])
	done := false
	for !done {
		if e = ctx.Err(); e != nil {
			return e
		}
		version := 2
		if text(job["collection_id"]) != "" {
			version = 3
		}
		manifest := map[string]any{"format": "singverse-media-index", "version": version, "collectionId": job["collection_id"], "collectionRevision": job["collection_revision"], "setId": id, "part": parts + 1, "baseId": text(job["base_id"])}
		entries := []any{}
		assets := map[string]any{}
		estimated := 0
		for len(entries) < 5000 {
			rows, e := s.rows(ctx, s.db, "share-page", map[string]any{"id": id, "cursor": cursor, "baseId": nullable(text(job["base_id"]))})
			if e != nil {
				return e
			}
			if len(rows) == 0 {
				done = true
				processed = number(job["total_count"])
				break
			}
			full := false
			for _, row := range rows {
				relative := text(row["relative_path"])
				if !validPortablePath(relative) {
					return bad("索引包路径无效")
				}
				result := map[string]any{}
				if row["result_json"] != nil {
					if e = decode(row["result_json"], &result); e != nil {
						return bad("元数据快照无效")
					}
				}
				data := map[string]any{}
				for _, key := range []string{"tags", "technical", "lyrics", "fields", "extractorVersion", "evidence", "origins", "aliases", "audioRoles"} {
					if v, ok := result[key]; ok {
						data[key] = v
					}
				}
				if _, ok := data["tags"]; !ok {
					parsed := map[string]any{}
					if e = decode(row["filename_json"], &parsed); e != nil {
						return e
					}
					data["tags"] = map[string]any{"title": text(parsed["title"]), "artists": stringsOf(parsed["artists"])}
					data["evidence"] = "FILENAME"
				}
				item := map[string]any{"path": relative, "metadata": data}
				if row["file_size"] != nil {
					item["size"] = row["file_size"]
				}
				key := text(result["cover"])
				encoded := ""
				if assetHash.MatchString(key) {
					data["coverRef"] = key
					if _, ok := assets[key]; !ok {
						prior, e := s.rows(ctx, s.db, "share-asset", map[string]any{"id": id, "hash": key})
						if e != nil {
							return e
						}
						if len(prior) == 0 {
							b, e := s.SharedCover(key)
							if e != nil {
								return e
							}
							encoded = base64.StdEncoding.EncodeToString(b)
						}
					}
				}
				size := 6*len(encode(item)) + 12*len(relative) + 2*len(encoded)
				if estimated+size > 24*1024*1024 && len(entries) > 0 {
					full = true
					break
				}
				entries = append(entries, item)
				if encoded != "" {
					assets[key] = encoded
				}
				estimated += size
				cursor = text(row["song_id"])
				processed++
				if len(entries) == 5000 {
					full = true
					break
				}
			}
			if full {
				break
			}
		}
		manifest["entries"], manifest["assets"] = entries, assets
		if len(entries) > 0 {
			var fs unix.Statfs_t
			if e = unix.Statfs(folder, &fs); e != nil {
				return e
			}
			if uint64(fs.Bavail)*uint64(fs.Bsize) < 64*1024*1024 {
				return &problem{507, "可用磁盘空间不足"}
			}
			packed, e := packManifest(manifest)
			if e != nil {
				return e
			}
			if e = atomicWrite(filepath.Join(folder, fmt.Sprintf("part-%06d.zip", parts+1)), packed); e != nil {
				return e
			}
			parts++
		}
		if done {
			if e = s.writeDeletions(ctx, job, folder); e != nil {
				return e
			}
		}
		e = s.transaction(ctx, func(tx store.DBTX) error {
			for key := range assets {
				if _, e := s.exec(ctx, tx, "share-assetInsert", map[string]any{"id": id, "hash": key}); e != nil {
					return e
				}
			}
			state := "RUNNING"
			if done {
				state = "SUCCEEDED"
			}
			return s.shareProgress(ctx, tx, job, state, cursor, processed, parts, nil)
		})
		if e != nil {
			return e
		}
	}
	return nil
}
func (s *Service) writeDeletions(ctx context.Context, job map[string]any, folder string) error {
	base := text(job["base_id"])
	if base == "" {
		return nil
	}
	cursor := ""
	part := 0
	for {
		rows, e := s.rows(ctx, s.db, "share-deleted", map[string]any{"id": job["id"], "baseId": base, "cursor": cursor})
		if e != nil {
			return e
		}
		if len(rows) == 0 {
			break
		}
		paths := []string{}
		for _, row := range rows {
			relative := text(row["relative_path"])
			if !validPortablePath(relative) {
				return bad("删除建议路径无效")
			}
			paths = append(paths, relative)
			cursor = text(row["song_id"])
		}
		part++
		if e = atomicWrite(filepath.Join(folder, fmt.Sprintf("deletions-%06d.json", part)), []byte(encode(map[string]any{"advisoryOnly": true, "paths": paths}))); e != nil {
			return e
		}
	}
	return nil
}

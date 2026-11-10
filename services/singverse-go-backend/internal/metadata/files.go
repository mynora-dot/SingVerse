package metadata

import (
	"archive/zip"
	"bytes"
	"context"
	"encoding/base64"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/library"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
	"io"
	"os"
	"path/filepath"
	"strings"
	"time"
)

func (s *Service) SetAudioRoles(fn func(context.Context, string) (map[string]any, error)) {
	s.hookMu.Lock()
	s.audioRoles = fn
	s.hookMu.Unlock()
}
func (s *Service) SetAudioClaim(fn func(context.Context, store.DBTX, string, map[string]any) error) {
	s.hookMu.Lock()
	s.audioClaim = fn
	s.hookMu.Unlock()
}
func (s *Service) filesLoop() {
	defer s.wg.Done()
	ticker := time.NewTicker(2 * time.Second)
	defer ticker.Stop()
	cleanup := time.Now().Add(time.Minute)
	for {
		select {
		case <-s.ctx.Done():
			return
		case <-ticker.C:
			if e := s.ProcessFiles(s.ctx); e != nil && s.ctx.Err() == nil {
				s.log.Error("metadata file worker failed")
			}
			if time.Now().After(cleanup) {
				if e := s.Cleanup(s.ctx); e != nil && s.ctx.Err() == nil {
					s.log.Error("metadata cleanup failed")
				}
				cleanup = time.Now().Add(time.Hour)
			}
		}
	}
}

// ProcessFiles writes only managed derivative directories; media sources remain read-only.
func (s *Service) ProcessFiles(ctx context.Context) error {
	s.fileMu.Lock()
	defer s.fileMu.Unlock()
	if _, e := s.exec(ctx, s.db, "file-enqueueAll", nil); e != nil {
		return e
	}
	pending, e := s.rows(ctx, s.db, "files-pending", map[string]any{"retryBefore": time.Now().UTC().Add(-5 * time.Minute).Format(time.RFC3339Nano)})
	if e != nil {
		return e
	}
	for _, row := range pending {
		if e = ctx.Err(); e != nil {
			return e
		}
		if e = s.writeFile(ctx, row); e != nil {
			if ctx.Err() != nil {
				return ctx.Err()
			}
			_, writeErr := s.exec(ctx, s.db, "files-failed", map[string]any{"songId": row["song_id"], "revision": row["revision"], "now": now()})
			if writeErr != nil {
				return writeErr
			}
		}
	}
	return nil
}
func (s *Service) writeFile(ctx context.Context, row map[string]any) error {
	id := text(row["song_id"])
	song, e := s.song(ctx, s.db, id)
	if e != nil {
		return e
	}
	sourceID := text(song["sourceId"])
	source, e := s.row(ctx, s.db, "source", map[string]any{"id": sourceID})
	if e != nil {
		return e
	}
	if !uuidPattern.MatchString(id) || !uuidPattern.MatchString(sourceID) {
		return bad("元数据文件标识无效")
	}
	var warning any
	var warmed *Cover
	if a := metaOf(song); a != nil && contains(a.Fields, "cover") {
		if cover, coverErr := s.SongCover(ctx, id); coverErr != nil {
			warning = "封面待补齐"
		} else {
			warmed = &cover
		}
	}
	var audio map[string]any
	s.hookMu.RLock()
	roles := s.audioRoles
	s.hookMu.RUnlock()
	if roles != nil {
		audio, e = roles(ctx, id)
		if e != nil {
			return e
		}
	}
	snapshot, e := s.Snapshot(ctx, s.db, id)
	if e != nil {
		return e
	}
	if audio != nil {
		snapshot["audioRoles"] = audio
	}
	if warmed != nil {
		key, e := s.portableCover(warmed.Bytes)
		if e != nil {
			return e
		}
		snapshot["cover"] = key
		fields, _ := snapshot["fields"].(map[string]any)
		if fields == nil {
			fields = map[string]any{}
			snapshot["fields"] = fields
		}
		fields["cover"] = "PRESENT"
	}
	if warning != nil {
		previous, e := s.rows(ctx, s.db, "files-previous", map[string]any{"songId": id})
		if e != nil {
			return e
		}
		if len(previous) > 0 && previous[0]["result_json"] != nil {
			var prior map[string]any
			if decode(previous[0]["result_json"], &prior) == nil && prior["coverKey"] == snapshot["coverKey"] && assetHash.MatchString(text(prior["cover"])) {
				snapshot["cover"] = prior["cover"]
			}
		}
	}
	packedSnapshot := map[string]any{}
	for k, v := range snapshot {
		packedSnapshot[k] = v
	}
	if key := text(snapshot["cover"]); key != "" {
		b, e := s.SharedCover(key)
		if e != nil {
			return e
		}
		packedSnapshot["coverData"] = base64.StdEncoding.EncodeToString(b)
	}
	manifest := map[string]any{"format": "singverse-media-index", "version": 2, "entries": []any{map[string]any{"path": "media", "metadata": packedSnapshot}}}
	bundle, e := packManifest(manifest)
	if e != nil {
		return e
	}
	zipReader, e := zip.NewReader(bytes.NewReader(bundle), int64(len(bundle)))
	if e != nil {
		return e
	}
	directory, e := managedDirectory(s.cfg.MetadataPath, "library", sourceID, id)
	if e != nil {
		return e
	}
	expected := map[string]bool{"metadata.json": true}
	for _, file := range zipReader.File {
		if strings.Contains(file.Name, "/") || !validPortablePath(file.Name) {
			return bad("元数据文件路径无效")
		}
		expected[file.Name] = true
		r, e := file.Open()
		if e != nil {
			return e
		}
		data, e := io.ReadAll(io.LimitReader(r, shareLimit+1))
		r.Close()
		if e != nil || len(data) > shareLimit {
			return bad("元数据衍生文件超过限制")
		}
		if e = atomicWrite(filepath.Join(directory, file.Name), data); e != nil {
			return e
		}
	}
	target, e := library.MediaURL(text(source["location"]), text(song["relativePath"]))
	if text(source["source_type"]) != "LOCAL" && e != nil {
		return e
	}
	if text(source["source_type"]) == "LOCAL" {
		target = filepath.Join(text(source["location"]), filepath.FromSlash(text(song["relativePath"])))
	}
	if e = atomicWrite(filepath.Join(directory, "media.strm"), []byte(target+"\n")); e != nil {
		return e
	}
	record := map[string]any{"relativePath": song["relativePath"], "sourceType": source["source_type"], "metadata": snapshot}
	if e = atomicWrite(filepath.Join(directory, "metadata.json"), []byte(encode(record))); e != nil {
		return e
	}
	children, e := os.ReadDir(directory)
	if e != nil {
		return e
	}
	for _, child := range children {
		if !expected[child.Name()] {
			if e = os.Remove(filepath.Join(directory, child.Name())); e != nil {
				return e
			}
		}
	}
	_, e = s.exec(ctx, s.db, "files-complete", map[string]any{"songId": id, "revision": row["revision"], "result": encode(snapshot), "location": source["location"], "error": warning, "now": now()})
	return e
}

// Cleanup removes expired derivative records and managed assets, never original media.
func (s *Service) Cleanup(ctx context.Context) error {
	before := time.Now().UTC().Add(-7 * 24 * time.Hour).Format(time.RFC3339Nano)
	e := s.transaction(ctx, func(tx store.DBTX) error {
		assets, e := s.rows(ctx, tx, "review-oldAssets", map[string]any{"before": before})
		if e != nil {
			return e
		}
		for _, row := range assets {
			id := text(row["id"])
			if !uuidPattern.MatchString(id) {
				return bad("封面资源标识无效")
			}
			if _, e = s.exec(ctx, tx, "review-assetDelete", map[string]any{"id": id}); e != nil {
				return e
			}
			_ = os.Remove(filepath.Join(s.cfg.MetadataCachePath, "uploads", id+".image"))
			_ = os.Remove(filepath.Join(s.cfg.MetadataPath, "uploads", id+".image"))
		}
		for _, name := range []string{"review-pruneReceipts", "meta-pruneCandidates"} {
			if _, e = s.exec(ctx, tx, name, map[string]any{"before": before}); e != nil {
				return e
			}
		}
		return nil
	})
	if e != nil {
		return e
	}
	cutoff := time.Now().Add(-30 * 24 * time.Hour)
	a := map[string]any{"before": cutoff.UTC().Format(time.RFC3339Nano)}
	e = s.transaction(ctx, func(tx store.DBTX) error {
		for _, name := range []string{"pruneMetadata", "pruneOverrides", "pruneAssignments", "pruneClassification", "pruneCredits", "pruneTasks", "pruneResources"} {
			if _, e := s.exec(ctx, tx, "dav-"+name, a); e != nil {
				return e
			}
		}
		return nil
	})
	if e != nil {
		return e
	}
	files, e := os.ReadDir(s.cfg.MetadataPath)
	if e != nil && !os.IsNotExist(e) {
		return e
	}
	for i, f := range files {
		if i >= 1000 {
			break
		}
		if f.Type()&os.ModeSymlink != 0 {
			continue
		}
		st, e := f.Info()
		if e != nil || st.ModTime().After(cutoff) || !st.Mode().IsRegular() {
			continue
		}
		name := f.Name()
		if len(name) >= 64 && assetHash.MatchString(name[:64]) && (name[64:] == ".image" || name[64:] == ".lyrics.json") {
			r, e := s.rows(ctx, s.db, "dav-assetReferences", map[string]any{"pattern": "%" + name[:64] + "%"})
			if e != nil {
				return e
			}
			refs := int64(0)
			if len(r) > 0 {
				for _, v := range r[0] {
					refs = number(v)
					break
				}
			}
			if refs == 0 {
				_ = os.Remove(filepath.Join(s.cfg.MetadataPath, name))
			}
		} else if strings.HasPrefix(name, "metadata-") && strings.HasSuffix(name, ".tmp") {
			_ = os.Remove(filepath.Join(s.cfg.MetadataPath, name))
		}
	}
	if e = s.cleanupImports(ctx, cutoff.UTC().Format(time.RFC3339Nano)); e != nil {
		return e
	}
	return s.cleanupShares(ctx, before)
}

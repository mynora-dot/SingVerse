package metadata

import (
	"archive/zip"
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"

	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/metastandard"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/metastore"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

// portableDocuments freezes a complete connected record set, including stable
// credit/release/work identities. No path, credential or local job is a document.
func (s *Service) portableDocuments(ctx context.Context, tx store.DBTX, media string) ([]metastandard.Document, error) {
	song, variant, e := s.library.FormalIDs(ctx, tx, media)
	if e != nil {
		return nil, e
	}
	queue := []string{song, variant, media}
	seen := map[string]bool{}
	docs := []metastandard.Document{}
	for len(queue) > 0 {
		id := queue[0]
		queue = queue[1:]
		if seen[id] {
			continue
		}
		seen[id] = true
		d, e := s.library.Formal().Read(ctx, tx, id)
		if e != nil {
			return nil, e
		}
		for _, name := range []string{"workId", "releaseId"} {
			if ref, ok := d.Fields[name].(string); ok {
				queue = append(queue, ref)
			}
		}
		if tags, ok := d.Fields["tagIds"].([]any); ok {
			for _, ref := range tags {
				if id, ok := ref.(string); ok {
					queue = append(queue, id)
				}
			}
		}
		if credits, ok := d.Fields["credits"].([]any); ok {
			for _, raw := range credits {
				if credit, ok := raw.(map[string]any); ok {
					if id, ok := credit["artistId"].(string); ok {
						queue = append(queue, id)
					}
				}
			}
		}
		if d.Type == "MEDIA" {
			if raw, ok := d.Fields["observation"].(map[string]any); ok {
				safe := map[string]any{}
				for _, name := range []string{"duration", "durationMs", "container", "bitRate", "video", "audio", "streams"} {
					if v := raw[name]; v != nil {
						safe[name] = portableTechnical(v)
					}
				}
				d.Fields["observation"] = safe
			}
			if roles, ok := d.Fields["audioRoles"]; ok {
				d.Fields["audioRoles"] = portableTechnical(roles)
			}
		}
		docs = append(docs, d)
		if len(docs) > 256 {
			return nil, bad("单条资料关联过多")
		}
	}
	sort.Slice(docs, func(i, j int) bool { return docs[i].ID < docs[j].ID })
	return docs, nil
}
func portableTechnical(v any) any {
	switch x := v.(type) {
	case map[string]any:
		out := map[string]any{}
		for k, value := range x {
			switch strings.ToLower(k) {
			case "url", "uri", "path", "filename", "input", "headers", "authorization", "cookie", "password", "token", "source", "command", "stderr":
				continue
			}
			out[k] = portableTechnical(value)
		}
		return out
	case []any:
		out := []any{}
		for _, value := range x {
			out = append(out, portableTechnical(value))
		}
		return out
	}
	return v
}
func v4Header(job map[string]any) map[string]any {
	return map[string]any{"format": "singverse-media-index", "version": 4, "metadataSchema": "1.0", "collectionId": job["collection_id"], "collectionRevision": strconv.FormatInt(number(job["collection_revision"]), 10), "setId": job["id"], "baseId": text(job["base_id"]), "entries": []any{}, "resources": []any{}}
}
func packV4(doc map[string]any, chunks map[string][]byte) ([]byte, error) {
	raw, e := metastandard.Canonical([]byte(encode(doc)))
	if e != nil {
		return nil, e
	}
	if len(raw) > metastandard.MaxJSON {
		return nil, bad("资料记录超过单片限制")
	}
	var out bytes.Buffer
	z := zip.NewWriter(&out)
	expanded := len(raw)
	write := func(name string, data []byte) error {
		expanded += len(data)
		if expanded > shareLimit {
			return &problem{413, "资料分片展开后超过 32 MiB"}
		}
		w, e := z.Create(name)
		if e != nil {
			return e
		}
		_, e = w.Write(data)
		return e
	}
	expanded = 0
	if e = write("manifest.json", raw); e != nil {
		return nil, e
	}
	keys := []string{}
	for key := range chunks {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	for _, key := range keys {
		data := chunks[key]
		if len(data) > metastandard.ChunkLimit || metastandard.Digest(data) != key {
			return nil, bad("资源块校验失败")
		}
		if e = write("chunks/"+key+".bin", data); e != nil {
			return nil, e
		}
	}
	if e = z.Close(); e != nil {
		return nil, e
	}
	if out.Len() > shareLimit {
		return nil, &problem{413, "资料分片超过 32 MiB"}
	}
	return out.Bytes(), nil
}

func (s *Service) exportV4(ctx context.Context, job map[string]any) error {
	folder, e := s.shareFolder(text(job["id"]))
	if e != nil {
		return e
	}
	if e = os.MkdirAll(folder, 0700); e != nil {
		return e
	}
	cursor := text(job["cursor_id"])
	processed, parts := number(job["processed_count"]), number(job["part_count"])
	if number(job["total_count"]) > 100000 || parts > 4096 {
		return &problem{413, "资料包超过十万条目或 4096 分片限制"}
	}
	totalBytes := int64(0)
	for i := int64(1); i <= parts; i++ {
		st, err := os.Lstat(filepath.Join(folder, fmt.Sprintf("part-%06d.zip", i)))
		if err != nil {
			return err
		}
		if !st.Mode().IsRegular() {
			return bad("分享分片路径无效")
		}
		totalBytes += st.Size()
	}
	checkpoint := func(asset string) error {
		return s.transaction(ctx, func(tx store.DBTX) error {
			if asset != "" {
				if _, e := s.exec(ctx, tx, "share-assetInsert", map[string]any{"id": job["id"], "hash": asset}); e != nil {
					return e
				}
			}
			return s.shareProgress(ctx, tx, job, "RUNNING", cursor, processed, parts, nil)
		})
	}
	writePart := func(doc map[string]any, chunks map[string][]byte) error {
		doc["part"] = parts + 1
		b, e := packV4(doc, chunks)
		if e != nil {
			return e
		}
		// Reserve space for the public index, deletion hints and ZIP headers.
		if parts >= 4096 || totalBytes+int64(len(b)) > uploadLimit-(16<<20) {
			return &problem{413, "资料包达到 8 GiB 或 4096 分片上限，请缩小分享范围"}
		}
		if e = atomicWrite(filepath.Join(folder, fmt.Sprintf("part-%06d.zip", parts+1)), b); e != nil {
			return e
		}
		parts++
		totalBytes += int64(len(b))
		return nil
	}
	for {
		if e = ctx.Err(); e != nil {
			return e
		}
		rows, e := s.rows(ctx, s.db, "share-page", map[string]any{"id": job["id"], "cursor": cursor, "baseId": nullable(text(job["base_id"]))})
		if e != nil {
			return e
		}
		if len(rows) == 0 {
			if e = s.writeDeletions(ctx, job, folder); e != nil {
				return e
			}
			return s.transaction(ctx, func(tx store.DBTX) error {
				return s.shareProgress(ctx, tx, job, "SUCCEEDED", cursor, number(job["total_count"]), parts, nil)
			})
		}
		entries := []any{}
		estimated := 0
		nextCursor := cursor
		for _, row := range rows {
			var snapshot map[string]any
			if e = decode(row["result_json"], &snapshot); e != nil {
				return e
			}
			docs, ok := snapshot["documents"].([]any)
			if !ok {
				return bad("缺少正式资料快照")
			}
			item := map[string]any{"rootId": "media", "path": row["relative_path"], "mediaId": row["song_id"], "documents": docs}
			if row["file_size"] != nil {
				item["size"] = row["file_size"]
			}
			cost := len(encode(item))
			if len(entries) > 0 && estimated+cost > 12<<20 {
				break
			}
			for _, raw := range docs {
				d, e := metastandard.Decode([]byte(encode(raw)))
				if e != nil {
					return e
				}
				for _, assetID := range metastandard.AssetIDs(d) {
					prior, e := s.rows(ctx, s.db, "share-asset", map[string]any{"id": job["id"], "hash": assetID})
					if e != nil {
						return e
					}
					if len(prior) > 0 {
						continue
					}
					a, data, e := s.library.Formal().ReadAsset(ctx, s.db, assetID)
					if e != nil {
						return e
					}
					chunks := []any{}
					for offset := 0; offset < len(data); offset += metastandard.ChunkLimit {
						b := data[offset:min(offset+metastandard.ChunkLimit, len(data))]
						chunks = append(chunks, map[string]any{"id": metastandard.Digest(b), "size": len(b)})
					}
					resource := map[string]any{"id": a.ID, "mime": a.MIME, "size": a.Size, "chunks": chunks}
					for offset := 0; offset < len(data); offset += metastandard.ChunkLimit {
						b := data[offset:min(offset+metastandard.ChunkLimit, len(data))]
						doc := v4Header(job)
						doc["resources"] = []any{resource}
						if e = writePart(doc, map[string][]byte{metastandard.Digest(b): b}); e != nil {
							return e
						}
					}
					// Checkpoint only a whole asset. A crash midway reuses the last
					// committed part number and safely rewrites unfinished chunks.
					if e = checkpoint(assetID); e != nil {
						return e
					}
				}
			}
			entries = append(entries, item)
			estimated += cost
			nextCursor = text(row["song_id"])
		}
		if len(entries) == 0 {
			return bad("单条资料超过单片限制")
		}
		doc := v4Header(job)
		doc["entries"] = entries
		if e = writePart(doc, nil); e != nil {
			return e
		}
		cursor = nextCursor
		processed += int64(len(entries))
		if e = checkpoint(""); e != nil {
			return e
		}
	}
}

// unpackV4 validates every record before it can reach a preview. Binary chunks
// are transient values, never base64 content in the public JSON format.
func unpackV4(doc map[string]any, z *zip.Reader) error {
	if !v4Integer(doc["version"], 4, 4) || !v4Base(doc["baseId"]) {
		return bad("v4 版本或基线类型无效")
	}
	if _, ok := doc["entries"].([]any); !ok {
		return bad("缺少条目数组")
	}
	if _, ok := doc["resources"].([]any); !ok {
		return bad("缺少附件数组")
	}
	if len(array(doc["entries"])) > 5000 || len(array(doc["resources"])) > 5000 {
		return bad("单片资料或附件条目超过 5000")
	}
	if n, ok := doc["part"].(json.Number); !ok {
		return bad("缺少分片序号")
	} else if part, e := n.Int64(); e != nil || part < 1 || part > 4096 {
		return bad("分片序号无效")
	}

	if e := onlyKeys(doc, "format", "version", "metadataSchema", "collectionId", "collectionRevision", "setId", "baseId", "part", "entries", "resources"); e != nil {
		return e
	}
	if doc["metadataSchema"] != "1.0" {
		return bad("不支持此元数据语义版本")
	}
	if _, _, e := sharedIdentity(doc); e != nil {
		return e
	}
	chunks := map[string][]byte{}
	for _, file := range z.File {
		if file.Name == "manifest.json" {
			continue
		}
		if !strings.HasPrefix(file.Name, "chunks/") || !strings.HasSuffix(file.Name, ".bin") {
			return bad("v4 分片包含未知文件")
		}
		key := strings.TrimSuffix(strings.TrimPrefix(file.Name, "chunks/"), ".bin")
		data, e := readZipFile(file, metastandard.ChunkLimit)
		if e != nil {
			return e
		}
		if !metastandard.SHA256.MatchString(key) || metastandard.Digest(data) != key {
			return bad("资源块摘要不匹配")
		}
		chunks[key] = data
	}
	for _, raw := range array(doc["resources"]) {
		r, ok := raw.(map[string]any)
		if !ok || !metastandard.SHA256.MatchString(text(r["id"])) || number(r["size"]) < 1 || number(r["size"]) > metastandard.ImageLimit {
			return bad("资源清单无效")
		}
		if e := onlyKeys(r, "id", "mime", "size", "chunks"); e != nil {
			return e
		}
		if !strings.Contains("|image/jpeg|image/png|image/webp|application/json|text/plain|application/x-subrip|text/vtt|", "|"+text(r["mime"])+"|") {
			return bad("不支持的资料附件类型")
		}
		if _, e := shareSize(r["size"]); e != nil {
			return e
		}
		total := int64(0)
		cs, ok := r["chunks"].([]any)
		if !ok || len(cs) == 0 || len(cs) > 2 {
			return bad("资源块清单无效")
		}
		for _, raw := range cs {
			c, ok := raw.(map[string]any)
			if !ok || !metastandard.SHA256.MatchString(text(c["id"])) || number(c["size"]) < 1 || number(c["size"]) > metastandard.ChunkLimit {
				return bad("资源块清单无效")
			}
			if e := onlyKeys(c, "id", "size"); e != nil {
				return e
			}
			if _, e := shareSize(c["size"]); e != nil {
				return e
			}
			total += number(c["size"])
		}
		if total != number(r["size"]) {
			return bad("资源大小与资源块不匹配")
		}
	}
	for _, raw := range array(doc["entries"]) {
		item, ok := raw.(map[string]any)
		if !ok || item["rootId"] != "media" || !metastandard.ValidPath(text(item["path"])) || !mediaPath(text(item["path"])) || !metastandard.UUID.MatchString(text(item["mediaId"])) {
			return bad("媒体记录或路径无效")
		}
		if e := onlyKeys(item, "rootId", "path", "size", "mediaId", "documents"); e != nil {
			return e
		}
		if _, e := shareSize(item["size"]); e != nil {
			return e
		}
		records, ok := item["documents"].([]any)
		if !ok || len(records) < 3 || len(records) > 256 {
			return bad("正式资料关联无效")
		}
		seen := map[string]metastandard.Document{}
		for _, raw := range records {
			d, e := metastandard.Decode([]byte(encode(raw)))
			if e != nil {
				return bad(e.Error())
			}
			if _, exists := seen[d.ID]; exists {
				return bad("对象标识重复")
			}
			seen[d.ID] = d
		}
		for _, record := range seen {
			for id, kind := range metastore.ReferenceIDs(record) {
				if seen[id].Type != kind {
					return bad("资料关联不存在或类型不符")
				}
			}
		}
		m := seen[text(item["mediaId"])]
		v := seen[m.ParentID]
		song := seen[v.ParentID]
		if m.Type != "MEDIA" || v.Type != "VARIANT" || song.Type != "SONG" {
			return bad("歌曲、版本与媒体关联缺失")
		}
		// Legacy adapter data is derived from the validated formal document;
		// clients cannot submit a contradictory second copy of the values.
		metadata := map[string]any{"tags": map[string]any{}, "documents": records, "portableMediaId": m.ID}
		tags := metadata["tags"].(map[string]any)
		for _, d := range []metastandard.Document{song, v} {
			for _, f := range []string{"title", "artists", "album", "releaseDate"} {
				if value, exists := d.Fields[f]; exists {
					tags[f] = value
				}
			}
		}
		if lyrics := v.Fields["lyrics"]; lyrics != nil {
			metadata["lyrics"] = lyrics
		}
		if aliases := song.Fields["aliases"]; aliases != nil {
			metadata["aliases"] = aliases
		}
		metadata["referenceDurationMs"] = song.Fields["referenceDurationMs"]
		item["metadata"] = metadata
	}
	doc["_v4Chunks"] = chunks
	return nil
}

func (s *Service) installV4Resources(ctx context.Context, doc map[string]any) error {
	if number(doc["version"]) != 4 {
		return nil
	}
	chunks, _ := doc["_v4Chunks"].(map[string][]byte)
	folder := filepath.Join(s.cfg.MetadataPath, "import-chunks", text(doc["setId"]))
	if !metastandard.UUID.MatchString(text(doc["setId"])) {
		return bad("快照标识无效")
	}
	for id, data := range chunks {
		if e := atomicWrite(filepath.Join(folder, id), data); e != nil {
			return e
		}
	}
	for _, raw := range array(doc["resources"]) {
		r := raw.(map[string]any)
		var combined bytes.Buffer
		complete := true
		for _, raw := range array(r["chunks"]) {
			c := raw.(map[string]any)
			f, e := os.Open(filepath.Join(folder, text(c["id"])))
			if os.IsNotExist(e) {
				complete = false
				break
			}
			if e != nil {
				return e
			}
			data, e := io.ReadAll(io.LimitReader(f, metastandard.ChunkLimit+1))
			f.Close()
			if e != nil || int64(len(data)) != number(c["size"]) || metastandard.Digest(data) != text(c["id"]) {
				return bad("暂存资源块校验失败")
			}
			combined.Write(data)
		}
		if !complete {
			continue
		}
		if metastandard.Digest(combined.Bytes()) != text(r["id"]) {
			return bad("原始附件摘要不匹配")
		}
		if e := s.transaction(ctx, func(tx store.DBTX) error {
			a, e := s.library.Formal().PutAsset(ctx, tx, combined.Bytes(), text(r["mime"]))
			if e != nil {
				return e
			}
			return s.leaseV4Asset(ctx, tx, leaseOwner(doc), a.ID)
		}); e != nil {
			return e
		}
	}
	for _, raw := range array(doc["entries"]) {
		item := raw.(map[string]any)
		for _, raw := range array(item["documents"]) {
			d, e := metastandard.Decode([]byte(encode(raw)))
			if e != nil {
				return e
			}
			for _, id := range metastandard.AssetIDs(d) {
				if _, _, e = s.library.Formal().ReadAsset(ctx, s.db, id); e != nil {
					return bad("缺少完整的原始附件，请导入完整资料包")
				}
				if e = s.transaction(ctx, func(tx store.DBTX) error { return s.leaseV4Asset(ctx, tx, leaseOwner(doc), id) }); e != nil {
					return e
				}
			}
		}
	}
	delete(doc, "_v4Chunks")
	return nil
}
func (s *Service) leaseV4Asset(ctx context.Context, tx store.DBTX, owner, id string) error {
	_, e := s.exec(ctx, tx, "v4-lease", map[string]any{"owner": owner, "asset": id, "expiry": metastore.LeaseExpiry()})
	return e
}

func fileDigest(path string) (string, int64, error) {
	f, e := os.Open(path)
	if e != nil {
		return "", 0, e
	}
	defer f.Close()
	h := sha256.New()
	n, e := io.Copy(h, f)
	return hex.EncodeToString(h.Sum(nil)), n, e
}
func (s *Service) v4Index(ctx context.Context, job map[string]any, index map[string]any) (map[string]any, error) {
	index["version"] = 4
	index["metadataSchema"] = "1.0"
	index["collectionRevision"] = strconv.FormatInt(number(job["collection_revision"]), 10)
	folder, e := s.shareFolder(text(job["id"]))
	if e != nil {
		return nil, e
	}
	files := map[string]any{}
	for _, key := range []string{"parts", "deletions"} {
		for _, name := range stringsOf(index[key]) {
			if e = ctx.Err(); e != nil {
				return nil, e
			}
			digest, size, e := fileDigest(filepath.Join(folder, name))
			if e != nil {
				return nil, e
			}
			files[name] = map[string]any{"sha256": digest, "size": size}
		}
	}
	index["files"] = files
	digest, e := metastandard.DocumentDigest([]byte(encode(index)))
	if e != nil {
		return nil, e
	}
	index["contentDigest"] = digest
	return index, nil
}
func checkV4Index(index map[string]any) error {
	if e := onlyKeys(index, "format", "version", "metadataSchema", "id", "collectionId", "collectionRevision", "state", "total", "baseId", "deletions", "parts", "files", "contentDigest"); e != nil {
		return e
	}
	if !metastandard.UUID.MatchString(text(index["id"])) || !metastandard.UUID.MatchString(text(index["collectionId"])) || !metastandard.ValidRevision(text(index["collectionRevision"])) {
		return bad("共享标识或修订号无效")
	}
	if index["format"] != "singverse-share-set" || !v4Integer(index["version"], 4, 4) || !v4Base(index["baseId"]) || !v4Integer(index["total"], 0, 100000) {
		return bad("v4 清单的版本、基线或条目总数无效")
	}
	listed := map[string]bool{}
	for _, field := range []string{"parts", "deletions"} {
		switch index[field].(type) {
		case []any, []string:
		default:
			return bad("v4 分片清单必须为数组")
		}
		names := stringsOf(index[field])
		if len(names) > 4096 {
			return bad("v4 分片数量超过限制")
		}
		for _, name := range names {
			valid := partName.MatchString(name)
			if field == "deletions" {
				valid = deletionName.MatchString(name)
			}
			if !valid || listed[name] {
				return bad("v4 分片名称无效或重复")
			}
			listed[name] = true
		}
	}
	files, ok := index["files"].(map[string]any)
	if !ok || len(files) != len(listed) {
		return bad("分片完整性清单与分片列表不匹配")
	}
	for name, value := range files {
		file, ok := value.(map[string]any)
		if !ok || !listed[name] || !metastandard.SHA256.MatchString(text(file["sha256"])) || !v4Integer(file["size"], 1, shareLimit) {
			return bad("分片完整性条目无效")
		}
		if e := onlyKeys(file, "sha256", "size"); e != nil {
			return e
		}
	}
	claimed := text(index["contentDigest"])
	if !metastandard.SHA256.MatchString(claimed) || index["metadataSchema"] != "1.0" || index["state"] != "SUCCEEDED" {
		return bad("v4 清单版本或摘要无效")
	}
	copy := map[string]any{}
	for k, v := range index {
		if k != "contentDigest" {
			copy[k] = v
		}
	}
	actual, e := metastandard.DocumentDigest([]byte(encode(copy)))
	if e != nil || actual != claimed {
		return bad("分享清单摘要不匹配")
	}
	if _, ok := index["collectionRevision"].(string); !ok {
		return bad("修订号必须为字符串")
	}
	if _, ok := index["files"].(map[string]any); !ok {
		return bad("缺少分片完整性清单")
	}
	return nil
}

func v4Integer(value any, min, max int64) bool {
	encoded, err := json.Marshal(value)
	if err != nil {
		return false
	}
	n, err := strconv.ParseInt(string(encoded), 10, 64)
	return err == nil && n >= min && n <= max
}
func v4Base(value any) bool {
	base, ok := value.(string)
	return ok && (base == "" || metastandard.UUID.MatchString(base))
}

func checkV4Part(index map[string]any, name string, data []byte) error {
	files, _ := index["files"].(map[string]any)
	file, ok := files[name].(map[string]any)
	if !ok || !v4Integer(file["size"], int64(len(data)), int64(len(data))) || text(file["sha256"]) != metastandard.Digest(data) {
		return bad("分片大小或 SHA-256 不匹配")
	}
	return nil
}

// ValidateShareFile is usable without a database or running service. Temporary
// chunk files bound memory to one original resource, including very large sets.
func ValidateShareFile(filename string) error {
	return visitValidatedShareFile(filename, func(map[string]any) error { return nil })
}
func visitValidatedShareFile(filename string, visit func(map[string]any) error) error {
	folder, e := os.MkdirTemp("", "singverse-validate-*")
	if e != nil {
		return e
	}
	defer os.RemoveAll(folder)
	known := map[string]bool{}
	records := map[string]string{}
	resources := map[string]string{}
	return visitSharePackage(filename, func(doc map[string]any) error {
		if number(doc["version"]) != 4 {
			return visit(doc)
		}
		chunks, _ := doc["_v4Chunks"].(map[string][]byte)
		for id, data := range chunks {
			if e := atomicWrite(filepath.Join(folder, id), data); e != nil {
				return e
			}
		}
		for _, raw := range array(doc["resources"]) {
			r := raw.(map[string]any)
			id := text(r["id"])
			encoded := encode(r)
			if prior := resources[id]; prior != "" && prior != encoded {
				return bad("资源清单相互矛盾")
			}
			resources[id] = encoded
			var data bytes.Buffer
			complete := true
			for _, raw := range array(r["chunks"]) {
				c := raw.(map[string]any)
				b, e := os.ReadFile(filepath.Join(folder, text(c["id"])))
				if os.IsNotExist(e) {
					complete = false
					break
				}
				if e != nil {
					return e
				}
				if int64(len(b)) != number(c["size"]) {
					return bad("资源块大小无效")
				}
				data.Write(b)
			}
			if complete {
				if int64(data.Len()) != number(r["size"]) || metastandard.Digest(data.Bytes()) != id {
					return bad("原始资源摘要不匹配")
				}
				if _, _, e := metastandard.InspectAsset(data.Bytes(), text(r["mime"])); e != nil {
					return bad(e.Error())
				}
				known[id] = true
			}
		}
		for _, raw := range array(doc["entries"]) {
			entry := raw.(map[string]any)
			for _, raw := range array(entry["documents"]) {
				d, e := metastandard.Decode([]byte(encode(raw)))
				if e != nil {
					return e
				}
				digest, e := metastandard.DocumentDigest([]byte(encode(d)))
				if e != nil {
					return e
				}
				if prior := records[d.ID]; prior != "" && prior != digest {
					return bad("同一对象在包中出现不同内容")
				}
				records[d.ID] = digest
				for _, id := range metastandard.AssetIDs(d) {
					if !known[id] {
						return bad("缺少被引用的完整附件")
					}
				}
				for _, field := range []string{"cover", "portrait", "lyricsAsset"} {
					ref, ok := d.Fields[field].(map[string]any)
					if !ok {
						continue
					}
					var resource map[string]any
					if e := decode(resources[text(ref["assetId"])], &resource); e != nil {
						return e
					}
					mime := text(resource["mime"])
					if (field == "cover" || field == "portrait") != strings.HasPrefix(mime, "image/") {
						return bad("附件类型与正式字段不匹配")
					}
					if field == "lyricsAsset" {
						var data bytes.Buffer
						for _, raw := range array(resource["chunks"]) {
							chunk := raw.(map[string]any)
							b, e := os.ReadFile(filepath.Join(folder, text(chunk["id"])))
							if e != nil {
								return e
							}
							data.Write(b)
						}
						if _, e := metastandard.LyricsAsset(mime, data.Bytes()); e != nil {
							return bad(e.Error())
						}
					}
				}
			}
		}
		return visit(doc)
	})
}

func (s *Service) applyV4Documents(ctx context.Context, tx store.DBTX, media string, snapshot map[string]any, collection string, rev int64) ([]string, error) {
	if snapshot["documents"] == nil {
		return nil, nil
	}
	legacy, e := s.song(ctx, tx, media)
	if e != nil {
		return nil, e
	}
	sourceID := text(legacy["sourceId"])
	docs := []metastandard.Document{}
	byID := map[string]metastandard.Document{}
	for _, raw := range array(snapshot["documents"]) {
		d, e := metastandard.Decode([]byte(encode(raw)))
		if e != nil {
			return nil, e
		}
		docs = append(docs, d)
		byID[d.ID] = d
	}
	remoteMedia := text(snapshot["portableMediaId"])
	remoteVariant := byID[remoteMedia].ParentID
	remoteSong := byID[remoteVariant].ParentID
	if _, e := s.library.Formal().Read(ctx, tx, media); metastore.IsMissing(e) && media == remoteMedia {
		for _, spec := range []struct{ kind, id, parent string }{{"SONG", remoteSong, ""}, {"VARIANT", remoteVariant, remoteSong}, {"MEDIA", remoteMedia, remoteVariant}} {
			if _, e = s.library.Formal().Read(ctx, tx, spec.id); metastore.IsMissing(e) {
				if _, e = s.library.Formal().Create(ctx, tx, spec.kind, spec.id, spec.parent); e != nil {
					return nil, e
				}
			} else if e != nil {
				return nil, e
			}
		}
	}
	if e := s.library.SyncMetadata(ctx, tx, media); e != nil {
		return nil, e
	}
	song, variant, e := s.library.FormalIDs(ctx, tx, media)
	if e != nil {
		return nil, e
	}
	mapping := map[string]string{}
	for _, d := range docs {
		rows, e := s.rows(ctx, tx, "v4-alias-read", map[string]any{"sourceId": sourceID, "collectionId": collection, "remoteId": d.ID})
		if e != nil {
			return nil, e
		}
		local := d.ID
		if len(rows) > 0 {
			local = text(rows[0]["local_id"])
		} else {
			switch d.ID {
			case remoteMedia:
				local = media
			case remoteVariant:
				local = variant
			case remoteSong:
				local = song
			}
		}
		mapping[d.ID] = local
	}

	// A shared relationship cannot make local decisions on a pre-scanned song
	// disappear behind another parent. Keep that branch and merge its fields.
	protectedAssociation := false
	currentSong, e := s.library.Formal().Read(ctx, tx, song)
	if e != nil {
		return nil, e
	}
	currentVariant, e := s.library.Formal().Read(ctx, tx, variant)
	if e != nil {
		return nil, e
	}
	if mapping[remoteSong] != song && metastore.HasLocalDecision(currentSong) {
		mapping[remoteSong], mapping[remoteVariant] = song, variant
		protectedAssociation = true
	} else if mapping[remoteVariant] != variant && metastore.HasLocalDecision(currentVariant) {
		mapping[remoteVariant] = variant
		protectedAssociation = true
	}
	if protectedAssociation {
		if e := s.library.Formal().AssociationDifference(ctx, tx, media, "SHARED:"+collection, remoteVariant); e != nil {
			return nil, e
		}
	}
	// Parentless entities first, then songs, variants, media. No title matching.
	sort.SliceStable(docs, func(i, j int) bool {
		rank := func(k string) int {
			switch k {
			case "VARIANT":
				return 2
			case "MEDIA":
				return 3
			case "SONG":
				return 1
			}
			return 0
		}
		return rank(docs[i].Type) < rank(docs[j].Type)
	})
	for _, remote := range docs {
		id, parent := mapping[remote.ID], mapping[remote.ParentID]
		if _, err := s.library.Formal().Read(ctx, tx, id); metastore.IsMissing(err) {
			if _, err = s.library.Formal().Create(ctx, tx, remote.Type, id, parent); err != nil {
				return nil, err
			}
		} else if err != nil {
			return nil, err
		}
	}
	differences := []string{}
	for _, remote := range docs {
		id := mapping[remote.ID]
		parent := mapping[remote.ParentID]
		d, e := s.library.Formal().Read(ctx, tx, id)
		if metastore.IsMissing(e) {
			d, e = s.library.Formal().Create(ctx, tx, remote.Type, id, parent)
		}
		if e != nil {
			return nil, e
		}
		if d.Type != remote.Type {
			return nil, conflict("共享对象标识与本机类型冲突")
		}
		if (remote.Type == "MEDIA" || remote.Type == "VARIANT") && d.ParentID != parent && (d.ParentState == nil || d.ParentState.Decision == "AUTO") {
			d, e = s.library.Formal().LinkEvidence(ctx, tx, id, parent, d.Revision, metastandard.Origin{Kind: "SHARED", CollectionID: collection, Revision: strconv.FormatInt(rev, 10)})
			if e != nil {
				return nil, e
			}
		}
		fields := map[string]any{}
		clears := []string{}
		for name, value := range remote.Fields {
			if value == nil {
				if remote.States[name].Status == "CLEARED" {
					clears = append(clears, name)
				}
				continue
			}
			fields[name] = remapReferences(name, value, mapping)
		}
		o := metastandard.Origin{Kind: "SHARED", CollectionID: collection, Revision: strconv.FormatInt(rev, 10)}
		_, diff, e := s.library.Formal().Observe(ctx, tx, id, metastore.Evidence{Origin: o, Revision: o.Revision, Fields: fields, Clears: clears})
		if e != nil {
			return nil, e
		}
		differences = append(differences, diff...)
		// Optional extension bytes are retained even when this implementation
		// does not interpret them; unsupported required capabilities fail Decode.
		if len(remote.Extensions) > 0 {
			if e = s.library.Formal().Extensions(ctx, tx, id, remote.Extensions); e != nil {
				return nil, e
			}
		}
		if _, e = s.exec(ctx, tx, "v4-alias-insert", map[string]any{"sourceId": sourceID, "collectionId": collection, "remoteId": remote.ID, "localId": id}); e != nil {
			return nil, e
		}
	}

	if protectedAssociation {
		differences = append(differences, "parentId")
	}
	if mapping[remoteVariant] != variant {
		if e := s.library.Formal().RetireEmptyPlaceholder(ctx, tx, variant); e != nil {
			return nil, e
		}
	}
	if mapping[remoteSong] != song {
		if e := s.library.Formal().RetireEmptyPlaceholder(ctx, tx, song); e != nil {
			return nil, e
		}
	}
	return differences, nil
}
func remapReferences(name string, value any, ids map[string]string) any {
	if name == "workId" || name == "releaseId" {
		if id, ok := value.(string); ok && ids[id] != "" {
			return ids[id]
		}
	}
	if name == "tagIds" {
		if list, ok := value.([]any); ok {
			out := []any{}
			for _, raw := range list {
				id, _ := raw.(string)
				if ids[id] != "" {
					out = append(out, ids[id])
				} else {
					out = append(out, raw)
				}
			}
			return out
		}
	}
	if name == "credits" {
		if list, ok := value.([]any); ok {
			for _, raw := range list {
				if credit, ok := raw.(map[string]any); ok {
					id, _ := credit["artistId"].(string)
					if ids[id] != "" {
						credit["artistId"] = ids[id]
					}
				}
			}
		}
	}
	return value
}

func leaseOwner(doc map[string]any) string {
	if owner := text(doc["_leaseOwner"]); owner != "" {
		return owner
	}
	return text(doc["setId"])
}

func onlyKeys(m map[string]any, allowed ...string) error {
	for k := range m {
		found := false
		for _, a := range allowed {
			if k == a {
				found = true
				break
			}
		}
		if !found {
			return bad("资料清单含有未知核心字段")
		}
	}
	return nil
}

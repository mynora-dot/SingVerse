package metadata

import (
	"context"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/metastandard"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/metastore"
	"strconv"
	"strings"

	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

// mergeShared stores shared evidence separately from local media and edits.
// Only a newer revision of the same collection may replace non-empty fields.
func (s *Service) mergeShared(ctx context.Context, tx store.DBTX, id string, snapshot map[string]any, collection string, revision int64, size *int64) (string, []string, error) {
	song, e := s.song(ctx, tx, id)
	if e != nil {
		return "", nil, e
	}
	rows, e := s.rows(ctx, tx, "shared-read", map[string]any{"songId": id})
	if e != nil {
		return "", nil, e
	}
	next := map[string]any{"tags": map[string]any{}}
	oldCollection := ""
	oldRevision := int64(0)
	if len(rows) > 0 {
		oldCollection = text(rows[0]["collection_id"])
		oldRevision = number(rows[0]["revision"])
		if e = decode(rows[0]["snapshot_json"], &next); e != nil {
			return "", nil, e
		}
	}
	if lyrics := snapshot["lyrics"]; sharedPresent(lyrics) {
		key, e := s.storeAsset([]byte(encode(lyrics)), ".lyrics.json")
		if e != nil {
			return "", nil, e
		}
		snapshot["lyricsRef"] = key
	}
	before := encode(next)
	contentBefore := sharedContent(next)
	owners, _ := next["sharedFields"].(map[string]any)
	if owners == nil {
		owners = map[string]any{}
		next["sharedFields"] = owners
	}
	adopt := func(field string, old, value any) bool {
		if !sharedPresent(value) {
			return false
		}
		owner, _ := owners[field].(map[string]any)
		for prefix := field; owner == nil; {
			at := strings.LastIndex(prefix, ".")
			if at < 0 {
				break
			}
			prefix = prefix[:at]
			owner, _ = owners[prefix].(map[string]any)
		}
		ownerCollection, ownerRevision := oldCollection, oldRevision
		if owner != nil {
			ownerCollection = text(owner["collectionId"])
			ownerRevision = number(owner["revision"])
		}
		if !sharedPresent(old) || (collection != "" && collection == ownerCollection && revision > ownerRevision) {
			owners[field] = map[string]any{"collectionId": collection, "revision": revision}
			return true
		}
		return false
	}
	same := collection != "" && collection == oldCollection
	newer := same && revision > oldRevision
	oldTags, _ := next["tags"].(map[string]any)
	if oldTags == nil {
		oldTags = map[string]any{}
		next["tags"] = oldTags
	}
	incoming, _ := snapshot["tags"].(map[string]any)
	differences := []string{}
	applied := metaOf(song)
	protected := func(f string) bool {
		return applied != nil && contains(applied.Fields, f) && contains([]string{"MANUAL", "UPLOAD", "PLATFORM", "DOWNLOAD"}, applied.Origins[f].Kind)
	}
	for _, f := range []string{"title", "artists", "album", "releaseDate"} {
		v := incoming[f]
		if !sharedPresent(v) {
			continue
		}
		if protected(f) {
			if encode(current(song)[f]) != encode(v) {
				differences = append(differences, f)
			}
		}
		if adopt(f, oldTags[f], v) {
			oldTags[f] = v
		}
	}
	if technical, ok := snapshot["technical"].(map[string]any); ok {
		prior, _ := next["technical"].(map[string]any)
		if prior == nil {
			prior = map[string]any{}
		}
		var merge func(string, map[string]any, map[string]any)
		merge = func(prefix string, old, incoming map[string]any) {
			for key, value := range incoming {
				field := prefix + "." + key
				if nested, ok := value.(map[string]any); ok {
					previous, _ := old[key].(map[string]any)
					if previous == nil {
						previous = map[string]any{}
					}
					merge(field, previous, nested)
					if len(previous) > 0 {
						old[key] = previous
					}
				} else if (field != "technical.duration" || decimal(value) > 0) && adopt(field, old[key], value) {
					old[key] = value
				}
			}
		}
		merge("technical", prior, technical)
		if len(prior) > 0 {
			next["technical"] = prior
		}
	}
	for _, f := range []string{"lyrics", "lyricsRef", "aliases", "evidence", "extractorVersion", "origins", "fields"} {
		v := snapshot[f]
		if v == nil {
			continue
		}
		if adopt(f, next[f], v) {
			next[f] = v
		}
	}
	cover := text(snapshot["coverRef"])
	if encoded := text(snapshot["coverData"]); encoded != "" {
		cover, e = s.InstallSharedCover(encoded)
		if e != nil {
			return "", nil, e
		}
	}
	if cover != "" {
		if !assetHash.MatchString(cover) {
			return "", nil, bad("共享封面标识无效")
		}
		if _, e = s.SharedCover(cover); e != nil {
			return "", nil, e
		}
		if protected("cover") {
			differences = append(differences, "cover")
		}
		if adopt("cover", next["cover"], cover) {
			next["cover"] = cover
		}
	}
	if len(rows) == 0 {
		oldCollection = collection
		oldRevision = revision
	}
	if newer {
		oldRevision = revision
	}
	next["collectionId"] = oldCollection
	changed := contentBefore != sharedContent(next)
	storedChanged := before != encode(next)
	revisionChanged := newer
	args := map[string]any{"songId": id, "sourceId": song["sourceId"], "collectionId": oldCollection, "revision": oldRevision, "snapshot": encode(next), "size": size, "now": now()}
	if _, e = s.exec(ctx, tx, "shared-insert", args); e != nil {
		return "", nil, e
	}
	if storedChanged || revisionChanged {
		if _, e = s.exec(ctx, tx, "shared-update", args); e != nil {
			return "", nil, e
		}
	}
	// Bootstrap an imported resource to avoid automatically extracting the same
	// cloud media again. Existing local FILE results and their identities survive.
	source, err := s.row(ctx, tx, "source", map[string]any{"id": song["sourceId"]})
	if err != nil {
		return "", nil, err
	}
	if text(source["source_type"]) == "WEBDAV" {
		_, cfg, e := s.ensureSource(ctx, tx, text(song["sourceId"]), true)
		if e != nil {
			return "", nil, e
		}
		if _, e = s.exec(ctx, tx, "dav-bootstrap", map[string]any{"sourceId": song["sourceId"], "binding": cfg["binding"], "songId": id}); e != nil {
			return "", nil, e
		}
	}
	if len(rows) == 0 {
		if _, e = s.exec(ctx, tx, "shared-markImported", args); e != nil {
			return "", nil, e
		}
	}
	if _, e = s.exec(ctx, tx, "shared-fromScan", args); e != nil {
		return "", nil, e
	}
	if snapshot["documents"] != nil {
		diff, err := s.applyV4Documents(ctx, tx, id, snapshot, collection, revision)
		if err != nil {
			return "", nil, err
		}
		differences = append(differences, diff...)
	} else if e = s.applySharedFormal(ctx, tx, id, snapshot, collection, revision); e != nil {
		return "", nil, e
	}
	if changed {
		if e = s.Touch(ctx, tx, id); e != nil {
			return "", nil, e
		}
	}
	if len(differences) > 0 {
		return "PRESERVED", differences, nil
	}
	if changed {
		return "UPDATED", differences, nil
	}
	return "UNCHANGED", differences, nil
}

func (s *Service) applySharedFormal(ctx context.Context, tx store.DBTX, id string, snapshot map[string]any, collection string, revision int64) error {
	if e := s.library.SyncMetadata(ctx, tx, id); e != nil {
		return e
	}
	song, variant, e := s.library.FormalIDs(ctx, tx, id)
	if e != nil {
		return e
	}
	o := metastandard.Origin{Kind: "SHARED", CollectionID: collection, Revision: strconv.FormatInt(max(revision, 1), 10)}
	// Old unversioned v2 files have no update identity. A package-specific key
	// is evidence only; it never enrolls the receiver into a shared series.
	key := ""
	if collection == "" {
		key = "SHARED:legacy:" + hash(encode(snapshot))[:32]
	}
	songFields := map[string]any{}
	tags, _ := snapshot["tags"].(map[string]any)
	for _, f := range []string{"title", "artists", "album", "releaseDate"} {
		if sharedPresent(tags[f]) {
			songFields[f] = tags[f]
		}
	}
	if sharedPresent(snapshot["aliases"]) {
		songFields["aliases"] = snapshot["aliases"]
	}
	if n := number(snapshot["referenceDurationMs"]); n > 0 {
		songFields["referenceDurationMs"] = n
	}
	cover := text(snapshot["coverRef"])
	if cover == "" {
		cover = text(snapshot["cover"])
	}
	if cover != "" {
		data, e := s.SharedCover(cover)
		if e != nil {
			return e
		}
		c, e := validateCover(data)
		if e != nil {
			return e
		}
		a, e := s.library.Formal().PutAsset(ctx, tx, data, c.MIME)
		if e != nil {
			return e
		}
		if e = s.library.Formal().Alias(ctx, tx, cover, a.ID); e != nil {
			return e
		}
		songFields["cover"] = metastandard.AssetRef{AssetID: a.ID}
	}
	// Legacy packages admitted wider text and technical shapes. Retain their
	// original snapshot, but do not make invalid fields formal or block startup.
	for name, value := range songFields {
		if metastandard.ValidateField("SONG", name, value) != nil {
			delete(songFields, name)
		}
	}
	if len(songFields) > 0 {
		if _, _, e = s.library.Formal().Observe(ctx, tx, song, metastore.Evidence{Key: key, Origin: o, Revision: o.Revision, Fields: songFields}); e != nil {
			return e
		}
	}
	variantFields := map[string]any{}
	if lyrics, ok := snapshot["lyrics"].(map[string]any); ok && len(lyrics) > 0 && metastandard.ValidateField("VARIANT", "lyrics", lyrics) == nil {
		a, e := s.library.Formal().PutAsset(ctx, tx, []byte(encode(lyrics)), "application/json")
		if e != nil {
			return e
		}
		variantFields["lyrics"] = lyrics
		variantFields["lyricsAsset"] = metastandard.AssetRef{AssetID: a.ID}
	}
	if len(variantFields) > 0 {
		if _, _, e = s.library.Formal().Observe(ctx, tx, variant, metastore.Evidence{Key: key, Origin: o, Revision: o.Revision, Fields: variantFields}); e != nil {
			return e
		}
	}
	if technical, ok := snapshot["technical"].(map[string]any); ok && len(technical) > 0 {
		fields := map[string]any{"observation": metastandard.NormalizeObservation(technical)}
		if d := decimal(technical["duration"]); d > 0 && d <= 604800 {
			fields["durationMs"] = int64(d * 1000)
		}
		if metastandard.ValidateField("MEDIA", "observation", fields["observation"]) != nil {
			delete(fields, "observation")
		}
		if _, _, e = s.library.Formal().Observe(ctx, tx, id, metastore.Evidence{Key: key, Origin: o, Revision: o.Revision, Fields: fields}); e != nil {
			return e
		}
	}
	return nil
}

func (s *Service) sharedSnapshot(ctx context.Context, tx store.DBTX, id string) (map[string]any, error) {
	rows, e := s.rows(ctx, tx, "shared-read", map[string]any{"songId": id})
	if e != nil {
		return nil, e
	}
	out := map[string]any{}
	if len(rows) > 0 {
		e = decode(rows[0]["snapshot_json"], &out)
	}
	return out, e
}

func sharedIdentity(doc map[string]any) (string, int64, error) {
	if number(doc["version"]) == 4 {
		if revision, ok := doc["collectionRevision"].(string); !ok || !metastandard.ValidRevision(revision) {
			return "", 0, bad("v4 修订号必须为字符串")
		}
	}
	if number(doc["version"]) == 2 {
		return "", 0, nil
	}
	id := text(doc["collectionId"])
	rev := number(doc["collectionRevision"])
	if !uuidPattern.MatchString(id) || rev < 1 || !uuidPattern.MatchString(text(doc["setId"])) {
		return "", 0, bad("共享曲库标识或版本无效")
	}
	return id, rev, nil
}

func (s *Service) checkSharedBaseline(ctx context.Context, tx store.DBTX, source string, doc map[string]any) error {
	if number(doc["version"]) == 4 && text(doc["contentDigest"]) != "" {
		if e := s.library.Formal().CheckPackage(ctx, tx, text(doc["collectionId"]), text(doc["collectionRevision"]), text(doc["contentDigest"])); e != nil {
			return e
		}
	}
	collection, rev, e := sharedIdentity(doc)
	if e != nil {
		return e
	}
	if collection == "" {
		return nil
	}
	rows, e := s.rows(ctx, tx, "shared-baseline", map[string]any{"sourceId": source, "collectionId": collection})
	if e != nil {
		return e
	}
	if len(rows) > 0 && number(rows[0]["revision"]) > rev {
		return conflict("此资料包比已导入版本旧，请使用最新资料包")
	}
	base := text(doc["baseId"])
	if base != "" && (len(rows) == 0 || (text(rows[0]["snapshot_id"]) != base && text(rows[0]["snapshot_id"]) != text(doc["setId"]))) {
		return conflict("缺少此增量包对应的基线，请先导入完整资料包")
	}
	return nil
}
func (s *Service) saveSharedBaseline(ctx context.Context, tx store.DBTX, source string, doc map[string]any) error {
	collection, rev, e := sharedIdentity(doc)
	if e != nil || collection == "" {
		return e
	}
	a := map[string]any{"sourceId": source, "collectionId": collection, "revision": rev, "snapshotId": text(doc["setId"])}
	if _, e = s.exec(ctx, tx, "shared-baselineInsert", a); e != nil {
		return e
	}
	_, e = s.exec(ctx, tx, "shared-baselineUpdate", a)
	return e
}

// normalizeSharedAssets keeps only validated content-addressed references in
// persistent import entries, rather than duplicating a cover for every song.
func (s *Service) normalizeSharedAssets(doc map[string]any) error {
	if number(doc["version"]) == 4 {
		return s.installV4Resources(s.ctx, doc)
	}
	if raw := doc["assets"]; raw != nil {
		assets, ok := raw.(map[string]any)
		if !ok {
			return bad("共享资源清单无效")
		}
		if len(assets) > 5000 {
			return bad("共享资源清单无效")
		}
		for key, v := range assets {
			encoded, ok := v.(string)
			if !ok || !assetHash.MatchString(key) || hash(encoded) != key {
				return bad("共享资源校验失败")
			}
			actual, e := s.InstallSharedCover(encoded)
			if e != nil || actual != key {
				return bad("共享封面校验失败")
			}
		}
	}
	for _, raw := range array(doc["entries"]) {
		item, ok := raw.(map[string]any)
		if !ok {
			return bad("索引包条目无效")
		}
		relative, ok := item["path"].(string)
		if !ok || !validPortablePath(relative) || !mediaPath(relative) {
			return bad("歌曲路径无效")
		}
		if _, e := shareSize(item["size"]); e != nil {
			return e
		}
		snap, ok := item["metadata"].(map[string]any)
		if !ok {
			return bad("共享元数据无效")
		}
		if e := validateSnapshot(snap); e != nil {
			return e
		}
		if encoded := text(snap["coverData"]); encoded != "" {
			key, e := s.InstallSharedCover(encoded)
			if e != nil {
				return e
			}
			snap["coverRef"] = key
			delete(snap, "coverData")
		}
		if key := text(snap["coverRef"]); key != "" {
			if !assetHash.MatchString(key) {
				return bad("共享封面标识无效")
			}
			if _, e := s.SharedCover(key); e != nil {
				return bad("缺少共享封面资源，请导入完整资料包")
			}
		}
	}
	return nil
}

func mapSharedPath(relative, remove, add string) (string, error) {
	for _, p := range []string{remove, add} {
		if p != "" && !validPortablePath(p) {
			return "", bad("目录前缀必须为相对目录，不能包含空段或 ..")
		}
	}
	if remove != "" {
		if !strings.HasPrefix(relative, remove+"/") {
			return relative, nil
		}
		relative = strings.TrimPrefix(relative, remove+"/")
	}
	if add != "" {
		relative = add + "/" + relative
	}
	if !validPortablePath(relative) || !mediaPath(relative) {
		return "", bad("映射后的媒体路径无效")
	}
	return relative, nil
}

func sharedContent(value map[string]any) string {
	out := map[string]any{}
	for k, v := range value {
		if k != "sharedFields" && k != "collectionId" {
			out[k] = v
		}
	}
	return encode(out)
}

func sharedPresent(v any) bool {
	switch value := v.(type) {
	case string:
		return strings.TrimSpace(value) != ""
	case []string:
		for _, item := range value {
			if sharedPresent(item) {
				return true
			}
		}
		return false
	case []any:
		for _, item := range value {
			if sharedPresent(item) {
				return true
			}
		}
		return false
	case map[string]any:
		for _, item := range value {
			if sharedPresent(item) {
				return true
			}
		}
		return false
	}
	return present(v)
}

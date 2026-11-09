package metadata

import (
	"bytes"
	"context"
	"encoding/base64"
	"github.com/gin-gonic/gin"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
	"image"
	"image/png"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"
)

var groupPattern = regexp.MustCompile(`^[A-Za-z0-9_-]{1,64}$`)
var assetHash = regexp.MustCompile(`^[a-f0-9]{64}$`)

func (s *Service) ensureSource(ctx context.Context, db store.DBTX, id string, dav bool) (map[string]any, map[string]any, error) {
	source, e := s.row(ctx, db, "source", map[string]any{"id": id})
	if e != nil {
		return nil, nil, absent(e, "媒体来源不存在")
	}
	if dav && text(source["source_type"]) != "WEBDAV" {
		return nil, nil, bad("仅适用于 WebDAV 媒体库")
	}
	u, e := url.Parse(text(source["location"]))
	if e != nil {
		return nil, nil, bad("媒体来源地址无效")
	}
	a := map[string]any{"sourceId": id, "binding": hash(text(source["location"])), "group": hash(u.Scheme + "://" + u.Host + "\x00" + text(source["username"]))}
	if _, e = s.exec(ctx, db, "dav-configInsert", a); e != nil {
		return nil, nil, e
	}
	cfg, e := s.row(ctx, db, "dav-config", a)
	if e != nil {
		return nil, nil, e
	}
	if text(cfg["binding"]) != a["binding"] {
		return nil, nil, conflict("媒体来源绑定已变化，请重新扫描")
	}
	return source, cfg, nil
}
func (s *Service) prepare(ctx context.Context, db store.DBTX, id string, dav bool) error {
	_, cfg, e := s.ensureSource(ctx, db, id, dav)
	if e != nil {
		return e
	}
	_, e = s.exec(ctx, db, "dav-bootstrap", map[string]any{"sourceId": id, "binding": cfg["binding"], "songId": nil})
	return e
}
func (s *Service) budget(ctx context.Context, db store.DBTX, group string) (map[string]any, error) {
	zone, _ := time.LoadLocation("Asia/Shanghai")
	a := map[string]any{"group": group, "day": time.Now().In(zone).Format("2006-01-02")}
	if _, e := s.exec(ctx, db, "dav-budgetInsert", a); e != nil {
		return nil, e
	}
	if _, e := s.exec(ctx, db, "dav-budgetReset", a); e != nil {
		return nil, e
	}
	return s.row(ctx, db, "dav-budget", a)
}
func (s *Service) summary(ctx context.Context, db store.DBTX, id string) (map[string]any, error) {
	source, e := s.row(ctx, db, "source", map[string]any{"id": id})
	if e != nil {
		return nil, absent(e, "媒体来源不存在")
	}
	args := map[string]any{"sourceId": id}
	files := map[string]any{"directory": "library/" + id}
	for key, name := range map[string]string{"pending": "files-pendingSource", "failed": "files-failures", "attachments": "files-attachments"} {
		rows, e := s.rows(ctx, db, name, args)
		if e != nil {
			return nil, e
		}
		count := int64(0)
		if len(rows) > 0 {
			for _, v := range rows[0] {
				count = number(v)
				break
			}
		}
		files[key] = count
	}
	if text(source["source_type"]) == "LOCAL" {
		r, e := s.row(ctx, db, "source-countSongs", args)
		if e != nil {
			return nil, e
		}
		return map[string]any{"enabled": false, "paused": false, "accessGroup": "", "privateOrigins": []string{}, "counts": map[string]any{"COMPLETE": number(r["count"])}, "presence": map[string]any{}, "budget": map[string]any{"files_started": 0, "bytes_read": 0}, "emptyScans": 0, "canConfirmEmpty": false, "files": files}, nil
	}
	_, cfg, e := s.ensureSource(ctx, db, id, true)
	if e != nil {
		return nil, e
	}
	counts, e := s.rows(ctx, db, "dav-counts", args)
	if e != nil {
		return nil, e
	}
	states := map[string]int64{}
	for _, r := range counts {
		states[text(r["state"])] = number(r["total"])
	}
	presence, e := s.row(ctx, db, "dav-presence", args)
	if e != nil {
		return nil, e
	}
	for _, k := range []string{"missing", "archived", "changed"} {
		presence[k] = number(presence[k])
	}
	budget, e := s.budget(ctx, db, text(cfg["access_group"]))
	if e != nil {
		return nil, e
	}
	emptySince, e := time.Parse(time.RFC3339Nano, text(cfg["empty_since"]))
	canConfirm := e == nil && number(cfg["empty_scans"]) >= 2 && time.Since(emptySince) >= 30*time.Minute
	out := map[string]any{"enabled": boolean(cfg["enabled"]), "paused": boolean(cfg["paused"]), "accessGroup": cfg["access_group"], "privateOrigins": stringsFromJSON(cfg["private_origins"]), "blockedUntil": cfg["blocked_until"], "blockedReason": cfg["blocked_reason"], "emptyScans": number(cfg["empty_scans"]), "emptySince": cfg["empty_since"], "canConfirmEmpty": canConfirm, "counts": states, "presence": presence, "budget": budget, "nodeStatus": "NOT_CHECKED", "remoteReady": false, "nodeMessage": "等待执行时检查计算节点能力", "files": files}
	s.hookMu.RLock()
	for k, v := range s.remoteState {
		out[k] = v
	}
	s.hookMu.RUnlock()
	return out, nil
}
func (s *Service) summaryHTTP(c *gin.Context) {
	out, e := s.summary(c.Request.Context(), s.db, c.Param("id"))
	if e != nil {
		s.fail(c, e)
		return
	}
	c.JSON(200, out)
}
func checkedOrigin(v string) (string, error) {
	u, e := url.Parse(v)
	if e != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Hostname() == "" || u.User != nil || u.RawQuery != "" || u.ForceQuery || u.Fragment != "" || (u.Path != "" && u.Path != "/") {
		return "", bad("私有代理地址须为 HTTP(S) 来源地址，不能包含路径或凭据")
	}
	return origin(u), nil
}
func origin(u *url.URL) string {
	host := strings.ToLower(u.Hostname())
	if strings.Contains(host, ":") {
		host = "[" + host + "]"
	}
	port := u.Port()
	if port != "" && !((port == "80" && u.Scheme == "http") || (port == "443" && u.Scheme == "https")) {
		host += ":" + port
	}
	return u.Scheme + "://" + host
}
func (s *Service) configureDAV(c *gin.Context) {
	var in struct {
		Enabled        bool      `json:"enabled"`
		Paused         bool      `json:"paused"`
		AccessGroup    *string   `json:"accessGroup"`
		PrivateOrigins *[]string `json:"privateOrigins"`
	}
	if !s.decode(c, &in) {
		return
	}
	ctx := c.Request.Context()
	id := c.Param("id")
	e := s.transaction(ctx, func(tx store.DBTX) error {
		_, cfg, e := s.ensureSource(ctx, tx, id, true)
		if e != nil {
			return e
		}
		group := text(cfg["access_group"])
		if in.AccessGroup != nil {
			group = strings.TrimSpace(*in.AccessGroup)
		}
		if !groupPattern.MatchString(group) {
			return bad("访问组仅支持 1–64 位字母、数字、下划线和连字符")
		}
		origins := stringsFromJSON(cfg["private_origins"])
		if in.PrivateOrigins != nil {
			if len(*in.PrivateOrigins) > 16 {
				return bad("最多配置 16 个私有代理地址")
			}
			origins = []string{}
			for _, v := range *in.PrivateOrigins {
				v, e = checkedOrigin(v)
				if e != nil {
					return e
				}
				if !contains(origins, v) {
					origins = append(origins, v)
				}
			}
		}
		if _, e = s.exec(ctx, tx, "dav-configSave", map[string]any{"sourceId": id, "enabled": in.Enabled, "paused": in.Paused, "group": group, "origins": encode(origins)}); e != nil {
			return e
		}
		if e = s.prepare(ctx, tx, id, true); e != nil {
			return e
		}
		_, e = s.exec(ctx, tx, "dav-queue", map[string]any{"sourceId": id, "now": now()})
		return e
	})
	if e != nil {
		s.fail(c, e)
		return
	}
	out, e := s.summary(ctx, s.db, id)
	if e != nil {
		s.fail(c, e)
		return
	}
	c.JSON(200, out)
}
func (s *Service) detail(ctx context.Context, db store.DBTX, id string) (map[string]any, error) {
	if _, e := s.song(ctx, db, id); e != nil {
		return nil, e
	}
	rows, e := s.rows(ctx, db, "dav-resource", map[string]any{"songId": id})
	if e != nil {
		return nil, e
	}
	if len(rows) == 0 {
		return map[string]any{"state": "NOT_WEBDAV"}, nil
	}
	row := rows[0]
	out := map[string]any{}
	for _, f := range []string{"state", "generation", "changed", "missing_scans", "bytes_read", "request_count", "elapsed_ms", "updated_at"} {
		out[f] = row[f]
	}
	var result any
	if row["result_json"] != nil {
		if e = decode(row["result_json"], &result); e != nil {
			return nil, e
		}
	}
	out["result"] = result
	return out, nil
}
func (s *Service) detailHTTP(c *gin.Context) {
	out, e := s.detail(c.Request.Context(), s.db, c.Param("id"))
	if e != nil {
		s.fail(c, e)
		return
	}
	c.JSON(200, out)
}
func (s *Service) refreshHTTP(c *gin.Context) {
	var in struct {
		SongIDs []string `json:"songIds"`
		Deep    bool     `json:"deep"`
	}
	if !s.decode(c, &in) {
		return
	}
	seen := map[string]bool{}
	if len(in.SongIDs) == 0 || len(in.SongIDs) > 100 {
		s.fail(c, bad("请选择 1–100 首不同歌曲"))
		return
	}
	for _, id := range in.SongIDs {
		if seen[id] || !idPattern.MatchString(id) {
			s.fail(c, bad("请选择 1–100 首不同歌曲"))
			return
		}
		seen[id] = true
	}
	ctx := c.Request.Context()
	e := s.transaction(ctx, func(tx store.DBTX) error {
		for _, id := range in.SongIDs {
			song, e := s.song(ctx, tx, id)
			if e != nil {
				return e
			}
			sourceID := text(song["sourceId"])
			if e = s.prepare(ctx, tx, sourceID, true); e != nil {
				return e
			}
			_, cfg, e := s.ensureSource(ctx, tx, sourceID, true)
			if e != nil {
				return e
			}
			if boolean(cfg["paused"]) {
				return conflict("请先继续提取，再执行单次读取")
			}
			r, e := s.row(ctx, tx, "dav-resource", map[string]any{"songId": id})
			if e != nil {
				return e
			}
			if in.Deep && text(r["state"]) == "RUNNING" {
				return conflict("歌曲正在提取，请等待完成后再选择深度提取")
			}
			a := map[string]any{"songId": id, "taskId": uuid(), "now": now()}
			if _, e = s.exec(ctx, tx, "dav-refresh", a); e != nil {
				return e
			}
			if _, e = s.exec(ctx, tx, "dav-queueManual", a); e != nil {
				return e
			}
			r, e = s.row(ctx, tx, "dav-resource", a)
			if e != nil {
				return e
			}
			a["generation"], a["deep"] = r["generation"], in.Deep
			if _, e = s.exec(ctx, tx, "dav-makeManual", a); e != nil {
				return e
			}
			if _, e = s.exec(ctx, tx, "dav-deep", a); e != nil {
				return e
			}
		}
		return nil
	})
	if e != nil {
		s.fail(c, e)
		return
	}
	c.JSON(202, map[string]any{"accepted": len(in.SongIDs)})
}
func (s *Service) confirmEmpty(c *gin.Context) {
	ctx := c.Request.Context()
	id := c.Param("id")
	e := s.transaction(ctx, func(tx store.DBTX) error {
		if _, _, e := s.ensureSource(ctx, tx, id, true); e != nil {
			return e
		}
		if _, e := s.exec(ctx, tx, "dav-lockSource", map[string]any{"sourceId": id}); e != nil {
			return e
		}
		summary, e := s.summary(ctx, tx, id)
		if e != nil {
			return e
		}
		if !boolean(summary["canConfirmEmpty"]) {
			return conflict("需要间隔至少 30 分钟的两次完整空扫描")
		}
		a := map[string]any{"sourceId": id, "now": now()}
		if _, e = s.exec(ctx, tx, "dav-confirmEmpty", a); e != nil {
			return e
		}
		_, e = s.exec(ctx, tx, "dav-removeArchivedSongs", a)
		return e
	})
	if e != nil {
		s.fail(c, e)
		return
	}
	c.Status(204)
}
func (s *Service) embeddedCover(ctx context.Context, id string) ([]byte, error) {
	detail, e := s.detail(ctx, s.db, id)
	if e != nil {
		return nil, e
	}
	result, _ := detail["result"].(map[string]any)
	key := text(result["cover"])
	if !assetHash.MatchString(key) {
		return nil, missing("没有内嵌封面")
	}
	return s.SharedCover(key)
}
func (s *Service) embeddedCoverHTTP(c *gin.Context) {
	b, e := s.embeddedCover(c.Request.Context(), c.Param("id"))
	if e != nil {
		s.fail(c, e)
		return
	}
	cover, e := validateCover(b)
	if e != nil {
		s.fail(c, e)
		return
	}
	imageResponse(c, cover)
}
func (s *Service) storeAsset(b []byte, suffix string) (string, error) {
	key := hash(base64.StdEncoding.EncodeToString(b))
	path := filepath.Join(s.cfg.MetadataPath, key+suffix)
	if st, e := os.Lstat(path); e == nil {
		if !st.Mode().IsRegular() {
			return "", conflict("元数据衍生路径无效")
		}
		return key, nil
	}
	if e := atomicWrite(path, b); e != nil {
		return "", &problem{503, "元数据衍生目录不可写"}
	}
	return key, nil
}
func (s *Service) SharedCover(key string) ([]byte, error) {
	if !assetHash.MatchString(key) {
		return nil, bad("无效资源哈希")
	}
	cover, e := readImage(filepath.Join(s.cfg.MetadataPath, key+".image"))
	if e != nil {
		return nil, conflict("缺少共享资源，请先导入前面的分片")
	}
	return cover.Bytes, nil
}
func (s *Service) InstallSharedCover(encoded string) (string, error) {
	if len(encoded) > 2_666_668 {
		return "", bad("共享封面超过限制")
	}
	b, e := base64.StdEncoding.DecodeString(encoded)
	if e != nil || base64.StdEncoding.EncodeToString(b) != encoded {
		return "", bad("共享封面无效")
	}
	if _, e = validateCover(b); e != nil {
		return "", bad("共享封面无效")
	}
	if _, _, e = image.Decode(bytes.NewReader(b)); e != nil {
		return "", bad("共享封面无效")
	}
	return s.storeAsset(b, ".image")
}
func (s *Service) portableCover(b []byte) (string, error) {
	if _, e := validateCover(b); e != nil {
		return "", e
	}
	img, _, e := image.Decode(bytes.NewReader(b))
	if e != nil {
		return "", bad("共享封面无效")
	}
	var out bytes.Buffer
	if e = png.Encode(&out, img); e != nil || out.Len() > 2_000_000 {
		return "", bad("共享封面超过限制")
	}
	return s.storeAsset(out.Bytes(), ".image")
}

func validateSnapshot(v map[string]any) error {
	tags, ok := v["tags"].(map[string]any)
	if !ok || len(encode(v)) > 4*1024*1024 {
		return bad("共享元数据格式无效或超过限制")
	}
	for _, f := range []string{"title", "album", "releaseDate"} {
		if tags[f] == nil {
			continue
		}
		t, ok := tags[f].(string)
		limit := 500
		if f == "releaseDate" {
			limit = 32
		}
		if !ok || len([]rune(t)) > limit {
			return bad("共享标签无效")
		}
	}
	if tags["artists"] != nil {
		if e := validateStrings(tags["artists"], 32, 200); e != nil {
			return e
		}
	}
	if v["aliases"] != nil {
		if e := validateStrings(v["aliases"], 32, 500); e != nil {
			return e
		}
	}
	if v["lyrics"] != nil && len(encode(v["lyrics"])) > 262144 {
		return bad("歌词超过限制")
	}
	if v["coverData"] != nil && len(text(v["coverData"])) > 2_666_668 {
		return bad("封面超过限制")
	}
	return nil
}
func validateStrings(v any, count, length int) error {
	a := array(v)
	if a == nil {
		if s, ok := v.([]string); ok {
			a = make([]any, len(s))
			for i, v := range s {
				a[i] = v
			}
		} else {
			return bad("文本数组无效")
		}
	}
	if len(a) > count {
		return bad("文本数组超过限制")
	}
	for _, item := range a {
		t, ok := item.(string)
		if !ok || len([]rune(t)) > length {
			return bad("文本数组无效")
		}
	}
	return nil
}

// Install applies a portable shared snapshot in the caller's transaction.
func (s *Service) Install(ctx context.Context, tx store.DBTX, id string, snapshot map[string]any) error {
	if e := validateSnapshot(snapshot); e != nil {
		return e
	}
	song, e := s.song(ctx, tx, id)
	if e != nil {
		return e
	}
	_, cfg, e := s.ensureSource(ctx, tx, text(song["sourceId"]), false)
	if e != nil {
		return e
	}
	if _, e = s.exec(ctx, tx, "dav-bootstrap", map[string]any{"sourceId": song["sourceId"], "binding": cfg["binding"], "songId": id}); e != nil {
		return e
	}
	result := map[string]any{}
	for _, key := range []string{"tags", "technical", "lyrics", "fields", "evidence", "extractorVersion", "aliases"} {
		if v, ok := snapshot[key]; ok {
			result[key] = v
		}
	}
	if v := snapshot["coverData"]; v != nil {
		key, e := s.InstallSharedCover(text(v))
		if e != nil {
			return e
		}
		result["cover"] = key
	}
	if v := snapshot["lyrics"]; v != nil {
		key, e := s.storeAsset([]byte(encode(v)), ".lyrics.json")
		if e != nil {
			return e
		}
		result["lyricsAsset"] = key
	}
	result["state"], result["origin"], result["importedAt"] = "IMPORTED", "SHARED", now()
	r, e := s.row(ctx, tx, "dav-resource", map[string]any{"songId": id})
	if e != nil {
		return e
	}
	a := map[string]any{"songId": id, "binding": r["binding"], "generation": r["generation"], "state": "IMPORTED", "result": encode(result), "tags": encode(snapshot["tags"]), "version": "shared-index-1", "now": now()}
	if _, e = s.exec(ctx, tx, "dav-finishResource", a); e != nil {
		return e
	}
	if _, e = s.exec(ctx, tx, "dav-applyTags", a); e != nil {
		return e
	}
	return s.Touch(ctx, tx, id)
}

// Snapshot returns portable metadata without upstream IO or opening another connection.
func (s *Service) Snapshot(ctx context.Context, tx store.DBTX, id string) (map[string]any, error) {
	if tx == nil {
		tx = s.db
	}
	song, e := s.song(ctx, tx, id)
	if e != nil {
		return nil, e
	}
	out := map[string]any{}
	rows, e := s.rows(ctx, tx, "dav-resource", map[string]any{"songId": id})
	if e != nil {
		return nil, e
	}
	if len(rows) > 0 && rows[0]["result_json"] != nil {
		var prior map[string]any
		if e = decode(rows[0]["result_json"], &prior); e != nil {
			return nil, e
		}
		for _, f := range []string{"tags", "technical", "lyrics", "fields", "extractorVersion", "evidence", "aliases", "cover"} {
			if v, ok := prior[f]; ok {
				out[f] = v
			}
		}
	}
	local, e := s.row(ctx, tx, "file-embedded", map[string]any{"id": id})
	if e != nil {
		return nil, e
	}
	if local["embedded_tags_json"] != nil {
		var embedded map[string]any
		if e = decode(local["embedded_tags_json"], &embedded); e != nil {
			return nil, e
		}
		if embedded["lyrics"] != nil {
			out["lyrics"] = embedded["lyrics"]
			coverage, _ := out["fields"].(map[string]any)
			if coverage == nil {
				coverage = map[string]any{}
				out["fields"] = coverage
			}
			coverage["lyrics"] = "PRESENT"
		}
	}

	tags, _ := out["tags"].(map[string]any)
	if tags == nil {
		tags = map[string]any{}
		out["tags"] = tags
	}
	values := current(song)
	for _, f := range []string{"title", "artists", "album", "releaseDate"} {
		if present(values[f]) {
			tags[f] = values[f]
		}
	}
	if out["evidence"] == nil {
		out["evidence"] = "FILENAME"
	}
	if applied := metaOf(song); applied != nil {
		out["evidence"] = "APPLIED"
		out["origins"] = applied.Origins
		coverage, _ := out["fields"].(map[string]any)
		if coverage == nil {
			coverage = map[string]any{}
			out["fields"] = coverage
		}
		for _, f := range applied.Fields {
			if contains([]string{"title", "artists", "album", "releaseDate"}, f) {
				coverage[f] = "PRESENT"
			}
		}
		if contains(applied.Fields, "aliases") {
			out["aliases"] = applied.Track.Aliases
		}
		if contains(applied.Fields, "durationMs") && applied.Track.DurationMs != nil {
			tech, _ := out["technical"].(map[string]any)
			if tech == nil {
				tech = map[string]any{}
				out["technical"] = tech
			}
			tech["duration"] = float64(*applied.Track.DurationMs) / 1000
		}
		if contains(applied.Fields, "cover") && applied.Track.CoverURL != nil {
			raw := *applied.Track.CoverURL
			out["coverKey"] = hash(raw)
			var cover Cover
			var coverErr error
			if strings.HasPrefix(raw, "asset:") {
				assetID := strings.TrimPrefix(raw, "asset:")
				if !uuidPattern.MatchString(assetID) {
					return nil, bad("封面资源标识无效")
				}
				cover, coverErr = readImage(filepath.Join(s.cfg.MetadataCachePath, "uploads", assetID+".image"))
			} else if strings.HasPrefix(raw, "embedded:") {
				coverErr = nil
				if _, ok := out["cover"]; !ok {
					coverErr = missing("没有封面")
				}
			} else {
				provider := applied.Origins["cover"].Provider
				if provider == nil {
					coverErr = missing("没有封面")
				} else {
					cover, coverErr = readImage(filepath.Join(s.cfg.MetadataCachePath, hash(*provider+":"+raw)+".image"))
				}
			}
			if coverErr == nil && len(cover.Bytes) > 0 {
				key, e := s.portableCover(cover.Bytes)
				if e != nil {
					return nil, e
				}
				out["cover"] = key
				coverage["cover"] = "PRESENT"
			} else if coverErr != nil {
				coverage["cover"] = "UNKNOWN"
				delete(out, "cover")
			}
		}
	}
	return out, nil
}

package metadata

import (
	"context"
	"encoding/json"
	"github.com/gin-gonic/gin"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
	"regexp"
	"sort"
	"time"
)

var uuidPattern = regexp.MustCompile(`^[a-fA-F0-9-]{36}$`)

func (s *Service) reviewHTTP(c *gin.Context) {
	ctx := c.Request.Context()
	song, e := s.song(ctx, s.db, c.Param("songId"))
	if e != nil {
		s.fail(c, e)
		return
	}
	c.JSON(200, Review{publicSong(song), fingerprint(song), version(song), current(song)})
}
func (s *Service) reviewApplyHTTP(c *gin.Context) {
	var in ReviewApply
	if !s.decode(c, &in) {
		return
	}
	ctx := c.Request.Context()
	var out ReviewResult
	e := s.transaction(ctx, func(tx store.DBTX) error {
		var e error
		out, e = s.reviewApply(ctx, tx, c.Param("songId"), in)
		return e
	})
	if e != nil {
		s.fail(c, e)
		return
	}
	c.JSON(200, out)
}
func editValue(field string, value any) (any, error) {
	if value == nil {
		return nil, nil
	}
	if field == "artists" || field == "aliases" {
		var items []any
		switch v := value.(type) {
		case []any:
			items = v
		case []string:
			for _, s := range v {
				items = append(items, s)
			}
		default:
			return nil, bad("歌手/别名必须为最多 20 项的文本数组")
		}
		if len(items) > 20 {
			return nil, bad("歌手/别名必须为最多 20 项的文本数组")
		}
		out := []string{}
		for _, item := range items {
			v, ok := item.(string)
			if !ok {
				return nil, bad("歌手/别名格式无效")
			}
			v, e := clean(v, 200)
			if e != nil {
				return nil, e
			}
			if v != "" && !contains(out, v) {
				out = append(out, v)
			}
		}
		return out, nil
	}
	if field == "durationMs" {
		var n int64
		switch v := value.(type) {
		case json.Number:
			i, e := v.Int64()
			if e != nil {
				return nil, bad("平台时长必须是整数毫秒")
			}
			n = i
		case int:
			n = int64(v)
		case int64:
			n = v
		case float64:
			if v != float64(int64(v)) {
				return nil, bad("平台时长必须是整数毫秒")
			}
			n = int64(v)
		default:
			return nil, bad("平台时长必须是整数毫秒")
		}
		if n < 1 || n > 86_400_000 {
			return nil, bad("平台时长必须是 1～86400000 的整数毫秒")
		}
		return int(n), nil
	}
	v, ok := value.(string)
	if !ok {
		return nil, bad("元数据字段必须为文本")
	}
	limit := 500
	if field == "releaseDate" {
		limit = 10
	}
	v, e := clean(v, limit)
	if e != nil {
		return nil, e
	}
	if v == "" {
		return nil, nil
	}
	if field == "releaseDate" {
		layout := "2006-01-02"
		if len(v) == 4 {
			layout = "2006"
		} else if len(v) == 7 {
			layout = "2006-01"
		}
		if _, e = time.Parse(layout, v); e != nil {
			return nil, bad("发行日期须为有效的 YYYY、YYYY-MM 或 YYYY-MM-DD")
		}
	}
	return v, nil
}
func (s *Service) ownedCandidate(ctx context.Context, tx store.DBTX, songID, id, fp string) (Track, error) {
	r, t, e := s.candidate(ctx, tx, id)
	if e != nil {
		return t, e
	}
	if text(r["song_id"]) != songID {
		return t, bad("候选不属于当前歌曲")
	}
	if text(r["fingerprint"]) != fp {
		return t, conflict("文件已变化，请重新匹配")
	}
	return t, nil
}
func (s *Service) reviewApply(ctx context.Context, tx store.DBTX, id string, in ReviewApply) (ReviewResult, error) {
	out := ReviewResult{}
	if !validCoverQuality(in.CoverQuality) {
		return out, bad("请选择原图或高清封面")
	}
	fields, e := selectedFields(in.Fields, false)
	if e != nil || !uuidPattern.MatchString(in.RequestID) {
		return out, bad("请选择有效的应用字段并提供提交标识")
	}
	for f := range in.Edits {
		if !contains(allFields, f) || f == "cover" {
			return out, bad("编辑字段无效，封面必须通过受管资源选择")
		}
	}
	requestHash := hash(encode(in))
	receipts, e := s.rows(ctx, tx, "review-receipt", map[string]any{"id": in.RequestID})
	if e != nil {
		return out, e
	}
	if len(receipts) > 0 {
		r := receipts[0]
		if text(r["song_id"]) != id || text(r["request_hash"]) != requestHash {
			return out, conflict("提交标识已用于其他操作，请重新确认")
		}
		e = decode(r["response_json"], &out)
		return out, e
	}
	song, e := s.song(ctx, tx, id)
	if e != nil {
		return out, e
	}
	fp := fingerprint(song)
	if in.Fingerprint != fp || in.Version != version(song) {
		return out, conflict("歌曲文件或确认元数据已变化，请重新加载后审核；编辑内容尚未应用")
	}
	before := current(song)
	values := map[string]any{}
	for k, v := range before {
		values[k] = v
	}
	origins := map[string]Origin{}
	supplied := map[string]bool{}
	var candidate *Track
	at := now()
	if in.CandidateID != nil {
		t, e := s.ownedCandidate(ctx, tx, id, *in.CandidateID, fp)
		if e != nil {
			return out, e
		}
		candidate = &t
		for f, v := range trackValues(t) {
			values[f] = v
			origins[f] = Origin{t.Provider, t.ExternalID, at, "PLATFORM"}
			supplied[f] = true
		}
	}
	for f, v := range in.Edits {
		if !contains(fields, f) {
			continue
		}
		value, e := editValue(f, v)
		if e != nil {
			return out, e
		}
		values[f] = value
		supplied[f] = true
		o := Origin{AppliedAt: at, Kind: "MANUAL"}
		if candidate != nil {
			o.Provider = candidate.Provider
			o.ExternalID = candidate.ExternalID
		}
		origins[f] = o
	}
	if contains(fields, "cover") {
		if in.CoverAssetID != nil && in.CoverCandidateID != nil {
			return out, bad("封面来源只能选择一个")
		}
		if in.CoverAssetID != nil {
			r, e := s.row(ctx, tx, "review-asset", map[string]any{"id": *in.CoverAssetID})
			if e != nil || text(r["song_id"]) != id {
				return out, bad("上传封面不存在或不属于当前歌曲")
			}
			if text(r["fingerprint"]) != fp {
				return out, conflict("上传后文件已变化，请重新上传封面")
			}
			values["cover"] = "asset:" + *in.CoverAssetID
			supplied["cover"] = true
			origins["cover"] = Origin{ExternalID: in.CoverAssetID, AppliedAt: at, Kind: "UPLOAD"}
		} else if in.CoverCandidateID != nil {
			t, e := s.ownedCandidate(ctx, tx, id, *in.CoverCandidateID, fp)
			if e != nil {
				return out, e
			}
			values["cover"] = coverVariant(t.Provider, t.CoverURL, in.CoverQuality)
			supplied["cover"] = true
			origins["cover"] = Origin{t.Provider, t.ExternalID, at, "PLATFORM"}
		} else if candidate != nil {
			values["cover"] = coverVariant(candidate.Provider, candidate.CoverURL, in.CoverQuality)
		}
	}
	prior := metaOf(song)
	after := map[string]any{}
	for k, v := range before {
		after[k] = v
	}
	out.Updated = []string{}
	out.Skipped = []string{}
	out.Retained = []string{}
	for _, f := range fields {
		if !supplied[f] {
			out.Skipped = append(out.Skipped, f)
		} else {
			out.Updated = append(out.Updated, f)
			after[f] = values[f]
		}
	}
	for _, f := range allFields {
		if !contains(fields, f) {
			out.Retained = append(out.Retained, f)
		}
	}
	if v, ok := in.Edits["artists"]; ok && contains(fields, "artists") && present(values["artists"]) {
		_ = v
		artists := stringsOf(values["artists"])
		s.hookMu.RLock()
		fn := s.artistHook
		s.hookMu.RUnlock()
		if fn != nil {
			if e = fn(ctx, tx, text(song["sourceId"]), text(song["relativePath"]), artists); e != nil {
				return out, e
			}
		} else {
			a := map[string]any{"sourceId": song["sourceId"], "path": hash(text(song["relativePath"]))}
			rows, e := s.rows(ctx, tx, "artist-override", a)
			if e != nil {
				return out, e
			}
			revision := int64(0)
			if len(rows) > 0 {
				revision = number(rows[0]["revision"])
			}
			if _, e = s.exec(ctx, tx, "artist-deleteOverride", a); e != nil {
				return out, e
			}
			a["artists"], a["revision"] = encode(artists), revision+1
			if _, e = s.exec(ctx, tx, "artist-insertOverride", a); e != nil {
				return out, e
			}
		}
		after["artists"] = artists
		if !contains(out.Updated, "artists") {
			out.Updated = append(out.Updated, "artists")
		}
		out.Skipped = remove(out.Skipped, "artists")
	}
	out.Metadata = prior
	if len(out.Updated) > 0 {
		all := []string{}
		merged := map[string]Origin{}
		if prior != nil {
			all = append(all, prior.Fields...)
			for k, v := range prior.Origins {
				merged[k] = v
			}
		}
		for _, f := range out.Updated {
			if !contains(all, f) {
				all = append(all, f)
			}
			merged[f] = origins[f]
		}
		base := Track{}
		if candidate != nil {
			base = *candidate
			if _, ok := in.Edits["artists"]; ok {
				base.ArtistRefs = []ArtistRef{}
			}
		}
		applied := Applied{Track: mergeTrack(after, base), Fields: all, AppliedAt: at, Origins: merged}
		if e = s.saveApplied(ctx, tx, song, applied, out.Updated, false); e != nil {
			return out, e
		}
		out.Metadata = &applied
	}
	out.Before = before
	out.After = after
	sort.Strings(out.Updated)
	sort.Strings(out.Skipped)
	sort.Strings(out.Retained)
	_, e = s.exec(ctx, tx, "review-receiptSave", map[string]any{"id": in.RequestID, "songId": id, "hash": requestHash, "response": encode(out), "now": now()})
	return out, e
}
func remove(v []string, s string) []string {
	out := []string{}
	for _, x := range v {
		if x != s {
			out = append(out, x)
		}
	}
	return out
}
func (s *Service) reviewItemHTTP(c *gin.Context) {
	var in ReviewApply
	if !s.decode(c, &in) {
		return
	}
	ctx := c.Request.Context()
	var out ReviewResult
	e := s.transaction(ctx, func(tx store.DBTX) error {
		item, e := s.requireItem(ctx, tx, c.Param("id"), c.Param("itemId"))
		if e != nil {
			return e
		}
		if text(item["fingerprint"]) != in.Fingerprint || !contains([]string{"REVIEW", "FAILED", "MANUAL_APPLIED"}, text(item["status"])) {
			return conflict("该明细当前不能人工应用或歌曲已变化")
		}
		out, e = s.reviewApply(ctx, tx, text(item["song_id"]), in)
		if e != nil {
			return e
		}
		if _, e = s.itemStatus(ctx, tx, text(item["id"]), text(item["status"]), "MANUAL_APPLIED", item["candidates_json"], nil); e != nil {
			return e
		}
		return s.project(ctx, tx, "METADATA", c.Param("id"))
	})
	if e != nil {
		s.fail(c, e)
		return
	}
	c.JSON(200, out)
}

package metadata

import (
	"context"
	"github.com/gin-gonic/gin"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
	"golang.org/x/text/unicode/norm"
	"math"
	"sort"
	"strings"
	"time"
	"unicode"
)

func normalize(v string) []rune {
	out := []rune{}
	for _, r := range strings.ToLower(norm.NFKC.String(v)) {
		if !unicode.IsPunct(r) && !unicode.IsSpace(r) && r != '·' && r != '•' {
			out = append(out, r)
		}
	}
	return out
}
func similarity(a, b string) float64 {
	x, y := normalize(a), normalize(b)
	if len(x) == 0 || len(y) == 0 {
		return 0
	}
	prev := make([]int, len(y)+1)
	for j := range prev {
		prev[j] = j
	}
	for i, r := range x {
		cur := make([]int, len(y)+1)
		cur[0] = i + 1
		for j, s := range y {
			cost := 0
			if r != s {
				cost = 1
			}
			cur[j+1] = min(cur[j]+1, prev[j+1]+1, prev[j]+cost)
		}
		prev = cur
	}
	return math.Max(0, 1-float64(prev[len(y)])/float64(max(len(x), len(y))))
}
func score(song map[string]any, t Track) float64 {
	v := current(song)
	title := ""
	if v["title"] != nil {
		_ = decode(encode(v["title"]), &title)
	}
	tt := ""
	if t.Title != nil {
		tt = *t.Title
	}
	return math.Round((.55*similarity(title, tt)+.35*similarity(strings.Join(stringsOf(v["artists"]), " "), strings.Join(t.Artists, " "))+.05)*1000) / 1000
}
func (s *Service) search(ctx context.Context, song map[string]any, input SearchInput, configured []string) (SearchResult, error) {
	out := SearchResult{Candidates: []Candidate{}, Errors: []ProviderError{}}
	settings, e := s.settings(ctx, s.db, true)
	if e != nil {
		return out, e
	}
	selected := input.Providers
	if len(selected) == 0 {
		selected = configured
	}
	if len(selected) > len(providers) {
		return out, bad("搜索包含未启用的平台")
	}
	seenP := map[string]bool{}
	for _, p := range selected {
		if !contains(settings.Providers, p) || !contains(configured, p) {
			return out, bad("搜索包含未启用的平台")
		}
		seenP[p] = true
	}
	keyword := ""
	if input.Keyword != nil {
		keyword = *input.Keyword
	}
	if strings.TrimSpace(keyword) == "" {
		v := current(song)
		if present(v["title"]) {
			_ = decode(encode(v["title"]), &keyword)
		}
		if keyword == "" {
			r, _ := song["result"].(map[string]any)
			keyword = text(r["originalFilename"])
		}
		keyword += " " + strings.Join(stringsOf(v["artists"]), " ")
	}
	keyword, e = clean(keyword, 200)
	if e != nil || keyword == "" {
		return out, bad("搜索关键词需要 1～200 个字符")
	}
	seen := map[string]bool{}
	for _, p := range selected {
		if !seenP[p] {
			continue
		}
		delete(seenP, p)
		provider := s.providers[p]
		query := keyword
		if p == "KUWO" {
			// Kuwo's all= query treats a natural "title artist" as a fuzzy OR
			// search. For the unchanged song query, search the full title and
			// let the normal title/artist score rank the actual returned tracks.
			v := current(song)
			title := text(v["title"])
			if title != "" && keyword == strings.TrimSpace(title+" "+strings.Join(stringsOf(v["artists"]), " ")) {
				query = title
			}
		}
		tracks, err := s.callProvider(ctx, p, provider, query)
		if err != nil {
			out.Errors = append(out.Errors, ProviderError{p, safeDetail(err)})
			continue
		}
		for _, t := range tracks {
			if t.Provider == nil || t.ExternalID == nil {
				continue
			}
			key := *t.Provider + ":" + *t.ExternalID
			if seen[key] {
				continue
			}
			seen[key] = true
			candidate := Candidate{ID: uuid(), Track: t, Score: score(song, t)}
			_, err = s.exec(ctx, s.db, "meta-insertCandidate", map[string]any{"id": candidate.ID, "songId": song["id"], "fingerprint": fingerprint(song), "track": encode(t), "score": candidate.Score, "now": now()})
			if err != nil {
				return out, err
			}
			out.Candidates = append(out.Candidates, candidate)
		}
	}
	unique := map[string]bool{}
	for _, p := range selected {
		unique[p] = true
	}
	if len(out.Errors) == len(unique) {
		return out, &problem{503, "所选平台均暂时不可用，请稍后重试或更换平台"}
	}
	sort.Slice(out.Candidates, func(i, j int) bool {
		if out.Candidates[i].Score != out.Candidates[j].Score {
			return out.Candidates[i].Score > out.Candidates[j].Score
		}
		return out.Candidates[i].ID < out.Candidates[j].ID
	})
	if len(out.Candidates) > 10 {
		out.Candidates = out.Candidates[:10]
	}
	return out, nil
}
func (s *Service) searchHTTP(c *gin.Context) {
	ctx := c.Request.Context()
	var in SearchInput
	if c.Request.ContentLength != 0 && !s.decode(c, &in) {
		return
	}
	song, e := s.song(ctx, s.db, c.Param("songId"))
	if e != nil {
		s.fail(c, e)
		return
	}
	v, e := s.settings(ctx, s.db, true)
	if e != nil {
		s.fail(c, e)
		return
	}
	out, e := s.search(ctx, song, in, v.Providers)
	if e != nil {
		s.fail(c, e)
		return
	}
	c.JSON(200, out)
}
func (s *Service) candidate(ctx context.Context, db store.DBTX, id string) (map[string]any, Track, error) {
	r, e := s.row(ctx, db, "meta-candidate", map[string]any{"id": id})
	if e != nil {
		return nil, Track{}, absent(e, "候选已过期，请重新搜索")
	}
	f, e := time.Parse(time.RFC3339Nano, text(r["fetched_at"]))
	if e != nil || time.Since(f) > 7*24*time.Hour {
		return nil, Track{}, missing("候选已过期，请重新搜索")
	}
	var t Track
	e = decode(r["track_json"], &t)
	return r, t, e
}
func selectedFields(in []string, defaults bool) ([]string, error) {
	if len(in) == 0 {
		if defaults {
			return append([]string{}, allFields...), nil
		}
		return nil, bad("请选择有效的应用字段")
	}
	out := []string{}
	for _, f := range in {
		if !contains(allFields, f) {
			return nil, bad("应用字段无效")
		}
		if !contains(out, f) {
			out = append(out, f)
		}
	}
	return out, nil
}
func mergeTrack(values map[string]any, base Track) Track {
	b := map[string]any{"provider": base.Provider, "externalId": base.ExternalID, "availability": base.Availability, "fetchedAt": base.FetchedAt, "artistRefs": base.ArtistRefs, "coverUrl": values["cover"]}
	for _, f := range allFields {
		if f != "cover" {
			b[f] = values[f]
		}
	}
	var t Track
	_ = decode(encode(b), &t)
	if t.Artists == nil {
		t.Artists = []string{}
	}
	if t.Aliases == nil {
		t.Aliases = []string{}
	}
	if t.ArtistRefs == nil {
		t.ArtistRefs = []ArtistRef{}
	}
	if t.FetchedAt == "" {
		t.FetchedAt = now()
	}
	if t.Availability == "" {
		t.Availability = "UNKNOWN"
	}
	return t
}
func (s *Service) saveApplied(ctx context.Context, tx store.DBTX, song map[string]any, a Applied) error {
	args := map[string]any{"songId": song["id"], "sourceId": song["sourceId"], "fingerprint": fingerprint(song), "track": encode(a.Track), "fields": encode(a.Fields), "origins": encode(a.Origins), "now": a.AppliedAt}
	if _, e := s.exec(ctx, tx, "meta-deleteApplied", args); e != nil {
		return e
	}
	if _, e := s.exec(ctx, tx, "meta-insertApplied", args); e != nil {
		return e
	}
	if e := s.Touch(ctx, tx, text(song["id"])); e != nil {
		return e
	}
	s.hookMu.RLock()
	fn := s.appliedHook
	s.hookMu.RUnlock()
	if fn != nil {
		return fn(ctx, tx, text(song["id"]))
	}
	return nil
}
func (s *Service) applyLocked(ctx context.Context, tx store.DBTX, id string, in ApplyInput, expected string) (Applied, error) {
	if _, e := s.settings(ctx, tx, true); e != nil {
		return Applied{}, e
	}
	r, t, e := s.candidate(ctx, tx, in.CandidateID)
	if e != nil {
		return Applied{}, e
	}
	if text(r["song_id"]) != id {
		return Applied{}, bad("候选不属于当前歌曲")
	}
	song, e := s.song(ctx, tx, id)
	if e != nil {
		return Applied{}, e
	}
	fp := fingerprint(song)
	if fp != text(r["fingerprint"]) || expected != "" && expected != fp {
		return Applied{}, conflict("歌曲文件已变化，请重新扫描并匹配元数据")
	}
	fields, e := selectedFields(in.Fields, true)
	if e != nil {
		return Applied{}, e
	}
	prior := metaOf(song)
	values := current(song)
	from := trackValues(t)
	origins := map[string]Origin{}
	all := []string{}
	if prior != nil {
		all = append(all, prior.Fields...)
		for k, v := range prior.Origins {
			origins[k] = v
		}
	}
	written := 0
	at := now()
	for _, f := range fields {
		if !present(from[f]) {
			continue
		}
		if origins[f].Kind == "FILE" {
			continue
		}
		values[f] = from[f]
		origins[f] = Origin{t.Provider, t.ExternalID, at, "PLATFORM"}
		if !contains(all, f) {
			all = append(all, f)
		}
		written++
	}
	if written == 0 {
		return Applied{}, bad("候选没有可写入字段，文件内嵌标签优先，原元数据未修改")
	}
	out := Applied{Track: mergeTrack(values, t), Fields: all, AppliedAt: at, Origins: origins}
	return out, s.saveApplied(ctx, tx, song, out)
}
func (s *Service) applyHTTP(c *gin.Context) {
	var in ApplyInput
	if !s.decode(c, &in) {
		return
	}
	if in.CandidateID == "" {
		s.fail(c, bad("请选择元数据候选"))
		return
	}
	ctx := c.Request.Context()
	var out Applied
	e := s.transaction(ctx, func(tx store.DBTX) error {
		var e error
		out, e = s.applyLocked(ctx, tx, c.Param("songId"), in, "")
		return e
	})
	if e != nil {
		s.fail(c, e)
		return
	}
	c.JSON(200, out)
}
func confirmed(song map[string]any) bool {
	a := metaOf(song)
	if a == nil {
		return false
	}
	for _, f := range a.Fields {
		if a.Origins[f].Kind != "FILE" {
			return true
		}
	}
	return false
}

// Touch records durable derived-file work in the caller's transaction.
func (s *Service) Touch(ctx context.Context, tx store.DBTX, id string) error {
	a := map[string]any{"songId": id}
	if _, e := s.exec(ctx, tx, "files-touch", a); e != nil {
		return e
	}
	_, e := s.exec(ctx, tx, "files-enqueue", a)
	return e
}

// TouchPath records derived-file work for a source-relative artist correction.
func (s *Service) TouchPath(ctx context.Context, tx store.DBTX, source, path string) error {
	args := map[string]any{"source": source, "path": path}
	if _, e := s.exec(ctx, tx, "files-touchPath", args); e != nil {
		return e
	}
	_, e := s.exec(ctx, tx, "files-enqueuePath", args)
	return e
}

// TouchSource registers each published source snapshot in the publication transaction.
func (s *Service) TouchSource(ctx context.Context, tx store.DBTX, source string) error {
	args := map[string]any{"sourceId": source}
	if _, e := s.exec(ctx, tx, "files-touchSource", args); e != nil {
		return e
	}
	_, e := s.exec(ctx, tx, "files-enqueueSource", args)
	return e
}

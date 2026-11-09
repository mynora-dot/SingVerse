package downloads

import (
	"context"
	"regexp"
	"strings"
	"sync"
	"time"

	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/search"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

var trackPattern = regexp.MustCompile(`^[a-f0-9]{64}$`)

func (s *Service) cached(ctx context.Context, tx store.DBTX, id string) (map[string]any, error) {
	if !trackPattern.MatchString(id) {
		return nil, bad("歌曲选择无效，请重新搜索")
	}
	row, e := s.row(ctx, tx, "track", map[string]any{"id": id})
	if e != nil {
		return nil, e
	}
	if row == nil {
		return nil, bad("歌曲预览已过期，请重新搜索")
	}
	return row, nil
}
func (s *Service) remember(ctx context.Context, tracks []Track) error {
	return s.transaction(ctx, func(tx store.DBTX) error {
		if e := s.lock(ctx, tx); e != nil {
			return e
		}
		for _, t := range tracks {
			if _, e := s.exec(ctx, tx, "deleteTrack", map[string]any{"id": t.ID}); e != nil {
				return e
			}
			if _, e := s.exec(ctx, tx, "insertTrack", map[string]any{"id": t.ID, "platform": t.Platform, "remoteId": t.RemoteID, "json": encode(t), "now": now()}); e != nil {
				return e
			}
		}
		return nil
	})
}
func (s *Service) view(ctx context.Context, tx store.DBTX, t Track) (map[string]any, error) {
	var song any
	rows, e := s.rows(ctx, tx, "assetsForTrack", map[string]any{"trackKey": t.ID})
	if e != nil {
		return nil, e
	}
	for _, asset := range rows {
		if present(asset) {
			song = asset["song_id"]
			break
		}
	}
	active, e := s.scalar(ctx, tx, "activeTrack", map[string]any{"trackKey": t.ID})
	if e != nil {
		return nil, e
	}
	return map[string]any{"id": t.ID, "platform": t.Platform, "title": t.Title, "artists": t.Artists, "album": t.Album, "durationMs": t.DurationMS, "coverUrl": s.covers.Reference(t.CoverURL), "inLibrary": song != nil, "downloading": active > 0, "songId": song}, nil
}
func (s *Service) views(ctx context.Context, tracks []Track) ([]map[string]any, error) {
	out := make([]map[string]any, 0, len(tracks))
	for _, t := range tracks {
		v, e := s.view(ctx, s.db, t)
		if e != nil {
			return nil, e
		}
		out = append(out, v)
	}
	return out, nil
}
func (s *Service) Search(ctx context.Context, query, platform string, page int) (map[string]any, error) {
	if strings.TrimSpace(query) == "" || search.UTF16Length(query) > 100 || page < 1 || page > 100 {
		return nil, bad("请输入 1–100 个字的歌名或歌手，页码最多 100")
	}
	_, script, e := s.requireSource(ctx, s.db)
	if e != nil {
		return nil, e
	}
	enabled, e := capabilities(script)
	if e != nil {
		return nil, e
	}
	codes := []string{}
	if platform == "" || platform == "all" {
		for _, p := range []string{"wy", "tx", "kg", "kw", "mg"} {
			if _, ok := enabled[p]; ok {
				codes = append(codes, p)
			}
		}
	} else {
		if _, ok := enabled[platform]; !ok {
			return nil, bad("当前音源不支持该平台，请切换平台或音源")
		}
		codes = append(codes, platform)
	}
	ctx, cancel := context.WithTimeout(ctx, 35*time.Second)
	defer cancel()
	type response struct {
		page CatalogPage
		err  error
	}
	answers := make([]response, len(codes))
	var wg sync.WaitGroup
	for i, p := range codes {
		wg.Add(1)
		go func(i int, p string) {
			defer wg.Done()
			select {
			case s.catalogSlots <- struct{}{}:
				defer func() { <-s.catalogSlots }()
			case <-ctx.Done():
				answers[i].err = ctx.Err()
				return
			}
			answers[i].page, answers[i].err = s.catalog.Search(ctx, p, query, page)
		}(i, p)
	}
	wg.Wait()
	tracks := []Track{}
	failures := []map[string]any{}
	warnings := []string{}
	more := false
	for i, a := range answers {
		if a.err != nil {
			failures = append(failures, map[string]any{"platform": codes[i], "message": "平台读取失败，请重试或切换平台"})
			continue
		}
		if e = s.remember(ctx, a.page.Tracks); e != nil {
			return nil, e
		}
		tracks = append(tracks, a.page.Tracks...)
		more = more || a.page.HasMore
		if a.page.Warning != "" {
			warnings = append(warnings, platforms[codes[i]]+"："+clean(a.page.Warning))
		}
	}
	views, e := s.views(ctx, tracks)
	if e != nil {
		return nil, e
	}
	return map[string]any{"tracks": views, "errors": failures, "warnings": warnings, "page": page, "hasMore": more}, nil
}
func (s *Service) Preview(ctx context.Context, address string, page int) (map[string]any, error) {
	if page < 1 || page > 500 {
		return nil, bad("每次最多读取 10000 首歌曲，请拆分后导入")
	}
	_, script, e := s.requireSource(ctx, s.db)
	if e != nil {
		return nil, e
	}
	enabled, e := capabilities(script)
	if e != nil {
		return nil, e
	}
	ref, e := s.catalog.Reference(ctx, address)
	if e != nil {
		return nil, e
	}
	if _, ok := enabled[ref.Platform]; !ok {
		return nil, bad("当前音源不支持该链接的平台，请更换音源")
	}
	reply, e := s.catalog.Collection(ctx, ref, page)
	if e != nil {
		return nil, e
	}
	if e = s.remember(ctx, reply.Tracks); e != nil {
		return nil, e
	}
	views, e := s.views(ctx, reply.Tracks)
	if e != nil {
		return nil, e
	}
	return map[string]any{"platform": reply.Platform, "kind": reply.Kind, "name": reply.Name, "coverUrl": s.covers.Reference(reply.CoverURL), "tracks": views, "page": reply.Page, "total": reply.Total, "hasMore": reply.HasMore, "warning": reply.Warning}, nil
}
func (s *Service) Detail(ctx context.Context, id string) (map[string]any, error) {
	_, script, e := s.requireSource(ctx, s.db)
	if e != nil {
		return nil, e
	}
	enabled, e := capabilities(script)
	if e != nil {
		return nil, e
	}
	row, e := s.cached(ctx, s.db, id)
	if e != nil {
		return nil, e
	}
	if _, ok := enabled[str(row, "platform")]; !ok {
		return nil, bad("当前音源不支持该平台")
	}
	t, e := parseTrack(str(row, "track_json"))
	if e != nil {
		return nil, e
	}
	t, e = s.catalog.Detail(ctx, t)
	if e != nil {
		return nil, e
	}
	if e = s.remember(ctx, []Track{t}); e != nil {
		return nil, e
	}
	return s.view(ctx, s.db, t)
}
func (s *Service) TrackStates(ctx context.Context, ids []string) ([]map[string]any, error) {
	if ids == nil || len(ids) > 1000 {
		return nil, bad("一次最多读取 1000 首歌曲状态")
	}
	out := []map[string]any{}
	seen := map[string]bool{}
	for _, id := range ids {
		if seen[id] {
			continue
		}
		seen[id] = true
		row, e := s.cached(ctx, s.db, id)
		if e != nil {
			return nil, e
		}
		t, e := parseTrack(str(row, "track_json"))
		if e != nil {
			return nil, e
		}
		v, e := s.view(ctx, s.db, t)
		if e != nil {
			return nil, e
		}
		out = append(out, v)
	}
	return out, nil
}

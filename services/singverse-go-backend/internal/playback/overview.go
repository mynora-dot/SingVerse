package playback

import "context"

// Summary30d returns the trailing 30-day playback summary, reusing the same
// query and projection as GET /playback-history/summary?period=30d.
func (s *Service) Summary30d(ctx context.Context) (map[string]any, error) {
	now := s.now()
	f := map[string]any{"from": now - 30*86400000, "until": now, "songId": "", "pattern": ""}
	r, e := s.row(ctx, s.db, "summary", f)
	if e != nil {
		return nil, e
	}
	return map[string]any{"totalPlays": n(r, "total_plays"), "effectivePlays": n(r, "effective_plays"), "playedSongs": n(r, "played_songs"), "playedMs": n(r, "played_ms")}, nil
}

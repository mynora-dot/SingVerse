package party

import (
	"context"

	"github.com/gin-gonic/gin"
	"github.com/jmoiron/sqlx"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/playback"
)

func (s *Service) registerRecommendations(router *gin.Engine) {
	router.GET("/api/v1/tv/popular", s.RequireActor(true), s.tvPopularHTTP)
	router.GET("/api/v1/tv/playback-history", s.RequireActor(true), s.tvHistoryHTTP)
}

func rejectTVScope(c *gin.Context) error {
	for _, key := range []string{"deviceId", "sessionId", "ownerSessionId"} {
		if _, supplied := c.Request.URL.Query()[key]; supplied {
			return fail(400, "设备范围由电视授权确定")
		}
	}
	return nil
}

func visibleSongIDs(index *readIndex) []string {
	ids := make([]string, 0, len(index.entries))
	for _, entry := range index.entries {
		ids = append(ids, str(entry.song, "id"))
	}
	return ids
}

func (s *Service) deviceSessions(ctx context.Context, tx store.DBTX, actor document) ([]string, error) {
	if str(actor, "kind") != "device" {
		return nil, fail(403, "需要电视设备授权")
	}
	var rows []struct {
		ID string `db:"id"`
	}
	if err := sqlx.SelectContext(ctx, tx, &rows, s.q["device_sessions-"+s.dialect], str(actor, "id")); err != nil {
		return nil, err
	}
	ids := make([]string, 0, len(rows))
	for _, row := range rows {
		ids = append(ids, row.ID)
	}
	return ids, nil
}

func (s *Service) popularSongs(ctx context.Context, tx store.DBTX, index *readIndex, page, size int) ([]document, int64, error) {
	items := []document{}
	if s.activity == nil {
		return items, 0, nil
	}
	rows, total, err := s.activity.TVPopular(ctx, tx, visibleSongIDs(index), page, size)
	if err != nil {
		return nil, 0, err
	}
	for _, row := range rows {
		entry, ok := index.byID[str(row, "songId")]
		if !ok {
			continue
		}
		song := clone(entry.song)
		for key, value := range row {
			if key != "songId" {
				song[key] = value
			}
		}
		items = append(items, song)
	}
	return items, total, nil
}

func (s *Service) recentPlayedSongs(ctx context.Context, tx store.DBTX, actor document, index *readIndex) ([]document, error) {
	items := []document{}
	if s.activity == nil {
		return items, nil
	}
	sessions, err := s.deviceSessions(ctx, tx, actor)
	if err != nil {
		return nil, err
	}
	rows, _, err := s.activity.TVHistory(ctx, tx, playback.TVHistoryQuery{SessionIDs: sessions, SongIDs: visibleSongIDs(index), AvailableOnly: true, LatestPerSong: true, Size: 8})
	if err != nil {
		return nil, err
	}
	for _, row := range rows {
		entry, ok := index.byID[str(row, "songId")]
		if !ok {
			continue
		}
		song := clone(entry.song)
		song["lastPlayedAt"], song["lastPlaybackStatus"], song["lastHistoryId"] = row["startedAt"], row["status"], row["id"]
		items = append(items, song)
	}
	return items, nil
}

func (s *Service) tvPopularHTTP(c *gin.Context) {
	if err := rejectTVScope(c); err != nil {
		s.report(c, err)
		return
	}
	page, size, err := pageParams(c)
	if err != nil {
		s.report(c, err)
		return
	}
	index, err := s.current(c.Request.Context())
	if err != nil {
		s.report(c, err)
		return
	}
	s.runAsActor(c, func(ctx context.Context, tx store.DBTX, actor document) (any, error) {
		if str(actor, "kind") != "device" {
			return nil, fail(403, "需要电视设备授权")
		}
		items, total, err := s.popularSongs(ctx, tx, index, page, size)
		return document{"items": items, "totalElements": total, "page": page, "size": size}, err
	})
}

func (s *Service) tvHistoryHTTP(c *gin.Context) {
	if err := rejectTVScope(c); err != nil {
		s.report(c, err)
		return
	}
	page, size, err := pageParams(c)
	if err != nil {
		s.report(c, err)
		return
	}
	index, err := s.current(c.Request.Context())
	if err != nil {
		s.report(c, err)
		return
	}
	s.runAsActor(c, func(ctx context.Context, tx store.DBTX, actor document) (any, error) {
		sessions, err := s.deviceSessions(ctx, tx, actor)
		if err != nil {
			return nil, err
		}
		items := []document{}
		var total int64
		if s.activity != nil {
			items, total, err = s.activity.TVHistory(ctx, tx, playback.TVHistoryQuery{SessionIDs: sessions, Page: page, Size: size})
			if err != nil {
				return nil, err
			}
		}
		for _, item := range items {
			entry, available := index.byID[str(item, "songId")]
			item["available"], item["song"] = available, nil
			if available {
				item["song"] = clone(entry.song)
			}
		}
		return document{"items": items, "totalElements": total, "page": page, "size": size}, nil
	})
}

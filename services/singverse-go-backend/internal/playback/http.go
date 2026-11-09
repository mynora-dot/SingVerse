package playback

import (
	"database/sql"
	"encoding/json"
	"errors"
	"math"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/auth"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/httpx"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

func (s *Service) Register(g *gin.RouterGroup) {
	for _, prefix := range []string{"", "/mobile"} {
		group := g.Group(prefix)
		group.POST("/playback-attempts", s.createHTTP)
		group.POST("/playback-attempts/:id/events", s.reportHTTP)
	}
	g.GET("/playback-history", s.historyHTTP)
	g.GET("/playback-history/popular", s.popularHTTP)
	g.GET("/playback-history/summary", s.summaryHTTP)
}
func (s *Service) error(c *gin.Context, err error) {
	var problem *failure
	if errors.As(err, &problem) {
		httpx.Error(c, problem.Status, "about:blank", problem.Detail)
		return
	}
	if errors.Is(err, sql.ErrNoRows) {
		httpx.Error(c, 404, "about:blank", "歌曲不存在或已从来源中移除")
		return
	}
	var domain interface{ StatusCode() int }
	if errors.As(err, &domain) && domain.StatusCode() >= 400 && domain.StatusCode() < 500 {
		httpx.Error(c, domain.StatusCode(), "about:blank", err.Error())
		return
	}
	s.log.ErrorContext(c.Request.Context(), "playback operation failed", "route", c.FullPath())
	httpx.Error(c, 500, "about:blank", "操作失败，请稍后重试")
}
func owner(c *gin.Context) (string, string, error) {
	cookie, _ := c.Cookie(auth.SessionCookie)
	if len(cookie) != 43 {
		return "", "", fail(401, "请先登录")
	}
	kind := "ADMIN"
	if strings.HasPrefix(c.Request.URL.Path, "/api/v1/mobile/") {
		kind = "MOBILE"
	}
	return kind, digest(cookie), nil
}
func (s *Service) decode(c *gin.Context) (map[string]any, bool) {
	var event map[string]any
	if !httpx.Decode(c, &event, 32768) {
		return nil, false
	}
	return event, true
}
func (s *Service) createHTTP(c *gin.Context) {
	kind, session, e := owner(c)
	if e != nil {
		s.error(c, e)
		return
	}
	in, ok := s.decode(c)
	if !ok {
		return
	}
	var started *int64
	if _, ok := in["startedAt"]; ok {
		v, e := counter(in, "startedAt")
		if e != nil {
			s.error(c, e)
			return
		}
		started = &v
	}
	result, e := s.Create(c.Request.Context(), kind, session, text(in, "songId"), text(in, "deviceName"), started)
	if e != nil {
		s.error(c, e)
		return
	}
	c.JSON(201, result)
}
func (s *Service) reportHTTP(c *gin.Context) {
	kind, session, e := owner(c)
	if e != nil {
		s.error(c, e)
		return
	}
	in, ok := s.decode(c)
	if !ok {
		return
	}
	result, e := s.Report(c.Request.Context(), kind, session, c.Param("id"), in)
	if e != nil {
		s.error(c, e)
		return
	}
	c.JSON(200, result)
}
func (s *Service) filter(c *gin.Context) (map[string]any, error) {
	q, song, period := c.Query("q"), c.Query("songId"), c.DefaultQuery("period", "30d")
	if !validText(q, 200, true) || !validText(song, 64, true) {
		return nil, fail(400, "关键词或歌曲编号无效")
	}
	now := s.now()
	from := int64(math.MinInt64)
	switch period {
	case "7d":
		from = now - 7*86400000
	case "30d":
		from = now - 30*86400000
	case "all":
	default:
		return nil, fail(400, "请选择近 7 天、近 30 天或全部时间")
	}
	q = strings.TrimSpace(strings.ToLower(q))
	q = strings.NewReplacer("!", "!!", "%", "!%", "_", "!_").Replace(q)
	if q != "" {
		q = "%" + q + "%"
	}
	return map[string]any{"from": from, "until": now, "songId": song, "pattern": q}, nil
}
func page(c *gin.Context) (int, int, error) {
	p, e := strconv.Atoi(c.DefaultQuery("page", "0"))
	if e != nil {
		return 0, 0, fail(400, "分页参数无效")
	}
	size, e := strconv.Atoi(c.DefaultQuery("size", "20"))
	if e != nil || p < 0 || p > 1000000 || size < 1 || size > 100 {
		return 0, 0, fail(400, "分页参数无效")
	}
	return p, size, nil
}
func (s *Service) historyHTTP(c *gin.Context) { s.listHTTP(c, false) }
func (s *Service) popularHTTP(c *gin.Context) { s.listHTTP(c, true) }
func (s *Service) listHTTP(c *gin.Context, popular bool) {
	f, e := s.filter(c)
	if e != nil {
		s.error(c, e)
		return
	}
	p, size, e := page(c)
	if e != nil {
		s.error(c, e)
		return
	}
	f["size"], f["offset"] = size, p*size
	name, count := "history", "countHistory"
	if popular {
		name, count = "popular", "countPopular"
	}
	items := []map[string]any{}
	var total int64
	e = store.WithinTx(c.Request.Context(), s.db, func(tx store.DBTX) error {
		q, a, e := s.statement(count, f)
		if e != nil {
			return e
		}
		if e = tx.QueryRowContext(c.Request.Context(), q, a...).Scan(&total); e != nil {
			return e
		}
		rows, e := s.rows(c.Request.Context(), tx, name, f)
		if e != nil {
			return e
		}
		for _, r := range rows {
			v, e := historyView(r, popular)
			if e != nil {
				return e
			}
			items = append(items, v)
		}
		return nil
	})
	if e != nil {
		s.error(c, e)
		return
	}
	c.JSON(200, gin.H{"items": items, "totalElements": total, "page": p, "size": size})
}
func (s *Service) summaryHTTP(c *gin.Context) {
	f, e := s.filter(c)
	if e != nil {
		s.error(c, e)
		return
	}
	r, e := s.row(c.Request.Context(), s.db, "summary", f)
	if e != nil {
		s.error(c, e)
		return
	}
	c.JSON(http.StatusOK, gin.H{"totalPlays": n(r, "total_plays"), "effectivePlays": n(r, "effective_plays"), "playedSongs": n(r, "played_songs"), "playedMs": n(r, "played_ms")})
}
func instant(r map[string]any, key string) any {
	if r[key] == nil {
		return nil
	}
	return time.UnixMilli(n(r, key)).UTC().Format(time.RFC3339Nano)
}
func historyView(r map[string]any, popular bool) (map[string]any, error) {
	artists := []string{}
	if e := json.Unmarshal([]byte(text(r, "artists_json")), &artists); e != nil {
		return nil, e
	}
	v := map[string]any{"songId": r["song_id"], "title": r["title"], "artists": artists, "versionLabel": r["version_label"], "sourceName": r["source_name"]}
	if popular {
		v["playCount"] = n(r, "play_count")
		v["effectivePlayCount"] = n(r, "effective_play_count")
		v["completedCount"] = n(r, "completed_count")
		v["lastPlayedAt"] = instant(r, "last_played_at")
		return v, nil
	}
	for out, key := range map[string]string{"id": "id", "clientType": "client_type", "deviceName": "device_name", "requestedBy": "requested_by", "durationMs": "duration_ms", "status": "status"} {
		v[out] = r[key]
	}
	v["startedAt"] = instant(r, "started_at")
	v["endedAt"] = instant(r, "ended_at")
	v["playedMs"] = n(r, "played_ms")
	v["effective"] = n(r, "effective_play") == 1
	return v, nil
}

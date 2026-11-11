package party

import (
	"context"
	"crypto/md5"
	"database/sql"
	"encoding/json"
	"fmt"
	"sort"
	"strconv"
	"strings"
	"time"
	"unicode"

	"github.com/gin-gonic/gin"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/search"
	"golang.org/x/text/unicode/norm"
)

func stringList(v any) []string {
	out := []string{}
	switch a := v.(type) {
	case []string:
		return a
	case []any:
		for _, v := range a {
			if s, ok := v.(string); ok {
				out = append(out, s)
			}
		}
	}
	return out
}
func Projection(song map[string]any) map[string]any {
	result := object(song, "result")
	metadata := object(song, "metadata")
	track := object(metadata, "track")
	fields := map[string]bool{}
	for _, v := range stringList(metadata["fields"]) {
		fields[v] = true
	}
	valid := !boolean(metadata, "stale") && len(track) > 0
	title := str(result, "title")
	artists := stringList(result["artists"])
	if valid {
		if fields["title"] {
			title = str(track, "title")
		}
		if fields["artists"] {
			artists = stringList(track["artists"])
		}
	}
	if strings.TrimSpace(title) == "" {
		title = "未命名歌曲"
	}
	out := document{"id": song["id"], "title": title, "artists": artists, "sourceName": song["sourceName"], "version": result["versionType"], "playability": "UNKNOWN", "scannedAt": song["scannedAt"], "classification": song["classification"]}
	if valid {
		for _, key := range []string{"album", "releaseDate", "durationMs"} {
			if fields[key] {
				out[key] = track[key]
			}
		}
		if fields["cover"] {
			out["coverPath"] = "/api/v1/tv/library/songs/" + str(song, "id") + "/cover"
		}
	}
	if actual, ok := metadata["media"].(map[string]any); ok {
		if out["durationMs"] != nil {
			out["referenceDurationMs"] = out["durationMs"]
		}
		out["durationMs"] = actual["durationMs"]
		variant := object(metadata, "variant")
		if variant["kind"] != nil {
			out["version"] = variant["kind"]
		}
		out["timelineDurationMs"] = variant["timelineDurationMs"]
	}
	fingerprint := str(song, "id") + ":" + encode(song["fileSize"]) + ":" + encode(song["modifiedAt"])
	out["revision"] = hash(fingerprint) + ":" + hash(encode(out)+encode(song["metadata"]))
	return out
}
func mobileProjection(song document) document {
	out := Projection(song)
	result := object(song, "result")
	extension := strings.ToLower(str(result, "extension"))
	out["format"] = strings.ToUpper(extension)
	mediaType := "VIDEO"
	if extension == "" {
		mediaType = "UNKNOWN"
	}
	if strings.Contains("|mp3|flac|wav|m4a|aac|ogg|oga|opus|wma|aif|aiff|ape|", "|"+extension+"|") {
		mediaType = "AUDIO"
	}
	out["mediaType"] = mediaType
	out["language"] = result["language"]
	if str(out, "coverPath") != "" {
		out["coverPath"] = "/api/v1/metadata/songs/" + str(song, "id") + "/cover"
	}
	return out
}

type readEntry struct {
	song, raw    document
	search, code string
	artists      []string
}
type readIndex struct {
	entries []readEntry
	byID    map[string]readEntry
	artists map[string]document
	built   time.Time
}

func artistID(name string) string {
	b := md5.Sum([]byte("artist:" + hash(name)))
	b[6] = (b[6] & 15) | 48
	b[8] = (b[8] & 63) | 128
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[:4], b[4:6], b[6:8], b[8:10], b[10:])
}
func (s *Service) Invalidate() { s.indexMu.Lock(); s.index = nil; s.indexMu.Unlock() }
func (s *Service) current(ctx context.Context) (*readIndex, error) {
	s.indexMu.Lock()
	defer s.indexMu.Unlock()
	if s.index != nil && s.now().Sub(s.index.built) < 30*time.Second {
		return s.index, nil
	}
	songs, err := s.library.LibrarySongs(ctx, nil)
	if err != nil {
		return nil, err
	}
	out := &readIndex{entries: []readEntry{}, byID: map[string]readEntry{}, artists: map[string]document{}, built: s.now()}
	profiles := map[string]document{}
	rows, err := s.db.QueryContext(ctx, s.q["artist_profiles"])
	if err != nil {
		return nil, err
	}
	for rows.Next() {
		var key, name, kind, state string
		var revision int64
		var active int
		var nameKey, portrait, background sql.NullString
		if err = rows.Scan(&key, &name, &kind, &revision, &active, &nameKey, &state, &portrait, &background); err != nil {
			rows.Close()
			return nil, err
		}
		if !nameKey.Valid {
			continue
		}
		v := document{"id": key, "name": name, "type": kind, "revision": revision, "active": active == 1, "automatic": true, "fetchState": state, "aliases": []string{}, "biography": ""}
		if portrait.Valid {
			v["portraitVersion"] = portrait.String
			v["portraitPath"] = "/api/v1/tv/library/artists/" + key + "/images/portrait"
		}
		if background.Valid {
			v["backgroundVersion"] = background.String
			v["backgroundPath"] = "/api/v1/tv/library/artists/" + key + "/images/background"
		}
		profiles[norm.NFKC.String(strings.TrimSpace(name))] = v
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return nil, err
	}
	for _, song := range songs {
		if state := str(song, "mediaVerification"); state != "" && state != "PRESENT" {
			continue
		}
		projection := Projection(song)
		names := stringList(projection["artists"])
		entry := readEntry{song: projection, raw: song, search: search.Searchable(str(projection, "title") + " " + strings.Join(names, " ") + " " + str(projection, "album")), code: str(object(song, "result"), "songCode"), artists: []string{}}
		seen := map[string]bool{}
		for _, name := range names {
			name = norm.NFKC.String(strings.TrimSpace(name))
			if name == "" {
				continue
			}
			profile := profiles[name]
			if profile == nil {
				profile = document{"id": artistID(name), "name": name, "type": "UNKNOWN", "aliases": []string{}, "biography": "", "automatic": true, "fetchState": "PENDING", "active": true, "revision": int64(0)}
				profiles[name] = profile
			}
			key := str(profile, "id")
			if seen[key] {
				continue
			}
			seen[key] = true
			entry.artists = append(entry.artists, key)
			if out.artists[key] == nil {
				out.artists[key] = clone(profile)
			}
			out.artists[key]["count"] = number(out.artists[key], "count") + 1
		}
		out.entries = append(out.entries, entry)
		out.byID[str(song, "id")] = entry
	}
	sort.Slice(out.entries, func(i, j int) bool {
		a, b := out.entries[i].song, out.entries[j].song
		if str(a, "scannedAt") == str(b, "scannedAt") {
			return str(a, "id") < str(b, "id")
		}
		return str(a, "scannedAt") > str(b, "scannedAt")
	})
	s.index = out
	return out, nil
}
func pageParams(c *gin.Context) (int, int, error) {
	page, err := strconv.Atoi(c.DefaultQuery("page", "0"))
	if err != nil {
		return 0, 0, fail(400, "搜索或分页参数无效")
	}
	size, err := strconv.Atoi(c.DefaultQuery("size", "20"))
	q := c.DefaultQuery("q", "")
	if err != nil || page < 0 || page > 1000000 || size < 1 || size > 100 || search.UTF16Length(q) > 200 {
		return 0, 0, fail(400, "搜索或分页参数无效")
	}
	for _, r := range q {
		if unicode.IsControl(r) {
			return 0, 0, fail(400, "搜索或分页参数无效")
		}
	}
	return page, size, nil
}
func paginate(items []document, page, size int) document {
	from := min(int64(len(items)), int64(page)*int64(size))
	to := min(int64(len(items)), from+int64(size))
	out := []document{}
	for _, v := range items[from:to] {
		out = append(out, clone(v))
	}
	return document{"items": out, "page": page, "size": size, "totalElements": len(items)}
}
func (s *Service) tags(ctx context.Context) ([]document, error) {
	rows, err := s.db.QueryContext(ctx, s.q["tags"])
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []document{}
	for rows.Next() {
		var key, dim, name string
		var revision, count int64
		var cover sql.NullString
		if err = rows.Scan(&key, &dim, &name, &revision, &cover, &count); err != nil {
			return nil, err
		}
		v := document{"id": key, "dimension": dim, "name": name, "revision": revision, "count": count}
		if cover.Valid {
			v["coverVersion"] = cover.String
			v["coverPath"] = "/api/v1/tv/library/classification-tags/" + key + "/cover"
		}
		out = append(out, v)
	}
	return out, rows.Err()
}
func (s *Service) searchSongs(c *gin.Context, mobile bool) {
	page, size, err := pageParams(c)
	if err != nil {
		s.report(c, err)
		return
	}
	tags := []string{}
	for _, value := range c.QueryArray("tagIds") {
		for _, tag := range strings.Split(value, ",") {
			tags = append(tags, strings.TrimSpace(tag))
		}
	}
	if len(tags) > 40 {
		s.report(c, fail(400, "分类最多选择40项"))
		return
	}
	allTags, err := s.tags(c.Request.Context())
	if err != nil {
		s.report(c, err)
		return
	}
	enabled := map[string]string{}
	for _, t := range allTags {
		enabled[str(t, "id")] = str(t, "dimension")
	}
	groups := map[string]map[string]bool{}
	for _, key := range tags {
		dimension, ok := enabled[key]
		if !ok {
			s.report(c, fail(400, "标签不存在或已停用"))
			return
		}
		if groups[dimension] == nil {
			groups[dimension] = map[string]bool{}
		}
		groups[dimension][key] = true
	}
	index, err := s.current(c.Request.Context())
	if err != nil {
		s.report(c, err)
		return
	}
	unclassified := strings.ToLower(strings.TrimSpace(c.DefaultQuery("unclassified", "false")))
	switch unclassified {
	case "true", "on", "yes", "1":
		unclassified = "true"
	case "false", "off", "no", "0":
		unclassified = "false"
	default:
		s.report(c, fail(400, "搜索条件无效"))
		return
	}
	needle := search.Normalize(c.DefaultQuery("q", ""))
	artist := c.Query("artistId")
	items := []document{}
	for _, entry := range index.entries {
		if needle != "" && !strings.Contains(entry.search, needle) && entry.code != needle {
			continue
		}
		if artist != "" {
			found := false
			for _, id := range entry.artists {
				found = found || id == artist
			}
			if !found {
				continue
			}
		}
		assigned := map[string]bool{}
		for _, raw := range object(object(entry.song, "classification"), "dimensions") {
			v, _ := raw.(map[string]any)
			for _, tag := range stringList(v["tagIds"]) {
				if _, ok := enabled[tag]; ok {
					assigned[tag] = true
				}
			}
		}
		if unclassified == "true" && len(assigned) > 0 {
			continue
		}
		matches := true
		for _, options := range groups {
			found := false
			for option := range options {
				found = found || assigned[option]
			}
			matches = matches && found
		}
		if !matches {
			continue
		}
		if mobile {
			items = append(items, mobileProjection(entry.raw))
		} else {
			items = append(items, entry.song)
		}
	}
	c.JSON(200, paginate(items, page, size))
}
func (s *Service) registerLibrary(r *gin.Engine) {
	for _, prefix := range []string{"tv", "party"} {
		r.GET("/api/v1/"+prefix+"/library/songs", s.RequireActor(false), func(c *gin.Context) { s.searchSongs(c, false) })
		r.GET("/api/v1/"+prefix+"/library/classification-tags", s.RequireActor(false), func(c *gin.Context) {
			v, e := s.tags(c.Request.Context())
			if e != nil {
				s.report(c, e)
				return
			}
			c.JSON(200, v)
		})
	}
	r.GET("/api/v1/mobile/library/songs", func(c *gin.Context) { s.searchSongs(c, true) })
	for _, prefix := range []string{"tv", "mobile"} {
		var middleware []gin.HandlerFunc
		if prefix == "tv" {
			middleware = []gin.HandlerFunc{s.RequireActor(false)}
		}
		r.GET("/api/v1/"+prefix+"/library/songs/:id", append(middleware, func(c *gin.Context) {
			v, err := s.library.Song(c.Request.Context(), c.Param("id"))
			if err != nil {
				s.report(c, err)
				return
			}
			if prefix == "mobile" {
				c.JSON(200, mobileProjection(v))
			} else {
				c.JSON(200, Projection(v))
			}
		})...)
		textMiddleware := middleware
		if prefix == "tv" {
			textMiddleware = []gin.HandlerFunc{s.RequireActor(true)}
		}
		r.GET("/api/v1/"+prefix+"/library/songs/:id/text-tracks", append(textMiddleware, s.textTracks)...)
	}
	r.GET("/api/v1/tv/media/:id", s.RequireActor(true), s.media)
	r.HEAD("/api/v1/tv/media/:id", s.RequireActor(true), s.media)
	r.GET("/api/v1/tv/library/classification-tags/:id/cover", s.RequireActor(false), func(c *gin.Context) {
		var data []byte
		var kind string
		var active int
		err := s.db.QueryRowContext(c.Request.Context(), s.q["tag_cover"], c.Param("id")).Scan(&data, &kind, &active)
		if err == sql.ErrNoRows {
			s.report(c, fail(404, "封面不存在或已停用"))
			return
		}
		if err != nil {
			s.report(c, err)
			return
		}
		if active != 1 {
			s.report(c, fail(404, "图片不存在或已停用"))
			return
		}
		c.Header("X-Content-Type-Options", "nosniff")
		c.Data(200, kind, data)
	})
	r.GET("/api/v1/tv/library/artists", s.RequireActor(false), func(c *gin.Context) {
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
		items := []document{}
		needle := search.Normalize(c.Query("q"))
		for _, v := range index.artists {
			if needle == "" || strings.Contains(search.Searchable(str(v, "name")), needle) {
				items = append(items, v)
			}
		}
		sort.Slice(items, func(i, j int) bool {
			if str(items[i], "name") == str(items[j], "name") {
				return str(items[i], "id") < str(items[j], "id")
			}
			return str(items[i], "name") < str(items[j], "name")
		})
		c.JSON(200, paginate(items, page, size))
	})
	r.GET("/api/v1/tv/library/artists/:id", s.RequireActor(false), func(c *gin.Context) {
		index, err := s.current(c.Request.Context())
		if err != nil {
			s.report(c, err)
			return
		}
		v := index.artists[c.Param("id")]
		if v == nil {
			s.report(c, fail(404, "歌手不在当前曲库中"))
			return
		}
		c.JSON(200, clone(v))
	})
	r.GET("/api/v1/tv/library/artists/:id/songs", s.RequireActor(false), func(c *gin.Context) {
		query := c.Request.URL.Query()
		query.Set("artistId", c.Param("id"))
		c.Request.URL.RawQuery = query.Encode()
		s.searchSongs(c, false)
	})
	r.GET("/api/v1/tv/library/artists/:id/images/:kind", s.RequireActor(false), func(c *gin.Context) {
		kind := c.Param("kind")
		if kind != "portrait" && kind != "background" {
			s.report(c, fail(400, "图片类型只能是portrait或background"))
			return
		}
		kind = strings.ToUpper(kind)
		var data []byte
		var active int
		err := s.db.QueryRowContext(c.Request.Context(), s.q["artist_image"], c.Param("id"), kind).Scan(&data, &active)
		if err == sql.ErrNoRows {
			s.report(c, fail(404, "图片不存在或已停用"))
			return
		}
		if err != nil {
			s.report(c, err)
			return
		}
		if active != 1 {
			s.report(c, fail(404, "图片不存在或已停用"))
			return
		}
		c.Header("X-Content-Type-Options", "nosniff")
		c.Data(200, "image/jpeg", data)
	})
	r.GET("/api/v1/tv/library/playlists", s.RequireActor(false), func(c *gin.Context) {
		page, size, err := pageParams(c)
		if err != nil {
			s.report(c, err)
			return
		}
		items, err := s.playlists(c.Request.Context(), c.Query("q"))
		if err != nil {
			s.report(c, err)
			return
		}
		c.JSON(200, paginate(items, page, size))
	})
	r.GET("/api/v1/tv/library/playlists/:id", s.RequireActor(false), func(c *gin.Context) {
		v, err := s.playlist(c.Request.Context(), c.Param("id"))
		if err != nil {
			s.report(c, err)
			return
		}
		c.JSON(200, v)
	})
	r.GET("/api/v1/tv/library/playlists/:id/songs", s.RequireActor(false), func(c *gin.Context) {
		page, size, err := pageParams(c)
		if err != nil {
			s.report(c, err)
			return
		}
		v, err := s.playlist(c.Request.Context(), c.Param("id"))
		if err != nil {
			s.report(c, err)
			return
		}
		items := []document{}
		for _, raw := range array(v, "songs") {
			items = append(items, raw.(map[string]any))
		}
		c.JSON(200, paginate(items, page, size))
	})
}
func (s *Service) playlists(ctx context.Context, q string) ([]document, error) {
	index, err := s.current(ctx)
	if err != nil {
		return nil, err
	}
	rows, err := s.db.QueryContext(ctx, s.q["playlists"])
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []document{}
	needle := search.Normalize(q)
	for rows.Next() {
		var key, name, description, raw, created, updated string
		var revision int64
		if err = rows.Scan(&key, &name, &description, &raw, &revision, &created, &updated); err != nil {
			return nil, err
		}
		if needle != "" && !strings.Contains(search.Searchable(name+" "+description), needle) {
			continue
		}
		var ids []string
		if err = json.Unmarshal([]byte(raw), &ids); err != nil {
			return nil, err
		}
		items := []any{}
		var cover, coverVersion string
		for _, id := range ids {
			if entry, ok := index.byID[id]; ok {
				items = append(items, clone(entry.song))
				if cover == "" {
					cover = str(entry.song, "coverPath")
					coverVersion = str(entry.song, "revision")
				}
			}
		}
		v := document{"id": key, "name": name, "description": description, "revision": revision, "createdAt": created, "updatedAt": updated, "count": len(items), "missingCount": len(ids) - len(items), "songIds": ids, "songs": items}
		if cover != "" {
			v["coverPath"] = cover
			v["coverVersion"] = coverVersion
		}
		out = append(out, v)
	}
	return out, rows.Err()
}
func (s *Service) playlist(ctx context.Context, key string) (document, error) {
	items, err := s.playlists(ctx, "")
	if err != nil {
		return nil, err
	}
	for _, v := range items {
		if str(v, "id") == key {
			return v, nil
		}
	}
	return nil, fail(404, "歌单不存在或已删除")
}

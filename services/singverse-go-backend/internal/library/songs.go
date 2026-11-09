package library

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"sort"
	"strings"

	"github.com/gin-gonic/gin"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

func readSong(row scanner) (Song, error) {
	var stored songRecord
	if err := row.StructScan(&stored); err != nil {
		return Song{}, err
	}
	return stored.model()
}
func (s *Service) readSong(ctx context.Context, db store.DBTX, id string) (Song, error) {
	song, e := readSong(db.QueryRowxContext(ctx, s.q["song-columns"]+s.q["song-find"], id))
	if errors.Is(e, sql.ErrNoRows) {
		return song, missing("歌曲不存在或已从来源中移除")
	}
	return song, e
}

// SongWith reads through the caller's transaction, including a single-connection SQLite transaction.
func (s *Service) SongWith(ctx context.Context, db store.DBTX, id string) (map[string]any, error) {
	v, e := s.readSong(ctx, db, id)
	if e != nil {
		return nil, e
	}
	out := map[string]any{}
	e = decodeSongMap(encode(v), &out)
	return out, e
}
func (s *Service) Song(ctx context.Context, id string) (map[string]any, error) {
	return s.SongWith(ctx, s.db, id)
}

// LibrarySongs reads one consistent bulk projection without nested database calls.
func (s *Service) LibrarySongs(ctx context.Context, db store.DBTX) ([]map[string]any, error) {
	if db == nil {
		db = s.db
	}
	rows, e := db.QueryxContext(ctx, s.q["song-columns"])
	if e != nil {
		return nil, e
	}
	defer rows.Close()
	out := []map[string]any{}
	for rows.Next() {
		song, e := readSong(rows)
		if e != nil {
			return nil, e
		}
		v, e := projectSong(song)
		if e != nil {
			return nil, e
		}
		out = append(out, v)
	}
	return out, rows.Err()
}
func (s *Service) listSongs(c *gin.Context)   { s.songsHTTP(c, false) }
func (s *Service) sourceSongs(c *gin.Context) { s.songsHTTP(c, true) }
func (s *Service) songsHTTP(c *gin.Context, sourceOnly bool) {
	page, size, e := pageParams(c)
	if e != nil {
		s.fail(c, e)
		return
	}
	sourceID, q, status := c.Query("sourceId"), c.Query("q"), c.DefaultQuery("status", "ALL")
	tagIDs := c.QueryArray("tagIds")
	unclassified := c.DefaultQuery("unclassified", "false")
	if sourceOnly {
		sourceID = c.Param("id")
		q = ""
		status = "ALL"
		tagIDs = nil
		unclassified = "false"
	}
	if (sourceID != "" && (!validID.MatchString(sourceID) || len(sourceID) > 36)) || len([]rune(q)) > 200 || controls(q) || len(tagIDs) > 40 || (unclassified != "true" && unclassified != "false") {
		s.fail(c, bad("歌曲筛选参数无效"))
		return
	}
	review := -1
	switch status {
	case "REVIEW":
		review = 1
	case "MATCHED":
		review = 0
	case "ALL":
	default:
		s.fail(c, bad("请选择有效的解析状态"))
		return
	}
	ctx := c.Request.Context()
	out := Page[Song]{Items: []Song{}, Page: page, Size: size}
	e = store.WithinTx(ctx, s.db, func(tx store.DBTX) error {
		if sourceOnly {
			if _, e := s.findSource(ctx, tx, sourceID); e != nil {
				return e
			}
		}
		groups := map[string][]string{}
		for _, id := range tagIDs {
			var dim string
			if e := tx.QueryRowContext(ctx, s.q["class-tag"], id).Scan(&dim); e != nil {
				if errors.Is(e, sql.ErrNoRows) {
					return bad("标签不存在或已停用")
				}
				return e
			}
			groups[dim] = append(groups[dim], id)
		}
		args := []any{sourceID, sourceID, strings.TrimSpace(q), pattern(q), review, review, unclassified == "true"}
		filter := s.q["song-filter"]
		dimensions := []string{}
		for d := range groups {
			dimensions = append(dimensions, d)
		}
		sort.Strings(dimensions)
		for _, dim := range dimensions {
			filter += s.q["song-tag-prefix"] + strings.TrimSuffix(strings.Repeat("?,", len(groups[dim])), ",") + s.q["song-tag-suffix"]
			args = append(args, dim)
			for _, id := range groups[dim] {
				args = append(args, id)
			}
		}
		if e := tx.QueryRowContext(ctx, s.q["song-count"]+filter, args...).Scan(&out.TotalElements); e != nil {
			return e
		}
		order := s.q["song-order"]
		if sourceOnly {
			order = s.q["song-source-order"]
		}
		rows, e := tx.QueryxContext(ctx, s.q["song-columns"]+filter+order, append(args, size, page*size)...)
		if e != nil {
			return e
		}
		defer rows.Close()
		for rows.Next() {
			v, e := readSong(rows)
			if e != nil {
				return e
			}
			out.Items = append(out.Items, v)
		}
		return rows.Err()
	})
	if e != nil {
		s.fail(c, e)
		return
	}
	c.JSON(200, out)
}

// SongIdentity reads the public projection and frozen metadata identity together.
func (s *Service) SongIdentity(ctx context.Context, db store.DBTX, id string) (map[string]any, string, error) {
	if db == nil {
		db = s.db
	}
	song, e := s.readSong(ctx, db, id)
	if e != nil {
		return nil, "", e
	}
	out := map[string]any{}
	e = decodeSongMap(encode(song), &out)
	return out, song.contentFingerprint, e
}

// LibraryIdentities resolves all frozen metadata identities with one query.
func (s *Service) LibraryIdentities(ctx context.Context, db store.DBTX) (map[string]string, error) {
	if db == nil {
		db = s.db
	}
	rows, e := db.QueryContext(ctx, s.q["song-identities"])
	if e != nil {
		return nil, e
	}
	defer rows.Close()
	out := map[string]string{}
	for rows.Next() {
		var id string
		var size *int64
		var modified *string
		if e = rows.Scan(&id, &size, &modified); e != nil {
			return nil, e
		}
		out[id] = contentIdentity(id, size, modified)
	}
	return out, rows.Err()
}

func decodeSongMap(value string, out *map[string]any) error {
	d := json.NewDecoder(strings.NewReader(value))
	d.UseNumber()
	return d.Decode(out)
}

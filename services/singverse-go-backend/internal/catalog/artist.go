package catalog

import (
	"context"
	"crypto/md5"
	"database/sql"
	"encoding/hex"
	"errors"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/search"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
	"golang.org/x/text/unicode/norm"
	"sort"
	"strings"
)

func artistName(v string) string { return strings.TrimSpace(norm.NFKC.String(strings.TrimSpace(v))) }
func artistID(name string) string {
	b := md5.Sum([]byte("artist:" + sha(artistName(name))))
	b[6] = (b[6] & 15) | 48
	b[8] = (b[8] & 63) | 128
	h := hex.EncodeToString(b[:])
	return h[:8] + "-" + h[8:12] + "-" + h[12:16] + "-" + h[16:20] + "-" + h[20:]
}
func (s *Service) artist(ctx context.Context, tx store.DBTX, id string) (map[string]any, error) {
	r, e := one(ctx, tx, s.q["a_find"], id)
	if errors.Is(e, sql.ErrNoRows) {
		e = fail(404, "歌手不存在")
	}
	return r, e
}
func artistProfile(r map[string]any) (map[string]any, error) {
	aliases, e := decode[[]string](r["aliases_json"])
	if e != nil {
		return nil, e
	}
	v := map[string]any{"id": r["id"], "name": r["name"], "type": r["artist_type"], "biography": r["biography"], "active": num(r["active"]) == 1, "revision": num(r["revision"]), "createdAt": r["created_at"], "updatedAt": r["updated_at"], "managed": true, "aliases": aliases, "automatic": r["name_key"] != nil}
	states := []string{}
	for _, kind := range []string{"portrait", "background"} {
		if r[kind+"_version"] != nil {
			v[kind+"Version"] = r[kind+"_version"]
			v[kind+"Path"] = "/api/v1/artists/" + str(r["id"]) + "/images/" + kind
		}
		state := str(r[kind+"_fetch_state"])
		if state == "" {
			state = "PENDING"
		}
		v[kind+"FetchState"] = state
		states = append(states, state)
		for _, key := range []string{"origin", "source", "attribution"} {
			if r[kind+"_"+key] != nil {
				suffix := map[string]string{"origin": "Origin", "source": "Source", "attribution": "Attribution"}[key]
				v[kind+suffix] = r[kind+"_"+key]
			}
		}
	}
	overall := "READY"
	for _, state := range []string{"FAILED", "NEEDS_REVIEW", "RETRY", "PENDING"} {
		if has(state, states...) {
			overall = state
		}
	}
	v["fetchState"] = overall
	return v, nil
}
func (s *Service) ensureArtists(ctx context.Context, tx store.DBTX) (map[string][]map[string]any, error) {
	songs, e := s.library.LibrarySongs(ctx, tx)
	if e != nil {
		return nil, e
	}
	groups := map[string][]map[string]any{}
	names := map[string]string{}
	for _, raw := range songs {
		p := partyProjection(raw)
		seen := map[string]bool{}
		for _, name := range stringsArray(p["artists"]) {
			name = artistName(name)
			if name == "" {
				continue
			}
			key := artistID(name)
			if !seen[key] {
				groups[key] = append(groups[key], raw)
				names[key] = name
				seen[key] = true
			}
		}
	}
	if e = s.exec(ctx, tx, "a_lock"); e != nil {
		return nil, e
	}
	priorRows, e := query(ctx, tx, s.q["a_list"])
	if e != nil {
		return nil, e
	}
	prior := map[string]map[string]any{}
	for _, r := range priorRows {
		prior[str(r["name_key"])] = r
	}
	for key, name := range names {
		r := prior[sha(name)]
		if r == nil {
			at := now()
			if e = s.exec(ctx, tx, "a_insert", key, name, sha(name), sha(name), at, at); e != nil {
				return nil, e
			}
			for _, kind := range []string{"PORTRAIT", "BACKGROUND"} {
				if e = s.exec(ctx, tx, "a_ensureFetch", key, kind); e != nil {
					return nil, e
				}
			}
		} else {
			for _, kind := range []string{"PORTRAIT", "BACKGROUND"} {
				column := strings.ToLower(kind) + "_fetch_state"
				if str(r[column]) == "DORMANT" {
					if e = s.exec(ctx, tx, "a_retryImage", r["id"], kind); e != nil {
						return nil, e
					}
				}
			}
		}
	}

	for key, items := range groups {
		sort.Slice(items, func(i, j int) bool {
			a, b := str(items[i]["scannedAt"]), str(items[j]["scannedAt"])
			if a == b {
				return str(items[i]["id"]) < str(items[j]["id"])
			}
			return a > b
		})
		groups[key] = items
	}
	return groups, nil
}
func (s *Service) artistGet(ctx context.Context, id string) (any, error) {
	var out any
	e := store.WithinTx(ctx, s.db, func(tx store.DBTX) error {
		groups, e := s.ensureArtists(ctx, tx)
		if e != nil {
			return e
		}
		r, e := s.artist(ctx, tx, id)
		if e != nil {
			return e
		}
		v, e := artistProfile(r)
		if e != nil {
			return e
		}
		v["count"] = len(groups[id])
		out = v
		return nil
	})
	return out, e
}
func (s *Service) artistList(ctx context.Context, q, kind string, less *int, p, size int) (any, error) {
	if e := pages(p, size); e != nil {
		return nil, e
	}
	if !textValid(q, 200, true) || !has(kind, "", "MALE", "FEMALE", "GROUP", "UNKNOWN") || less != nil && (*less < 1 || *less > 1000000) {
		return nil, fail(400, "歌手筛选条件无效")
	}
	items := []map[string]any{}
	e := store.WithinTx(ctx, s.db, func(tx store.DBTX) error {
		groups, e := s.ensureArtists(ctx, tx)
		if e != nil {
			return e
		}
		rows, e := query(ctx, tx, s.q["a_list"])
		if e != nil {
			return e
		}
		for _, r := range rows {
			id := str(r["id"])
			if _, ok := groups[id]; !ok {
				continue
			}
			if !strings.Contains(search.Searchable(str(r["name"])+" "+str(r["aliases_json"])), search.Normalize(q)) || kind != "" && str(r["artist_type"]) != kind || less != nil && len(groups[id]) >= *less {
				continue
			}
			v, e := artistProfile(r)
			if e != nil {
				return e
			}
			v["count"] = len(groups[id])
			items = append(items, v)
		}
		return nil
	})
	if e != nil {
		return nil, e
	}
	return page(items, p, size), nil
}
func (s *Service) artistSongs(ctx context.Context, id string, p, size int) (any, error) {
	if e := pages(p, size); e != nil {
		return nil, e
	}
	var out any
	e := store.WithinTx(ctx, s.db, func(tx store.DBTX) error {
		groups, e := s.ensureArtists(ctx, tx)
		if e != nil {
			return e
		}
		items, ok := groups[id]
		if !ok {
			return fail(404, "歌手不存在")
		}
		out = page(items, p, size)
		return nil
	})
	return out, e
}
func (s *Service) category(ctx context.Context, id, kind string, revision int64) (any, error) {
	if !has(kind, "MALE", "FEMALE", "GROUP", "UNKNOWN") {
		return nil, fail(400, "歌手分类无效")
	}
	return s.tx(ctx, func(tx store.DBTX) (any, error) {
		if e := s.exec(ctx, tx, "a_lock"); e != nil {
			return nil, e
		}
		if _, e := s.artist(ctx, tx, id); e != nil {
			return nil, e
		}
		if e := s.cas(ctx, tx, "a_category", kind, now(), id, revision); e != nil {
			return nil, e
		}
		r, e := s.artist(ctx, tx, id)
		if e != nil {
			return nil, e
		}
		return artistProfile(r)
	})
}
func imageKind(v string) (string, error) {
	if !has(v, "portrait", "background") {
		return "", fail(400, "图片类型只能是portrait或background")
	}
	return strings.ToUpper(v), nil
}
func (s *Service) artistImage(ctx context.Context, id, kind, expected string, b []byte, remove bool) (any, error) {
	k, e := imageKind(kind)
	if e != nil {
		return nil, e
	}
	return s.tx(ctx, func(tx store.DBTX) (any, error) {
		if e := s.exec(ctx, tx, "a_lock"); e != nil {
			return nil, e
		}
		if _, e := s.artist(ctx, tx, id); e != nil {
			return nil, e
		}
		old, e := one(ctx, tx, s.q["a_image"], id, k)
		if e != nil && !errors.Is(e, sql.ErrNoRows) {
			return nil, e
		}
		prior := ""
		if old != nil {
			prior = str(old["version"])
		}
		version := ""
		if !remove {
			version = sha(string(b))
		}
		if (!remove && prior != version) || remove && old != nil {
			if prior != expected {
				return nil, fail(409, "图片已变化，请刷新歌手资料后重试")
			}
			if remove {
				e = s.exec(ctx, tx, "a_removeImage", id, k)
			} else if old == nil {
				e = s.exec(ctx, tx, "a_insertImage", id, k, version, b)
			} else {
				e = s.exec(ctx, tx, "a_updateImage", version, b, id, k)
			}
			if e != nil {
				return nil, e
			}
		}
		if !remove {
			if e = s.exec(ctx, tx, "a_imageSource", "UPLOAD", nil, nil, nil, id, k); e != nil {
				return nil, e
			}
		}
		if e = s.exec(ctx, tx, "a_deletePolicy", id, k); e != nil {
			return nil, e
		}
		if e = s.exec(ctx, tx, "a_insertPolicy", id, k, true); e != nil {
			return nil, e
		}
		r, e := s.artist(ctx, tx, id)
		if e != nil {
			return nil, e
		}
		return artistProfile(r)
	})
}
func (s *Service) retryArtwork(ctx context.Context, id string) error {
	_, e := s.tx(ctx, func(tx store.DBTX) (any, error) {
		if e := s.exec(ctx, tx, "a_lock"); e != nil {
			return nil, e
		}
		if _, e := s.artist(ctx, tx, id); e != nil {
			return nil, e
		}
		for _, kind := range []string{"PORTRAIT", "BACKGROUND"} {
			if e := s.exec(ctx, tx, "a_deletePolicy", id, kind); e != nil {
				return nil, e
			}
			if e := s.exec(ctx, tx, "a_retryImage", id, kind); e != nil {
				return nil, e
			}
		}
		return nil, s.exec(ctx, tx, "a_retry", id)
	})
	return e
}
func (s *Service) artistOverride(ctx context.Context, song string, reset bool, revision int64) (any, error) {
	return s.tx(ctx, func(tx store.DBTX) (any, error) {
		if reset {
			if e := s.exec(ctx, tx, "a_lock"); e != nil {
				return nil, e
			}
		}
		raw, e := s.library.SongWith(ctx, tx, song)
		if e != nil {
			return nil, e
		}
		source, path := str(raw["sourceId"]), sha(str(raw["relativePath"]))
		r, e := one(ctx, tx, s.q["a_override"], source, path)
		if e != nil && !errors.Is(e, sql.ErrNoRows) {
			return nil, e
		}
		rev := num(r["revision"])
		if !reset {
			return map[string]any{"revision": rev, "overridden": r["artists_json"] != nil}, nil
		}
		if rev != revision {
			return nil, fail(409, "歌手订正已变化，请刷新")
		}
		if e = s.exec(ctx, tx, "a_deleteOverride", source, path); e != nil {
			return nil, e
		}
		if e = s.exec(ctx, tx, "a_insertOverride", source, path, nil, revision+1); e != nil {
			return nil, e
		}
		if s.pathChanged != nil {
			if e = s.pathChanged(ctx, tx, source, str(raw["relativePath"])); e != nil {
				return nil, e
			}
		}
		return map[string]bool{"success": true}, nil
	})
}
func (s *Service) artworkSettings(ctx context.Context) (any, error) {
	var key *string
	e := s.db.QueryRowContext(ctx, s.q["a_fanartKey"]).Scan(&key)
	return map[string]bool{"fanartConfigured": key != nil}, e
}
func (s *Service) saveArtworkSettings(ctx context.Context, key *string, clear bool) (any, error) {
	if key != nil && !textValid(*key, 256, true) || clear && key != nil && strings.TrimSpace(*key) != "" {
		return nil, fail(400, "Fanart.tv 密钥无效或同时设置和清除")
	}
	_, e := s.tx(ctx, func(tx store.DBTX) (any, error) {
		if e := s.exec(ctx, tx, "a_lock"); e != nil {
			return nil, e
		}
		if clear {
			return nil, s.exec(ctx, tx, "a_saveFanartKey", nil)
		}
		if key != nil && strings.TrimSpace(*key) != "" {
			value, e := s.cipher.Encrypt(strings.TrimSpace(*key), "singverse:fanart-personal:v1")
			if e != nil {
				return nil, e
			}
			if e = s.exec(ctx, tx, "a_saveFanartKey", value); e != nil {
				return nil, e
			}
			return nil, s.exec(ctx, tx, "a_requeueMissingBackgrounds")
		}
		return nil, nil
	})
	if e != nil {
		return nil, e
	}
	return s.artworkSettings(ctx)
}

// ArtistEdited persists a NAS artist override in the caller's transaction.
func (s *Service) ArtistEdited(ctx context.Context, tx store.DBTX, source, relativePath string, artists []string) error {
	if e := s.exec(ctx, tx, "a_lock"); e != nil {
		return e
	}
	path := sha(relativePath)
	old, e := one(ctx, tx, s.q["a_override"], source, path)
	if e != nil && !errors.Is(e, sql.ErrNoRows) {
		return e
	}
	revision := num(old["revision"])
	if e = s.exec(ctx, tx, "a_deleteOverride", source, path); e != nil {
		return e
	}
	if e = s.exec(ctx, tx, "a_insertOverride", source, path, encode(artists), revision+1); e != nil {
		return e
	}
	if s.pathChanged != nil {
		return s.pathChanged(ctx, tx, source, relativePath)
	}
	return nil
}
func (s *Service) SetPathChanged(fn func(context.Context, store.DBTX, string, string) error) {
	s.pathChanged = fn
}

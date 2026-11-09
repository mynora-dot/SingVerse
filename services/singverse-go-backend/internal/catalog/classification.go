package catalog

import (
	"context"
	"database/sql"
	"errors"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/party"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
	"reflect"
	"sort"
	"strings"
)

var Dimensions = []string{"LANGUAGE", "DECADE", "GENRE", "MOOD", "SCENE", "CUSTOM"}

type Tag struct {
	ID           string   `json:"id"`
	Dimension    string   `json:"dimension"`
	Name         string   `json:"name"`
	Aliases      []string `json:"aliases"`
	Active       bool     `json:"active"`
	Revision     int64    `json:"revision"`
	Count        int64    `json:"count"`
	CoverPath    *string  `json:"coverPath"`
	CoverVersion *string  `json:"coverVersion"`
}
type SaveTag struct {
	Dimension string   `json:"dimension"`
	Name      string   `json:"name"`
	Aliases   []string `json:"aliases"`
	Active    bool     `json:"active"`
	Revision  int64    `json:"revision"`
}
type Value struct {
	TagIDs    []string `json:"tagIds"`
	Origin    string   `json:"origin"`
	Locked    bool     `json:"locked"`
	Confirmed bool     `json:"confirmed"`
}
type State struct {
	SongID     string           `json:"songId"`
	Revision   int64            `json:"revision"`
	Dimensions map[string]Value `json:"dimensions"`
}
type Change struct {
	RequestID        string              `json:"requestId"`
	Revision         int64               `json:"revision"`
	Dimensions       map[string][]string `json:"dimensions"`
	LockedDimensions []string            `json:"lockedDimensions"`
}
type Input struct {
	SongID      string   `json:"songId"`
	Title       string   `json:"title"`
	Artists     []string `json:"artists"`
	Album       *string  `json:"album"`
	ReleaseDate *string  `json:"releaseDate"`
	Language    string   `json:"language"`
	Version     string   `json:"version"`
	QualityTags []string `json:"qualityTags"`
}
type Suggestion struct {
	Dimensions        map[string][]string `json:"dimensions"`
	Reasons           map[string]string   `json:"reasons"`
	NewTagSuggestions []string            `json:"newTagSuggestions"`
}
type Start struct {
	All            bool     `json:"all"`
	SongIDs        []string `json:"songIds"`
	Dimensions     []string `json:"dimensions"`
	AutoDimensions []string `json:"autoDimensions"`
}
type Review struct {
	RequestID  string   `json:"requestId"`
	Revision   int64    `json:"revision"`
	Dimensions []string `json:"dimensions"`
}
type Undo struct {
	RequestID string `json:"requestId"`
	Revision  int64  `json:"revision"`
}

func dimensions(values []string, ai bool) error {
	if len(values) == 0 {
		return fail(400, "分类维度无效；AI 不能修改自定义标签")
	}
	for _, v := range values {
		if !has(v, Dimensions...) || ai && v == "CUSTOM" {
			return fail(400, "分类维度无效；AI 不能修改自定义标签")
		}
	}
	return nil
}
func (s *Service) tags(ctx context.Context, tx store.DBTX) ([]Tag, error) {
	rows, e := query(ctx, tx, s.q["c_tags"])
	if e != nil {
		return nil, e
	}
	out := []Tag{}
	for _, r := range rows {
		aliases, e := decode[[]string](r["aliases_json"])
		if e != nil {
			return nil, e
		}
		t := Tag{ID: str(r["id"]), Dimension: str(r["dimension"]), Name: str(r["name"]), Aliases: aliases, Active: num(r["active"]) == 1, Revision: num(r["revision"]), Count: num(r["song_count"])}
		if r["cover_version"] != nil {
			v := str(r["cover_version"])
			p := "/api/v1/classification/tags/" + t.ID + "/cover"
			t.CoverPath = &p
			t.CoverVersion = &v
		}
		out = append(out, t)
	}
	return out, nil
}
func (s *Service) saveTag(ctx context.Context, target string, v SaveTag) (any, error) {
	if !has(v.Dimension, Dimensions...) || !textValid(v.Name, 80, false) || v.Aliases == nil || len(v.Aliases) > 20 {
		return nil, fail(400, "标签名称、维度或别名无效")
	}
	for _, a := range v.Aliases {
		if !textValid(a, 80, false) {
			return nil, fail(400, "标签别名无效")
		}
	}
	return s.tx(ctx, func(tx store.DBTX) (any, error) {
		if e := s.lock(ctx, tx); e != nil {
			return nil, e
		}
		all, e := s.tags(ctx, tx)
		if e != nil {
			return nil, e
		}
		if len(all) >= 1000 && target == "" {
			return nil, fail(400, "词表最多支持 1000 个标签")
		}
		var old *Tag
		for _, t := range all {
			if t.ID == target {
				x := t
				old = &x
			} else if t.Dimension == v.Dimension && strings.EqualFold(t.Name, strings.TrimSpace(v.Name)) {
				return nil, fail(409, "该维度已有同名标签")
			}
		}
		if target == "" {
			target = id()
			e = s.exec(ctx, tx, "c_insertTag", target, v.Dimension, strings.TrimSpace(v.Name), encode(v.Aliases), v.Active)
		} else {
			if old == nil {
				return nil, fail(400, "标签不存在")
			}
			if old.Dimension != v.Dimension {
				return nil, fail(400, "已有标签不能跨维度移动")
			}
			e = s.cas(ctx, tx, "c_updateTag", strings.TrimSpace(v.Name), encode(v.Aliases), v.Active, target, v.Revision)
		}
		if e != nil {
			return nil, e
		}
		all, e = s.tags(ctx, tx)
		if e != nil {
			return nil, e
		}
		for _, t := range all {
			if t.ID == target {
				return t, nil
			}
		}
		return nil, fail(404, "标签不存在")
	})
}
func (s *Service) stored(ctx context.Context, tx store.DBTX, song string) (State, error) {
	r, e := one(ctx, tx, s.q["c_state"], song)
	if errors.Is(e, sql.ErrNoRows) {
		return State{song, 0, map[string]Value{}}, nil
	}
	if e != nil {
		return State{}, e
	}
	v, e := decode[map[string]Value](r["state_json"])
	return State{song, num(r["revision"]), v}, e
}
func (s *Service) state(ctx context.Context, tx store.DBTX, song string) (State, error) {
	if _, e := s.library.SongWith(ctx, tx, song); e != nil {
		return State{}, e
	}
	return s.stored(ctx, tx, song)
}
func (s *Service) input(ctx context.Context, tx store.DBTX, song string) (Input, string, error) {
	raw, e := s.library.SongWith(ctx, tx, song)
	if e != nil {
		return Input{}, "", e
	}
	p := partyProjection(raw)
	r := obj(raw["result"])
	meta := obj(obj(raw["metadata"])["fields"])
	nullable := func(v any) *string {
		if v == nil {
			return nil
		}
		s := str(v)
		return &s
	}
	artists := []string{}
	if a, ok := p["artists"].([]string); ok {
		artists = a
	} else if a, ok := p["artists"].([]any); ok {
		for _, v := range a {
			artists = append(artists, str(v))
		}
	}
	tags := []string{}
	if a, ok := r["qualityTags"].([]string); ok {
		tags = a
	} else if a, ok := r["qualityTags"].([]any); ok {
		for _, v := range a {
			tags = append(tags, str(v))
		}
	}
	in := Input{song, str(p["title"]), artists, nullable(meta["album"]), nullable(meta["releaseDate"]), str(r["language"]), str(r["versionType"]), tags}
	fingerprint := map[string]any{}
	for k, v := range raw {
		if k != "classification" {
			fingerprint[k] = v
		}
	}
	return in, sha(encode([]any{in, fingerprint})), nil
}
func (s *Service) validateTags(ctx context.Context, tx store.DBTX, values map[string][]string) error {
	dims := []string{}
	for k := range values {
		dims = append(dims, k)
	}
	if e := dimensions(dims, false); e != nil {
		return e
	}
	tags, e := s.tags(ctx, tx)
	if e != nil {
		return e
	}
	for dim, ids := range values {
		if ids == nil || len(ids) > 8 {
			return fail(400, "每个维度最多 8 个不重复标签")
		}
		seen := map[string]bool{}
		for _, id := range ids {
			if seen[id] {
				return fail(400, "每个维度最多 8 个不重复标签")
			}
			seen[id] = true
			valid := false
			for _, t := range tags {
				if t.ID == id && t.Active && t.Dimension == dim {
					valid = true
				}
			}
			if !valid {
				return fail(409, "标签已停用、缺失或不属于当前维度，请重新选择")
			}
		}
	}
	return nil
}
func (s *Service) write(ctx context.Context, tx store.DBTX, before State, after map[string]Value, origin string, task any) (State, error) {
	if reflect.DeepEqual(before.Dimensions, after) {
		return before, nil
	}
	data := encode(after)
	var e error
	if before.Revision == 0 {
		e = s.exec(ctx, tx, "c_insertState", before.SongID, data)
	} else {
		e = s.cas(ctx, tx, "c_updateState", data, before.SongID, before.Revision)
	}
	if e != nil {
		return State{}, e
	}
	if e = s.exec(ctx, tx, "c_clearAssignments", before.SongID); e != nil {
		return State{}, e
	}
	for dim, v := range after {
		for _, tag := range v.TagIDs {
			if e = s.exec(ctx, tx, "c_assign", before.SongID, dim, tag); e != nil {
				return State{}, e
			}
		}
	}
	if e = s.exec(ctx, tx, "c_insertHistory", id(), before.SongID, task, origin, encode(before.Dimensions), data, before.Revision+1, now()); e != nil {
		return State{}, e
	}
	return State{before.SongID, before.Revision + 1, after}, nil
}
func (s *Service) receipt(ctx context.Context, tx store.DBTX, requestID string, request any, fn func() (State, error)) (State, error) {
	if !uuidPattern.MatchString(requestID) {
		return State{}, fail(400, "需要有效的提交标识")
	}
	hash := sha(encode(request))
	old, e := one(ctx, tx, s.q["c_receipt"], requestID)
	if e == nil {
		if hash != str(old["request_hash"]) {
			return State{}, fail(409, "提交标识已用于其他操作")
		}
		return decode[State](old["response_json"])
	}
	if !errors.Is(e, sql.ErrNoRows) {
		return State{}, e
	}
	v, e := fn()
	if e != nil {
		return v, e
	}
	e = s.exec(ctx, tx, "c_receiptSave", requestID, hash, encode(v))
	return v, e
}
func copyValues(v map[string]Value) map[string]Value {
	r := map[string]Value{}
	for k, x := range v {
		r[k] = x
	}
	return r
}
func (s *Service) change(ctx context.Context, song string, v Change) (any, error) {
	if v.Dimensions == nil || v.LockedDimensions == nil {
		return nil, fail(400, "分类修改无效")
	}
	for _, d := range v.LockedDimensions {
		if !has(d, Dimensions...) {
			return nil, fail(400, "分类锁定维度无效")
		}
	}
	return s.tx(ctx, func(tx store.DBTX) (any, error) {
		if e := s.lock(ctx, tx); e != nil {
			return nil, e
		}
		return s.receipt(ctx, tx, v.RequestID, []any{song, v}, func() (State, error) {
			current, e := s.state(ctx, tx, song)
			if e != nil {
				return current, e
			}
			if current.Revision != v.Revision {
				return current, fail(409, "分类已变化，请刷新后保存")
			}
			if e = s.validateTags(ctx, tx, v.Dimensions); e != nil {
				return current, e
			}
			next := copyValues(current.Dimensions)
			for dim, ids := range v.Dimensions {
				next[dim] = Value{ids, "MANUAL", has(dim, v.LockedDimensions...), true}
			}
			return s.write(ctx, tx, current, next, "MANUAL", nil)
		})
	})
}
func (s *Service) history(ctx context.Context, song string, p, size int) (any, error) {
	if e := pages(p, size); e != nil {
		return nil, e
	}
	rows, e := query(ctx, s.db, s.q["c_history"], song, size, int64(p)*int64(size))
	if e != nil {
		return nil, e
	}
	out := []map[string]any{}
	for _, r := range rows {
		before, e := decode[map[string]Value](r["before_json"])
		if e != nil {
			return nil, e
		}
		after, e := decode[map[string]Value](r["after_json"])
		if e != nil {
			return nil, e
		}
		out = append(out, map[string]any{"id": r["id"], "songId": r["song_id"], "taskId": r["task_id"], "origin": r["origin"], "before": before, "after": after, "afterRevision": num(r["after_revision"]), "reverted": num(r["reverted"]) == 1, "createdAt": r["created_at"]})
	}
	return out, nil
}
func (s *Service) undo(ctx context.Context, history string, v Undo) (any, error) {
	return s.tx(ctx, func(tx store.DBTX) (any, error) {
		if e := s.lock(ctx, tx); e != nil {
			return nil, e
		}
		return s.receipt(ctx, tx, v.RequestID, []any{history, v}, func() (State, error) {
			h, e := one(ctx, tx, s.q["c_historyOne"], history)
			if errors.Is(e, sql.ErrNoRows) || num(h["reverted"]) == 1 {
				return State{}, fail(409, "历史不存在或已撤回")
			}
			if e != nil {
				return State{}, e
			}
			current, e := s.state(ctx, tx, str(h["song_id"]))
			if e != nil {
				return current, e
			}
			after, e := decode[map[string]Value](h["after_json"])
			if e != nil {
				return current, e
			}
			if current.Revision != v.Revision || current.Revision != num(h["after_revision"]) || !reflect.DeepEqual(current.Dimensions, after) {
				return current, fail(409, "分类或锁定状态已有后续修改，不能直接撤回")
			}
			before, e := decode[map[string]Value](h["before_json"])
			if e != nil {
				return current, e
			}
			updated, e := s.write(ctx, tx, current, before, "UNDO", h["task_id"])
			if e == nil {
				e = s.exec(ctx, tx, "c_reverted", history)
			}
			return updated, e
		})
	})
}
func sorted(v []string) []string { out := append([]string{}, v...); sort.Strings(out); return out }

func partyProjection(raw map[string]any) map[string]any { return party.Projection(raw) }

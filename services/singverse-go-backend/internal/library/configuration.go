package library

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"strconv"
	"strings"

	"github.com/gin-gonic/gin"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

type parserState struct {
	revision int64
	updated  *string
	doc      Document
}

func (s *Service) state(ctx context.Context, db store.DBTX) (parserState, error) {
	st := parserState{doc: defaultDocument()}
	var raw, updated string
	err := db.QueryRowContext(ctx, s.q["config-read"]).Scan(&st.revision, &raw, &updated)
	if errors.Is(err, sql.ErrNoRows) {
		return st, nil
	}
	if err != nil {
		return st, err
	}
	st.updated = &updated
	if err = json.Unmarshal([]byte(raw), &st.doc); err != nil {
		return st, err
	}
	if err = validateDocument(st.doc); err != nil {
		return st, err
	}
	return st, nil
}
func validateDocument(d Document) error {
	if d.SchemaVersion != 2 || len(d.Definitions) > 1000 || len(d.Profiles) > 100 {
		return invalid("", -1, "definitions", "配置版本或规则/方案数量无效")
	}
	ids := map[string]bool{}
	for i, v := range d.Definitions {
		if !validID.MatchString(v.ID) || ids[v.ID] {
			return invalid(v.ID, i, "id", "规则 ID 无效或重复")
		}
		ids[v.ID] = true
		if _, err := compileRule(executable(v, true), i); err != nil {
			return err
		}
	}
	profiles := map[string]bool{}
	for _, p := range d.Profiles {
		if !validID.MatchString(p.ID) || profiles[p.ID] || !text(p.Name, 100) {
			return invalid("", -1, "profiles", "方案 ID 或名称无效")
		}
		profiles[p.ID] = true
		if _, err := resolve(d, p.Entries); err != nil {
			return err
		}
	}
	if !profiles["default"] {
		return invalid("", -1, "profiles", "默认解析方案必须保留")
	}
	return nil
}
func executable(d Definition, enabled bool) Rule {
	return Rule{d.ID, d.Name, d.Type, ptr(enabled), d.Expression, d.BuiltinID}
}
func resolve(d Document, entries []Entry) ([]Rule, error) {
	if entries == nil || len(entries) > 100 {
		return nil, invalid("", -1, "entries", "方案成员列表必填，最多 100 条")
	}
	out := make([]Rule, 0, len(entries))
	seen := map[string]bool{}
	for i, e := range entries {
		if e.Enabled == nil || seen[e.RuleID] {
			return nil, invalid(e.RuleID, i, "entries", "成员需要唯一 ruleId 和 enabled")
		}
		seen[e.RuleID] = true
		found := false
		for _, def := range d.Definitions {
			if def.ID == e.RuleID {
				out = append(out, executable(def, *e.Enabled))
				found = true
				break
			}
		}
		if !found {
			return nil, invalid(e.RuleID, i, "entries", "引用的规则不存在，请加载最新规则库")
		}
	}
	return out, nil
}
func profile(st parserState, id string) (Profile, error) {
	for _, p := range st.doc.Profiles {
		if p.ID == id {
			return p, nil
		}
	}
	return Profile{}, missing("解析方案不存在")
}
func definition(st parserState, id string) (Definition, error) {
	for _, v := range st.doc.Definitions {
		if v.ID == id {
			return v, nil
		}
	}
	return Definition{}, missing("解析规则不存在")
}
func revision(expected *int64, st parserState) error {
	if expected == nil || *expected < 0 || *expected == int64(^uint64(0)>>1) {
		return invalid("", -1, "revision", "必须提供有效配置修订号")
	}
	if *expected != st.revision {
		return conflict("配置已被其他窗口修改，请加载最新配置并保留草稿")
	}
	return nil
}
func (s *Service) persist(ctx context.Context, tx store.DBTX, st parserState, d Document) (parserState, error) {
	if err := validateDocument(d); err != nil {
		return st, err
	}
	updated := now()
	if st.updated == nil {
		if _, err := tx.ExecContext(ctx, s.q["config-insert"], 1, encode(d), updated); err != nil {
			return st, err
		}
	} else if err := changed(tx.ExecContext(ctx, s.q["config-update"], encode(d), updated, st.revision)); err != nil {
		return st, err
	}
	return parserState{st.revision + 1, &updated, d}, nil
}
func references(d Document, id string) []Reference {
	out := []Reference{}
	for _, p := range d.Profiles {
		for _, e := range p.Entries {
			if e.RuleID == id {
				out = append(out, Reference{p.ID, p.Name})
				break
			}
		}
	}
	return out
}
func workspace(st parserState, changed *string) Workspace {
	out := Workspace{st.revision, st.updated, []DefinitionView{}, st.doc.Profiles, presets(), changed}
	for _, d := range st.doc.Definitions {
		out.Definitions = append(out.Definitions, DefinitionView{d, references(st.doc, d.ID)})
	}
	return out
}
func configuration(st parserState) (Configuration, error) {
	p, e := profile(st, "default")
	if e != nil {
		return Configuration{}, e
	}
	rules, e := resolve(st.doc, p.Entries)
	return Configuration{st.revision, st.updated, rules, presets()}, e
}
func (s *Service) getRules(c *gin.Context) {
	st, e := s.state(c.Request.Context(), s.db)
	if e != nil {
		s.fail(c, e)
		return
	}
	out, e := configuration(st)
	if e != nil {
		s.fail(c, e)
		return
	}
	c.JSON(200, out)
}
func (s *Service) workspaceHTTP(c *gin.Context) {
	st, e := s.state(c.Request.Context(), s.db)
	if e != nil {
		s.fail(c, e)
		return
	}
	c.JSON(200, workspace(st, nil))
}
func (s *Service) saveRules(c *gin.Context) {
	var in struct {
		Revision *int64 `json:"revision"`
		Rules    []Rule `json:"rules"`
	}
	if !s.decode(c, &in) {
		return
	}
	var out Configuration
	err := s.transaction(c.Request.Context(), func(tx store.DBTX) error {
		st, e := s.state(c.Request.Context(), tx)
		if e != nil {
			return e
		}
		if e = revision(in.Revision, st); e != nil {
			return e
		}
		if _, e = compileRules(in.Rules); e != nil {
			return e
		}
		d := st.doc
		entries := []Entry{}
		for _, r := range in.Rules {
			next := Definition{r.ID, r.Name, r.Type, r.Expression, r.BuiltinID}
			found := false
			for i, old := range d.Definitions {
				if old.ID == r.ID {
					if old.Type == "BUILTIN" && encode(old) != encode(next) {
						return conflict("内置定义只读，请使用复制后的自定义规则")
					}
					d.Definitions[i] = next
					found = true
					break
				}
			}
			if !found {
				d.Definitions = append(d.Definitions, next)
			}
			entries = append(entries, Entry{r.ID, r.Enabled})
		}
		for i, p := range d.Profiles {
			if p.ID == "default" {
				d.Profiles[i].Entries = entries
			}
		}
		st, e = s.persist(c.Request.Context(), tx, st, d)
		if e == nil {
			out, e = configuration(st)
		}
		return e
	})
	if err != nil {
		s.fail(c, err)
		return
	}
	c.JSON(200, out)
}
func (s *Service) previewRules(c *gin.Context) {
	var in struct {
		Filename *string `json:"filename"`
		Rules    []Rule  `json:"rules"`
	}
	if !s.decode(c, &in) {
		return
	}
	if in.Filename == nil {
		s.fail(c, invalid("", -1, "filename", "必须提供待解析文本"))
		return
	}
	rules := in.Rules
	if rules == nil {
		st, e := s.state(c.Request.Context(), s.db)
		if e != nil {
			s.fail(c, e)
			return
		}
		out, e := configuration(st)
		if e != nil {
			s.fail(c, e)
			return
		}
		rules = out.Rules
	}
	p, e := Parse(*in.Filename, rules)
	if e != nil {
		s.fail(c, e)
		return
	}
	c.JSON(200, p)
}
func (s *Service) writeDefinition(c *gin.Context) {
	var in struct {
		Revision   *int64      `json:"revision"`
		Definition *Definition `json:"definition"`
	}
	if !s.decode(c, &in) {
		return
	}
	if in.Definition == nil {
		s.fail(c, invalid("", -1, "definition", "规则定义必填"))
		return
	}
	id := c.Param("id")
	created := id == ""
	if created {
		id = uuid()
	}
	var out Workspace
	err := s.transaction(c.Request.Context(), func(tx store.DBTX) error {
		st, e := s.state(c.Request.Context(), tx)
		if e != nil {
			return e
		}
		if e = revision(in.Revision, st); e != nil {
			return e
		}
		if !created {
			old, e := definition(st, id)
			if e != nil {
				return e
			}
			if old.Type == "BUILTIN" {
				return conflict("内置规则只读，请复制为自定义规则")
			}
		}
		d := *in.Definition
		d.ID = id
		if d.Type == "BUILTIN" || d.BuiltinID != nil {
			return invalid(id, -1, "type", "请创建模板或正则，内置规则只能复制")
		}
		defs := []Definition{d}
		for _, old := range st.doc.Definitions {
			if old.ID != id {
				defs = append(defs, old)
			}
		}
		st.doc.Definitions = defs
		st, e = s.persist(c.Request.Context(), tx, st, st.doc)
		if e == nil {
			out = workspace(st, &id)
		}
		return e
	})
	if err != nil {
		s.fail(c, err)
		return
	}
	status := 200
	if created {
		status = 201
	}
	c.JSON(status, out)
}
func expectedRevision(c *gin.Context) (*int64, error) {
	v, e := strconv.ParseInt(c.Query("revision"), 10, 64)
	if e != nil {
		return nil, invalid("", -1, "revision", "必须提供有效配置修订号")
	}
	return &v, nil
}
func (s *Service) deleteDefinition(c *gin.Context) {
	expected, e := expectedRevision(c)
	if e != nil {
		s.fail(c, e)
		return
	}
	id := c.Param("id")
	var out Workspace
	e = s.transaction(c.Request.Context(), func(tx store.DBTX) error {
		st, e := s.state(c.Request.Context(), tx)
		if e != nil {
			return e
		}
		if e = revision(expected, st); e != nil {
			return e
		}
		old, e := definition(st, id)
		if e != nil {
			return e
		}
		if old.Type == "BUILTIN" {
			return conflict("内置规则不能删除，可以在方案中停用")
		}
		refs := references(st.doc, id)
		if len(refs) > 0 {
			return &problem{409, "规则正被方案引用，请先移除引用", map[string]any{"referencedBy": refs}}
		}
		defs := []Definition{}
		for _, v := range st.doc.Definitions {
			if v.ID != id {
				defs = append(defs, v)
			}
		}
		st.doc.Definitions = defs
		st, e = s.persist(c.Request.Context(), tx, st, st.doc)
		if e == nil {
			out = workspace(st, &id)
		}
		return e
	})
	if e != nil {
		s.fail(c, e)
		return
	}
	c.JSON(200, out)
}
func (s *Service) writeProfile(c *gin.Context) {
	var in struct {
		Revision *int64   `json:"revision"`
		Profile  *Profile `json:"profile"`
	}
	if !s.decode(c, &in) {
		return
	}
	if in.Profile == nil {
		s.fail(c, invalid("", -1, "profile", "方案内容必填"))
		return
	}
	id := c.Param("id")
	created := id == ""
	if created {
		id = uuid()
	}
	var out Workspace
	e := s.transaction(c.Request.Context(), func(tx store.DBTX) error {
		st, e := s.state(c.Request.Context(), tx)
		if e != nil {
			return e
		}
		if e = revision(in.Revision, st); e != nil {
			return e
		}
		if !created {
			if _, e = profile(st, id); e != nil {
				return e
			}
		}
		next := *in.Profile
		next.ID = id
		next.Name = strings.TrimSpace(next.Name)
		if !text(next.Name, 100) {
			return invalid("", -1, "name", "方案名称需要 1～100 个字符")
		}
		if _, e = resolve(st.doc, next.Entries); e != nil {
			return e
		}
		if created {
			st.doc.Profiles = append(st.doc.Profiles, next)
		} else {
			for i, p := range st.doc.Profiles {
				if p.ID == id {
					st.doc.Profiles[i] = next
				}
			}
		}
		st, e = s.persist(c.Request.Context(), tx, st, st.doc)
		if e == nil {
			out = workspace(st, &id)
		}
		return e
	})
	if e != nil {
		s.fail(c, e)
		return
	}
	status := 200
	if created {
		status = 201
	}
	c.JSON(status, out)
}
func (s *Service) deleteProfile(c *gin.Context) {
	expected, e := expectedRevision(c)
	if e != nil {
		s.fail(c, e)
		return
	}
	id := c.Param("id")
	var out Workspace
	e = s.transaction(c.Request.Context(), func(tx store.DBTX) error {
		st, e := s.state(c.Request.Context(), tx)
		if e != nil {
			return e
		}
		if e = revision(expected, st); e != nil {
			return e
		}
		if _, e = profile(st, id); e != nil {
			return e
		}
		if id == "default" {
			return conflict("默认解析方案不能删除，可以改名或清空成员")
		}
		rows, e := tx.QueryContext(c.Request.Context(), s.q["source-reference"], id)
		if e != nil {
			return e
		}
		bound := rows.Next()
		rows.Close()
		if bound {
			return conflict("解析方案正被媒体来源使用，请先修改媒体来源的方案")
		}
		ps := []Profile{}
		for _, p := range st.doc.Profiles {
			if p.ID != id {
				ps = append(ps, p)
			}
		}
		st.doc.Profiles = ps
		st, e = s.persist(c.Request.Context(), tx, st, st.doc)
		if e == nil {
			out = workspace(st, &id)
		}
		return e
	})
	if e != nil {
		s.fail(c, e)
		return
	}
	c.JSON(200, out)
}
func (s *Service) previewProfile(c *gin.Context) {
	var in struct {
		Filename  *string `json:"filename"`
		ProfileID *string `json:"profileId"`
		Revision  *int64  `json:"revision"`
		Entries   []Entry `json:"entries"`
	}
	if !s.decode(c, &in) {
		return
	}
	if in.Filename == nil {
		s.fail(c, invalid("", -1, "filename", "必须提供待解析文本"))
		return
	}
	st, e := s.state(c.Request.Context(), s.db)
	if e != nil {
		s.fail(c, e)
		return
	}
	entries := in.Entries
	var name *string
	if in.ProfileID != nil {
		if in.Revision != nil || in.Entries != nil {
			s.fail(c, invalid("", -1, "profileId", "已保存方案和草稿来源不能同时提供"))
			return
		}
		p, e := profile(st, *in.ProfileID)
		if e != nil {
			s.fail(c, e)
			return
		}
		entries = p.Entries
		name = &p.Name
	} else if e = revision(in.Revision, st); e != nil {
		s.fail(c, e)
		return
	}
	rules, e := resolve(st.doc, entries)
	if e != nil {
		s.fail(c, e)
		return
	}
	p, e := Parse(*in.Filename, rules)
	if e != nil {
		s.fail(c, e)
		return
	}
	c.JSON(200, gin.H{"revision": st.revision, "profileId": in.ProfileID, "profileName": name, "preview": p})
}

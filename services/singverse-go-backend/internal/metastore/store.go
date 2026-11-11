// Package metastore owns current formal documents, evidence, local decisions
// and persistent assets. All methods accept the caller's transaction.
package metastore

import (
	"context"
	"database/sql"
	"embed"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jmoiron/sqlx"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/metastandard"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

//go:embed sql/*.sql
var sqlFiles embed.FS

type Store struct {
	q    map[string]string
	Root string
}
type Problem struct {
	Status  int
	Message string
}

func (e *Problem) Error() string   { return e.Message }
func (e *Problem) StatusCode() int { return e.Status }
func New(root string) (*Store, error) {
	q, e := store.ReadQueries(sqlFiles, "sql")
	return &Store{q: q, Root: root}, e
}
func NewID() string       { return uuid.NewString() }
func timestamp() string   { return time.Now().UTC().Format(time.RFC3339Nano) }
func encode(v any) string { b, _ := json.Marshal(v); return string(b) }
func (s *Store) Read(ctx context.Context, db store.DBTX, id string) (metastandard.Document, error) {
	var raw string
	if e := db.QueryRowContext(ctx, s.q["document-read"], id).Scan(&raw); e != nil {
		return metastandard.Document{}, e
	}
	return metastandard.Decode([]byte(raw))
}
func (s *Store) Create(ctx context.Context, db store.DBTX, kind, id, parent string) (metastandard.Document, error) {
	d := metastandard.Document{SchemaVersion: metastandard.Version, ID: id, Type: kind, Revision: "1", ParentID: parent, Fields: map[string]any{}, States: map[string]metastandard.FieldState{}}
	if e := s.validateRelations(ctx, db, d); e != nil {
		return d, e
	}
	_, e := db.ExecContext(ctx, s.q["document-insert"], id, kind, nullable(parent), d.Revision, encode(d), "", "", timestamp())
	return d, e
}
func nullable(v string) any {
	if v == "" {
		return nil
	}
	return v
}
func (s *Store) save(ctx context.Context, db store.DBTX, d *metastandard.Document, prior string) error {
	n, e := strconv.ParseInt(prior, 10, 64)
	if e != nil || n == 9223372036854775807 {
		return errors.New("metadata revision exhausted")
	}
	d.Revision = strconv.FormatInt(n+1, 10)
	if e = s.validateRelations(ctx, db, *d); e != nil {
		return e
	}
	title, _ := d.Fields["title"].(string)
	if title == "" {
		title, _ = d.Fields["name"].(string)
	}
	complete := title != "" && (!blank(d.Fields["artists"]) || !blank(d.Fields["credits"]))
	search := strings.ToLower(title + " " + encode(d.Fields["artists"]) + " " + encode(d.Fields["credits"]) + " " + encode(d.Fields["aliases"]))
	r, e := db.ExecContext(ctx, s.q["document-update"], nullable(d.ParentID), d.Revision, encode(d), title, search, complete, timestamp(), d.ID, prior)
	if e != nil {
		return e
	}
	n, e = r.RowsAffected()
	if e != nil {
		return e
	}
	if n != 1 {
		return &Problem{409, "资料已变化，请重新加载后确认"}
	}
	return s.References(ctx, db, "CURRENT", d.ID, metastandard.AssetIDs(*d), nil)
}

type Evidence struct {
	Key         string              `json:"key"`
	Revision    string              `json:"revision"`
	Origin      metastandard.Origin `json:"origin"`
	Fields      map[string]any      `json:"fields"`
	Unavailable bool                `json:"unavailable,omitempty"`
	Clears      []string            `json:"clears,omitempty"`
}

func sourceKey(o metastandard.Origin) string {
	switch o.Kind {
	case "SHARED":
		return "SHARED:" + o.CollectionID
	case "PLATFORM", "DOWNLOAD":
		return o.Kind + ":" + o.Provider + ":" + o.EntityType + ":" + o.ExternalID
	default:
		return o.Kind
	}
}
func local(state metastandard.FieldState) bool {
	return state.Decision == "SET" || state.Decision == "CLEAR"
}
func blank(v any) bool {
	switch x := v.(type) {
	case nil:
		return true
	case string:
		return x == ""
	case []any:
		return len(x) == 0
	case []string:
		return len(x) == 0
	case map[string]any:
		return len(x) == 0
	}
	return false
}

// Observe records a source independently of the current value. Baselines survive
// task cleanup. Local decisions win; a followed source may advance its own value.
// Atomic fields (credits, tags, lyrics and a whole media observation) never splice.
func (s *Store) Observe(ctx context.Context, db store.DBTX, id string, in Evidence) (metastandard.Document, []string, error) {
	if _, e := db.ExecContext(ctx, s.q["document-lock"], id); e != nil {
		return metastandard.Document{}, nil, e
	}
	d, e := s.Read(ctx, db, id)
	if e != nil {
		return d, nil, e
	}
	if in.Key == "" {
		in.Key = sourceKey(in.Origin)
	}
	if len(in.Key) > 160 {
		return d, nil, errors.New("evidence source too long")
	}
	if in.Revision == "" {
		in.Revision = "1"
	}
	if !metastandard.ValidRevision(in.Revision) {
		return d, nil, &Problem{400, "来源修订号无效"}
	}
	if in.Fields == nil {
		in.Fields = map[string]any{}
	}
	clearFields := map[string]bool{}
	for _, name := range in.Clears {
		clearFields[name] = true
		in.Fields[name] = nil
	}
	var old Evidence
	var raw string
	e = db.QueryRowContext(ctx, s.q["evidence-read"], id, in.Key).Scan(&raw)
	if e != nil && !errors.Is(e, sql.ErrNoRows) {
		return d, nil, e
	}
	if e == nil {
		if e = json.Unmarshal([]byte(raw), &old); e != nil {
			return d, nil, e
		}
	}
	if in.Origin.Kind == "SHARED" && old.Revision != "" {
		a, ae := strconv.ParseInt(in.Revision, 10, 64)
		b, be := strconv.ParseInt(old.Revision, 10, 64)
		if ae != nil || be != nil {
			return d, nil, errors.New("invalid shared revision")
		}
		if a < b {
			return d, nil, &Problem{409, "共享资料版本已过期"}
		}
		if a == b {
			for name, value := range in.Fields {
				if (!blank(value) || contains(in.Clears, name)) && (encode(old.Fields[name]) != encode(value) || contains(old.Clears, name) != contains(in.Clears, name)) {
					return d, nil, &Problem{409, "同一共享修订包含不同内容"}
				}
			}
		}
	}
	prior := d.Revision
	changed := false
	differences := []string{}
	for name, value := range in.Fields {
		if blank(value) && !clearFields[name] {
			continue
		}
		if e = metastandard.ValidateField(d.Type, name, value); e != nil {
			return d, nil, fmt.Errorf("%s: %w", name, e)
		}
		state, exists := d.States[name]
		sameSource := sourceKey(state.Origin) == in.Key
		// A three-way comparison also protects values changed through a legacy
		// writer between imports, even when that writer had no decision flag.
		base, hasBase := old.Fields[name]
		diverged := hasBase && encode(d.Fields[name]) != encode(base) && !blank(d.Fields[name])
		adopt := !local(state) && (!exists || blank(d.Fields[name]) && state.Status != "CLEARED" || state.Origin.Kind == "FILENAME" || sameSource && !diverged)
		if adopt && name == "cover" {
			var pendingRevision string
			e := db.QueryRowContext(ctx, s.q["asset-pending-decision"], id, id, id).Scan(&pendingRevision)
			if e != nil && !errors.Is(e, sql.ErrNoRows) {
				return d, nil, e
			}
			if e == nil && pendingRevision == state.Revision {
				adopt = false
			}
		}
		if !adopt && encode(d.Fields[name]) != encode(value) {
			differences = append(differences, name)
			if _, e = db.ExecContext(ctx, s.q["difference-delete"], id, name, in.Key); e != nil {
				return d, nil, e
			}
			if _, e = db.ExecContext(ctx, s.q["difference-insert"], id, name, in.Key, encode(value)); e != nil {
				return d, nil, e
			}
			continue
		}
		if adopt && (encode(d.Fields[name]) != encode(value) || !exists || sourceKey(state.Origin) != in.Key || (state.Status == "CLEARED") != clearFields[name]) {
			d.Fields[name] = value
			status := "PRESENT"
			if clearFields[name] {
				status = "CLEARED"
			}
			d.States[name] = metastandard.FieldState{Origin: in.Origin, Decision: "AUTO", Status: status, Revision: next(prior)}
			changed = true
		}
		if _, e = db.ExecContext(ctx, s.q["difference-delete"], id, name, in.Key); e != nil {
			return d, nil, e
		}
	}
	if changed {
		if e = s.save(ctx, db, &d, prior); e != nil {
			return d, nil, e
		}
	}
	mergedFields := map[string]any{}
	for name, value := range old.Fields {
		mergedFields[name] = value
	}
	for name, value := range in.Fields {
		if !blank(value) || clearFields[name] {
			mergedFields[name] = value
		}
	}
	for _, name := range old.Clears {
		if _, supplied := in.Fields[name]; !supplied {
			in.Clears = append(in.Clears, name)
		}
	}
	in.Fields = mergedFields
	data := encode(in)
	digest, e := metastandard.DocumentDigest([]byte(data))
	if e != nil {
		return d, nil, e
	}
	if _, e = db.ExecContext(ctx, s.q["evidence-delete"], id, in.Key); e != nil {
		return d, nil, e
	}
	_, e = db.ExecContext(ctx, s.q["evidence-insert"], id, in.Key, in.Revision, digest, data)
	return d, differences, e
}
func next(rev string) string {
	n, _ := strconv.ParseInt(rev, 10, 64)
	return strconv.FormatInt(n+1, 10)
}

type Operation struct {
	Field  string               `json:"field"`
	Op     string               `json:"op"`
	Value  any                  `json:"value,omitempty"`
	Origin *metastandard.Origin `json:"origin,omitempty"`
}
type Update struct {
	ExpectedRevision string      `json:"expectedRevision"`
	RequestID        string      `json:"requestId"`
	Operations       []Operation `json:"operations"`
	ParentID         *string     `json:"parentId,omitempty"`
}

// Update implements optimistic concurrency and durable exactly-once receipts.
// Confirming an equal value advances the revision and protects it as requested.
func (s *Store) Update(ctx context.Context, db store.DBTX, id string, in Update) (metastandard.Document, error) {
	if !metastandard.UUID.MatchString(in.RequestID) || (len(in.Operations) == 0 && in.ParentID == nil) || len(in.Operations) > 64 {
		return metastandard.Document{}, &Problem{400, "提交标识或字段操作无效"}
	}
	digest, e := metastandard.DocumentDigest([]byte(encode(in)))
	if e != nil {
		return metastandard.Document{}, e
	}
	var receipt struct {
		EntityID string `db:"entity_id"`
		Hash     string `db:"request_hash"`
		Response string `db:"response_json"`
	}
	e = sqlx.GetContext(ctx, db, &receipt, s.q["request-read"], in.RequestID)
	if e == nil {
		if receipt.EntityID != id || receipt.Hash != digest {
			return metastandard.Document{}, &Problem{409, "提交标识已用于其他操作"}
		}
		return metastandard.Decode([]byte(receipt.Response))
	}
	if !errors.Is(e, sql.ErrNoRows) {
		return metastandard.Document{}, e
	}
	if _, e = db.ExecContext(ctx, s.q["document-lock"], id); e != nil {
		return metastandard.Document{}, e
	}
	cached, _, found, err := s.receipt(ctx, db, id, in.RequestID, in)
	if err != nil || found {
		return cached, err
	}
	d, e := s.Read(ctx, db, id)
	if e != nil {
		return d, e
	}
	if d.Revision != in.ExpectedRevision {
		return d, &Problem{409, "资料已变化，请重新加载后确认"}
	}
	if in.ParentID != nil {
		p, e := s.Read(ctx, db, *in.ParentID)
		if e != nil {
			return d, e
		}
		if !(d.Type == "VARIANT" && p.Type == "SONG" || d.Type == "MEDIA" && p.Type == "VARIANT") {
			return d, &Problem{400, "关联层级无效"}
		}
		d.ParentID = p.ID
		d.ParentState = &metastandard.FieldState{Decision: "SET", Status: "PRESENT", Revision: next(d.Revision), Origin: metastandard.Origin{Kind: "MANUAL"}}
	}
	seen := map[string]bool{}
	for _, op := range in.Operations {
		if op.Op != "SET" && op.Value != nil {
			return d, &Problem{400, "CLEAR 和 RESET 不接受字段值"}
		}
		if seen[op.Field] {
			return d, &Problem{400, "同一字段不能重复操作"}
		}
		seen[op.Field] = true
		if e = metastandard.ValidateField(d.Type, op.Field, op.Value); e != nil {
			return d, &Problem{400, e.Error()}
		}
		origin := metastandard.Origin{Kind: "MANUAL"}
		if op.Op == "SET" && encode(op.Value) == encode(d.Fields[op.Field]) && d.States[op.Field].Origin.Kind != "" {
			origin = d.States[op.Field].Origin
		}
		if op.Origin != nil {
			origin = *op.Origin
		}
		state := metastandard.FieldState{Origin: origin, Decision: op.Op, Status: "PRESENT", Revision: next(d.Revision)}
		switch op.Op {
		case "SET":
			if op.Value == nil || encode(op.Value) == "null" {
				return d, &Problem{400, "SET 需要值，请使用 CLEAR 清空"}
			}
			d.Fields[op.Field] = op.Value
		case "CLEAR":
			d.Fields[op.Field] = nil
			state.Status = "CLEARED"
		case "RESET":
			rows, e := store.Maps(ctx, db, s.q["evidence-all"], id)
			if e != nil {
				return d, e
			}
			var best *Evidence
			for _, row := range rows {
				var candidate Evidence
				if e = json.Unmarshal([]byte(fmt.Sprint(row["evidence_json"])), &candidate); e != nil {
					return d, e
				}
				if candidate.Unavailable || blank(candidate.Fields[op.Field]) && !contains(candidate.Clears, op.Field) {
					continue
				}
				if typ := metastandard.Fields[d.Type][op.Field]; typ == "asset" && !contains(candidate.Clears, op.Field) {
					var ref metastandard.AssetRef
					_ = json.Unmarshal([]byte(encode(candidate.Fields[op.Field])), &ref)
					var asset Asset
					if e := sqlx.GetContext(ctx, db, &asset, s.q["asset-read"], ref.AssetID); e != nil {
						continue
					}
					if info, e := os.Stat(s.AssetPath(ref.AssetID)); e != nil || info.Size() != asset.Size {
						continue
					}
				}
				if best == nil || priority(candidate.Origin.Kind) > priority(best.Origin.Kind) {
					best = &candidate
				}
			}
			state.Decision = "AUTO"
			if best != nil {
				d.Fields[op.Field] = best.Fields[op.Field]
				state.Origin = best.Origin
				if contains(best.Clears, op.Field) {
					state.Status = "CLEARED"
				}
			} else {
				d.Fields[op.Field] = nil
				state.Origin = metastandard.Origin{Kind: "UNKNOWN"}
				state.Status = "UNREAD"
			}
		default:
			return d, &Problem{400, "操作必须为 SET、CLEAR 或 RESET"}
		}
		d.States[op.Field] = state
	}
	if e = s.save(ctx, db, &d, d.Revision); e != nil {
		return d, e
	}
	_, e = db.ExecContext(ctx, s.q["request-insert"], in.RequestID, id, digest, encode(d), timestamp())
	return d, e
}
func priority(kind string) int {
	switch kind {
	case "PLATFORM", "DOWNLOAD":
		return 4
	case "SHARED":
		return 3
	case "FILE":
		return 2
	case "FILENAME":
		return 1
	}
	return 0
}

func (s *Store) Decide(ctx context.Context, db store.DBTX, id string, fields map[string]any, origin metastandard.Origin) error {
	d, e := s.Read(ctx, db, id)
	if e != nil {
		return e
	}
	ops := []Operation{}
	for name, value := range fields {
		op := "SET"
		if value == nil || encode(value) == "null" {
			op = "CLEAR"
			value = nil
		}
		o := origin
		ops = append(ops, Operation{Field: name, Op: op, Value: value, Origin: &o})
	}
	if len(ops) == 0 {
		return nil
	}
	_, e = s.Update(ctx, db, id, Update{ExpectedRevision: d.Revision, RequestID: NewID(), Operations: ops})
	return e
}

func (s *Store) Page(ctx context.Context, db store.DBTX, kind, search string, offset, size int) ([]metastandard.Document, int64, error) {
	return s.PageSource(ctx, db, kind, search, "", offset, size)
}
func (s *Store) PageSource(ctx context.Context, db store.DBTX, kind, search, source string, offset, size int) ([]metastandard.Document, int64, error) {
	if Fields := metastandard.Fields[kind]; Fields == nil || size < 1 || size > 200 || offset < 0 {
		return nil, 0, &Problem{400, "分页参数无效"}
	}
	search = "%" + strings.NewReplacer("!", "!!", "%", "!%", "_", "!_").Replace(strings.ToLower(search)) + "%"
	countQuery, pageQuery := s.q["document-count"], s.q["document-page"]
	args := []any{kind, search}
	if source != "" {
		if kind != "SONG" {
			return nil, 0, &Problem{400, "来源筛选适用于歌曲"}
		}
		countQuery, pageQuery = s.q["document-count-source"], s.q["document-page-source"]
		args = append(args, source)
	}
	var total int64
	if e := db.QueryRowContext(ctx, countQuery, args...).Scan(&total); e != nil {
		return nil, 0, e
	}
	rows, e := db.QueryxContext(ctx, pageQuery, append(args, size, offset)...)
	if e != nil {
		return nil, 0, e
	}
	defer rows.Close()
	items := []metastandard.Document{}
	for rows.Next() {
		var raw string
		if e = rows.Scan(&raw); e != nil {
			return nil, 0, e
		}
		d, e := metastandard.Decode([]byte(raw))
		if e != nil {
			return nil, 0, e
		}
		items = append(items, d)
	}
	return items, total, rows.Err()
}
func (s *Store) Children(ctx context.Context, db store.DBTX, id string) ([]metastandard.Document, error) {
	items, _, e := s.ChildrenPage(ctx, db, id, 0, 200)
	return items, e
}
func (s *Store) ChildrenPage(ctx context.Context, db store.DBTX, id string, offset, size int) ([]metastandard.Document, int64, error) {
	if size < 1 || size > 200 || offset < 0 {
		return nil, 0, &Problem{400, "分页参数无效"}
	}
	var total int64
	if e := db.QueryRowContext(ctx, s.q["document-child-count"], id).Scan(&total); e != nil {
		return nil, 0, e
	}
	rows, e := db.QueryxContext(ctx, s.q["document-children"], id, size, offset)
	if e != nil {
		return nil, 0, e
	}
	defer rows.Close()
	out := []metastandard.Document{}
	for rows.Next() {
		var raw string
		if e = rows.Scan(&raw); e != nil {
			return nil, 0, e
		}
		d, e := metastandard.Decode([]byte(raw))
		if e != nil {
			return nil, 0, e
		}
		out = append(out, d)
	}
	return out, total, rows.Err()
}

func (s *Store) Link(ctx context.Context, db store.DBTX, id, parent, expected string) (metastandard.Document, error) {
	return s.link(ctx, db, id, parent, expected, metastandard.Origin{Kind: "MANUAL"}, "SET")
}

// LinkEvidence preserves the distinction between the sender's relationship and
// a local association decision. Later shared updates may follow only the former.
func (s *Store) LinkEvidence(ctx context.Context, db store.DBTX, id, parent, expected string, origin metastandard.Origin) (metastandard.Document, error) {
	return s.link(ctx, db, id, parent, expected, origin, "AUTO")
}
func (s *Store) link(ctx context.Context, db store.DBTX, id, parent, expected string, origin metastandard.Origin, decision string) (metastandard.Document, error) {
	d, e := s.Read(ctx, db, id)
	if e != nil {
		return d, e
	}
	if d.Revision != expected {
		return d, &Problem{409, "资料已变化"}
	}
	p, e := s.Read(ctx, db, parent)
	if e != nil {
		return d, e
	}
	if !(d.Type == "VARIANT" && p.Type == "SONG" || d.Type == "MEDIA" && p.Type == "VARIANT") {
		return d, &Problem{400, "关联层级无效"}
	}
	d.ParentID = parent
	d.ParentState = &metastandard.FieldState{Decision: decision, Status: "PRESENT", Revision: next(d.Revision), Origin: origin}
	e = s.save(ctx, db, &d, d.Revision)
	return d, e
}

func (s *Store) Extensions(ctx context.Context, db store.DBTX, id string, extensions map[string]json.RawMessage) error {
	d, e := s.Read(ctx, db, id)
	if e != nil {
		return e
	}
	if d.Extensions == nil {
		d.Extensions = map[string]json.RawMessage{}
	}
	before := encode(d.Extensions)
	for k, v := range extensions {
		d.Extensions[k] = v
	}
	if before == encode(d.Extensions) {
		return nil
	}
	return s.save(ctx, db, &d, d.Revision)
}

func (s *Store) CheckPackage(ctx context.Context, db store.DBTX, collection, revision, digest string) error {
	var prior string
	e := db.QueryRowContext(ctx, s.q["package-read"], collection, revision).Scan(&prior)
	if e == nil && prior != digest {
		return &Problem{409, "同一共享系列与修订号出现不同内容"}
	}
	if errors.Is(e, sql.ErrNoRows) {
		_, e = db.ExecContext(ctx, s.q["package-insert"], collection, revision, digest)
	}
	return e
}

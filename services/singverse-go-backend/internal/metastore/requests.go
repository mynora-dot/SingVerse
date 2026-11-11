package metastore

import (
	"context"
	"database/sql"
	"errors"

	"github.com/jmoiron/sqlx"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/metastandard"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

func (s *Store) receipt(ctx context.Context, db store.DBTX, id, requestID string, payload any) (metastandard.Document, string, bool, error) {
	if !metastandard.UUID.MatchString(requestID) {
		return metastandard.Document{}, "", false, &Problem{400, "提交标识无效"}
	}
	digest, e := metastandard.DocumentDigest([]byte(encode(payload)))
	if e != nil {
		return metastandard.Document{}, "", false, e
	}
	var row struct {
		ID   string `db:"entity_id"`
		Hash string `db:"request_hash"`
		Data string `db:"response_json"`
	}
	e = sqlx.GetContext(ctx, db, &row, s.q["request-read"], requestID)
	if errors.Is(e, sql.ErrNoRows) {
		return metastandard.Document{}, digest, false, nil
	}
	if e != nil {
		return metastandard.Document{}, digest, false, e
	}
	if row.ID != id || row.Hash != digest {
		return metastandard.Document{}, digest, false, &Problem{409, "提交标识已用于其他操作"}
	}
	d, e := metastandard.Decode([]byte(row.Data))
	return d, digest, true, e
}

// Mutate advances the object revision and records an idempotent receipt in the
// caller's transaction, including location, visibility and association changes.
func (s *Store) Mutate(ctx context.Context, db store.DBTX, id, expected, requestID string, payload any, fn func(*metastandard.Document) error) (metastandard.Document, error) {
	if _, e := db.ExecContext(ctx, s.q["document-lock"], id); e != nil {
		return metastandard.Document{}, e
	}
	d, digest, found, e := s.receipt(ctx, db, id, requestID, payload)
	if e != nil || found {
		return d, e
	}
	d, e = s.Read(ctx, db, id)
	if e != nil {
		return d, e
	}
	if d.Revision != expected {
		return d, &Problem{409, "资料已变化，请重新加载后确认"}
	}
	if e = fn(&d); e != nil {
		return d, e
	}
	if e = s.save(ctx, db, &d, expected); e != nil {
		return d, e
	}
	_, e = db.ExecContext(ctx, s.q["request-insert"], requestID, id, digest, encode(d), timestamp())
	return d, e
}

func (s *Store) CreateRequested(ctx context.Context, db store.DBTX, kind, id, parent string, fields map[string]any) (metastandard.Document, error) {
	payload := map[string]any{"action": "CREATE", "type": kind, "parentId": parent, "fields": fields}
	d, digest, found, e := s.receipt(ctx, db, id, id, payload)
	if e != nil || found {
		return d, e
	}
	if _, e = s.Read(ctx, db, id); e == nil {
		return d, &Problem{409, "提交标识已被使用"}
	} else if !errors.Is(e, sql.ErrNoRows) {
		return d, e
	}
	d, e = s.Create(ctx, db, kind, id, parent)
	if e != nil {
		return d, e
	}
	if e = s.Decide(ctx, db, id, fields, metastandard.Origin{Kind: "MANUAL"}); e != nil {
		return d, e
	}
	d, e = s.Read(ctx, db, id)
	if e != nil {
		return d, e
	}
	_, e = db.ExecContext(ctx, s.q["request-insert"], id, id, digest, encode(d), timestamp())
	return d, e
}

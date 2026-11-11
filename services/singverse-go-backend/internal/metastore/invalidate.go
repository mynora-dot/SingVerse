package metastore

import (
	"context"
	"encoding/json"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/metastandard"

	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

// InvalidateFile only invalidates file-dependent technical evidence. Song names,
// local decisions, version lyrics and durable attachments are retained.
func (s *Store) InvalidateFile(ctx context.Context, db store.DBTX, id string) error {
	d, e := s.Read(ctx, db, id)
	if e != nil {
		return e
	}
	changed := false
	for _, name := range []string{"observation", "durationMs", "sha256", "audioRoles"} {
		state, exists := d.States[name]
		if !exists || local(state) || state.Origin.Kind != "FILE" || state.Status == "UNREAD" {
			continue
		}
		d.Fields[name] = nil
		state.Status = "UNREAD"
		state.Revision = next(d.Revision)
		d.States[name] = state
		changed = true
	}
	if changed {
		if e = s.save(ctx, db, &d, d.Revision); e != nil {
			return e
		}
	}
	rows, e := store.Maps(ctx, db, s.q["evidence-all"], id)
	if e != nil {
		return e
	}
	for _, row := range rows {
		var evidence Evidence
		if e = json.Unmarshal([]byte(row["evidence_json"].(string)), &evidence); e != nil {
			return e
		}
		if evidence.Origin.Kind != "FILE" || evidence.Unavailable {
			continue
		}
		evidence.Unavailable = true
		if _, e = db.ExecContext(ctx, s.q["evidence-invalidate"], encode(evidence), id, evidence.Key); e != nil {
			return e
		}
	}
	return nil
}

// MarkMissingAsset is used only during legacy adoption. It does not install a
// fabricated digest and never replaces a usable attachment or a local clear.
func (s *Store) MarkMissingAsset(ctx context.Context, db store.DBTX, id, field string) error {
	d, e := s.Read(ctx, db, id)
	if e != nil {
		return e
	}
	if state, exists := d.States[field]; exists && (local(state) || state.Status == "PRESENT" || state.Status == "CLEARED") {
		return nil
	}
	d.Fields[field] = nil
	d.States[field] = metastandard.FieldState{Decision: "AUTO", Status: "MISSING", Origin: metastandard.Origin{Kind: "UNKNOWN"}, Revision: next(d.Revision)}
	return s.save(ctx, db, &d, d.Revision)
}

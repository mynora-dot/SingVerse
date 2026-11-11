package metastore

import (
	"context"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/metastandard"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

func HasLocalDecision(d metastandard.Document) bool {
	if d.ParentState != nil && local(*d.ParentState) {
		return true
	}
	for _, state := range d.States {
		if local(state) {
			return true
		}
	}
	return false
}

// RetireEmptyPlaceholder hides only an automatic scan wrapper left without
// children by an explicit shared relationship. The document remains available
// by UUID; manual decisions, creation receipts and resources are never hidden.
func (s *Store) RetireEmptyPlaceholder(ctx context.Context, db store.DBTX, id string) error {
	d, e := s.Read(ctx, db, id)
	if e != nil {
		return e
	}
	if HasLocalDecision(d) || len(d.Extensions) > 0 || len(metastandard.AssetIDs(d)) > 0 {
		return nil
	}
	if d.Type != "SONG" && d.Type != "VARIANT" {
		return nil
	}
	_, e = db.ExecContext(ctx, s.q["document-retire-empty"], id, id, id)
	return e
}
func (s *Store) AssociationDifference(ctx context.Context, db store.DBTX, id, source, parent string) error {
	if _, e := db.ExecContext(ctx, s.q["difference-delete"], id, "parentId", source); e != nil {
		return e
	}
	_, e := db.ExecContext(ctx, s.q["difference-insert"], id, "parentId", source, encode(parent))
	return e
}

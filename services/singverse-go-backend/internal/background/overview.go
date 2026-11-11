package background

import (
	"context"

	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

// listItems projects task rows through the shared view used by /background-tasks.
func (s *Service) listItems(ctx context.Context, tx store.DBTX, filter doc) ([]doc, error) {
	rows, e := s.rows(ctx, tx, "list", filter)
	if e != nil {
		return nil, e
	}
	items := []doc{}
	for _, r := range rows {
		v, e := s.view(ctx, tx, r, false)
		if e != nil {
			return nil, e
		}
		items = append(items, v)
	}
	return items, nil
}

// ActiveSnapshot summarizes active background tasks for the admin home page:
// per-status counts, the total active count, and the newest active items using
// the exact /background-tasks projection (relatedPath, unknown progress, etc.).
func (s *Service) ActiveSnapshot(ctx context.Context, limit int) (doc, error) {
	if limit < 0 {
		limit = 0
	}
	out := doc{"activeTotal": int64(0), "counts": doc{}, "items": []doc{}, "observedAt": observed()}
	e := s.transact(ctx, func(tx store.DBTX) error {
		filter := doc{"page": 0, "size": limit, "offset": 0, "view": "ACTIVE", "type": nil, "status": nil, "pattern": nil, "now": now()}
		items, e := s.listItems(ctx, tx, filter)
		if e != nil {
			return e
		}
		groups, e := s.rows(ctx, tx, "activeCounts", filter)
		if e != nil {
			return e
		}
		counts := doc{}
		var total int64
		for _, g := range groups {
			n := number(g, "amount")
			counts[text(g, "status")] = n
			total += n
		}
		out["items"] = items
		out["counts"] = counts
		out["activeTotal"] = total
		out["observedAt"] = observed()
		return nil
	})
	return out, e
}

package metadata

import (
	"context"

	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

// A durable keyset cursor makes the legacy shared-evidence migration resumable
// without running a whole-library JSON projection on every startup.
func (s *Service) bootstrapShared(ctx context.Context) error {
	for {
		cursor, e := s.row(ctx, s.db, "v4-migration-read", nil)
		if e != nil {
			return e
		}
		rows, e := s.rows(ctx, s.db, "v4-shared-backfill", map[string]any{"after": cursor["value"]})
		if e != nil {
			return e
		}
		if len(rows) == 0 {
			_, e = s.exec(ctx, s.db, "v4-migration-write", map[string]any{"after": "~"})
			return e
		}
		if e = s.transaction(ctx, func(tx store.DBTX) error {
			for _, row := range rows {
				snapshot := map[string]any{}
				if e := decode(row["snapshot_json"], &snapshot); e != nil {
					return e
				}
				cover := text(snapshot["coverRef"])
				if cover == "" {
					cover = text(snapshot["cover"])
				}
				missingCover := false
				if cover != "" {
					if _, err := s.SharedCover(cover); err != nil {
						missingCover = true
						delete(snapshot, "coverRef")
						delete(snapshot, "cover")
						delete(snapshot, "coverData")
					}
				}
				if e := s.applySharedFormal(ctx, tx, text(row["song_id"]), snapshot, text(row["collection_id"]), number(row["revision"])); e != nil {
					return e
				}
				if missingCover {
					song, _, err := s.library.FormalIDs(ctx, tx, text(row["song_id"]))
					if err != nil {
						return err
					}
					if err = s.library.Formal().MarkMissingAsset(ctx, tx, song, "cover"); err != nil {
						return err
					}
				}
			}
			_, e := s.exec(ctx, tx, "v4-migration-write", map[string]any{"after": rows[len(rows)-1]["song_id"]})
			return e
		}); e != nil {
			return e
		}
	}
}

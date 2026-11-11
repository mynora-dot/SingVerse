package metadata

import (
	"context"

	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

func (s *Service) preferredMedia(ctx context.Context, tx store.DBTX, source, collection string, snapshot map[string]any) (string, error) {
	id := text(snapshot["portableMediaId"])
	if id == "" || collection == "" {
		return id, nil
	}
	rows, e := s.rows(ctx, tx, "v4-alias-read", map[string]any{"sourceId": source, "collectionId": collection, "remoteId": id})
	if e != nil {
		return "", e
	}
	if len(rows) > 0 {
		return text(rows[0]["local_id"]), nil
	}
	return id, nil
}

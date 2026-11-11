package library

import (
	"context"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

// SourceQuality aggregates indexed columns; polling never deserializes library JSON.
func (s *Service) SourceQuality(ctx context.Context, db store.DBTX, id string) (map[string]int64, error) {
	var total, songs, variants, complete, pending, issues int64
	e := db.QueryRowContext(ctx, s.q["formal-source-stats"], id).Scan(&total, &songs, &variants, &complete, &pending, &issues)
	return map[string]int64{"total": total, "songs": songs, "variants": variants, "files": total, "identityComplete": complete, "informationMissing": songs - complete, "pendingVerification": pending, "mediaIssues": issues}, e
}

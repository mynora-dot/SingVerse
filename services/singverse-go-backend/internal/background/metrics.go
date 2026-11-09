package background

import (
	"context"

	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

type Metric struct {
	Type, Status string
	Count        int64
}

func (s *Service) Metrics(ctx context.Context) ([]Metric, error) {
	rows, e := store.Maps(ctx, s.db, s.q["metrics-"+s.dialect])
	if e != nil {
		return nil, e
	}
	out := make([]Metric, 0, len(rows))
	for _, row := range rows {
		out = append(out, Metric{Type: text(row, "type"), Status: text(row, "status"), Count: number(row, "amount")})
	}
	return out, nil
}

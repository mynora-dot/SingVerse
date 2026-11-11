package feedback

import "context"

// PendingCount returns the number of missing-song submissions awaiting review.
// It reuses the admin list query with the PENDING filter and a single-row page,
// reading only TotalElements so no feedback rows are projected.
func (s *Service) PendingCount(ctx context.Context) (int64, error) {
	page, err := s.List(ctx, "", "PENDING", 0, 1)
	return page.TotalElements, err
}

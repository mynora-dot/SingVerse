package execution

import "context"

// CacheUsage reports the total cache usage and entry count for the admin home
// page. It reuses the exact sources behind the /song-cache list endpoint: the
// shared usage helper (media bytes, owner markers and orphan bytes) and the
// cache-totals entry count. It performs only index aggregation, never a row scan.
func (s *Service) CacheUsage(ctx context.Context) (map[string]any, error) {
	usage, err := s.usage(ctx)
	if err != nil {
		return nil, err
	}
	totals, err := s.one(ctx, s.db, "cache-totals", nil)
	if err != nil {
		return nil, err
	}
	return map[string]any{"usageBytes": usage, "entryCount": integer(totals["entry_count"])}, nil
}

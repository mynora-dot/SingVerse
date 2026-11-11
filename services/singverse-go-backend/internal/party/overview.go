package party

import (
	"context"

	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

// DeviceOverview counts non-revoked TV devices and non-expired pending
// authorization requests, using the same filters as the admin device list.
func (s *Service) DeviceOverview(ctx context.Context) (map[string]any, error) {
	out := map[string]any{"activeDevices": int64(0), "pending": int64(0)}
	_, e := s.transact(ctx, func(tx store.DBTX) (any, error) {
		devices, e := s.list(ctx, tx, "device")
		if e != nil {
			return nil, e
		}
		authorizations, e := s.list(ctx, tx, "authorization")
		if e != nil {
			return nil, e
		}
		var active, pending int64
		for _, d := range devices {
			if !boolean(d, "revoked") {
				active++
			}
		}
		for _, v := range authorizations {
			if !s.expired(v) && str(v, "status") == "PENDING" {
				pending++
			}
		}
		out["activeDevices"] = active
		out["pending"] = pending
		return nil, nil
	})
	return out, e
}

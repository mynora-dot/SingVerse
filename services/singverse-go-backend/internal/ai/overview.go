package ai

import "context"

// Readiness reports AI feature configuration state for the admin home page. It
// reuses the persisted features view and performs no network calls. AI is
// configured when at least one feature is enabled; enabledFeatures lists their
// stable identifiers in storage order.
func (s *Service) Readiness(ctx context.Context) (map[string]any, error) {
	features, err := s.Features(ctx)
	if err != nil {
		return nil, err
	}
	enabled := []string{}
	for _, feature := range features {
		if feature.Enabled {
			enabled = append(enabled, feature.ID)
		}
	}
	return map[string]any{"configured": len(enabled) > 0, "enabledFeatures": enabled}, nil
}

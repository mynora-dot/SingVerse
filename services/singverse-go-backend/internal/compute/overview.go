package compute

import "context"

// Readiness reports the configured compute connection state for the admin home
// page. It reads only the persisted settings and deployment configuration; it
// never performs a network probe. A node is configured when its mode resolves to
// a non-empty endpoint (AUTO uses the deployment URL, MANUAL the saved address).
func (s *Service) Readiness(ctx context.Context) (map[string]any, error) {
	settings, err := s.View(ctx)
	if err != nil {
		return nil, err
	}
	configured := false
	switch settings.Mode {
	case "AUTO":
		configured = settings.AutomaticEndpoint != ""
	case "MANUAL":
		configured = settings.Endpoint != ""
	}
	return map[string]any{"configured": configured, "mode": settings.Mode}, nil
}

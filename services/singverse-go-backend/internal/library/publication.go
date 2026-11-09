package library

import "context"

// Publication serializes indexing and local download publication for one source.
// It does not hold a database transaction or the library configuration mutex.
func (s *Service) Publication(ctx context.Context, sourceID string, fn func() error) error {
	release, e := s.acquirePublication(ctx, sourceID)
	if e != nil {
		return e
	}
	defer release()
	return fn()
}
func (s *Service) acquirePublication(ctx context.Context, sourceID string) (func(), error) {
	s.publicationMu.Lock()
	if s.publications == nil {
		s.publications = map[string]chan struct{}{}
	}
	gate := s.publications[sourceID]
	if gate == nil {
		gate = make(chan struct{}, 1)
		s.publications[sourceID] = gate
	}
	s.publicationMu.Unlock()
	select {
	case gate <- struct{}{}:
		return func() { <-gate }, nil
	case <-ctx.Done():
		return nil, ctx.Err()
	case <-s.ctx.Done():
		return nil, conflict("服务正在停止")
	}
}

package execution

import (
	"context"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

// AudioScopes connects durable scan origins to execution in the same SQL transaction.
type AudioScopes interface {
	AudioOrigins(context.Context, store.DBTX, string, string) ([]string, error)
	AutomaticOrigin(context.Context, store.DBTX, string, string, string) ([]string, error)
	AudioSubmitted(context.Context, store.DBTX, string, string, string, string, []string, bool) error
	ResolveAudioScopes(context.Context, store.DBTX, string, string, string, string) error
	PendingScopes(context.Context, string, string) ([]map[string]any, error)
	SkipPendingAudio(context.Context, string) error
	WaitingForAudioNode(context.Context) error
	FinishDiscoveryRound(context.Context, string) error
}

func (s *Service) SetAudioScopes(scopes AudioScopes) {
	s.projectMu.Lock()
	s.scopes = scopes
	s.projectMu.Unlock()
}
func (s *Service) audioScopes() AudioScopes {
	s.projectMu.RLock()
	defer s.projectMu.RUnlock()
	return s.scopes
}
func (s *Service) recordAudio(ctx context.Context, tx store.DBTX, row map[string]any, origins []string, automatic, created bool) error {
	scopes := s.audioScopes()
	if scopes == nil {
		return nil
	}
	song, fp := str(row, "song_id"), str(row, "fingerprint")
	var err error
	if automatic && len(origins) == 0 {
		origins, err = scopes.AudioOrigins(ctx, tx, song, fp)
		if err != nil {
			return err
		}
		if len(origins) == 0 {
			origins, err = scopes.AutomaticOrigin(ctx, tx, s.discoveryRound, song, fp)
			if err != nil {
				return err
			}
		}
	}
	return scopes.AudioSubmitted(ctx, tx, str(row, "id"), str(row, "kind"), song, fp, origins, created)
}

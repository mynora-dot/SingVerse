package party

import (
	"context"

	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

func (s *Service) applySessionAction(ctx context.Context, tx store.DBTX, session, input document, action string) error {
	switch action {
	case "LOCK", "UNLOCK":
		session["acceptingRequests"] = action == "UNLOCK"
	case "CLEAR", "END":
		for _, raw := range array(session, "queue") {
			item := raw.(map[string]any)
			if str(item, "status") == "WAITING" {
				item["status"] = "CANCELLED"
			}
		}
		if action == "END" {
			if err := s.finishActivity(ctx, tx, session, "STOPPED"); err != nil {
				return err
			}
			session["status"] = "ENDED"
			session["acceptingRequests"] = false
			session["commands"] = []any{}
			return enqueuePlaybackCommand(session, "STOP")
		}
	case "HOST", "REVOKE":
		return s.updateSessionParticipant(ctx, tx, str(session, "id"), input, action)
	}
	return nil
}

func (s *Service) updateSessionParticipant(ctx context.Context, tx store.DBTX, sessionID string, input document, action string) error {
	participantID, err := text(input, "participantId", 64)
	if err != nil {
		return err
	}
	if err = s.lock(ctx, tx, participantID); err != nil {
		return err
	}
	participant, err := s.get(ctx, tx, participantID)
	if err != nil {
		return err
	}
	if str(participant, "sessionId") != sessionID {
		return fail(403, "参与者不属于本场")
	}
	if action == "HOST" {
		participant["host"] = true
	} else {
		participant["revoked"] = true
	}
	return s.save(ctx, tx, participant)
}

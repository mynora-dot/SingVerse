package execution

import (
	"context"
	"math"

	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/metastandard"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/metastore"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

func formalAudioEvidence(fields map[string]any) metastore.Evidence {
	return metastore.Evidence{Origin: metastandard.Origin{Kind: "FILE"}, Fields: fields}
}
func (s *Service) saveFormalProbe(ctx context.Context, tx store.DBTX, media string, probe map[string]any) error {
	if len(array(probe["streams"])) == 0 || number(probe["duration"]) <= 0 || number(probe["duration"]) > 604800 {
		return nil
	}
	current, err := s.library.Formal().Read(ctx, tx, media)
	if err != nil {
		return err
	}
	// A roles-only probe cannot replace a complete video/audio observation.
	if prior := array(obj(current.Fields["observation"])["streams"]); len(prior) > len(audioOnly(prior)) && len(array(probe["streams"])) == len(audioOnly(probe["streams"])) {
		return nil
	}
	_, _, err = s.library.Formal().Observe(ctx, tx, media, formalAudioEvidence(map[string]any{"observation": metastandard.NormalizeObservation(map[string]any{"streams": probe["streams"], "duration": probe["duration"]}), "durationMs": int64(math.Round(number(probe["duration"]) * 1000))}))
	return err
}
func audioOnly(streams any) []any {
	out := []any{}
	for _, raw := range array(streams) {
		if str(obj(raw), "kind") == "audio" {
			out = append(out, raw)
		}
	}
	return out
}

// Shared claims are retained as formal metadata, but playback only adopts them
// after this receiver has observed matching streams and the media timeline.
func (s *Service) formalAudioClaim(ctx context.Context, tx store.DBTX, id string) (map[string]any, error) {
	d, err := s.library.Formal().Read(ctx, tx, id)
	if err != nil {
		return nil, err
	}
	original, backing := metastandard.AudioRoleIDs(d.Fields["audioRoles"])
	observed := obj(d.Fields["observation"])
	return map[string]any{"state": "CONFIRMED", "originalId": original, "backingId": backing, "streams": audioOnly(observed["streams"]), "duration": number(d.Fields["durationMs"]) / 1000}, nil
}
func (s *Service) projectFormalRoles(ctx context.Context, db store.DBTX, id string, value map[string]any) error {
	d, err := s.library.Formal().Read(ctx, db, id)
	if err != nil {
		return err
	}
	state, exists := d.States["audioRoles"]
	if !exists {
		return nil
	}
	value["state"] = "UNKNOWN"
	delete(value, "originalId")
	delete(value, "backingId")
	if state.Status == "CLEARED" {
		return nil
	}
	original, backing := metastandard.AudioRoleIDs(d.Fields["audioRoles"])
	ids := map[string]bool{}
	for _, stream := range array(value["streams"]) {
		ids[str(obj(stream), "id")] = true
	}
	if !ids[original] || backing != "" && !ids[backing] {
		return nil
	}
	if state.Decision != "SET" {
		claim, err := s.formalAudioClaim(ctx, db, id)
		if err != nil {
			return err
		}
		if encoded(claim["streams"]) != encoded(value["streams"]) || math.Abs(number(claim["duration"])-number(value["duration"])) > .25 {
			return nil
		}
	}
	value["state"], value["originalId"], value["backingId"] = "CONFIRMED", original, backing
	value["origin"] = state.Origin.Kind
	return nil
}
func (s *Service) bootstrapFormalAudio(ctx context.Context) error {
	cursor := ""
	for {
		rows, err := s.maps(ctx, s.db, "formal-audio-pending", map[string]any{"cursor": cursor})
		if err != nil {
			return err
		}
		if len(rows) == 0 {
			return nil
		}
		if err = store.WithinTx(ctx, s.db, func(tx store.DBTX) error {
			for _, row := range rows {
				id := str(row, "id")
				media, err := s.song(ctx, tx, id)
				if err != nil {
					return err
				}
				fp := contentFingerprint(media)
				history, err := s.maps(ctx, tx, "audio-for-song", map[string]any{"song": id, "fingerprint": fp})
				if err != nil {
					return err
				}
				for _, job := range history {
					if str(job, "state") == "SUCCEEDED" && len(array(decoded(job["result_json"])["streams"])) > 0 {
						if err = s.saveFormalProbe(ctx, tx, id, decoded(job["result_json"])); err != nil {
							return err
						}
						break
					}
				}
				role, err := s.optional(ctx, tx, "audio-role", map[string]any{"song": id})
				if err != nil {
					return err
				}
				if role != nil && str(role, "fingerprint") == fp {
					current, err := s.library.Formal().Read(ctx, tx, id)
					if err != nil {
						return err
					}
					if _, exists := current.States["audioRoles"]; exists {
						continue
					}
					fields := map[string]any{"audioRoles": metastandard.AudioRoles(str(role, "original_id"), str(role, "backing_id"))}
					if str(role, "origin") == "MANUAL" {
						err = s.library.Formal().Decide(ctx, tx, id, fields, metastandard.Origin{Kind: "MANUAL"})
					} else {
						_, _, err = s.library.Formal().Observe(ctx, tx, id, formalAudioEvidence(fields))
					}
					if err != nil {
						return err
					}
				}
			}
			return nil
		}); err != nil {
			return err
		}
		cursor = str(rows[len(rows)-1], "id")
	}
}

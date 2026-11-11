package execution

import (
	"context"
	"math"
	"path/filepath"
	"regexp"
	"strings"

	"github.com/jmoiron/sqlx"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/metastandard"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

var audioPattern = regexp.MustCompile(`^stream:[0-9]{1,3}$`)
var sourceAudioPattern = regexp.MustCompile(`^stream:[0-9]{1,3}(?::(?:LEFT|RIGHT))?$`)
var idPattern = regexp.MustCompile(`^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$`)

func roleView(song, row map[string]any) map[string]any {
	current := contentFingerprint(song)
	revision := int64(0)
	if row != nil {
		revision = integer(row["revision"])
	}
	v := map[string]any{"songId": str(song, "id"), "fingerprint": current, "state": "UNKNOWN", "revision": revision}
	if row != nil && str(row, "fingerprint") == current {
		v["state"] = "CONFIRMED"
		v["origin"] = str(row, "origin")
		v["originalId"] = str(row, "original_id")
		v["backingId"] = str(row, "backing_id")
	}
	return v
}
func (s *Service) audioStatus(ctx context.Context, songID string) (map[string]any, error) {
	return s.audioStatusWith(ctx, s.db, songID)
}
func (s *Service) audioStatusWith(ctx context.Context, db store.DBTX, songID string) (map[string]any, error) {
	song, err := s.song(ctx, db, songID)
	if err != nil {
		return nil, err
	}
	fp := contentFingerprint(song)
	row, err := s.optional(ctx, db, "audio-role", map[string]any{"song": songID})
	if err != nil {
		return nil, err
	}
	history, err := s.maps(ctx, db, "audio-for-song", map[string]any{"song": songID, "fingerprint": fp})
	if err != nil {
		return nil, err
	}
	selection, err := s.optional(ctx, db, "audio-selection", map[string]any{"song": songID})
	if err != nil {
		return nil, err
	}
	selected := ""
	if str(selection, "fingerprint") == fp {
		selected = str(selection, "job_id")
	}
	if selected != "" {
		found := false
		for _, job := range history {
			if str(job, "id") == selected {
				found = true
			}
		}
		if !found {
			job, e := s.optional(ctx, db, "audio-job", map[string]any{"id": selected})
			if e != nil {
				return nil, e
			}
			if job != nil && str(job, "song_id") == songID && str(job, "fingerprint") == fp {
				history = append(history, job)
			}
		}
	}
	v := roleView(song, row)
	jobs := []any{}
	versions := []any{}
	var active map[string]any
	for _, job := range history {
		view := map[string]any{"id": str(job, "id"), "kind": str(job, "kind"), "state": str(job, "state"), "error": str(job, "error")}
		spec := storedRecipe(job)
		if str(job, "kind") == "SEPARATE" {
			for key, value := range spec.view() {
				view[key] = value
			}
		}
		data := decoded(job["result_json"])
		for _, key := range []string{"backend", "modelVersion", "fallbackReason"} {
			view[key] = str(data, key)
		}
		for _, key := range []string{"stage", "timingsMs", "progress", "inference", "sourceReused", "sourceOffsetSeconds", "precision"} {
			if value, ok := data[key]; ok {
				view[key] = value
			}
		}
		if oneOf(str(job, "kind"), "FAST", "ANALYZE") {
			view["resultState"] = str(data, "state")
			view["reason"] = str(data, "reason")
			if data["evidence"] != nil {
				view["evidence"] = data["evidence"]
			}
		}
		if v["streams"] == nil {
			if streams, ok := data["streams"].([]any); ok {
				v["streams"] = streams
				if data["duration"] != nil {
					v["duration"] = data["duration"]
				}
			}
		}
		jobs = append(jobs, view)
		if str(job, "kind") == "SEPARATE" && str(job, "state") == "SUCCEEDED" && regular(s.audioPath(job)) {
			resource := spec.view()
			resource["jobId"] = str(job, "id")
			resource["mediaType"] = "audio/mp4"
			if spec.format == "flac" {
				resource["mediaType"] = "audio/flac"
			}
			if data["duration"] != nil {
				resource["duration"] = data["duration"]
			}
			versions = append(versions, resource)
			if spec.playable() && (active == nil || str(job, "id") == selected) {
				active = resource
			}
		}
	}
	if streams := array(v["streams"]); len(streams) == 1 && str(v, "state") != "CONFIRMED" {
		v["originalId"] = str(obj(streams[0]), "id")
		v["state"] = "CONFIRMED"
		v["origin"] = "SOURCE"
	}
	v["jobs"], v["versions"] = jobs, versions
	if active != nil {
		active["active"] = true
		v["backingResource"] = active
	}
	if err = s.projectFormalRoles(ctx, db, songID, v); err != nil {
		return nil, err
	}
	return v, nil
}
func (s *Service) AudioStatus(ctx context.Context, song string) (map[string]any, error) {
	return s.audioStatus(ctx, song)
}
func (s *Service) correctRoles(ctx context.Context, songID, original, backing string, expected int64) (map[string]any, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if expected < 0 || !audioPattern.MatchString(original) || !audioPattern.MatchString(backing) || original == backing {
		return nil, fail(400, "请选择两条不同的源音轨")
	}
	err := s.transaction(ctx, "", "", func(tx *sqlx.Tx) error {
		song, err := s.song(ctx, tx, songID)
		if err != nil {
			return err
		}
		fp := contentFingerprint(song)
		history, err := s.maps(ctx, tx, "audio-for-song", map[string]any{"song": songID, "fingerprint": fp})
		if err != nil {
			return err
		}
		valid := false
		for _, job := range history {
			ids := map[string]bool{}
			for _, stream := range array(decoded(job["result_json"])["streams"]) {
				ids[str(obj(stream), "id")] = true
			}
			if len(ids) == 2 && ids[original] && ids[backing] {
				valid = true
				break
			}
		}
		if !valid {
			return fail(409, "请先完成媒体探测后再标记音轨")
		}
		prior, err := s.optional(ctx, tx, "audio-role", map[string]any{"song": songID})
		if err != nil {
			return err
		}
		revision := integer(prior["revision"])
		if revision != expected {
			return fail(409, "音轨标记已变化，请刷新后重试")
		}
		return s.writeRole(ctx, tx, prior, songID, fp, original, backing, "MANUAL", revision)
	})
	if err != nil {
		return nil, err
	}
	return s.audioStatus(ctx, songID)
}
func (s *Service) writeRole(ctx context.Context, tx store.DBTX, prior map[string]any, song, fp, original, backing, origin string, revision int64) error {
	values := map[string]any{"song_id": song, "fingerprint": fp, "original_id": original, "backing_id": backing, "origin": origin, "revision": revision + 1, "previous_revision": revision, "updated_at": now()}
	name := "audio-update-role"
	if prior == nil {
		name = "audio-insert-role"
	}
	result, err := s.exec(ctx, tx, name, values)
	if err != nil {
		return err
	}
	count, err := result.RowsAffected()
	if err != nil {
		return err
	}
	if count != 1 {
		return fail(409, "音轨标记已变化，请刷新后重试")
	}
	roles := metastandard.AudioRoles(original, backing)
	if origin == "MANUAL" {
		err = s.library.Formal().Decide(ctx, tx, song, map[string]any{"audioRoles": roles}, metastandard.Origin{Kind: "MANUAL"})
	} else {
		_, _, err = s.library.Formal().Observe(ctx, tx, song, formalAudioEvidence(map[string]any{"audioRoles": roles}))
	}
	if err != nil {
		return err
	}
	return s.dirtyMetadata(ctx, tx, song)
}
func (s *Service) applySuggestion(ctx context.Context, tx store.DBTX, songID, fp string, result map[string]any, shared bool) error {
	if str(result, "state") != "CONFIDENT" {
		return nil
	}
	original, backing := str(result, "original"), str(result, "backing")
	if !audioPattern.MatchString(original) || !audioPattern.MatchString(backing) || original == backing {
		return nil
	}
	song, err := s.song(ctx, tx, songID)
	if err != nil {
		return err
	}
	if contentFingerprint(song) != fp {
		return nil
	}
	prior, err := s.optional(ctx, tx, "audio-role", map[string]any{"song": songID})
	if err != nil {
		return err
	}
	if str(prior, "fingerprint") == fp && (oneOf(str(prior, "origin"), "MANUAL", "SHARED") || (!shared && strings.HasPrefix(str(obj(result["evidence"]), "analysisVersion"), "fast-"))) {
		return nil
	}
	origin := "AUTO"
	if shared {
		origin = "SHARED"
	}
	return s.writeRole(ctx, tx, prior, songID, fp, original, backing, origin, integer(prior["revision"]))
}
func (s *Service) verifyImported(ctx context.Context, tx store.DBTX, songID, fp string, probe map[string]any) error {
	row, err := s.optional(ctx, tx, "audio-claim", map[string]any{"song": songID})
	if err != nil {
		return err
	}
	var claim map[string]any
	if row != nil {
		envelope := decoded(row["claim_json"])
		if str(envelope, "fingerprint") != fp {
			return nil
		}
		claim = obj(envelope["claim"])
	} else {
		claim, err = s.formalAudioClaim(ctx, tx, songID)
		if err != nil {
			return err
		}
	}
	if str(claim, "state") != "CONFIRMED" || encoded(claim["streams"]) != encoded(probe["streams"]) || math.Abs(number(claim["duration"])-number(probe["duration"])) > .25 {
		return nil
	}
	ids := map[string]bool{}
	for _, stream := range array(probe["streams"]) {
		ids[str(obj(stream), "id")] = true
	}
	if !ids[str(claim, "originalId")] || !ids[str(claim, "backingId")] {
		return nil
	}
	return s.applySuggestion(ctx, tx, songID, fp, map[string]any{"state": "CONFIDENT", "original": str(claim, "originalId"), "backing": str(claim, "backingId")}, true)
}
func (s *Service) audioPath(row map[string]any) string {
	return filepath.Join(s.cfg.AudioDerivedPath, str(row, "id")+"."+storedRecipe(row).extension())
}

package metastandard

import (
	"encoding/json"
	"errors"
	"math"
	"regexp"
	"strconv"
)

var streamID = regexp.MustCompile(`^stream:[0-9]{1,3}(?::(?:LEFT|RIGHT))?$`)

// Audio roles refer to ordered source streams. Derived backing files and local
// execution receipts belong to the receiver and never enter this value.
func validateAudioRoles(v any) error {
	items, ok := v.([]any)
	if !ok || len(items) > 128 {
		return errors.New("audio roles require an ordered array, max 128")
	}
	seen, roles := map[string]bool{}, map[string]bool{}
	for _, raw := range items {
		item, ok := raw.(map[string]any)
		if !ok || len(item) != 2 {
			return errors.New("audio role requires streamId and role")
		}
		id, _ := item["streamId"].(string)
		role, _ := item["role"].(string)
		if !streamID.MatchString(id) || seen[id] {
			return errors.New("invalid or duplicate source stream")
		}
		if role != "ORIGINAL" && role != "BACKING" && role != "OTHER" && role != "UNKNOWN" {
			return errors.New("invalid audio role")
		}
		if (role == "ORIGINAL" || role == "BACKING") && roles[role] {
			return errors.New("ambiguous primary audio role")
		}
		seen[id], roles[role] = true, true
	}
	return nil
}

func AudioRoles(original, backing string) []any {
	out := []any{}
	for _, value := range []struct{ id, role string }{{original, "ORIGINAL"}, {backing, "BACKING"}} {
		if streamID.MatchString(value.id) {
			out = append(out, map[string]any{"streamId": value.id, "role": value.role})
		}
	}
	return out
}
func AudioRoleIDs(v any) (original, backing string) {
	b, _ := json.Marshal(v)
	var rows []struct {
		StreamID string `json:"streamId"`
		Role     string `json:"role"`
	}
	_ = json.Unmarshal(b, &rows)
	for _, row := range rows {
		if row.Role == "ORIGINAL" {
			original = row.StreamID
		}
		if row.Role == "BACKING" {
			backing = row.StreamID
		}
	}
	return
}

// NormalizeObservation adapts existing compute/legacy seconds to the public
// integer-millisecond format, without mutating the retained original evidence.
func NormalizeObservation(raw map[string]any) map[string]any {
	b, _ := json.Marshal(raw)
	var out map[string]any
	_ = json.Unmarshal(b, &out)
	if out == nil {
		out = map[string]any{}
	}
	if out["durationMs"] == nil {
		n, ok := out["duration"].(float64)
		if value, stringValue := out["duration"].(string); stringValue {
			var err error
			n, err = strconv.ParseFloat(value, 64)
			ok = err == nil
		}
		if ok && n > 0 && n <= 604800 {
			out["durationMs"] = int64(math.Round(n * 1000))
		}
	}
	delete(out, "duration")
	return out
}

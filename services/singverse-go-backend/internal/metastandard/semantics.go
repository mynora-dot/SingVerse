package metastandard

import (
	"encoding/json"
	"errors"
	"math"
	"math/big"
	"strconv"
)

func validateNumbers(v any) error {
	switch x := v.(type) {
	case json.Number:
		exact, ok := new(big.Rat).SetString(string(x))
		if !ok || new(big.Rat).Abs(exact).Cmp(new(big.Rat).SetInt64(SafeInteger)) > 0 {
			return errors.New("core numeric value exceeds interoperable boundary")
		}
		n, e := x.Float64()
		if e != nil || math.IsInf(n, 0) || math.IsNaN(n) || math.Abs(n) > float64(SafeInteger) {
			return errors.New("core numeric value exceeds interoperable boundary")
		}
	case map[string]any:
		for _, item := range x {
			if e := validateNumbers(item); e != nil {
				return e
			}
		}
	case []any:
		for _, item := range x {
			if e := validateNumbers(item); e != nil {
				return e
			}
		}
	}
	return nil
}
func validFieldState(state FieldState, value any, docRevision string) error {
	if state.Decision != "AUTO" && state.Decision != "SET" && state.Decision != "CLEAR" {
		return errors.New("invalid field decision")
	}
	switch state.Status {
	case "PRESENT", "UNREAD", "MISSING", "NOT_APPLICABLE", "CLEARED":
	default:
		return errors.New("invalid field status")
	}
	switch state.Origin.Kind {
	case "FILENAME", "FILE", "PLATFORM", "SHARED", "MANUAL", "UPLOAD", "DOWNLOAD", "UNKNOWN":
	default:
		return errors.New("invalid field origin")
	}
	if state.Decision == "CLEAR" && (value != nil || state.Status != "CLEARED") {
		return errors.New("CLEAR requires null and CLEARED")
	}
	if !ValidRevision(state.Revision) {
		return errors.New("invalid field revision")
	}
	if state.Origin.Kind == "SHARED" && state.Origin.CollectionID != "" && !UUID.MatchString(state.Origin.CollectionID) {
		return errors.New("invalid shared origin")
	}
	r, e := strconv.ParseInt(state.Revision, 10, 64)
	maxR, _ := strconv.ParseInt(docRevision, 10, 64)
	if e != nil || r < 1 || r > maxR {
		return errors.New("field revision must be within document revision")
	}
	if state.Status == "PRESENT" && value == nil {
		return errors.New("PRESENT requires a value")
	}
	if state.Status != "PRESENT" && value != nil {
		return errors.New("absent field status requires null")
	}
	if state.Origin.Revision != "" {
		if _, e = strconv.ParseInt(state.Origin.Revision, 10, 64); e != nil || !revision.MatchString(state.Origin.Revision) {
			return errors.New("invalid origin revision")
		}
	}
	return nil
}
func validateLyrics(v map[string]any) error {
	timing, _ := v["timing"].(string)
	if timing != "none" && timing != "lrc" && timing != "milliseconds" && timing != "mpeg-frames" {
		return errors.New("unsupported lyric timing")
	}
	for key := range v {
		if key != "timing" && key != "text" && key != "lines" && key != "language" {
			return errors.New("unknown lyric field")
		}
	}
	if text, ok := v["text"]; ok {
		if _, ok = text.(string); !ok {
			return errors.New("lyric text must be a string")
		}
	}
	if language, ok := v["language"]; ok {
		s, ok := language.(string)
		if !ok || len(s) > 100 {
			return errors.New("invalid lyric language")
		}
	}
	if timing == "none" || timing == "lrc" {
		if _, ok := v["text"].(string); !ok {
			return errors.New("untimed/LRC lyrics require text")
		}
	}
	if timing == "milliseconds" || timing == "mpeg-frames" {
		lines, ok := v["lines"].([]any)
		if !ok || len(lines) > 20000 {
			return errors.New("timed lyrics require bounded lines")
		}
		var previous int64 = -1
		for _, raw := range lines {
			line, ok := raw.(map[string]any)
			if !ok || len(line) != 2 {
				return errors.New("lyric line requires time and text")
			}
			n, ok := line["time"].(json.Number)
			if !ok {
				return errors.New("lyric time must be integer")
			}
			at, e := n.Int64()
			if e != nil || at < 0 || at > 604800000 || at < previous {
				return errors.New("invalid lyric timeline order")
			}
			if _, ok := line["text"].(string); !ok {
				return errors.New("lyric line text must be a string")
			}
			previous = at
		}
	}
	return nil
}
func validateCapabilities(doc Document) error {
	seen := map[string]bool{}
	for _, capability := range doc.RequiredCapabilities {
		if seen[capability] {
			return errors.New("duplicate required capability")
		}
		seen[capability] = true
		if capability == "music/1" || capability == "ktv/1" {
			switch doc.Type {
			case "SONG":
				if doc.Fields["title"] == nil || arrayLength(doc.Fields["artists"]) == 0 && arrayLength(doc.Fields["credits"]) == 0 {
					return errors.New("music profile requires title and performer credits")
				}
			case "WORK", "RELEASE":
				if doc.Fields["title"] == nil {
					return errors.New("music profile requires title")
				}
			case "ARTIST", "TAG":
				if doc.Fields["name"] == nil {
					return errors.New("music profile requires name")
				}
			case "VARIANT":
				if doc.Fields["kind"] == nil {
					return errors.New("music profile requires variant kind")
				}
			}
		}
		if capability == "ktv/1" && doc.Type == "MEDIA" {
			if doc.Fields["durationMs"] == nil || doc.Fields["observation"] == nil || doc.Fields["audioRoles"] == nil {
				return errors.New("KTV profile requires observed duration, streams and audio roles")
			}
			b, _ := json.Marshal(doc.Fields["observation"])
			var observation map[string]any
			_ = json.Unmarshal(b, &observation)
			if arrayLength(observation["streams"]) == 0 {
				return errors.New("KTV profile requires nonempty observed streams")
			}
		}
		if capability == "ktv/1" && doc.Type == "VARIANT" && doc.Fields["timelineDurationMs"] == nil {
			return errors.New("KTV profile requires a version timeline")
		}
	}
	return nil
}

func arrayLength(v any) int {
	b, _ := json.Marshal(v)
	var list []any
	_ = json.Unmarshal(b, &list)
	return len(list)
}

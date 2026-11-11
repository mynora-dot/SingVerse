package metastandard

import "testing"

func TestOrderedAudioRolesAndObservationUnits(t *testing.T) {
	for _, raw := range []string{`[{"streamId":"stream:2","role":"BACKING"},{"streamId":"stream:1","role":"ORIGINAL"}]`, `[]`} {
		v, err := Parse([]byte(raw))
		if err != nil {
			t.Fatal(err)
		}
		if err = ValidateField("MEDIA", "audioRoles", v); err != nil {
			t.Fatal(err)
		}
	}
	for _, raw := range []string{`{"originalId":"stream:1"}`, `[{"streamId":"stream:1","role":"ORIGINAL"},{"streamId":"stream:1","role":"BACKING"}]`, `[{"streamId":"stream:1","role":"ORIGINAL"},{"streamId":"stream:2","role":"ORIGINAL"}]`} {
		v, err := Parse([]byte(raw))
		if err != nil {
			t.Fatal(err)
		}
		if err = ValidateField("MEDIA", "audioRoles", v); err == nil {
			t.Fatal("ambiguous audio mapping accepted", raw)
		}
	}
	raw := map[string]any{"duration": 120.25, "streams": []any{map[string]any{"id": "stream:1", "kind": "audio"}}}
	out := NormalizeObservation(raw)
	if out["durationMs"] != int64(120250) || out["duration"] != nil || raw["duration"] != 120.25 {
		t.Fatal("observation normalization changed evidence", out, raw)
	}
	for _, value := range []any{"120.250000", "N/A", "NaN", "Infinity", "-1", "604801"} {
		out := NormalizeObservation(map[string]any{"duration": value})
		if value == "120.250000" {
			if out["durationMs"] != int64(120250) {
				t.Fatal("ffprobe string duration lost", out)
			}
		} else if out["durationMs"] != nil {
			t.Fatal("invalid duration accepted", value, out)
		}
	}
}

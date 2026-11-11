package library

import (
	"encoding/json"
	"fmt"
	"math"
	"strings"
)

func contentIdentity(id string, size *int64, modified *string) string {
	s, m := "null", "null"
	if size != nil {
		s = fmt.Sprint(*size)
	}
	if modified != nil {
		m = *modified
	}
	return hash(id + ":" + s + ":" + m)
}
func metadataObject(raw *string) (map[string]any, error) {
	v := map[string]any{}
	if raw != nil {
		if e := json.Unmarshal([]byte(*raw), &v); e != nil {
			return nil, e
		}
	}
	return v, nil
}
func fieldList(raw *string) ([]string, error) {
	v := []string{}
	if raw != nil {
		if e := json.Unmarshal([]byte(*raw), &v); e != nil {
			return nil, e
		}
	}
	return v, nil
}
func hasField(v []string, f string) bool {
	for _, x := range v {
		if x == f {
			return true
		}
	}
	return false
}
func addField(v []string, f string) []string {
	if !hasField(v, f) {
		v = append(v, f)
	}
	return v
}
func originKind(v any) string {
	if m, ok := v.(map[string]any); ok {
		k, _ := m["kind"].(string)
		return k
	}
	return ""
}
func effectiveMetadata(song Song, rawTrack, rawFields, appliedAt, rawOrigins, fp, remote, overrideSource, overrideArtists, download, downloadAt *string) (any, error) {
	track, e := metadataObject(rawTrack)
	if e != nil {
		return nil, e
	}
	fields, e := fieldList(rawFields)
	if e != nil {
		return nil, e
	}
	origins, e := metadataObject(rawOrigins)
	if e != nil {
		return nil, e
	}
	retained := []string{}
	for _, f := range fields {
		if originKind(origins[f]) != "FILE" {
			retained = append(retained, f)
		} else {
			delete(origins, f)
		}
	}
	fields = retained
	// The fingerprint validates file evidence, never a person's song data.
	stale := false
	var metadata map[string]any
	if rawTrack != nil && len(fields) > 0 {
		metadata = map[string]any{"track": track, "fields": fields, "appliedAt": appliedAt, "stale": stale, "origins": origins}
	}
	ensure := func() {
		if metadata == nil || stale {
			track = map[string]any{"provider": nil, "externalId": nil, "title": nil, "artists": []string{}, "album": nil, "durationMs": nil, "releaseDate": nil, "aliases": []string{}, "coverUrl": nil, "availability": "UNKNOWN", "fetchedAt": song.ScannedAt, "artistRefs": []any{}}
			fields = []string{}
			origins = map[string]any{}
			stale = false
		}
		metadata = map[string]any{"track": track, "fields": fields, "appliedAt": song.ScannedAt, "stale": false, "origins": origins}
	}
	put := func(f string, value any, kind string, at any, external any) {
		fields = addField(fields, f)
		name := f
		if f == "cover" {
			name = "coverUrl"
		}
		track[name] = value
		origins[f] = map[string]any{"provider": nil, "externalId": external, "appliedAt": at, "kind": kind}
	}
	if download != nil {
		d, e := metadataObject(download)
		if e != nil {
			return nil, e
		}
		ensure()
		for _, f := range []string{"title", "artists", "album", "durationMs", "cover"} {
			key := f
			if f == "cover" {
				key = "coverUrl"
			}
			v := d[key]
			if v == nil || f == "cover" && v == "" {
				continue
			}
			if !hasField(fields, f) {
				external := fmt.Sprint(d["platform"]) + ":" + fmt.Sprint(d["remoteId"])
				put(f, v, "DOWNLOAD", downloadAt, external)
			}
		}
		metadata["appliedAt"] = downloadAt
	}
	if song.embedded != nil {
		tags, e := metadataObject(song.embedded)
		if e != nil {
			return nil, e
		}
		r, e := metadataObject(remote)
		if e != nil {
			return nil, e
		}
		kind := "FILE"
		if r["origin"] == "SHARED" {
			kind = "SHARED"
		}
		supplied := map[string]any{}
		for _, f := range []string{"title", "artists", "album", "releaseDate"} {
			v := tags[f]
			if projectionEmpty(v) {
				continue
			}
			if a, ok := v.([]any); ok && len(a) == 0 {
				continue
			}
			supplied[f] = v
		}
		if tech, ok := r["technical"].(map[string]any); ok {
			if n, ok := tech["duration"].(float64); ok && !math.IsNaN(n) && !math.IsInf(n, 0) && n > 0 && n < 604800 {
				supplied["durationMs"] = int(math.Round(n * 1000))
			}
		}
		if cover, ok := r["cover"].(string); ok && len(cover) == 64 {
			supplied["cover"] = "embedded:" + song.ID
		}
		if kind == "SHARED" {
			if aliases, ok := r["aliases"].([]any); ok {
				supplied["aliases"] = aliases
			}
		}
		for f := range supplied {
			if metadata != nil && hasField(fields, f) && originKind(origins[f]) != kind {
				delete(supplied, f)
			}
		}
		if len(supplied) > 0 {
			ensure()
			for f, v := range supplied {
				put(f, v, kind, song.ScannedAt, nil)
			}
		}
	}
	if overrideSource != nil {
		if overrideArtists == nil {
			if metadata != nil && originKind(origins["artists"]) == "MANUAL" {
				next := []string{}
				for _, f := range fields {
					if f != "artists" {
						next = append(next, f)
					}
				}
				fields = next
				delete(origins, "artists")
			}
		} else {
			var artists []string
			if e = json.Unmarshal([]byte(*overrideArtists), &artists); e != nil {
				return nil, e
			}
			ensure()
			if track["title"] == nil {
				track["title"] = song.Result.Title
			}
			put("artists", artists, "MANUAL", song.ScannedAt, nil)
		}
	}
	if metadata == nil {
		return nil, nil
	}
	metadata["fields"] = fields
	metadata["track"] = track
	metadata["origins"] = origins
	return metadata, nil
}

func projectionEmpty(v any) bool {
	if v == nil {
		return true
	}
	if text, ok := v.(string); ok {
		return text == ""
	}
	if a, ok := v.([]any); ok {
		return len(a) == 0
	}
	if a, ok := v.([]string); ok {
		return len(a) == 0
	}
	return false
}

func validEmbeddedTags(tags map[string]any) bool {
	if tags == nil || len(encode(tags)) > 4*1024*1024 {
		return false
	}
	for _, f := range []string{"title", "album", "releaseDate"} {
		if tags[f] == nil {
			continue
		}
		s, ok := tags[f].(string)
		limit := 500
		if f == "releaseDate" {
			limit = 32
		}
		if !ok || len([]rune(s)) > limit {
			return false
		}
	}
	if tags["artists"] != nil {
		var values []string
		if json.Unmarshal([]byte(encode(tags["artists"])), &values) != nil || len(values) > 32 {
			return false
		}
		for _, s := range values {
			if strings.TrimSpace(s) == "" || len([]rune(s)) > 200 {
				return false
			}
		}
	}
	if tags["lyrics"] != nil && len(encode(tags["lyrics"])) > 262144 {
		return false
	}
	return true
}
func embeddedIdentity(tags map[string]any) bool {
	title, ok := tags["title"].(string)
	if !ok || strings.TrimSpace(title) == "" {
		return false
	}
	var artists []string
	return json.Unmarshal([]byte(encode(tags["artists"])), &artists) == nil && len(artists) > 0
}

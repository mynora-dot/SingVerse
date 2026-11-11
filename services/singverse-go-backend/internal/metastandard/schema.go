package metastandard

import "sort"

// JSONSchema returns the distributable Draft 2020-12 structural schema. The
// independent validator additionally checks dates, identities, field/state
// correspondence, graph references and package integrity.
func JSONSchema() map[string]any {
	ref := func(name string) map[string]any { return map[string]any{"$ref": "#/$defs/" + name} }
	text := map[string]any{"type": "string", "minLength": 1, "maxLength": 500}
	uuid := map[string]any{"type": "string", "pattern": UUID.String()}
	digest := map[string]any{"type": "string", "pattern": SHA256.String()}
	rev := map[string]any{"type": "string", "pattern": revision.String()}
	object := func(props map[string]any, required ...string) map[string]any {
		out := map[string]any{"type": "object", "properties": props, "additionalProperties": false}
		if len(required) > 0 {
			out["required"] = required
		}
		return out
	}
	defs := map[string]any{
		"uuid": uuid, "sha256": digest, "revision": rev, "text": text,
		"texts":       map[string]any{"type": "array", "maxItems": 64, "items": text},
		"uuids":       map[string]any{"type": "array", "maxItems": 64, "items": uuid},
		"date":        map[string]any{"type": "string", "pattern": `^[0-9]{4}(-[0-9]{2}(-[0-9]{2})?)?$`},
		"duration":    map[string]any{"type": "integer", "minimum": 1, "maximum": 604800000},
		"digest":      digest,
		"asset":       object(map[string]any{"assetId": digest}, "assetId"),
		"variantKind": map[string]any{"enum": []string{"UNKNOWN", "MV", "KTV", "LIVE", "AUDIO", "OTHER"}},
		"lyrics":      object(map[string]any{"timing": map[string]any{"enum": []string{"none", "lrc", "milliseconds", "mpeg-frames"}}, "text": map[string]any{"type": "string", "maxLength": 262144}, "language": map[string]any{"type": "string", "maxLength": 100}, "lines": map[string]any{"type": "array", "maxItems": 20000, "items": object(map[string]any{"time": map[string]any{"type": "integer", "minimum": 0, "maximum": 604800000}, "text": map[string]any{"type": "string"}}, "time", "text")}}, "timing"), "object": map[string]any{"type": "object"},
		"observation": map[string]any{"type": "object", "properties": map[string]any{"durationMs": ref("duration"), "streams": map[string]any{"type": "array", "maxItems": 128, "items": map[string]any{"type": "object", "properties": map[string]any{"frameRate": object(map[string]any{"numerator": map[string]any{"type": "integer", "minimum": 0, "maximum": SafeInteger}, "denominator": map[string]any{"type": "integer", "minimum": 1, "maximum": SafeInteger}}, "numerator", "denominator")}}}}},
		"externalIds": map[string]any{"type": "array", "maxItems": 128, "items": object(map[string]any{"provider": text, "entityType": text, "id": text}, "provider", "entityType", "id")},
		"credits":     map[string]any{"type": "array", "maxItems": 64, "items": object(map[string]any{"artistId": uuid, "name": map[string]any{"type": "string", "minLength": 1, "maxLength": 200}, "role": text, "join": map[string]any{"type": "string", "maxLength": 100}}, "artistId", "name")},
	}
	defs["lyrics"].(map[string]any)["allOf"] = []any{
		map[string]any{"if": map[string]any{"properties": map[string]any{"timing": map[string]any{"enum": []string{"none", "lrc"}}}}, "then": map[string]any{"required": []string{"text"}}},
		map[string]any{"if": map[string]any{"properties": map[string]any{"timing": map[string]any{"enum": []string{"milliseconds", "mpeg-frames"}}}}, "then": map[string]any{"required": []string{"lines"}}},
	}
	defs["audioRoles"] = map[string]any{"type": "array", "maxItems": 128, "items": object(map[string]any{"streamId": map[string]any{"type": "string", "pattern": streamID.String()}, "role": map[string]any{"enum": []string{"ORIGINAL", "BACKING", "OTHER", "UNKNOWN"}}}, "streamId", "role")}
	defs["origin"] = object(map[string]any{"kind": map[string]any{"enum": []string{"FILENAME", "FILE", "PLATFORM", "SHARED", "MANUAL", "UPLOAD", "DOWNLOAD", "UNKNOWN"}}, "provider": text, "entityType": text, "externalId": text, "collectionId": uuid, "revision": rev}, "kind")
	state := object(map[string]any{"origin": ref("origin"), "decision": map[string]any{"enum": []string{"AUTO", "SET", "CLEAR"}}, "status": map[string]any{"enum": []string{"PRESENT", "UNREAD", "MISSING", "NOT_APPLICABLE", "CLEARED"}}, "revision": rev}, "origin", "decision", "status", "revision")
	state["allOf"] = []any{map[string]any{"if": map[string]any{"properties": map[string]any{"decision": map[string]any{"const": "CLEAR"}}}, "then": map[string]any{"properties": map[string]any{"status": map[string]any{"const": "CLEARED"}}}}}
	defs["state"] = state
	props := map[string]any{"schemaVersion": map[string]any{"const": Version}, "id": uuid, "type": map[string]any{"enum": []string{"WORK", "SONG", "VARIANT", "MEDIA", "ARTIST", "RELEASE", "TAG"}}, "revision": rev, "parentId": uuid, "parentState": ref("state"), "fields": map[string]any{"type": "object"}, "states": map[string]any{"type": "object", "additionalProperties": ref("state")}, "extensions": map[string]any{"type": "object", "propertyNames": map[string]any{"pattern": namespace.String()}}, "requiredCapabilities": map[string]any{"type": "array", "uniqueItems": true, "items": map[string]any{"enum": []string{"core/1", "music/1", "ktv/1"}}}}
	schema := object(props, "schemaVersion", "id", "type", "revision", "fields", "states")
	schema["$schema"] = "https://json-schema.org/draft/2020-12/schema"
	schema["$id"] = "urn:singverse:metadata:1.0"
	schema["title"] = "SingVerse Metadata Schema 1.0"
	schema["$defs"] = defs
	conditions := []any{}
	for _, kind := range []string{"WORK", "SONG", "VARIANT", "MEDIA", "ARTIST", "RELEASE", "TAG"} {
		fields := map[string]any{}
		states := map[string]any{}
		fieldRules := []any{}
		names := make([]string, 0, len(Fields[kind]))
		for name := range Fields[kind] {
			names = append(names, name)
		}
		sort.Strings(names)
		for _, name := range names {
			typ := Fields[kind][name]
			fields[name] = map[string]any{"anyOf": []any{ref(typ), map[string]any{"type": "null"}}}
			states[name] = ref("state")
			fieldRules = append(fieldRules,
				map[string]any{"if": map[string]any{"properties": map[string]any{"fields": map[string]any{"required": []string{name}}}}, "then": map[string]any{"properties": map[string]any{"states": map[string]any{"required": []string{name}}}}, "else": map[string]any{"properties": map[string]any{"states": map[string]any{"not": map[string]any{"required": []string{name}}}}}},
				map[string]any{"if": map[string]any{"properties": map[string]any{"states": map[string]any{"required": []string{name}, "properties": map[string]any{name: map[string]any{"properties": map[string]any{"status": map[string]any{"const": "PRESENT"}}}}}}}, "then": map[string]any{"properties": map[string]any{"fields": map[string]any{"properties": map[string]any{name: map[string]any{"not": map[string]any{"type": "null"}}}}}}, "else": map[string]any{"properties": map[string]any{"fields": map[string]any{"properties": map[string]any{name: map[string]any{"type": "null"}}}}}},
			)
		}
		then := map[string]any{"properties": map[string]any{"fields": object(fields), "states": object(states)}}
		then["allOf"] = fieldRules
		if kind == "VARIANT" || kind == "MEDIA" {
			then["required"] = []string{"parentId"}
		} else {
			then["not"] = map[string]any{"anyOf": []any{map[string]any{"required": []string{"parentId"}}, map[string]any{"required": []string{"parentState"}}}}
		}
		conditions = append(conditions, map[string]any{"if": map[string]any{"properties": map[string]any{"type": map[string]any{"const": kind}}}, "then": then})
	}
	schema["allOf"] = conditions
	return schema
}

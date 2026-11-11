package metastandard

import (
	"encoding/json"
	"errors"
	"fmt"
	"regexp"
	"strconv"
	"strings"
	"time"
)

const Version = "1.0"
const ImageLimit = 32 << 20
const ChunkLimit = 16 << 20
const SafeInteger int64 = 9007199254740991

var UUID = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)
var SHA256 = regexp.MustCompile(`^[0-9a-f]{64}$`)
var revision = regexp.MustCompile(`^[1-9][0-9]{0,18}$`)
var namespace = regexp.MustCompile(`^[a-z][a-z0-9]*(\.[a-z][a-z0-9-]*)+:[a-zA-Z][a-zA-Z0-9._-]*$`)

type Origin struct {
	Kind         string `json:"kind"`
	Provider     string `json:"provider,omitempty"`
	EntityType   string `json:"entityType,omitempty"`
	ExternalID   string `json:"externalId,omitempty"`
	CollectionID string `json:"collectionId,omitempty"`
	Revision     string `json:"revision,omitempty"`
}
type FieldState struct {
	Origin   Origin `json:"origin"`
	Decision string `json:"decision"`
	Status   string `json:"status"`
	Revision string `json:"revision"`
}
type Document struct {
	SchemaVersion        string                     `json:"schemaVersion"`
	ID                   string                     `json:"id"`
	Type                 string                     `json:"type"`
	Revision             string                     `json:"revision"`
	ParentState          *FieldState                `json:"parentState,omitempty"`
	ParentID             string                     `json:"parentId,omitempty"`
	Fields               map[string]any             `json:"fields"`
	States               map[string]FieldState      `json:"states"`
	Extensions           map[string]json.RawMessage `json:"extensions,omitempty"`
	RequiredCapabilities []string                   `json:"requiredCapabilities,omitempty"`
}
type AssetRef struct {
	AssetID string `json:"assetId"`
}
type Credit struct {
	ArtistID string `json:"artistId"`
	Name     string `json:"name"`
	Role     string `json:"role,omitempty"`
	Join     string `json:"join,omitempty"`
}
type ExternalID struct {
	Provider   string `json:"provider"`
	EntityType string `json:"entityType"`
	ID         string `json:"id"`
}
type Rational struct {
	Numerator   int64 `json:"numerator"`
	Denominator int64 `json:"denominator"`
}

// Every field has one owner and one merge unit. Lists and observations are
// atomic; merging individual audio streams from separate observations is forbidden.
var Fields = map[string]map[string]string{
	"WORK":    {"title": "text", "aliases": "texts", "credits": "credits", "externalIds": "externalIds"},
	"SONG":    {"title": "text", "artists": "texts", "credits": "credits", "aliases": "texts", "album": "text", "releaseId": "uuid", "releaseDate": "date", "referenceDurationMs": "duration", "cover": "asset", "workId": "uuid", "tagIds": "uuids", "externalIds": "externalIds"},
	"VARIANT": {"title": "text", "artists": "texts", "credits": "credits", "kind": "variantKind", "languages": "texts", "timelineDurationMs": "duration", "lyrics": "lyrics", "lyricsAsset": "asset", "cover": "asset", "aliases": "texts", "externalIds": "externalIds"},
	"MEDIA":   {"sha256": "digest", "durationMs": "duration", "observation": "observation", "audioRoles": "audioRoles", "externalIds": "externalIds"},
	"ARTIST":  {"name": "text", "aliases": "texts", "externalIds": "externalIds", "portrait": "asset"},
	"RELEASE": {"title": "text", "credits": "credits", "releaseDate": "date", "cover": "asset", "externalIds": "externalIds"},
	"TAG":     {"name": "text", "dimension": "text", "externalIds": "externalIds"},
}

func Decode(data []byte) (Document, error) {
	var doc Document
	if _, err := Parse(data); err != nil {
		return doc, err
	}
	d := json.NewDecoder(strings.NewReader(string(data)))
	d.UseNumber()
	d.DisallowUnknownFields()
	if err := d.Decode(&doc); err != nil {
		return doc, err
	}
	return doc, Validate(doc)
}
func Validate(doc Document) error {
	if doc.SchemaVersion != Version || !UUID.MatchString(doc.ID) || !revision.MatchString(doc.Revision) {
		return errors.New("invalid schema version, UUID or revision")
	}
	if _, err := strconv.ParseInt(doc.Revision, 10, 64); err != nil {
		return errors.New("revision overflow")
	}
	if doc.ParentID != "" && !UUID.MatchString(doc.ParentID) {
		return errors.New("invalid parent UUID")
	}
	if Fields[doc.Type] == nil || doc.Fields == nil || doc.States == nil {
		return errors.New("invalid entity type or fields")
	}
	if (doc.Type == "VARIANT" || doc.Type == "MEDIA") && doc.ParentID == "" {
		return errors.New("variant/media require a parent")
	}
	for _, capability := range doc.RequiredCapabilities {
		if capability != "core/1" && capability != "music/1" && capability != "ktv/1" {
			return fmt.Errorf("unsupported required capability: %s", capability)
		}
	}
	for key, value := range doc.Extensions {
		if !namespace.MatchString(key) {
			return errors.New("extension requires a namespace")
		}
		if _, e := Parse(value); e != nil {
			return e
		}
	}
	if doc.ParentID != "" && doc.Type != "VARIANT" && doc.Type != "MEDIA" {
		return errors.New("parentId only belongs to variant/media")
	}
	if doc.ParentState != nil {
		if doc.ParentID == "" {
			return errors.New("parentState requires a parent")
		}
		if err := validFieldState(*doc.ParentState, doc.ParentID, doc.Revision); err != nil {
			return err
		}
	}
	if err := validateCapabilities(doc); err != nil {
		return err
	}
	for name, value := range doc.Fields {
		if err := ValidateField(doc.Type, name, value); err != nil {
			return fmt.Errorf("%s: %w", name, err)
		}
		state, ok := doc.States[name]
		if !ok {
			return fmt.Errorf("%s has no field state", name)
		}
		if err := validFieldState(state, value, doc.Revision); err != nil {
			return err
		}
		if state.Decision != "AUTO" && state.Decision != "SET" && state.Decision != "CLEAR" {
			return errors.New("invalid field decision")
		}
		if state.Status != "PRESENT" && state.Status != "UNREAD" && state.Status != "MISSING" && state.Status != "NOT_APPLICABLE" && state.Status != "CLEARED" {
			return errors.New("invalid field status")
		}
		if state.Decision == "CLEAR" && (value != nil || state.Status != "CLEARED") {
			return errors.New("CLEAR requires null and CLEARED")
		}
		if !revision.MatchString(state.Revision) {
			return errors.New("invalid field revision")
		}
		switch state.Origin.Kind {
		case "FILENAME", "FILE", "PLATFORM", "SHARED", "MANUAL", "UPLOAD", "DOWNLOAD", "UNKNOWN":
		default:
			return errors.New("invalid field origin")
		}
		if state.Origin.Kind == "SHARED" && (state.Origin.CollectionID != "" && !UUID.MatchString(state.Origin.CollectionID)) {
			return errors.New("invalid shared origin")
		}
	}
	for name := range doc.States {
		if _, ok := doc.Fields[name]; !ok {
			return errors.New("orphan field state")
		}
	}
	return nil
}
func ValidateField(kind, name string, value any) error {
	t := Fields[kind][name]
	if t == "" {
		return errors.New("unknown field")
	}
	if value == nil {
		return nil
	}
	b, e := json.Marshal(value)
	if e != nil {
		return e
	}
	v, e := Parse(b)
	if e != nil {
		return e
	}
	if v == nil {
		return nil
	}
	str := func(v any, limit int) bool {
		s, ok := v.(string)
		return ok && strings.TrimSpace(s) != "" && len([]rune(s)) <= limit
	}
	integer := func(v any, min, max int64) bool {
		n, ok := v.(json.Number)
		if !ok {
			return false
		}
		i, e := n.Int64()
		return e == nil && i >= min && i <= max
	}
	if err := validateNumbers(v); err != nil {
		return err
	}
	switch t {
	case "text":
		if !str(v, 500) {
			return errors.New("expected nonempty text, max 500 characters")
		}
	case "texts", "uuids":
		a, ok := v.([]any)
		if !ok || len(a) > 64 {
			return errors.New("expected array, max 64")
		}
		for _, item := range a {
			if !str(item, 500) {
				return errors.New("invalid text item")
			}
			if t == "uuids" && !UUID.MatchString(item.(string)) {
				return errors.New("invalid UUID")
			}
		}
	case "uuid":
		s, ok := v.(string)
		if !ok || !UUID.MatchString(s) {
			return errors.New("invalid UUID")
		}
	case "digest":
		s, ok := v.(string)
		if !ok || !SHA256.MatchString(s) {
			return errors.New("invalid SHA-256")
		}
	case "date":
		s, ok := v.(string)
		if !ok {
			return errors.New("invalid date")
		}
		layout := "2006-01-02"
		if len(s) == 4 {
			layout = "2006"
		} else if len(s) == 7 {
			layout = "2006-01"
		}
		if _, e := time.Parse(layout, s); e != nil || len(s) != len(layout) {
			return errors.New("invalid partial date")
		}
	case "duration":
		if !integer(v, 1, 604800000) {
			return errors.New("duration must be integer milliseconds, max seven days")
		}
	case "variantKind":
		s, ok := v.(string)
		if !ok || !strings.Contains("|UNKNOWN|MV|KTV|LIVE|AUDIO|OTHER|", "|"+s+"|") {
			return errors.New("invalid variant kind")
		}
	case "asset":
		m, ok := v.(map[string]any)
		if !ok || len(m) != 1 {
			return errors.New("asset reference required")
		}
		s, ok := m["assetId"].(string)
		if !ok || !SHA256.MatchString(s) {
			return errors.New("invalid asset ID")
		}
	case "credits":
		a, ok := v.([]any)
		if !ok || len(a) > 64 {
			return errors.New("invalid ordered credits")
		}
		for _, item := range a {
			m, ok := item.(map[string]any)
			if !ok || !str(m["name"], 200) {
				return errors.New("credit name required")
			}
			id, ok := m["artistId"].(string)
			if !ok || !UUID.MatchString(id) {
				return errors.New("artist UUID required")
			}
			if role, ok := m["role"]; ok && !str(role, 100) {
				return errors.New("invalid credit role")
			}
			if join, ok := m["join"]; ok {
				if text, ok := join.(string); !ok || len([]rune(text)) > 100 {
					return errors.New("invalid credit join")
				}
			}
			for k := range m {
				if k != "artistId" && k != "name" && k != "role" && k != "join" {
					return errors.New("unknown credit field")
				}
			}
		}
	case "externalIds":
		a, ok := v.([]any)
		if !ok || len(a) > 128 {
			return errors.New("invalid external identifiers")
		}
		for _, item := range a {
			m, ok := item.(map[string]any)
			if !ok || len(m) != 3 || !str(m["provider"], 100) || !str(m["entityType"], 100) || !str(m["id"], 500) {
				return errors.New("external identifier requires provider, entityType and string id")
			}
		}
	case "lyrics":
		if _, ok := v.(map[string]any); !ok || len(b) > 262144 {
			return errors.New("invalid lyrics document")
		}
		return validateLyrics(v.(map[string]any))
	case "object":
		if _, ok := v.(map[string]any); !ok {
			return errors.New("expected object")
		}
	case "audioRoles":
		return validateAudioRoles(v)
	case "observation":
		m, ok := v.(map[string]any)
		if !ok {
			return errors.New("invalid atomic observation")
		}
		if x := m["durationMs"]; x != nil && !integer(x, 1, 604800000) {
			return errors.New("invalid observed duration")
		}
		if streams, exists := m["streams"]; exists && streams != nil {
			streams, ok := streams.([]any)
			if !ok {
				return errors.New("streams must be an ordered array")
			}
			if len(streams) > 128 {
				return errors.New("too many streams")
			}
			for _, stream := range streams {
				st, ok := stream.(map[string]any)
				if !ok {
					return errors.New("invalid stream")
				}
				if rate := st["frameRate"]; rate != nil {
					r, ok := rate.(map[string]any)
					if !ok || !integer(r["numerator"], 0, SafeInteger) || !integer(r["denominator"], 1, SafeInteger) {
						return errors.New("frame rate requires an integer rational")
					}
				}
			}
		}
	}
	return nil
}

func AssetIDs(doc Document) []string {
	ids := []string{}
	for _, name := range []string{"cover", "portrait", "lyricsAsset"} {
		if value, ok := doc.Fields[name].(map[string]any); ok {
			if id, ok := value["assetId"].(string); ok {
				ids = append(ids, id)
			}
		} else if value, ok := doc.Fields[name].(AssetRef); ok {
			ids = append(ids, value.AssetID)
		}
	}
	return ids
}

func ValidRevision(value string) bool {
	if !revision.MatchString(value) {
		return false
	}
	n, e := strconv.ParseInt(value, 10, 64)
	return e == nil && n > 0
}

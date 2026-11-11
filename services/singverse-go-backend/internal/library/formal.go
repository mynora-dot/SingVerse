package library

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"sort"
	"strconv"

	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/metastandard"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/metastore"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

func (s *Service) Formal() *metastore.Store { return s.formal }
func (s *Service) indexIdentity(ctx context.Context, tx store.DBTX, source, path string) (string, error) {
	var id string
	e := tx.QueryRowContext(ctx, s.q["song-path-identity"], source, hash(path), source, hash(path)).Scan(&id)
	if errors.Is(e, sql.ErrNoRows) {
		return metastore.NewID(), nil
	}
	return id, e
}
func (s *Service) FormalIDs(ctx context.Context, tx store.DBTX, media string) (song, variant string, err error) {
	m, e := s.formal.Read(ctx, tx, media)
	if e != nil {
		return "", "", e
	}
	v, e := s.formal.Read(ctx, tx, m.ParentID)
	return v.ParentID, m.ParentID, e
}
func (s *Service) bootstrapFormal(ctx context.Context) error {
	for {
		ids := []string{}
		if e := s.db.SelectContext(ctx, &ids, s.q["formal-unregistered"]); e != nil {
			return e
		}
		if len(ids) == 0 {
			return nil
		}
		if e := store.WithinTx(ctx, s.db, func(tx store.DBTX) error {
			for _, id := range ids {
				if e := s.SyncMetadata(ctx, tx, id); e != nil {
					return e
				}
			}
			return nil
		}); e != nil {
			return e
		}
	}
}
func (s *Service) SyncSourceMetadata(ctx context.Context, tx store.DBTX, source string) error {
	// Keyset batches close their rows before issuing writes on SQLite's sole
	// connection. A publication never loads the entire library into memory.
	after := ""
	for {
		var ids []string
		rows, e := tx.QueryxContext(ctx, s.q["formal-source-ids"], source, after)
		if e != nil {
			return e
		}
		for rows.Next() {
			var id string
			if e = rows.Scan(&id); e != nil {
				rows.Close()
				return e
			}
			ids = append(ids, id)
		}
		e = rows.Err()
		rows.Close()
		if e != nil {
			return e
		}
		if len(ids) == 0 {
			return nil
		}
		for _, id := range ids {
			if e = s.SyncMetadata(ctx, tx, id); e != nil {
				return e
			}
		}
		after = ids[len(ids)-1]
	}
}
func (s *Service) SyncMetadata(ctx context.Context, tx store.DBTX, id string) error {
	var row songRecord
	if e := tx.QueryRowxContext(ctx, s.q["song-columns"]+s.q["song-find"], id).StructScan(&row); e != nil {
		return e
	}
	row.FormalSong, row.FormalVariant, row.FormalMedia = nil, nil, nil
	legacy, e := row.model()
	if e != nil {
		return e
	}
	songID, variantID, e := s.FormalIDs(ctx, tx, id)
	fresh := errors.Is(e, sql.ErrNoRows)
	if fresh {
		songID, variantID = metastore.NewID(), metastore.NewID()
		for _, obj := range []struct{ kind, id, parent string }{{"SONG", songID, ""}, {"VARIANT", variantID, songID}, {"MEDIA", id, variantID}} {
			if _, e = s.formal.Create(ctx, tx, obj.kind, obj.id, obj.parent); e != nil {
				return e
			}
		}
	} else if e != nil {
		return e
	}
	filename := map[string]any{}
	if row.Result != "" {
		if legacy.Result.Title != nil {
			filename["title"] = *legacy.Result.Title
		}
		if len(legacy.Result.Artists) > 0 {
			filename["artists"] = legacy.Result.Artists
		}
	}
	for name, value := range filename {
		if metastandard.ValidateField("SONG", name, value) != nil {
			delete(filename, name)
		}
	}
	if _, _, e = s.formal.Observe(ctx, tx, songID, metastore.Evidence{Origin: metastandard.Origin{Kind: "FILENAME"}, Fields: filename}); e != nil {
		return e
	}
	// Retained legacy values are migration input; the formal document is the
	// only read projection once created. New local decisions use ApplyFormal.
	if m, ok := legacy.Metadata.(map[string]any); ok {
		track, _ := m["track"].(map[string]any)
		origins, _ := m["origins"].(map[string]any)
		for _, field := range []string{"title", "artists", "album", "releaseDate", "aliases", "durationMs"} {
			v := track[field]
			if projectionEmpty(v) {
				continue
			}
			name := field
			if name == "durationMs" {
				name = "referenceDurationMs"
			}
			if field == "durationMs" && (originKind(origins[field]) == "FILE" || originKind(origins[field]) == "SHARED") {
				continue
			}
			o := formalOrigin(origins[field])
			// An unsupported old value remains in its original evidence row;
			// it must not prevent the rest of a deployed library from opening.
			if metastandard.ValidateField("SONG", name, v) != nil {
				continue
			}
			if o.Kind == "UNKNOWN" || o.Kind == "SHARED" {
				continue
			}
			if o.Kind == "MANUAL" || o.Kind == "UPLOAD" {
				if fresh {
					if e = s.formal.Decide(ctx, tx, songID, map[string]any{name: v}, o); e != nil {
						return e
					}
				}
				continue
			}
			if _, _, e = s.formal.Observe(ctx, tx, songID, metastore.Evidence{Origin: o, Revision: o.Revision, Fields: map[string]any{name: v}}); e != nil {
				return e
			}
		}
	}
	remoteCurrent := true
	if row.RemoteID != nil {
		if row.IdentitySize != nil && row.FileSize != nil && *row.IdentitySize != *row.FileSize {
			remoteCurrent = false
		}
		if row.IdentityModified != nil && row.ModifiedAt != nil && *row.IdentityModified != *row.ModifiedAt {
			remoteCurrent = false
		}
	}
	if !remoteCurrent {
		if e = s.formal.InvalidateFile(ctx, tx, id); e != nil {
			return e
		}
	}
	// File observations and shared observations stay on Media. A platform
	// reference duration is never copied onto the media's playback timeline.
	for _, raw := range []*string{row.Remote, row.Embedded, row.Shared} {
		if raw == nil || raw == row.Shared || raw == row.Remote && !remoteCurrent {
			continue
		}
		var observed map[string]any
		if e = json.Unmarshal([]byte(*raw), &observed); e != nil {
			return e
		}
		o := metastandard.Origin{Kind: "FILE"}
		if raw == row.Shared {
			o.Kind = "SHARED"
			o.CollectionID, _ = observed["collectionId"].(string)
		}
		variantFields := map[string]any{}
		if lyrics, ok := observed["lyrics"].(map[string]any); ok && len(lyrics) > 0 && metastandard.ValidateField("VARIANT", "lyrics", lyrics) == nil {
			variantFields["lyrics"] = lyrics
		}
		if len(variantFields) > 0 {
			if _, _, e = s.formal.Observe(ctx, tx, variantID, metastore.Evidence{Origin: o, Fields: variantFields}); e != nil {
				return e
			}
		}
		if tech, ok := observed["technical"].(map[string]any); ok {
			fields := map[string]any{"observation": metastandard.NormalizeObservation(tech)}
			if duration := decimalNumber(tech["duration"]); duration > 0 && duration <= 604800 {
				fields["durationMs"] = int64(math.Round(duration * 1000))
			}
			if metastandard.ValidateField("MEDIA", "observation", fields["observation"]) != nil {
				delete(fields, "observation")
			}
			if _, _, e = s.formal.Observe(ctx, tx, id, metastore.Evidence{Origin: o, Fields: fields}); e != nil {
				return e
			}
		}
	}
	state := "PRESENT"
	if row.LocationState != "" {
		state = row.LocationState
	}
	if row.Verification != nil && state == "PRESENT" {
		state = *row.Verification
	}
	sourceID := row.SourceID
	if state == "UNBOUND" || state == "REPLACED" {
		sourceID = ""
	}
	return s.formal.Location(ctx, tx, id, sourceID, row.RelativePath, "", state, map[string]any{"size": row.FileSize, "modifiedAt": row.ModifiedAt, "etag": row.ETag})
}
func decimalNumber(v any) float64 {
	switch x := v.(type) {
	case float64:
		return x
	case json.Number:
		f, _ := x.Float64()
		return f
	case string:
		f, _ := strconv.ParseFloat(x, 64)
		return f
	}
	return 0
}
func formalOrigin(v any) metastandard.Origin {
	o := metastandard.Origin{Kind: "UNKNOWN"}
	if m, ok := v.(map[string]any); ok {
		o.Kind, _ = m["kind"].(string)
		o.Provider, _ = m["provider"].(string)
		o.ExternalID, _ = m["externalId"].(string)
		if o.Kind == "SHARED" {
			o.CollectionID = o.ExternalID
			o.ExternalID = ""
		}
		if o.Provider != "" {
			o.EntityType = "track"
		}
	}
	return o
}
func (s *Service) ApplyFormal(ctx context.Context, tx store.DBTX, media string, values, origins map[string]any) error {
	return s.applyFormal(ctx, tx, media, values, origins, false)
}
func (s *Service) ObserveFormal(ctx context.Context, tx store.DBTX, media string, values, origins map[string]any) error {
	return s.applyFormal(ctx, tx, media, values, origins, true)
}
func (s *Service) applyFormal(ctx context.Context, tx store.DBTX, media string, values, origins map[string]any, automatic bool) error {
	if e := s.SyncMetadata(ctx, tx, media); e != nil {
		return e
	}
	song, _, e := s.FormalIDs(ctx, tx, media)
	if e != nil {
		return e
	}
	for field, value := range values {
		name := field
		if field == "durationMs" {
			name = "referenceDurationMs"
		}
		if field == "cover" {
			continue
		}
		if metastandard.Fields["SONG"][name] == "" {
			continue
		}
		o := formalOrigin(origins[field])
		if o.Kind != "MANUAL" && o.Kind != "UPLOAD" && o.Kind != "PLATFORM" {
			continue
		}
		d, e := s.formal.Read(ctx, tx, song)
		if e != nil {
			return e
		}
		state := d.States[name]
		if state.Decision == "SET" && encode(d.Fields[name]) == encode(value) && encode(state.Origin) == encode(o) {
			continue
		}
		if automatic {
			_, _, e = s.formal.Observe(ctx, tx, song, metastore.Evidence{Origin: o, Fields: map[string]any{name: value}})
		} else {
			e = s.formal.Decide(ctx, tx, song, map[string]any{name: value}, o)
		}
		if e != nil {
			return e
		}
	}
	return nil
}

// formalProjection is the legacy /media-songs adapter. Inheritance is field
// based: a variant's explicit CLEAR overrides its song's inherited value too.
func formalProjection(songRaw, variantRaw, mediaRaw *string) (any, error) {
	if songRaw == nil {
		return nil, nil
	}
	song, e := metastandard.Decode([]byte(*songRaw))
	if e != nil {
		return nil, e
	}
	docs := []metastandard.Document{song}
	if variantRaw != nil {
		v, e := metastandard.Decode([]byte(*variantRaw))
		if e != nil {
			return nil, e
		}
		docs = append(docs, v)
	}
	track := map[string]any{}
	origins := map[string]any{}
	states := map[string]any{}
	fields := []string{}
	for _, doc := range docs {
		for name, value := range doc.Fields {
			if _, _, overrides := metastandard.DisplayField(doc, name); doc.Type == "VARIANT" && !overrides {
				continue
			}
			field := name
			switch name {
			case "referenceDurationMs":
				field = "durationMs"
			case "cover":
				field = "cover"
				if ref, ok := value.(map[string]any); ok {
					track["coverUrl"] = "asset:" + fmt.Sprint(ref["assetId"])
				} else {
					track["coverUrl"] = nil
				}
				fields = addField(fields, "cover")
				st := doc.States[name]
				origins["cover"] = map[string]any{"kind": st.Origin.Kind, "provider": nullableFormal(st.Origin.Provider), "externalId": nullableFormal(st.Origin.ExternalID)}
				states["cover"] = st
				continue
			case "lyrics", "lyricsAsset", "kind", "credits", "languages", "timelineDurationMs", "externalIds", "workId", "tagIds", "releaseId":
				continue
			}
			track[field] = value
			fields = addField(fields, field)
			st := doc.States[name]
			origins[field] = map[string]any{"kind": st.Origin.Kind, "provider": nullableFormal(st.Origin.Provider), "externalId": nullableFormal(st.Origin.ExternalID), "appliedAt": nil}
			states[field] = st
		}
		if artists, state, exists := metastandard.Performers(doc); exists {
			track["artists"] = artists
			fields = addField(fields, "artists")
			states["artists"] = state
			origins["artists"] = map[string]any{"kind": state.Origin.Kind, "provider": nullableFormal(state.Origin.Provider), "externalId": nullableFormal(state.Origin.ExternalID)}
		}
	}
	var media any
	var variant any
	if variantRaw != nil {
		var v metastandard.Document
		v, e = metastandard.Decode([]byte(*variantRaw))
		if e != nil {
			return nil, e
		}
		variant = v.Fields
	}
	if mediaRaw != nil {
		d, e := metastandard.Decode([]byte(*mediaRaw))
		if e != nil {
			return nil, e
		}
		media = d.Fields
	}
	for name, raw := range states {
		if st, ok := raw.(metastandard.FieldState); ok && st.Origin.Kind == "SHARED" {
			origins[name].(map[string]any)["externalId"] = nullableFormal(st.Origin.CollectionID)
		}
	}
	sort.Strings(fields)
	return map[string]any{"track": track, "fields": fields, "origins": origins, "fieldStates": states, "stale": false, "appliedAt": nil, "revision": song.Revision, "songId": song.ID, "media": media, "variant": variant}, nil
}
func nullableFormal(v string) any {
	if v == "" {
		return nil
	}
	return v
}

func (s *Service) FormalLyrics(ctx context.Context, tx store.DBTX, media string) (map[string]any, error) {
	_, variant, e := s.FormalIDs(ctx, tx, media)
	if e != nil {
		return nil, e
	}
	v, e := s.formal.Read(ctx, tx, variant)
	if e != nil {
		return nil, e
	}
	if v.States["lyrics"].Status == "CLEARED" || v.States["lyricsAsset"].Status == "CLEARED" && v.Fields["lyrics"] == nil {
		return map[string]any{"timing": "none", "text": ""}, nil
	}
	lyrics, _ := v.Fields["lyrics"].(map[string]any)
	if lyrics != nil {
		return lyrics, nil
	}
	if ref, ok := v.Fields["lyricsAsset"].(map[string]any); ok {
		id, _ := ref["assetId"].(string)
		asset, data, e := s.formal.ReadAsset(ctx, tx, id)
		if e != nil {
			return nil, e
		}
		return metastandard.LyricsAsset(asset.MIME, data)
	}
	return nil, nil
}

func (s *Service) StringIdentity(ctx context.Context, tx store.DBTX, media string) string {
	song, variant, e := s.FormalIDs(ctx, tx, media)
	if e != nil {
		return ""
	}
	return fmt.Sprintf("%s/%s/%s", song, variant, media)
}

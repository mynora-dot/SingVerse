package metadata

import (
	"context"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/metastandard"

	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

// projectFormalSnapshot is also used by the compatibility NFO/v2 adapters.
// Explicit nulls remove older evidence instead of falling back to a filename.
func (s *Service) projectFormalSnapshot(ctx context.Context, tx store.DBTX, id string, out map[string]any) error {
	song, variant, e := s.library.FormalIDs(ctx, tx, id)
	if e != nil {
		return e
	}
	songDoc, e := s.library.Formal().Read(ctx, tx, song)
	if e != nil {
		return e
	}
	variantDoc, e := s.library.Formal().Read(ctx, tx, variant)
	if e != nil {
		return e
	}
	tags, _ := out["tags"].(map[string]any)
	if tags == nil {
		tags = map[string]any{}
		out["tags"] = tags
	}
	for _, name := range []string{"title", "artists", "album", "releaseDate"} {
		delete(tags, name)
	}
	for _, name := range []string{"aliases", "referenceDurationMs", "lyrics", "lyricsRef", "technical", "cover", "coverKey", "coverRef", "coverData"} {
		delete(out, name)
	}
	for _, formal := range []metastandard.Document{songDoc, variantDoc} {
		doc := formal.Fields
		for _, name := range []string{"title", "artists", "album", "releaseDate"} {
			if _, _, overrides := metastandard.DisplayField(formal, name); formal.Type == "VARIANT" && !overrides {
				continue
			}
			if value, exists := doc[name]; exists {
				if value == nil {
					delete(tags, name)
				} else {
					tags[name] = value
				}
			}
		}
		if artists, _, exists := metastandard.Performers(formal); exists {
			if artists == nil {
				delete(tags, "artists")
			} else {
				tags["artists"] = artists
			}
		}
	}
	for name, value := range songDoc.Fields {
		if name == "aliases" || name == "referenceDurationMs" {
			if value == nil {
				delete(out, name)
			} else {
				out[name] = value
			}
		}
	}
	if lyrics, e := s.library.FormalLyrics(ctx, tx, id); e != nil {
		return e
	} else if lyrics != nil {
		out["lyrics"] = lyrics
	} else if lyrics, exists := variantDoc.Fields["lyrics"]; exists {
		if lyrics == nil {
			delete(out, "lyrics")
			delete(out, "lyricsRef")
		} else {
			out["lyrics"] = lyrics
		}
	}
	mediaDoc, e := s.library.Formal().Read(ctx, tx, id)
	if e != nil {
		return e
	}
	if technical, exists := mediaDoc.Fields["observation"]; exists {
		if technical == nil {
			delete(out, "technical")
		} else {
			out["technical"] = technical
		}
	}
	out["documents"] = []metastandard.Document{songDoc, variantDoc, mediaDoc}
	for _, doc := range []metastandard.Document{songDoc, variantDoc} {
		if value, _, exists := metastandard.DisplayField(doc, "cover"); exists {
			if value == nil {
				delete(out, "cover")
				delete(out, "coverKey")
				continue
			}
			ref, _ := value.(map[string]any)
			asset, _ := ref["assetId"].(string)
			_, data, e := s.library.Formal().ReadAsset(ctx, tx, asset)
			if e != nil {
				return e
			}
			key, e := s.portableCover(data)
			if e != nil {
				return e
			}
			out["cover"] = key
		}
	}
	return nil
}

package metadata

import (
	"context"
	"path/filepath"
	"strings"

	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/metastandard"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/metastore"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

func (s *Service) enqueueAttachment(ctx context.Context, tx store.DBTX, id, url string, origin Origin, automatic ...bool) error {
	rows, e := s.rows(ctx, tx, "attachment-read", map[string]any{"songId": id})
	if e != nil {
		return e
	}
	song, _, e := s.library.FormalIDs(ctx, tx, id)
	if e != nil {
		return e
	}
	current, e := s.library.Formal().Read(ctx, tx, song)
	if e != nil {
		return e
	}
	localDecision := origin.Kind == "MANUAL" || origin.Kind == "UPLOAD" || (origin.Kind == "PLATFORM" || origin.Kind == "DOWNLOAD") && (len(automatic) == 0 || !automatic[0])
	if len(rows) > 0 && text(rows[0]["cover_url"]) == url && text(rows[0]["origin_json"]) == encode(origin) && text(rows[0]["cover_revision"]) == current.States["cover"].Revision {
		return nil
	}
	a := map[string]any{"localDecision": localDecision, "revision": current.States["cover"].Revision, "songId": id, "url": url, "origin": encode(origin), "now": now()}
	if _, e = s.exec(ctx, tx, "attachment-delete", a); e != nil {
		return e
	}
	_, e = s.exec(ctx, tx, "attachment-insert", a)
	return e
}

// PersistCover performs network I/O outside the transaction. It keeps the
// previous formal cover until bytes and their registry row are durably ready.
func (s *Service) PersistCover(ctx context.Context, id string) (bool, error) {
	rows, e := s.rows(ctx, s.db, "attachment-read", map[string]any{"songId": id})
	if e != nil {
		return false, e
	}
	if len(rows) == 0 {
		// Adopt available old originals only until there is a formal cover decision.
		if song, _, err := s.library.FormalIDs(ctx, s.db, id); err == nil {
			if d, err := s.library.Formal().Read(ctx, s.db, song); err == nil {
				if state, exists := d.States["cover"]; exists && (state.Status == "PRESENT" || state.Status == "CLEARED") {
					return false, nil
				}
			}
		}
		a, e := s.legacyApplied(ctx, s.db, id)
		if e != nil {
			return false, e
		}
		if a != nil && a.Track.CoverURL != nil {
			e = s.transaction(ctx, func(tx store.DBTX) error {
				return s.enqueueAttachment(ctx, tx, id, *a.Track.CoverURL, a.Origins["cover"])
			})
			if e != nil {
				return false, e
			}
			return s.PersistCover(ctx, id)
		}
		downloads, e := s.rows(ctx, s.db, "attachment-download", map[string]any{"songId": id})
		if e != nil {
			return false, e
		}
		if len(downloads) > 0 {
			var track map[string]any
			if e = decode(downloads[0]["track_json"], &track); e != nil {
				return false, e
			}
			if url := text(track["coverUrl"]); url != "" {
				if e = s.transaction(ctx, func(tx store.DBTX) error { return s.enqueueAttachment(ctx, tx, id, url, Origin{Kind: "DOWNLOAD"}) }); e != nil {
					return false, e
				}
				return s.PersistCover(ctx, id)
			}
		}
		// Embedded artwork already present on disk can be adopted without
		// reopening media or requiring a compute node.
		if _, err := s.embeddedCover(ctx, id); err == nil {
			if err = s.transaction(ctx, func(tx store.DBTX) error {
				return s.enqueueAttachment(ctx, tx, id, "embedded:"+id, Origin{Kind: "FILE"})
			}); err != nil {
				return false, err
			}
			return s.PersistCover(ctx, id)
		}
		return false, nil
	}
	r := rows[0]
	if text(r["status"]) == "SAVED" {
		return false, nil
	}
	url := text(r["cover_url"])
	var origin Origin
	if e = decode(r["origin_json"], &origin); e != nil {
		return false, e
	}
	var cover Cover
	switch {
	case origin.Kind == "DOWNLOAD":
		s.hookMu.RLock()
		read := s.downloadCover
		s.hookMu.RUnlock()
		if read == nil {
			e = missing("下载封面暂不可用")
		} else {
			cover, e = read(ctx, id)
		}
	case strings.HasPrefix(url, "asset:"):
		key := strings.TrimPrefix(url, "asset:")
		if metastandard.SHA256.MatchString(key) {
			_, b, err := s.library.Formal().ReadAsset(ctx, s.db, key)
			e = err
			if e == nil {
				cover, e = validateCover(b)
			}
		} else {
			cover, e = readRemoteCover(filepath.Join(s.cfg.MetadataPath, "uploads", key+".image"))
			if e != nil {
				cover, e = s.uploadedCover(key)
			}
		}
	case strings.HasPrefix(url, "embedded:"):
		var b []byte
		b, e = s.embeddedCover(ctx, strings.TrimPrefix(url, "embedded:"))
		if e == nil {
			cover, e = validateRemoteCover(b)
		}
	default:
		cover, e = s.loadCover(ctx, origin.Provider, &url)
	}
	if e != nil {
		_, _ = s.exec(ctx, s.db, "attachment-fail", map[string]any{"songId": id, "url": url, "origin": r["origin_json"], "revision": r["cover_revision"], "now": now()})
		return true, e
	}
	e = s.transaction(ctx, func(tx store.DBTX) error {
		latest, e := s.rows(ctx, tx, "attachment-read", map[string]any{"songId": id})
		if e != nil {
			return e
		}
		if len(latest) == 0 || text(latest[0]["cover_url"]) != url || text(latest[0]["origin_json"]) != text(r["origin_json"]) || text(latest[0]["cover_revision"]) != text(r["cover_revision"]) || number(latest[0]["local_decision"]) != number(r["local_decision"]) {
			return nil
		}
		song, _, e := s.library.FormalIDs(ctx, tx, id)
		if e != nil {
			return e
		}
		current, e := s.library.Formal().Read(ctx, tx, song)
		if e != nil {
			return e
		}
		if current.States["cover"].Revision != text(r["cover_revision"]) {
			_, e = s.exec(ctx, tx, "attachment-complete", map[string]any{"songId": id, "url": url, "now": now()})
			return e
		}
		asset, e := s.library.Formal().PutAsset(ctx, tx, cover.Bytes, cover.MIME)
		if e != nil {
			return e
		}
		o := metastandard.Origin{Kind: origin.Kind}
		if origin.Provider != nil {
			o.Provider = *origin.Provider
			o.EntityType = "track"
		}
		if origin.ExternalID != nil {
			o.ExternalID = *origin.ExternalID
		}
		if o.Kind == "" {
			o.Kind = "UNKNOWN"
		}
		if number(r["local_decision"]) == 1 {
			e = s.library.Formal().Decide(ctx, tx, song, map[string]any{"cover": metastandard.AssetRef{AssetID: asset.ID}}, o)
		} else {
			_, _, e = s.library.Formal().Observe(ctx, tx, song, metastore.Evidence{Origin: o, Fields: map[string]any{"cover": metastandard.AssetRef{AssetID: asset.ID}}})
		}
		if e != nil {
			return e
		}
		_, e = s.exec(ctx, tx, "attachment-complete", map[string]any{"songId": id, "url": url, "now": now()})
		return e
	})
	return true, e
}
func (s *Service) formalCover(ctx context.Context, id string) (Cover, bool, error) {
	song, variant, e := s.library.FormalIDs(ctx, s.db, id)
	if e != nil {
		return Cover{}, false, e
	}
	for _, key := range []string{variant, song} {
		d, e := s.library.Formal().Read(ctx, s.db, key)
		if e != nil {
			return Cover{}, false, e
		}
		value, state, exists := metastandard.DisplayField(d, "cover")
		if !exists {
			continue
		}
		if state.Status == "CLEARED" || state.Status == "NOT_APPLICABLE" {
			return Cover{}, true, missing("封面已清空")
		}
		ref, ok := value.(map[string]any)
		if !ok {
			continue
		}
		asset, _ := ref["assetId"].(string)
		_, data, e := s.library.Formal().ReadAsset(ctx, s.db, asset)
		if e != nil {
			return Cover{}, true, e
		}
		cover, e := validateCover(data)
		return cover, true, e
	}
	return Cover{}, false, nil
}
func (s *Service) legacyApplied(ctx context.Context, tx store.DBTX, id string) (*Applied, error) {
	rows, e := s.rows(ctx, tx, "formal-legacy", map[string]any{"songId": id})
	if e != nil || len(rows) == 0 {
		return nil, e
	}
	a := &Applied{}
	if e = decode(rows[0]["track_json"], &a.Track); e != nil {
		return nil, e
	}
	if e = decode(rows[0]["fields_json"], &a.Fields); e != nil {
		return nil, e
	}
	e = decode(rows[0]["origins_json"], &a.Origins)
	return a, e
}

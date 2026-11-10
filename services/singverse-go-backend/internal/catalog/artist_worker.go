package catalog

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/metadata"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
	"time"
)

func (s *Service) artworkBlocked(ctx context.Context, tx store.DBTX, id, kind string) (bool, error) {
	var n int
	if e := tx.QueryRowContext(ctx, s.q["a_policy"], id, kind).Scan(&n); e != nil {
		return false, e
	}
	if n > 0 {
		return true, nil
	}
	_, e := one(ctx, tx, s.q["a_image"], id, kind)
	if errors.Is(e, sql.ErrNoRows) {
		return false, nil
	}
	return e == nil, e
}
func (s *Service) storeArtwork(ctx context.Context, tx store.DBTX, id, kind string, b []byte, c ArtworkChoice, address, origin string) error {
	blocked, e := s.artworkBlocked(ctx, tx, id, kind)
	if e != nil || blocked {
		return e
	}
	if e = s.exec(ctx, tx, "a_insertImage", id, kind, sha(string(b)), b); e != nil {
		return e
	}
	if e = s.exec(ctx, tx, "a_imageSource", origin, c.Provider, c.ExternalID, address, id, kind); e != nil {
		return e
	}
	return s.exec(ctx, tx, "a_attribution", c.Attribution, id, kind)
}
func (s *Service) artworkLoop(ctx context.Context, done chan<- struct{}) {
	defer close(done)
	start := time.NewTimer(5 * time.Second)
	defer start.Stop()
	select {
	case <-ctx.Done():
		return
	case <-start.C:
	}
	ticker := time.NewTicker(2 * time.Second)
	defer ticker.Stop()
	for {
		s.processArtwork(ctx)
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
	}
}
func (s *Service) processArtwork(ctx context.Context) {
	if s.processArtworkBatch(ctx) {
		return
	}
	var job map[string]any
	var name string
	var titles []string
	refs := []metadata.ArtistRef{}
	e := store.WithinTx(ctx, s.db, func(tx store.DBTX) error {
		groups := s.artworkIndex
		if groups == nil || time.Since(s.artworkBuilt) >= 30*time.Second {
			all, e := s.ensureArtists(ctx, tx)
			if e != nil {
				return e
			}
			groups = map[string][]map[string]any{}
			for artist, songs := range all {
				groups[artist] = append([]map[string]any{}, songs[:min(len(songs), 3)]...)
			}
			s.artworkIndex = groups
			s.artworkBuilt = time.Now()
		}
		var e error
		job, e = one(ctx, tx, s.q["a_pendingImage"], now())
		if errors.Is(e, sql.ErrNoRows) {
			job = nil
			return nil
		}
		if e != nil {
			return e
		}
		id, kind := str(job["artist_id"]), str(job["image_kind"])
		songs, ok := groups[id]
		if !ok {
			e = s.exec(ctx, tx, "a_imageFetchState", "DORMANT", job["attempts"], "", id, kind, job["generation"])
			job = nil
			return e
		}
		blocked, e := s.artworkBlocked(ctx, tx, id, kind)
		if e != nil {
			return e
		}
		if blocked {
			state := "READY"
			if _, imageErr := one(ctx, tx, s.q["a_image"], id, kind); errors.Is(imageErr, sql.ErrNoRows) {
				state = "BLOCKED"
			} else if imageErr != nil {
				return imageErr
			}
			e = s.exec(ctx, tx, "a_imageFetchState", state, job["attempts"], "", id, kind, job["generation"])
			job = nil
			return e
		}
		artist, e := s.artist(ctx, tx, id)
		if e != nil {
			return e
		}
		name = str(artist["name"])
		titles = []string{}
		for i, raw := range songs {
			if i >= 3 {
				break
			}
			titles = append(titles, str(partyProjection(raw)["title"]))
			meta := obj(raw["metadata"])
			if meta["stale"] != true && str(obj(obj(meta["origins"])["artists"])["kind"]) == "PLATFORM" {
				b, _ := json.Marshal(obj(meta["track"])["artistRefs"])
				var found []metadata.ArtistRef
				if json.Unmarshal(b, &found) == nil {
					refs = append(refs, found...)
				}
			}
		}
		return nil
	})
	if e != nil || job == nil {
		return
	}
	id, kind := str(job["artist_id"]), str(job["image_kind"])

	chosen, address, data, review, e := s.lookupArtwork(ctx, name, titles, refs, kind, "AUTO")

	if ctx.Err() != nil {
		return
	}
	_, _ = s.tx(ctx, func(tx store.DBTX) (any, error) {
		if er := s.exec(ctx, tx, "a_lock"); er != nil {
			return nil, er
		}
		fresh, er := one(ctx, tx, s.q["a_imageFetch"], id, kind)
		if er != nil {
			return nil, er
		}
		if num(fresh["generation"]) != num(job["generation"]) {
			return nil, nil
		}
		attempts := num(job["attempts"])
		if e != nil {
			attempts++
			state := "RETRY"
			if attempts > 3 {
				state = "FAILED"
			}
			delay := 600
			if attempts == 2 {
				delay = 3600
			} else if attempts > 2 {
				delay = 21600
			}
			return nil, s.exec(ctx, tx, "a_imageFetchState", state, attempts, time.Now().UTC().Add(time.Duration(delay)*time.Second).Format(time.RFC3339Nano), id, kind, job["generation"])
		}
		if len(review) > 0 {
			if er = s.exec(ctx, tx, "a_imageCandidates", encode(review), id, kind, job["generation"]); er != nil {
				return nil, er
			}
		}
		if chosen != nil && data != nil {
			if er = s.storeArtwork(ctx, tx, id, kind, data, *chosen, address, "AUTO"); er != nil {
				return nil, er
			}
		}
		state := "NO_MATCH"
		if data != nil {
			state = "READY"
		} else if len(review) > 0 {
			state = "NEEDS_REVIEW"
		}
		return nil, s.exec(ctx, tx, "a_imageFetchState", state, attempts, "", id, kind, job["generation"])
	})
}

func (s *Service) lookupArtwork(ctx context.Context, name string, titles []string, refs []metadata.ArtistRef, kind, provider string) (*ArtworkChoice, string, []byte, []ArtworkChoice, error) {
	review := []ArtworkChoice{}
	var chosen *ArtworkChoice
	address := ""
	var data []byte
	var primaryError error
	var e error
	if kind == "PORTRAIT" && s.artistProvider != nil && has(provider, "AUTO", "NETEASE", "QQ", "KUGOU") {
		old, err := s.artistProvider.ArtistArtworkFrom(ctx, name, titles, refs, provider)
		primaryError = err
		if old != nil {
			for _, candidate := range old.Candidates {
				review = append(review, ArtworkChoice{Provider: candidate.Provider, ExternalID: candidate.ExternalID, Portrait: candidate.Portrait, Attribution: candidate.Provider})
			}
			address = old.Portrait
			if address != "" {
				chosen = &ArtworkChoice{Provider: old.Provider, ExternalID: old.ExternalID, Portrait: old.Portrait, Attribution: old.Provider, Verified: true}
				data, err = downloadArtwork(ctx, address)
				if err != nil {
					primaryError = err
					chosen = nil
					address = ""
				}
			}
		}
	}
	if kind == "PORTRAIT" && data == nil && len(review) == 0 && has(provider, "AUTO", "KUWO", "QISHUI") {
		for _, source := range []string{"KUWO", "QISHUI"} {
			if provider != "AUTO" && provider != source {
				continue
			}
			choices, err := s.findMusicArtwork(ctx, source, name, titles, refs)
			if err != nil {
				primaryError = err
				continue
			}
			if len(choices) > 1 || len(choices) == 1 && !choices[0].Verified {
				review = choices
				break
			}
			if len(choices) == 1 {
				chosen = &choices[0]
				address = chosen.Portrait
				data, err = downloadArtwork(ctx, address)
				if err == nil {
					return chosen, address, data, nil, nil
				}
				primaryError = err
				chosen = nil
				address = ""
			}
		}
	}
	if data == nil && len(review) == 0 && (provider == "AUTO" || provider == "THEAUDIODB") {
		var choices []ArtworkChoice
		choices, e = s.findArtwork(ctx, name, titles)
		if e == nil {
			if kind == "PORTRAIT" {
				filtered := []ArtworkChoice{}
				for _, c := range choices {
					if c.Portrait != "" {
						filtered = append(filtered, c)
					}
				}
				choices = filtered
			}
			if len(choices) > 1 || len(choices) == 1 && !choices[0].Verified {
				review = choices
			} else if len(choices) == 1 {
				chosen = &choices[0]
				address = chosen.Portrait
				if kind == "BACKGROUND" {
					address = chosen.Background
					if address == "" {
						extra, err := s.fanart(ctx, *chosen)
						if err != nil {
							e = err
						} else if extra != nil {
							chosen = extra
							address = extra.Background
						}
					}
				}
			}
			if e == nil && address != "" {
				data, e = downloadArtwork(ctx, address)
				if e != nil && kind == "BACKGROUND" && chosen != nil {
					extra, err := s.fanart(ctx, *chosen)
					if err == nil && extra != nil && extra.Background != "" {
						chosen = extra
						address = extra.Background
						data, e = downloadArtwork(ctx, address)
					}
				}
			}
			if address == "" && len(review) == 0 && primaryError != nil {
				e = primaryError
			}
		}
	}

	if e == nil && data == nil && len(review) == 0 {
		e = primaryError
	}
	return chosen, address, data, review, e
}

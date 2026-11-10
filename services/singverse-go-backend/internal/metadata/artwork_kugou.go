package metadata

import (
	"context"
	"net/url"
)

// Search artist portraits separately from album artwork. An exact name alone
// produces review candidates; a confirmed artist ID or a matching song is needed.
func (s *Service) kugouArtistArtwork(ctx context.Context, name string, titles []string, known []ArtistRef) (*Artwork, error) {
	p := &musicProvider{service: s, name: "KUGOU"}
	root, err := guarded(ctx, s, "KUGOU", func(callCtx context.Context) (map[string]any, error) {
		return p.kugouSearch(callCtx, "author", name, 20)
	})
	if err != nil {
		return nil, err
	}
	if number(root["status"]) != 1 || number(root["error_code"]) != 0 {
		return nil, &problem{503, "酷狗歌手搜索暂时不可用"}
	}
	choices := []Artwork{}
	seen := map[string]bool{}
	for _, raw := range array(path(root, "data", "lists")) {
		row, _ := raw.(map[string]any)
		id := text(row["AuthorId"])
		if artistCanonical(text(row["AuthorName"])) != artistCanonical(name) || !neteaseID.MatchString(id) || seen[id] {
			continue
		}
		portrait := text(row["Avatar"])
		checked := checkedCover("KUGOU", &portrait)
		if checked == nil {
			continue
		}
		u, e := url.Parse(*checked)
		if e != nil || u.Hostname() != "singerimg.kugou.com" {
			continue
		}
		seen[id] = true
		choices = append(choices, Artwork{Provider: "KUGOU", ExternalID: id, Portrait: *checked})
		if len(choices) >= 20 {
			break
		}
	}
	if len(choices) == 0 {
		return nil, nil
	}
	matches := map[string]bool{}
	for _, ref := range known {
		if ref.Provider == "KUGOU" && artistCanonical(ref.Name) == artistCanonical(name) {
			matches[ref.ID] = true
		}
	}
	if len(matches) == 0 {
		for _, title := range titles[:min(3, len(titles))] {
			tracks, e := guarded(ctx, s, "KUGOU", func(callCtx context.Context) ([]Track, error) { return p.Search(callCtx, name+" "+title, 20) })
			if e != nil {
				return nil, e
			}
			for _, track := range tracks {
				if track.Title == nil || artistCanonical(*track.Title) != artistCanonical(title) {
					continue
				}
				for _, ref := range track.ArtistRefs {
					if artistCanonical(ref.Name) == artistCanonical(name) {
						matches[ref.ID] = true
					}
				}
			}
		}
	}
	if len(matches) == 1 {
		for i := range choices {
			if matches[choices[i].ExternalID] {
				return &choices[i], nil
			}
		}
	}
	// Conflicting confirmed IDs and same-name search hits both require confirmation.
	return &Artwork{Candidates: choices}, nil
}

package catalog

import (
	"context"
	"encoding/json"
	"errors"
	"html"
	"net/url"
	"regexp"
	"strings"

	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/metadata"
)

var musicArtistID = regexp.MustCompile(`^[0-9]{1,20}$`)
var kuwoImageHost = regexp.MustCompile(`^img[1-4]\.kuwo\.cn$`)
var qishuiImageURI = regexp.MustCompile(`^[A-Za-z0-9_/-]{1,400}$`)
var qishuiImageTemplate = regexp.MustCompile(`^tplv-[A-Za-z0-9]+$`)

func musicID(value any) string {
	if number, ok := value.(json.Number); ok {
		return number.String()
	}
	return str(value)
}

// These are read-only, public artist catalogues. They do not expose a browser
// proxy, accept an arbitrary upstream URL, or require a user's platform cookie.
func (s *Service) findMusicArtwork(ctx context.Context, provider, name string, titles []string, refs []metadata.ArtistRef) ([]ArtworkChoice, error) {
	var choices []ArtworkChoice
	var err error
	if provider == "KUWO" {
		choices, err = kuwoArtistChoices(ctx, name)
	} else {
		choices, err = qishuiArtistChoices(ctx, name)
	}
	if err != nil || len(choices) == 0 {
		return choices, err
	}
	matched := map[string]bool{}
	for _, ref := range refs {
		if ref.Provider == provider && canonical(ref.Name) == canonical(name) && musicArtistID.MatchString(ref.ID) {
			matched[ref.ID] = true
		}
	}
	if len(matched) == 0 {
		for _, title := range titles[:min(3, len(titles))] {
			var ids []string
			if provider == "KUWO" {
				ids, err = kuwoSongArtists(ctx, name, title)
			} else {
				ids, err = qishuiSongArtists(ctx, name, title)
			}
			if err != nil {
				return nil, err
			}
			for _, id := range ids {
				matched[id] = true
			}
		}
	}
	// A second matching ID is an ambiguity, even when only one has a portrait.
	if len(matched) == 1 {
		for i := range choices {
			if matched[choices[i].ExternalID] {
				choices[i].Verified = true
				return choices[i : i+1], nil
			}
		}
	}
	return choices, nil
}

func kuwoSearch(ctx context.Context, keyword, kind string) (map[string]any, error) {
	q := url.Values{"all": {keyword}, "ft": {kind}, "rformat": {"json"}, "encoding": {"utf8"}, "pn": {"0"}, "rn": {"20"}}
	root, err := sourceJSON(ctx, "https://search.kuwo.cn/r.s?"+q.Encode(), "search.kuwo.cn", "")
	if err != nil {
		return nil, err
	}
	if root["TOTAL"] == nil {
		return nil, errors.New("kuwo artist source unavailable")
	}
	return root, nil
}

func kuwoArtistChoices(ctx context.Context, name string) ([]ArtworkChoice, error) {
	root, err := kuwoSearch(ctx, name, "artist")
	if err != nil {
		return nil, err
	}
	if num(root["TOTAL"]) == 0 && len(list(root["abslist"])) == 0 {
		return nil, nil
	}
	base, err := url.Parse(str(root["BASEPICPATH"]))
	if err != nil || base == nil || !has(base.Scheme, "http", "https") || !kuwoImageHost.MatchString(base.Hostname()) || base.Port() != "" || base.User != nil {
		return nil, errors.New("invalid kuwo artist image base")
	}
	base.Scheme = "https"
	out := []ArtworkChoice{}
	seen := map[string]bool{}
	for _, raw := range list(root["abslist"]) {
		row := obj(raw)
		id := musicID(row["ARTISTID"])
		if canonical(html.UnescapeString(str(row["ARTIST"]))) != canonical(name) || !musicArtistID.MatchString(id) || seen[id] {
			continue
		}
		path, e := url.Parse(str(row["PICPATH"]))
		if e != nil || path == nil || path.Path == "" || path.IsAbs() || path.Host != "" || path.User != nil || path.RawQuery != "" || path.Fragment != "" {
			continue
		}
		address := base.ResolveReference(path)
		if !kuwoImageHost.MatchString(address.Hostname()) {
			continue
		}
		seen[id] = true
		out = append(out, ArtworkChoice{Provider: "KUWO", ExternalID: id, Portrait: address.String(), Attribution: "酷我音乐"})
		if len(out) >= 20 {
			break
		}
	}
	return out, nil
}

func kuwoSongArtists(ctx context.Context, name, title string) ([]string, error) {
	root, err := kuwoSearch(ctx, name+" "+title, "music")
	if err != nil {
		return nil, err
	}
	out := []string{}
	for _, raw := range list(root["abslist"]) {
		row := obj(raw)
		song := str(row["SONGNAME"])
		if song == "" {
			song = str(row["NAME"])
		}
		id := musicID(row["ARTISTID"])
		if canonical(html.UnescapeString(song)) == canonical(title) && musicArtistID.MatchString(id) {
			out = append(out, id)
		}
	}
	return out, nil
}

func qishuiSearch(ctx context.Context, keyword, kind string) (map[string]any, error) {
	q := url.Values{"aid": {"386088"}, "app_name": {"luna"}, "region": {"cn"}, "device_platform": {"android"}, "version_name": {"3.0.0"}, "version_code": {"30000000"}, "q": {keyword}, "cursor": {"0"}, "count": {"20"}}
	root, err := sourceJSON(ctx, "https://api.qishui.com/luna/search/"+kind+"?"+q.Encode(), "api.qishui.com", "")
	if err != nil {
		return nil, err
	}
	if num(root["status_code"]) != 0 || num(obj(root["status_info"])["code"]) != 0 || root["result_groups"] == nil {
		return nil, errors.New("qishui artist source unavailable")
	}
	return root, nil
}

func qishuiPortrait(raw any) string {
	image := obj(raw)
	uri, template := str(image["uri"]), str(image["template_prefix"])
	for _, rawURL := range list(image["urls"]) {
		u, err := url.Parse(str(rawURL))
		if err != nil || u.Scheme != "https" || u.User != nil || u.Port() != "" || !has(u.Hostname(), "p3-luna.douyinpic.com", "p6-luna.douyinpic.com") {
			continue
		}
		if u.Path == "/img/" && qishuiImageURI.MatchString(uri) && qishuiImageTemplate.MatchString(template) && u.RawQuery == "" && u.Fragment == "" {
			return u.String() + uri + "~" + template + "-resize:640:640.png"
		}
		if strings.HasPrefix(u.Path, "/img/") && (strings.HasSuffix(u.Path, ".png") || strings.HasSuffix(u.Path, ".jpg") || strings.HasSuffix(u.Path, ".jpeg")) {
			return u.String()
		}
	}
	return ""
}

func qishuiArtistChoices(ctx context.Context, name string) ([]ArtworkChoice, error) {
	root, err := qishuiSearch(ctx, name, "artist")
	if err != nil {
		return nil, err
	}
	out := []ArtworkChoice{}
	seen := map[string]bool{}
	for _, group := range list(root["result_groups"]) {
		for _, raw := range list(obj(group)["data"]) {
			artist := obj(obj(obj(raw)["entity"])["artist"])
			id := musicID(artist["id"])
			portrait := qishuiPortrait(artist["url_avatar"])
			if canonical(str(artist["name"])) == canonical(name) && musicArtistID.MatchString(id) && !seen[id] && portrait != "" {
				seen[id] = true
				out = append(out, ArtworkChoice{Provider: "QISHUI", ExternalID: id, Portrait: portrait, Attribution: "汽水音乐"})
				if len(out) >= 20 {
					return out, nil
				}
			}
		}
	}
	return out, nil
}

func qishuiSongArtists(ctx context.Context, name, title string) ([]string, error) {
	root, err := qishuiSearch(ctx, name+" "+title, "track")
	if err != nil {
		return nil, err
	}
	out := []string{}
	for _, group := range list(root["result_groups"]) {
		for _, raw := range list(obj(group)["data"]) {
			track := obj(obj(obj(raw)["entity"])["track"])
			if canonical(str(track["name"])) != canonical(title) {
				continue
			}
			for _, rawArtist := range list(track["artists"]) {
				artist := obj(rawArtist)
				id := musicID(artist["id"])
				if canonical(str(artist["name"])) == canonical(name) && musicArtistID.MatchString(id) {
					out = append(out, id)
				}
			}
		}
	}
	return out, nil
}

package metadata

import (
	"context"
	"html"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"time"
)

var publicMusicID = regexp.MustCompile(`^[0-9]{1,20}$`)
var kuwoAlbumPath = regexp.MustCompile(`^[0-9]+/[A-Za-z0-9_/-]+\.(jpg|jpeg|png)$`)
var lunaImageURI = regexp.MustCompile(`^[A-Za-z0-9_/-]{1,400}$`)
var lunaImageTemplate = regexp.MustCompile(`^tplv-[A-Za-z0-9]+$`)

// Public catalogue metadata only: no platform login, playback URL or media bytes.
func (p *musicProvider) searchKuwo(ctx context.Context, keyword string, limit int) ([]Track, error) {
	q := url.Values{"all": {keyword}, "ft": {"music"}, "rformat": {"json"}, "encoding": {"utf8"}, "pn": {"0"}, "rn": {strconv.Itoa(limit)}, "client": {"kt"}}
	root, err := p.request(ctx, "GET", "https://search.kuwo.cn/r.s?"+q.Encode(), nil, map[string]string{"Referer": "https://www.kuwo.cn/"})
	if err != nil {
		return nil, err
	}
	if root["TOTAL"] == nil || root["abslist"] == nil {
		return nil, &problem{503, "酷我搜索暂时不可用"}
	}
	return parseKuwo(array(root["abslist"]), limit), nil
}

func publicText(row map[string]any, fields ...string) *string {
	value := first(row, fields...)
	if value == nil {
		return nil
	}
	cleaned, err := clean(strings.ReplaceAll(html.UnescapeString(*value), "\u00a0", " "), 500)
	if err != nil || cleaned == "" {
		return nil
	}
	return &cleaned
}

func kuwoAlbumCover(row map[string]any) *string {
	if direct := checkedCover("KUWO", first(row, "albumpic", "pic", "img")); direct != nil {
		return direct
	}
	// This field is an album cover, never substitute web_artistpic_short or MVPIC.
	path := text(row["web_albumpic_short"])
	if !kuwoAlbumPath.MatchString(path) || strings.Contains(path, "..") {
		return nil
	}
	path = "0/" + strings.SplitN(path, "/", 2)[1]
	return ptr("https://img1.kuwo.cn/star/albumcover/" + path)
}

func parseKuwo(rows []any, limit int) []Track {
	out := []Track{}
	for _, raw := range rows {
		row, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		id := strings.TrimPrefix(text(row["MUSICRID"]), "MUSIC_")
		if !publicMusicID.MatchString(id) {
			continue
		}
		var duration *int
		if n := number(row["DURATION"]); n > 0 && n <= 86400 {
			duration = ptr(int(n * 1000))
		}
		var release *string
		if value := publicText(row, "RELEASEDATE", "RELEASE_DATE", "web_timingonline"); value != nil && len(*value) >= 10 {
			if _, err := time.Parse("2006-01-02", (*value)[:10]); err == nil {
				release = ptr((*value)[:10])
			}
		}
		artists := splitArtists(publicText(row, "ARTIST"))
		t := track("KUWO", &id, publicText(row, "SONGNAME", "NAME"), artists, publicText(row, "ALBUM"), duration, release, nil, kuwoAlbumCover(row), "UNKNOWN")
		if t == nil {
			continue
		}
		artistID := text(row["ARTISTID"])
		if len(artists) == 1 && publicMusicID.MatchString(artistID) && artistID != "0" {
			t.ArtistRefs = []ArtistRef{{Provider: "KUWO", ID: artistID, Name: artists[0]}}
		}
		out = append(out, *t)
		if len(out) >= limit {
			break
		}
	}
	return out
}

func (p *musicProvider) searchQishui(ctx context.Context, keyword string, limit int) ([]Track, error) {
	q := url.Values{"aid": {"386088"}, "app_name": {"luna"}, "region": {"cn"}, "device_platform": {"android"}, "version_name": {"3.0.0"}, "version_code": {"30000000"}, "q": {keyword}, "cursor": {"0"}, "count": {strconv.Itoa(limit)}}
	root, err := p.request(ctx, "GET", "https://api.qishui.com/luna/search/track?"+q.Encode(), nil, map[string]string{"User-Agent": "Luna/19.1.0 Android"})
	if err != nil {
		return nil, err
	}
	if number(root["status_code"]) != 0 || number(path(root, "status_info", "code")) != 0 || root["result_groups"] == nil {
		return nil, &problem{503, "汽水搜索暂时不可用"}
	}
	return parseQishui(array(root["result_groups"]), limit), nil
}

func qishuiAlbumCover(raw any) *string {
	image, _ := raw.(map[string]any)
	uri, template := text(image["uri"]), text(image["template_prefix"])
	for _, rawURL := range array(image["urls"]) {
		u, err := url.Parse(text(rawURL))
		if err != nil || u.Scheme != "https" || u.User != nil || u.Port() != "" || u.Fragment != "" || !contains([]string{"p3-luna.douyinpic.com", "p6-luna.douyinpic.com"}, u.Hostname()) {
			continue
		}
		if u.Path == "/img/" && u.RawQuery == "" && lunaImageURI.MatchString(uri) && !strings.Contains(uri, "..") && lunaImageTemplate.MatchString(template) {
			return ptr(u.String() + uri + "~" + template + "-image.image")
		}
		if strings.HasPrefix(u.Path, "/img/") && (strings.HasSuffix(u.Path, ".jpg") || strings.HasSuffix(u.Path, ".png") || strings.HasSuffix(u.Path, ".image")) {
			return checkedCover("QISHUI", ptr(u.String()))
		}
	}
	return nil
}

func parseQishui(groups []any, limit int) []Track {
	out := []Track{}
	for _, group := range groups {
		for _, raw := range array(path(group, "data")) {
			row, ok := path(raw, "entity", "track").(map[string]any)
			if !ok || !publicMusicID.MatchString(text(row["id"])) {
				continue
			}
			album, _ := row["album"].(map[string]any)
			var duration *int
			if n := number(row["duration"]); n > 0 && n <= 86_400_000 {
				duration = ptr(int(n))
			}
			var release *string
			if n := number(album["release_date"]); n > 0 && n < 253402300800 {
				release = ptr(time.Unix(n, 0).UTC().Format("2006-01-02"))
			}
			t := track("QISHUI", first(row, "id"), publicText(row, "name"), names(row["artists"], "name"), publicText(album, "name"), duration, release, nil, qishuiAlbumCover(album["url_cover"]), "UNKNOWN")
			if t != nil {
				t.ArtistRefs = refs(row["artists"], "QISHUI", "id")
				out = append(out, *t)
			}
			if len(out) >= limit {
				return out
			}
		}
	}
	return out
}

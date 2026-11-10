package catalog

import (
	"context"
	"encoding/json"
	"errors"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
	"io"
	"net"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"sync"
	"time"
)

type ArtworkChoice struct {
	Provider    string `json:"provider"`
	ExternalID  string `json:"externalId"`
	MBID        string `json:"mbid"`
	Portrait    string `json:"portrait"`
	Background  string `json:"background"`
	Attribution string `json:"attribution"`
	Verified    bool   `json:"verified"`
}

var sourceGate struct {
	sync.Mutex
	next map[string]time.Time
}
var sourceClient = &http.Client{Transport: &http.Transport{Proxy: nil, TLSHandshakeTimeout: 3 * time.Second, IdleConnTimeout: 90 * time.Second, DialContext: func(ctx context.Context, network, address string) (net.Conn, error) {
	host, port, e := net.SplitHostPort(address)
	if e != nil {
		return nil, e
	}
	ips, e := net.DefaultResolver.LookupIPAddr(ctx, host)
	if e != nil {
		return nil, e
	}
	for _, a := range ips {
		if !a.IP.IsGlobalUnicast() || a.IP.IsPrivate() || a.IP.IsLoopback() || a.IP.IsLinkLocalUnicast() {
			return nil, errors.New("nonpublic image address")
		}
	}
	var last error
	for _, a := range ips {
		c, e := (&net.Dialer{Timeout: 3 * time.Second}).DialContext(ctx, network, net.JoinHostPort(a.IP.String(), port))
		if e == nil {
			return c, nil
		}
		last = e
	}
	if last == nil {
		last = errors.New("empty DNS")
	}
	return nil, last
}}, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}

func sourceJSON(ctx context.Context, address, host, key string) (map[string]any, error) {
	u, e := url.Parse(address)
	if e != nil || u.Scheme != "https" || u.Hostname() != host || u.Port() != "" || u.User != nil {
		return nil, errors.New("invalid source address")
	}
	sourceGate.Lock()
	defer sourceGate.Unlock()
	if sourceGate.next == nil {
		sourceGate.next = map[string]time.Time{}
	}
	if d := time.Until(sourceGate.next[host]); d > 0 {
		timer := time.NewTimer(d)
		defer timer.Stop()
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-timer.C:
		}
	}
	delay := 2 * time.Second
	if host == "musicbrainz.org" {
		delay = 1100 * time.Millisecond
	}
	sourceGate.next[host] = time.Now().Add(delay)
	ctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	r, e := http.NewRequestWithContext(ctx, "GET", address, nil)
	if e != nil {
		return nil, e
	}
	r.Header.Set("Accept", "application/json")
	r.Header.Set("User-Agent", "SingVerse/0.1 (https://github.com/mynora-dot/SingVerse)")
	if key != "" {
		r.Header.Set("client-key", key)
	}
	resp, e := sourceClient.Do(r)
	if e != nil {
		return nil, e
	}
	defer resp.Body.Close()
	if resp.StatusCode == 404 {
		return map[string]any{}, nil
	}
	if resp.StatusCode != 200 {
		return nil, errors.New("artwork source status")
	}
	b, e := io.ReadAll(io.LimitReader(resp.Body, 2000001))
	if e != nil || len(b) > 2000000 {
		return nil, errors.New("artwork source too large")
	}
	var root map[string]any
	e = json.Unmarshal(b, &root)
	return root, e
}
func list(v any) []any          { a, _ := v.([]any); return a }
func canonical(s string) string { return strings.ToLower(artistName(s)) }
func recording(ctx context.Context, mbid string, titles []string) (bool, error) {
	for i, title := range titles {
		if i >= 3 {
			break
		}
		root, e := sourceJSON(ctx, "https://musicbrainz.org/ws/2/recording/?query="+url.QueryEscape("recording:\""+title+"\" AND arid:"+mbid)+"&fmt=json&limit=5", "musicbrainz.org", "")
		if e != nil {
			return false, e
		}
		for _, raw := range list(root["recordings"]) {
			if canonical(str(obj(raw)["title"])) == canonical(title) {
				return true, nil
			}
		}
	}
	return false, nil
}
func (s *Service) findArtwork(ctx context.Context, name string, titles []string) ([]ArtworkChoice, error) {
	out := []ArtworkChoice{}
	audio := func(row map[string]any) error {
		id := str(row["idArtist"])
		if !regexp.MustCompile(`^[0-9]{1,20}$`).MatchString(id) {
			return nil
		}
		mbid := str(row["strMusicBrainzID"])
		verified := false
		if uuidPattern.MatchString(mbid) {
			var e error
			verified, e = recording(ctx, mbid, titles)
			if e != nil {
				return e
			}
		}
		out = append(out, ArtworkChoice{"THEAUDIODB", id, mbid, str(row["strArtistThumb"]), str(row["strArtistFanart"]), "TheAudioDB", verified})
		return nil
	}
	root, e := sourceJSON(ctx, "https://www.theaudiodb.com/api/v1/json/123/search.php?s="+url.QueryEscape(name), "www.theaudiodb.com", "")
	if e != nil {
		return nil, e
	}
	for _, raw := range list(root["artists"]) {
		r := obj(raw)
		if canonical(str(r["strArtist"])) == canonical(name) {
			if e = audio(r); e != nil {
				return nil, e
			}
		}
	}
	verified := false
	for _, c := range out {
		verified = verified || c.Verified
	}
	if !verified {
		root, e = sourceJSON(ctx, "https://musicbrainz.org/ws/2/artist/?query="+url.QueryEscape("alias:\""+name+"\"")+"&fmt=json&limit=10", "musicbrainz.org", "")
		if e != nil {
			return nil, e
		}
		for _, raw := range list(root["artists"]) {
			r := obj(raw)
			mbid := str(r["id"])
			alias := canonical(str(r["name"])) == canonical(name)
			for _, a := range list(r["aliases"]) {
				if canonical(str(obj(a)["name"])) == canonical(name) {
					alias = true
				}
			}
			if !uuidPattern.MatchString(mbid) || !alias {
				continue
			}
			matched, e := recording(ctx, mbid, titles)
			if e != nil {
				return nil, e
			}
			if !matched {
				continue
			}
			found, e := sourceJSON(ctx, "https://www.theaudiodb.com/api/v1/json/123/artist-mb.php?i="+url.QueryEscape(mbid), "www.theaudiodb.com", "")
			if e != nil {
				return nil, e
			}
			before := len(out)
			for _, raw := range list(found["artists"]) {
				r := obj(raw)
				if strings.EqualFold(mbid, str(r["strMusicBrainzID"])) {
					if e = audio(r); e != nil {
						return nil, e
					}
				}
			}
			if len(out) == before {
				out = append(out, ArtworkChoice{"MUSICBRAINZ", mbid, mbid, "", "", "MusicBrainz", true})
			}
		}
	}
	unique := []ArtworkChoice{}
	seen := map[string]bool{}
	for _, c := range out {
		k := encode(c)
		if !seen[k] {
			seen[k] = true
			unique = append(unique, c)
		}
	}
	return unique, nil
}
func (s *Service) fanart(ctx context.Context, c ArtworkChoice) (*ArtworkChoice, error) {
	var encrypted *string
	if e := s.db.QueryRowContext(ctx, s.q["a_fanartKey"]).Scan(&encrypted); e != nil {
		return nil, e
	}
	if encrypted == nil || !c.Verified || !uuidPattern.MatchString(c.MBID) {
		return nil, nil
	}
	key, e := s.cipher.Decrypt(*encrypted, "singverse:fanart-personal:v1")
	if e != nil {
		return nil, e
	}
	root, e := sourceJSON(ctx, "https://webservice.fanart.tv/v3.2/music/"+c.MBID, "webservice.fanart.tv", key)
	if e != nil {
		return nil, e
	}
	for _, raw := range list(root["artistbackground"]) {
		u := str(obj(raw)["url"])
		if u != "" {
			return &ArtworkChoice{"FANART", c.MBID, c.MBID, "", u, "fanart.tv", true}, nil
		}
	}
	return nil, nil
}
func downloadArtwork(ctx context.Context, address string) ([]byte, error) {
	u, e := url.Parse(address)
	if e != nil {
		return nil, e
	}
	host := strings.ToLower(u.Hostname())
	allowed := has(host, "y.gtimg.cn", "music.126.net", "www.theaudiodb.com", "assets.fanart.tv", "fanart.tv") || regexp.MustCompile(`^p[1-4]\.music\.126\.net$`).MatchString(host) || regexp.MustCompile(`^r[0-9]\.theaudiodb\.com$`).MatchString(host)
	if u.Scheme != "https" || u.User != nil || u.Port() != "" || !allowed {
		return nil, errors.New("invalid artwork address")
	}
	ctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	req, e := http.NewRequestWithContext(ctx, "GET", address, nil)
	if e != nil {
		return nil, e
	}
	response, e := sourceClient.Do(req)
	if e != nil {
		return nil, e
	}
	defer response.Body.Close()
	if response.StatusCode != 200 {
		return nil, errors.New("artwork fetch failed")
	}
	b, e := io.ReadAll(io.LimitReader(response.Body, 2000001))
	if e != nil {
		return nil, e
	}
	// Some upstream CDNs label valid PNG content as image/jpg. Treat the
	// remote header as untrusted; sniff bytes, then fully decode and validate.
	// Uploads retain their separate, strict declared-type validation.
	return normalizeImage(b, http.DetectContentType(b))
}
func (s *Service) candidates(ctx context.Context, tx store.DBTX, id, kind string) ([]ArtworkChoice, error) {
	k, e := imageKind(kind)
	if e != nil {
		return nil, e
	}
	if _, e = s.artist(ctx, tx, id); e != nil {
		return nil, e
	}
	r, e := one(ctx, tx, s.q["a_imageFetch"], id, k)
	if e != nil {
		return nil, e
	}
	if r["candidates_json"] == nil {
		return []ArtworkChoice{}, nil
	}
	return decode[[]ArtworkChoice](r["candidates_json"])
}
func (s *Service) candidate(ctx context.Context, tx store.DBTX, id, kind, provider, external string) (ArtworkChoice, error) {
	choices, e := s.candidates(ctx, tx, id, kind)
	if e != nil {
		return ArtworkChoice{}, e
	}
	for _, c := range choices {
		if c.Provider == provider && c.ExternalID == external {
			return c, nil
		}
	}
	return ArtworkChoice{}, fail(404, "图片候选不存在")
}
func (s *Service) selectArtwork(ctx context.Context, id, kind, provider, external string) (any, error) {
	choice, e := s.candidate(ctx, s.db, id, kind, provider, external)
	if e != nil {
		return nil, e
	}
	address := choice.Portrait
	if kind == "background" {
		address = choice.Background
	}
	if address == "" {
		return nil, fail(400, "候选没有此类图片")
	}
	b, e := downloadArtwork(ctx, address)
	if e != nil {
		return nil, fail(502, "候选图片读取失败")
	}
	return s.tx(ctx, func(tx store.DBTX) (any, error) {
		if e := s.exec(ctx, tx, "a_lock"); e != nil {
			return nil, e
		}
		if _, e := s.candidate(ctx, tx, id, kind, provider, external); e != nil {
			return nil, fail(409, "图片候选已变化，请刷新")
		}
		k, _ := imageKind(kind)
		blocked, e := s.artworkBlocked(ctx, tx, id, k)
		if e != nil {
			return nil, e
		}
		if blocked {
			return nil, fail(409, "已有图片或暂停补图，请刷新")
		}
		if e = s.storeArtwork(ctx, tx, id, k, b, choice, address, "SELECTED"); e != nil {
			return nil, e
		}
		r, e := one(ctx, tx, s.q["a_imageFetch"], id, k)
		if e != nil {
			return nil, e
		}
		if e = s.exec(ctx, tx, "a_imageFetchState", "READY", 0, "", id, k, r["generation"]); e != nil {
			return nil, e
		}
		r, e = s.artist(ctx, tx, id)
		if e != nil {
			return nil, e
		}
		return artistProfile(r)
	})
}

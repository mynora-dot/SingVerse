// Provider protocol adapters are adapted from ktv-home (MIT); see docs/third-party/ktv-home-LICENSE.txt.
package metadata

import (
	"context"
	"crypto/aes"
	"crypto/md5"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/jsondata"
)

type providerGuard struct {
	permit   chan struct{}
	next     time.Time
	cooldown time.Time
}
type musicProvider struct {
	service *Service
	name    string
}

func guarded[T any](ctx context.Context, s *Service, name string, action func(context.Context) (T, error)) (T, error) {
	var zero T
	guard := s.guards[name]
	if guard == nil {
		return zero, &problem{503, "平台暂时不可用"}
	}
	waitCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	select {
	case guard.permit <- struct{}{}:
	case <-waitCtx.Done():
		return zero, &problem{503, "平台繁忙，请稍后重试"}
	}
	defer func() { <-guard.permit }()
	if time.Now().Before(guard.cooldown) {
		return zero, &problem{429, "平台限流冷却中，请一分钟后重试"}
	}
	if duration := time.Until(guard.next); duration > 0 {
		timer := time.NewTimer(duration)
		defer timer.Stop()
		select {
		case <-ctx.Done():
			return zero, ctx.Err()
		case <-timer.C:
		}
	}
	cfg, e := s.settings(ctx, s.db, true)
	if e != nil {
		return zero, e
	}
	if !contains(cfg.Providers, name) {
		return zero, conflict("平台已停用")
	}
	guard.next = time.Now().Add(1500 * time.Millisecond)
	callCtx, stop := context.WithTimeout(ctx, 5*time.Second)
	defer stop()
	result, e := action(callCtx)
	if code(e) == 429 {
		guard.cooldown = time.Now().Add(time.Minute)
	}
	return result, e
}
func (s *Service) callProvider(ctx context.Context, name string, p Provider, keyword string) ([]Track, error) {
	if p == nil {
		return nil, &problem{503, "平台暂时不可用"}
	}
	return guarded(ctx, s, name, func(callCtx context.Context) ([]Track, error) { return p.Search(callCtx, keyword, 8) })
}

func eapi(path, body string) string {
	sum := md5.Sum([]byte("nobody" + path + "use" + body + "md5forencrypt"))
	data := []byte(path + "-36cd479b6b5-" + body + "-36cd479b6b5-" + hex.EncodeToString(sum[:]))
	padding := aes.BlockSize - len(data)%aes.BlockSize
	for i := 0; i < padding; i++ {
		data = append(data, byte(padding))
	}
	block, _ := aes.NewCipher([]byte("e82ckenh8dichen8"))
	out := make([]byte, len(data))
	for i := 0; i < len(data); i += aes.BlockSize {
		block.Encrypt(out[i:i+aes.BlockSize], data[i:i+aes.BlockSize])
	}
	return strings.ToUpper(hex.EncodeToString(out))
}
func (p *musicProvider) request(ctx context.Context, method, target string, form url.Values, headers map[string]string) (map[string]any, error) {
	u, e := url.Parse(target)
	allowed := map[string][]string{"NETEASE": {"interfacepc.music.163.com", "music.163.com"}, "QQ": {"c.y.qq.com"}, "KUGOU": {"gateway.kugou.com", "songsearch.kugou.com"}, "KUWO": {"search.kuwo.cn"}, "QISHUI": {"api.qishui.com"}}
	if e != nil || u.Scheme != "https" || u.User != nil || u.Port() != "" || !contains(allowed[p.name], u.Hostname()) {
		return nil, bad("平台请求地址无效")
	}
	var body io.Reader
	if form != nil {
		body = strings.NewReader(form.Encode())
	}
	req, e := http.NewRequestWithContext(ctx, method, u.String(), body)
	if e != nil {
		return nil, e
	}
	req.Header.Set("Accept", "application/json")
	req.Header.Set("User-Agent", "SingVerse/0.1 metadata-only")
	if form != nil {
		req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	}
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	resp, e := p.service.client.Do(req)
	if e != nil {
		return nil, &problem{503, "平台连接失败或响应无效，请稍后重试"}
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		if resp.StatusCode == 429 {
			return nil, &problem{429, "平台返回限流，请稍后重试"}
		}
		return nil, &problem{503, fmt.Sprintf("平台返回 HTTP %d", resp.StatusCode)}
	}
	data, e := io.ReadAll(io.LimitReader(resp.Body, 2_000_001))
	if e != nil || len(data) > 2_000_000 {
		return nil, &problem{503, "平台响应无效"}
	}
	var root map[string]any
	text := string(data)
	if p.name == "KUWO" {
		text, e = jsondata.NormalizeSingleQuoted(text)
		if e != nil {
			return nil, &problem{503, "酷我响应无效"}
		}
	}
	d := json.NewDecoder(strings.NewReader(text))
	d.UseNumber()
	if d.Decode(&root) != nil || root == nil {
		return nil, &problem{503, "平台响应无效"}
	}
	var extra any
	if d.Decode(&extra) != io.EOF {
		return nil, &problem{503, "平台响应无效"}
	}
	return root, nil
}
func (p *musicProvider) Search(ctx context.Context, keyword string, limit int) ([]Track, error) {
	limit = min(50, limit)
	switch p.name {
	case "NETEASE":
		data := map[string]any{"s": keyword, "type": 1, "limit": limit, "offset": 0, "total": true, "header": map[string]any{"os": "pc", "appver": "3.1.0", "requestId": strconv.FormatInt(time.Now().UnixMilli(), 10)}}
		root, e := p.request(ctx, "POST", "https://interfacepc.music.163.com/eapi/cloudsearch/pc", url.Values{"params": {eapi("/api/cloudsearch/pc", encode(data))}}, map[string]string{"Referer": "https://music.163.com/"})
		if e != nil {
			return nil, e
		}
		if number(root["code"]) != 200 {
			return nil, &problem{503, "网易云搜索暂时不可用"}
		}
		return parseNetease(array(path(root, "result", "songs")), limit), nil
	case "QQ":
		q := url.Values{"format": {"json"}, "outCharset": {"utf-8"}, "ct": {"24"}, "qqmusic_ver": {"1298"}, "remoteplace": {"txt.yqq.song"}, "platform": {"yqq.json"}, "aggr": {"1"}, "cr": {"1"}, "p": {"1"}, "n": {strconv.Itoa(limit)}, "w": {keyword}}
		root, e := p.request(ctx, "GET", "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?"+q.Encode(), nil, nil)
		if e != nil {
			return nil, e
		}
		if number(root["code"]) != 0 {
			return nil, &problem{503, "QQ 音乐搜索暂时不可用"}
		}
		return parseQQ(array(path(root, "data", "song", "list")), limit), nil
	case "KUGOU":
		root, e := p.kugouSearch(ctx, "song", keyword, limit)
		if e != nil {
			return nil, e
		}
		if number(root["status"]) != 1 || number(root["error_code"]) != 0 {
			return nil, &problem{503, "酷狗搜索暂时不可用"}
		}
		songs := path(root, "data", "info")
		if songs == nil {
			songs = path(root, "data", "lists")
		}
		return parseKugou(array(songs), limit), nil
	case "KUWO":
		return p.searchKuwo(ctx, keyword, limit)
	case "QISHUI":
		return p.searchQishui(ctx, keyword, limit)
	}
	return nil, bad("平台无效")
}
func kugouSignature(q url.Values) string {
	const salt = "OIlwieks28dk2k092lksi2UIkp"
	keys := []string{}
	for k := range q {
		if k != "signature" {
			keys = append(keys, k)
		}
	}
	sort.Strings(keys)
	var b strings.Builder
	b.WriteString(salt)
	for _, k := range keys {
		b.WriteString(k)
		b.WriteByte('=')
		b.WriteString(q.Get(k))
	}
	b.WriteString(salt)
	sum := md5.Sum([]byte(b.String()))
	return hex.EncodeToString(sum[:])
}
func path(v any, keys ...string) any {
	for _, k := range keys {
		m, ok := v.(map[string]any)
		if !ok {
			return nil
		}
		v = m[k]
	}
	return v
}
func array(v any) []any { a, _ := v.([]any); return a }
func first(m map[string]any, fields ...string) *string {
	for _, f := range fields {
		if v := text(m[f]); v != "" {
			if c, e := clean(v, 500); e == nil && c != "" {
				return &c
			}
		}
	}
	return nil
}
func names(v any, key string) []string {
	out := []string{}
	for _, item := range array(v) {
		if key != "" {
			item = path(item, key)
		}
		s, ok := item.(string)
		if !ok {
			continue
		}
		s, e := clean(s, 200)
		if e == nil && s != "" && !contains(out, s) {
			out = append(out, s)
		}
		if len(out) >= 20 {
			break
		}
	}
	return out
}
func splitArtists(v *string) []string {
	if v == nil {
		return []string{}
	}
	return namesFromStrings(strings.FieldsFunc(*v, func(r rune) bool { return strings.ContainsRune("/&、,，;；", r) }))
}
func namesFromStrings(v []string) []string {
	out := []string{}
	for _, n := range v {
		n = strings.TrimSpace(n)
		if n != "" && !contains(out, n) {
			out = append(out, n)
		}
	}
	return out
}
func checkedCover(provider string, raw *string) *string {
	if raw == nil {
		return nil
	}
	v := strings.Replace(*raw, "http://", "https://", 1)
	u, e := url.Parse(v)
	allowed := coverHosts[provider]
	if e != nil || u.Scheme != "https" || u.User != nil || u.Port() != "" || u.Fragment != "" || u.Hostname() == "" {
		return nil
	}
	for _, h := range allowed {
		host := strings.ToLower(u.Hostname())
		if host == h || strings.HasSuffix(host, "."+h) {
			return &v
		}
	}
	return nil
}
func track(provider string, id, title *string, artists []string, album *string, duration *int, release *string, aliases []string, cover *string, availability string) *Track {
	if id == nil || title == nil || *id == "" || *title == "" || len(*id) > 160 {
		return nil
	}
	if duration != nil && (*duration < 1 || *duration > 86_400_000) {
		duration = nil
	}
	if artists == nil {
		artists = []string{}
	}
	if aliases == nil {
		aliases = []string{}
	}
	return &Track{Provider: ptr(provider), ExternalID: id, Title: title, Artists: artists, Album: album, DurationMs: duration, ReleaseDate: release, Aliases: aliases, CoverURL: canonicalCover(ptr(provider), cover), Availability: availability, FetchedAt: now(), ArtistRefs: []ArtistRef{}}
}
func refs(v any, provider, idfield string) []ArtistRef {
	out := []ArtistRef{}
	for _, s := range array(v) {
		m, ok := s.(map[string]any)
		if !ok {
			continue
		}
		id, name := text(m[idfield]), text(m["name"])
		if id != "" && name != "" && len(id) <= 160 && len([]rune(name)) <= 200 {
			out = append(out, ArtistRef{provider, id, name})
		}
	}
	return out
}
func parseNetease(songs []any, limit int) []Track {
	out := []Track{}
	for _, s := range songs {
		m, ok := s.(map[string]any)
		if !ok {
			continue
		}
		al := m["al"]
		if al == nil {
			al = m["album"]
		}
		album, _ := al.(map[string]any)
		ar := m["ar"]
		if ar == nil {
			ar = m["artists"]
		}
		aliases := m["alia"]
		if aliases == nil {
			aliases = m["alias"]
		}
		duration := m["dt"]
		if duration == nil {
			duration = m["duration"]
		}
		var d *int
		if duration != nil {
			d = ptr(int(number(duration)))
		}
		var release *string
		if n := number(m["publishTime"]); n > 0 {
			release = ptr(time.UnixMilli(n).UTC().Format("2006-01-02"))
		}
		available := "AVAILABLE"
		if number(m["fee"]) == 4 {
			available = "UNAVAILABLE"
		}
		t := track("NETEASE", first(m, "id"), first(m, "name"), names(ar, "name"), first(album, "name"), d, release, names(aliases, ""), first(album, "picUrl"), available)
		if t != nil {
			t.ArtistRefs = refs(ar, "NETEASE", "id")
			out = append(out, *t)
		}
		if len(out) >= limit {
			break
		}
	}
	return out
}
func parseQQ(songs []any, limit int) []Track {
	out := []Track{}
	for _, s := range songs {
		m, ok := s.(map[string]any)
		if !ok {
			continue
		}
		singers := m["singer"]
		if singers == nil {
			singers = m["singername"]
		}
		artists := names(singers, "name")
		if _, ok := singers.(string); ok {
			v := text(singers)
			artists = splitArtists(&v)
		}
		album, _ := m["album"].(map[string]any)
		an := first(m, "albumname")
		if an == nil {
			an = first(album, "name")
		}
		mid := first(m, "albummid")
		if mid == nil {
			mid = first(album, "mid")
		}
		var cover *string
		if mid != nil {
			cover = ptr("https://y.gtimg.cn/music/photo_new/T002R300x300M000" + *mid + ".jpg")
		}
		var d *int
		duration := m["interval"]
		if duration == nil {
			duration = m["duration"]
		}
		if duration != nil {
			d = ptr(int(number(duration)) * 1000)
		}
		var release *string
		if n := number(m["pubtime"]); n > 0 {
			release = ptr(time.Unix(n, 0).UTC().Format("2006-01-02"))
		}
		t := track("QQ", first(m, "songmid", "mid"), first(m, "songname", "name", "title"), artists, an, d, release, names(m["grp"], "title"), cover, "AVAILABLE")
		if t != nil {
			t.ArtistRefs = refs(singers, "QQ", "mid")
			out = append(out, *t)
		}
		if len(out) >= limit {
			break
		}
	}
	return out
}
func parseKugou(songs []any, limit int) []Track {
	out := []Track{}
	for _, s := range songs {
		m, ok := s.(map[string]any)
		if !ok {
			continue
		}
		artists := names(m["authors"], "author_name")
		if len(artists) == 0 {
			artists = splitArtists(first(m, "singername", "author_name", "singer_name", "SingerName"))
		}
		cover := first(m, "sizable_cover", "album_sizable_cover", "img", "Image", "AlbumImage")
		if cover != nil {
			cover = ptr(strings.ReplaceAll(*cover, "{size}", "0"))
		}
		var d *int
		for _, f := range []string{"duration", "Duration", "timelength"} {
			if m[f] != nil {
				v := int(number(m[f]))
				if f != "timelength" {
					v *= 1000
				}
				d = &v
				break
			}
		}
		t := track("KUGOU", first(m, "hash", "audio_id", "audioid", "FileHash"), first(m, "songname", "song_name", "name", "SongName", "OriSongName"), artists, first(m, "album_name", "albumname", "AlbumName"), d, first(m, "publish_date", "publish_time", "PublishDate"), []string{}, cover, "AVAILABLE")
		if t != nil {
			t.ArtistRefs = refs(m["Singers"], "KUGOU", "id")
			for _, raw := range array(m["authors"]) {
				a, _ := raw.(map[string]any)
				id, name := text(a["author_id"]), text(a["author_name"])
				if neteaseID.MatchString(id) && name != "" {
					t.ArtistRefs = append(t.ArtistRefs, ArtistRef{"KUGOU", id, name})
				}
			}
			out = append(out, *t)
		}
		if len(out) >= limit {
			break
		}
	}
	return out
}

func (p *musicProvider) kugouSearch(ctx context.Context, kind, keyword string, limit int) (map[string]any, error) {
	route := "v3/search/song"
	if kind == "author" {
		route = "v1/search/author"
	}
	mid := p.service.deviceID
	at := strconv.FormatInt(time.Now().Unix(), 10)
	q := url.Values{"dfid": {"-"}, "mid": {mid}, "uuid": {"-"}, "appid": {"1005"}, "clientver": {"20489"}, "clienttime": {at}, "albumhide": {"0"}, "iscorrection": {"1"}, "keyword": {keyword}, "nocollect": {"0"}, "page": {"1"}, "pagesize": {strconv.Itoa(limit)}, "platform": {"AndroidFilter"}}
	q.Set("signature", kugouSignature(q))
	root, e := p.request(ctx, "GET", "https://gateway.kugou.com/"+route+"?"+q.Encode(), nil, map[string]string{"x-router": "complexsearch.kugou.com", "dfid": "-", "mid": mid, "clienttime": at, "User-Agent": "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi"})
	if e != nil {
		return nil, e
	}
	if kind == "song" && number(root["error_code"]) == 152 {
		q = url.Values{"keyword": {keyword}, "page": {"1"}, "pagesize": {strconv.Itoa(limit)}, "userid": {"-1"}, "clientver": {""}, "platform": {"WebFilter"}, "filter": {"2"}, "iscorrection": {"1"}, "privilege_filter": {"0"}}
		root, e = p.request(ctx, "GET", "https://songsearch.kugou.com/song_search_v2?"+q.Encode(), nil, nil)
		if e != nil {
			return nil, e
		}
	}

	return root, nil
}

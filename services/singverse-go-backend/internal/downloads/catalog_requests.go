package downloads

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/downloads/lxruntime"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/search"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"unicode/utf8"
)

func (c *publicCatalog) Search(ctx context.Context, platform, query string, page int) (CatalogPage, error) {
	if platforms[platform] == "" || strings.TrimSpace(query) == "" || search.UTF16Length(query) > 100 || page < 1 || page > 100 {
		return CatalogPage{}, bad("请输入 1–100 个字的歌名或歌手，页码最多 100")
	}
	var root map[string]any
	var rows any
	var total int64
	var e error
	switch platform {
	case "wy":
		body := url.Values{"s": {query}, "type": {"1"}, "limit": {"20"}, "offset": {strconv.Itoa((page - 1) * 20)}}
		root, e = c.send(ctx, "https://music.163.com/api/search/get/web", platform, "POST", map[string]string{"Content-Type": "application/x-www-form-urlencoded"}, []byte(body.Encode()))
		rows = at(root, "result", "songs")
		total = integer(at(root, "result", "songCount"), 0)
	case "tx":
		root, e = c.get(ctx, catalogURL("https://c.y.qq.com/soso/fcgi-bin/client_search_cp", "w", query, "p", page, "n", 20, "format", "json"), platform)
		song := asObject(at(root, "data", "song"))
		if e == nil && song["curpage"] != nil && integer(song["curpage"], 0) != int64(page) {
			e = unavailable("平台未返回新的搜索页，请稍后再试")
		}
		rows = song["list"]
		total = integer(song["totalnum"], 0)
	case "kg":
		root, e = c.get(ctx, catalogURL("https://songsearch.kugou.com/song_search_v2", "keyword", query, "page", page, "pagesize", 20, "platform", "WebFilter", "format", "json"), platform)
		data := asObject(root["data"])
		expanded := []any{}
		for _, r := range asArray(data["lists"]) {
			expanded = append(expanded, r)
			expanded = append(expanded, asArray(asObject(r)["Grp"])...)
		}
		rows = expanded
		total = integer(data["total"], 0)
	case "kw":
		root, e = c.get(ctx, catalogURL("https://search.kuwo.cn/r.s", "all", query, "pn", page-1, "rn", 20, "client", "kt", "ft", "music", "rformat", "json", "encoding", "utf8"), platform)
		rows = root["abslist"]
		total = integer(root["TOTAL"], 0)
	case "mg":
		root, e = c.get(ctx, catalogURL("https://app.c.nf.migu.cn/MIGUM2.0/v1.0/content/search_all.do", "text", query, "pageNo", page, "pageSize", 20, "searchSwitch", `{"song":1}`), platform)
		data := root["songResultData"]
		if data == nil {
			data = at(root, "data", "songResultData")
		}
		flat := []any{}
		for _, r := range asArray(first(data, "result", "resultList", "songList")) {
			if a, ok := r.([]any); ok {
				flat = append(flat, a...)
			} else {
				flat = append(flat, r)
			}
		}
		rows = flat
		total = integer(asObject(data)["totalCount"], 0)
	}
	if e != nil {
		return CatalogPage{}, e
	}
	return catalogPage(platform, rows, total, int64(page*20) < total, "")
}

var shareLink = regexp.MustCompile(`(?i)https?://[^\s<>“”"']+`)
var catalogDigits = regexp.MustCompile(`^[0-9]{1,100}$`)

func catalogPlatform(address string) (string, error) {
	u, e := url.Parse(address)
	if e != nil || u.Hostname() == "" || u.User != nil || !contains([]string{"http", "https"}, u.Scheme) || u.Port() != "" && u.Port() != map[string]string{"http": "80", "https": "443"}[u.Scheme] {
		return "", bad("请使用音乐平台的公开分享链接")
	}
	host := strings.ToLower(u.Hostname())
	for p, domains := range map[string][]string{"wy": {"music.163.com", "163cn.tv"}, "tx": {"y.qq.com", "qqmusic.qq.com", "qm.qq.com"}, "kg": {"kugou.com"}, "kw": {"kuwo.cn"}, "mg": {"migu.cn"}} {
		for _, d := range domains {
			if host == d || strings.HasSuffix(host, "."+d) {
				return p, nil
			}
		}
	}
	return "", bad("目前支持网易云、QQ、酷狗、酷我和咪咕的歌单或专辑链接")
}

var refPath = regexp.MustCompile(`(?i)(?:album(?:_?detail)?|playlist(?:_?detail)?|playsquare|songlist|special/single|playlist/index)/(?:detail/|info/)?([A-Za-z0-9_]+)(?:\.html|/|$)`)
var playlistKind = regexp.MustCompile(`playlist|songlist|playsquare|special|diss|listdetail|list_details`)

func parseCatalogReference(address, platform string) *CatalogReference {
	u, e := url.Parse(address)
	if e != nil {
		return nil
	}
	full := u.Path + u.Fragment
	lower := strings.ToLower(full)
	kind := ""
	if strings.Contains(lower, "album") {
		kind = "album"
	} else if playlistKind.MatchString(lower) {
		kind = "playlist"
	}
	q := u.Query()
	if i := strings.IndexByte(u.Fragment, '?'); i >= 0 {
		fragment, _ := url.ParseQuery(u.Fragment[i+1:])
		for key, values := range fragment {
			q[key] = values
		}
	}
	if kind == "" {
		for _, k := range []string{"global_collection_id", "gcid", "disstid"} {
			if q.Has(k) {
				kind = "playlist"
			}
		}
		if kind == "" && (q.Has("albumId") || q.Has("albummid")) {
			kind = "album"
		}
	}
	if kind == "" {
		return nil
	}
	keys := []string{"global_collection_id", "gcid", "specialid", "disstid", "playlistId", "pid", "id"}
	if kind == "album" {
		keys = []string{"albumId", "albumid", "albummid", "id"}
	}
	id := ""
	for _, key := range keys {
		if q.Has(key) {
			id = q.Get(key)
			break
		}
	}
	if id == "" {
		m := refPath.FindStringSubmatch(full)
		if len(m) > 1 {
			id = m[1]
		}
	}
	pattern := `^[0-9]+$`
	if platform == "kg" {
		pattern = `^(?:[0-9]+|gcid_[A-Za-z0-9]+|collection_[A-Za-z0-9_]+)$`
	} else if platform == "tx" {
		pattern = `^[A-Za-z0-9]+$`
	}
	if id == "" || len(id) > 100 || !regexp.MustCompile(pattern).MatchString(id) {
		return nil
	}
	return &CatalogReference{platform, kind, id}
}
func (c *publicCatalog) Reference(ctx context.Context, input string) (CatalogReference, error) {
	if len(input) > 4096 {
		return CatalogReference{}, bad("请粘贴歌单或专辑的分享链接")
	}
	address := shareLink.FindString(input)
	if address == "" {
		return CatalogReference{}, bad("未识别到分享链接，请从音乐平台复制歌单或专辑链接")
	}
	address = strings.TrimRight(address, "。，、；！？）)]】")
	p, e := catalogPlatform(address)
	if e != nil {
		return CatalogReference{}, e
	}
	if ref := parseCatalogReference(address, p); ref != nil {
		return *ref, nil
	}
	clean := strings.SplitN(address, "#", 2)[0]
	if _, e = lxruntime.CheckPublicURL(clean, false); e != nil {
		return CatalogReference{}, bad("分享链接无效")
	}
	response, e := c.network.Request(ctx, clean, "GET", map[string]string{"User-Agent": "Mozilla/5.0", "Referer": clean}, nil, 1024*1024, false)
	if e != nil {
		return CatalogReference{}, e
	}
	final, e := catalogPlatform(response.URL)
	if e != nil {
		return CatalogReference{}, e
	}
	if final != p {
		return CatalogReference{}, bad("分享链接跳转到了其他平台，请复制原始歌单或专辑链接")
	}
	if ref := parseCatalogReference(response.URL, p); ref != nil {
		return *ref, nil
	}
	body := strings.ReplaceAll(strings.ReplaceAll(string(response.Bytes), `\/`, "/"), "&amp;", "&")
	for _, link := range embeddedShareLink.FindAllString(body, -1) {
		if len(link) > 2048 {
			continue
		}
		other, e := catalogPlatform(link)
		if e == nil && other == p {
			if ref := parseCatalogReference(link, p); ref != nil {
				return *ref, nil
			}
		}
	}
	if p == "kg" {
		matches := regexp.MustCompile(`(?:global_collection_id|global_specialid|gcid)["']?\s*[:=]\s*["'](gcid_[A-Za-z0-9]+|collection_[A-Za-z0-9_]+)`).FindStringSubmatch(body)
		if len(matches) > 1 {
			return CatalogReference{"kg", "playlist", matches[1]}, nil
		}
	}
	return CatalogReference{}, bad("未识别到歌单或专辑，请检查链接是否公开，或复制完整分享链接")
}
func (c *publicCatalog) Detail(ctx context.Context, track Track) (Track, error) {
	p, id := track.Platform, track.RemoteID
	var root map[string]any
	var rows any
	var e error
	switch p {
	case "wy":
		if !catalogDigits.MatchString(id) {
			return Track{}, bad("歌曲编号无效")
		}
		root, e = c.get(ctx, catalogURL("https://music.163.com/api/v3/song/detail", "c", encode([]any{map[string]any{"id": json.Number(id)}})), p)
		rows = root["songs"]
	case "tx":
		root, e = c.get(ctx, catalogURL("https://c.y.qq.com/v8/fcg-bin/fcg_play_single_song.fcg", "songmid", id, "format", "json"), p)
		rows = root["data"]
	case "kw":
		root, e = c.get(ctx, catalogURL("https://search.kuwo.cn/r.s", "rid", "MUSIC_"+id, "ft", "music", "client", "kt", "rformat", "json", "encoding", "utf8"), p)
		rows = first(root, "musiclist", "abslist")
	case "kg":
		root, e = c.get(ctx, catalogURL("https://www.kugou.com/yy/index.php", "r", "play/getdata", "hash", id), p)
		rows = []any{root["data"]}
	case "mg":
		root, e = c.get(ctx, catalogURL("https://app.u.nf.migu.cn/MIGUM3.0/resource/song/by-songids/v2.0", "songId", id), p)
		rows = root["data"]
	default:
		return Track{}, bad("不支持的平台")
	}
	if e != nil {
		return Track{}, e
	}
	list := asArray(rows)
	if len(list) == 0 {
		return Track{}, unavailable("平台未返回歌曲详情")
	}
	detailed, e := mapCatalogTrack(p, asObject(list[0]))
	if e != nil {
		return Track{}, unavailable("平台未返回歌曲详情")
	}
	if detailed.RemoteID != id {
		return Track{}, unavailable("平台返回的歌曲不匹配，请重新搜索")
	}
	return detailed, nil
}
func (c *publicCatalog) Lyrics(ctx context.Context, track Track) (string, error) {
	p, id := track.Platform, track.RemoteID
	var root map[string]any
	var e error
	switch p {
	case "wy":
		root, e = c.get(ctx, catalogURL("https://music.163.com/api/song/lyric", "id", id, "lv", 1, "kv", 1, "tv", -1), p)
		return valueText(at(root, "lrc", "lyric")), e
	case "tx":
		root, e = c.get(ctx, catalogURL("https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg", "songmid", id, "format", "json", "nobase64", 1), p)
		return valueText(root["lyric"]), e
	case "kw":
		root, e = c.get(ctx, catalogURL("https://mlyric.kuwo.cn/newh5/singles/songinfoandlrc", "musicId", id), p)
		if e != nil {
			return "", e
		}
		var out strings.Builder
		for _, line := range asArray(at(root, "data", "lrclist")) {
			row := asObject(line)
			seconds, _ := strconv.ParseFloat(valueText(row["time"]), 64)
			if seconds < 0 || seconds > 604800 {
				continue
			}
			fmt.Fprintf(&out, "[%02d:%05.2f]%s\n", int(seconds/60), mathRemainder(seconds, 60), valueText(row["lineLyric"]))
		}
		return out.String(), nil
	case "kg":
		duration := int64(0)
		if track.DurationMS != nil {
			duration = *track.DurationMS
		}
		root, e = c.get(ctx, catalogURL("https://lyrics.kugou.com/search", "ver", 1, "man", "yes", "client", "pc", "hash", id, "keyword", track.Title, "duration", duration), p)
		if e != nil {
			return "", e
		}
		rows := asArray(root["candidates"])
		if len(rows) == 0 {
			return "", nil
		}
		candidate := asObject(rows[0])
		root, e = c.get(ctx, catalogURL("https://lyrics.kugou.com/download", "ver", 1, "client", "pc", "fmt", "lrc", "charset", "utf8", "id", valueText(candidate["id"]), "accesskey", valueText(candidate["accesskey"])), p)
		if e != nil {
			return "", e
		}
		decoded, e := base64.StdEncoding.DecodeString(valueText(root["content"]))
		if e != nil || !utf8.Valid(decoded) {
			return "", nil
		}
		return string(decoded), nil
	case "mg":
		address := catalogText(track.MusicInfo["meta"], "lrcUrl")
		if address == "" {
			return "", nil
		}
		u, e := lxruntime.CheckPublicURL(address, true)
		if e != nil || !strings.HasSuffix(strings.ToLower(u.Hostname()), ".migu.cn") {
			return "", nil
		}
		r, e := c.network.Request(ctx, address, "GET", nil, nil, 256*1024, true)
		if e != nil {
			return "", e
		}
		if r.Status == 200 && utf8.Valid(r.Bytes) {
			return string(r.Bytes), nil
		}
		return "", nil
	}
	return "", nil
}
func mathRemainder(v, denominator float64) float64 {
	return v - float64(int64(v/denominator))*denominator
}

var embeddedShareLink = regexp.MustCompile(`https?://[^\s<>"']+`)

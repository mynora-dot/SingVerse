package downloads

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/downloads/lxruntime"
	"html"
	"io"
	"math"
	"net/url"
	"regexp"
	"strconv"
	"strings"
)

type catalogNetwork interface {
	Request(context.Context, string, string, map[string]string, []byte, int, bool) (Reply, error)
}
type publicCatalog struct{ network catalogNetwork }

func newCatalog(network *Network) Catalog { return &publicCatalog{network} }
func unavailable(message string) error    { return &problem{502, message} }
func asObject(v any) map[string]any       { m, _ := v.(map[string]any); return m }
func asArray(v any) []any                 { a, _ := v.([]any); return a }
func at(v any, keys ...string) any {
	for _, k := range keys {
		v = asObject(v)[k]
	}
	return v
}
func first(v any, keys ...string) any {
	m := asObject(v)
	for _, k := range keys {
		if p, ok := m[k]; ok && p != nil {
			return p
		}
	}
	return nil
}
func valueText(v any) string {
	if v == nil {
		return ""
	}
	switch s := v.(type) {
	case string:
		return s
	case json.Number:
		return s.String()
	case int:
		return strconv.Itoa(s)
	case int64:
		return strconv.FormatInt(s, 10)
	case float64:
		return strconv.FormatFloat(s, 'f', -1, 64)
	case bool:
		return strconv.FormatBool(s)
	}
	return ""
}
func catalogText(v any, keys ...string) string { return valueText(first(v, keys...)) }
func integer(v any, defaultValue int64) int64 {
	if v == nil {
		return defaultValue
	}
	n, e := strconv.ParseInt(valueText(v), 10, 64)
	if e == nil {
		return n
	}
	f, e := strconv.ParseFloat(valueText(v), 64)
	if e != nil {
		return defaultValue
	}
	return int64(f)
}
func catalogURL(base string, kv ...any) string {
	u, e := url.Parse(base)
	if e != nil {
		panic(e)
	}
	q := u.Query()
	for i := 0; i < len(kv); i += 2 {
		q.Add(fmt.Sprint(kv[i]), fmt.Sprint(kv[i+1]))
	}
	u.RawQuery = q.Encode()
	return u.String()
}

var jsonp = regexp.MustCompile(`(?s)^[A-Za-z_$][\w.$]*\s*\(.*\);?\s*$`)

func (c *publicCatalog) send(ctx context.Context, address, platform, method string, extra map[string]string, body []byte) (map[string]any, error) {
	referer := "https://www.kuwo.cn/"
	if platform == "tx" {
		referer = "https://y.qq.com/"
	} else if platform == "wy" {
		referer = "https://music.163.com/"
	}
	h := map[string]string{"Referer": referer, "User-Agent": "Mozilla/5.0", "Accept": "application/json"}
	for k, v := range extra {
		h[k] = v
	}
	r, e := c.network.Request(ctx, address, method, h, body, 8*1024*1024, false)
	if e != nil {
		return nil, e
	}
	if r.Status != 200 {
		if r.Status == 401 || r.Status == 403 {
			return nil, unavailable("该目录需要权限，请检查是否公开或更换平台")
		}
		return nil, unavailable("平台请求失败，请稍后重试")
	}
	text := strings.TrimSpace(string(r.Bytes))
	if jsonp.MatchString(text) {
		text = text[strings.IndexByte(text, '(')+1 : strings.LastIndexByte(text, ')')]
	}
	if platform == "kw" {
		text, e = singleQuotedJSON(text)
		if e != nil {
			return nil, unavailable("平台响应发生变化，请稍后重试或更换平台")
		}
	}
	dec := json.NewDecoder(strings.NewReader(text))
	dec.UseNumber()
	var root map[string]any
	if e = dec.Decode(&root); e != nil || root == nil {
		return nil, unavailable("平台响应发生变化，请稍后重试或更换平台")
	}
	var extraValue any
	if dec.Decode(&extraValue) != io.EOF {
		return nil, unavailable("平台响应发生变化，请稍后重试或更换平台")
	}
	code := valueText(root["code"])
	if platform == "wy" && code != "200" || platform == "tx" && code != "" && code != "0" || platform == "kg" && root["status"] != nil && integer(root["status"], 0) != 1 || platform == "mg" && code != "" && !contains([]string{"000000", "0", "200", "00000000"}, code) {
		return nil, unavailable("该目录暂不可读取，可能已删除或需要登录权限")
	}
	return root, nil
}
func (c *publicCatalog) get(ctx context.Context, address, platform string) (map[string]any, error) {
	return c.send(ctx, address, platform, "GET", nil, nil)
}

// Single quoted JSON is parsed as data; provider text is never executed.
func singleQuotedJSON(s string) (string, error) {
	var out strings.Builder
	for i := 0; i < len(s); {
		if s[i] == '"' {
			start := i
			i++
			for i < len(s) {
				if s[i] == '\\' {
					i += 2
					continue
				}
				if s[i] == '"' {
					i++
					break
				}
				i++
			}
			if i > len(s) {
				return "", errors.New("invalid string")
			}
			out.WriteString(s[start:i])
			continue
		}
		if s[i] != '\'' {
			out.WriteByte(s[i])
			i++
			continue
		}
		i++
		var v strings.Builder
		closed := false
		for i < len(s) {
			ch := s[i]
			i++
			if ch == '\'' {
				closed = true
				break
			}
			if ch != '\\' {
				v.WriteByte(ch)
				continue
			}
			if i >= len(s) {
				return "", errors.New("invalid escape")
			}
			esc := s[i]
			i++
			switch esc {
			case '\'', '\\', '/':
				v.WriteByte(esc)
			case '"':
				v.WriteByte('"')
			case 'n':
				v.WriteByte('\n')
			case 'r':
				v.WriteByte('\r')
			case 't':
				v.WriteByte('\t')
			case 'b':
				v.WriteByte('\b')
			case 'f':
				v.WriteByte('\f')
			case 'u':
				if i+4 > len(s) {
					return "", errors.New("invalid unicode")
				}
				quoted := "\"\\u" + s[i:i+4] + "\""
				var decoded string
				if json.Unmarshal([]byte(quoted), &decoded) != nil {
					return "", errors.New("invalid unicode")
				}
				v.WriteString(decoded)
				i += 4
			default:
				return "", errors.New("invalid escape")
			}
		}
		if !closed {
			return "", errors.New("unclosed string")
		}
		b, _ := json.Marshal(v.String())
		out.Write(b)
	}
	return out.String(), nil
}

var htmlTag = regexp.MustCompile(`<[^>]*>`)

func cleanCatalog(v string) string {
	v = html.UnescapeString(htmlTag.ReplaceAllString(v, ""))
	v = strings.Map(func(r rune) rune {
		if r >= 0 && r <= 31 {
			return ' '
		}
		return r
	}, v)
	v = strings.TrimSpace(v)
	units := 0
	for offset, r := range v {
		length := 1
		if r > 0xffff {
			length = 2
		}
		if units+length > 300 {
			v = v[:offset]
			break
		}
		units += length
	}
	return v
}
func safeCatalogCover(v string) string {
	if strings.HasPrefix(v, "//") {
		v = "https:" + v
	}
	u, e := lxruntime.CheckPublicURL(v, false)
	if e != nil {
		return ""
	}
	return u.String()
}
func catalogArtists(v any) []string {
	out := []string{}
	add := func(s string) {
		s = cleanCatalog(s)
		if s != "" && !contains(out, s) {
			out = append(out, s)
		}
	}
	if a, ok := v.([]any); ok {
		for _, row := range a {
			if _, ok := row.(map[string]any); ok {
				add(catalogText(row, "name", "singerName"))
			} else {
				add(valueText(row))
			}
		}
	} else if s, ok := v.(string); ok {
		for _, name := range strings.FieldsFunc(s, func(r rune) bool { return strings.ContainsRune(";&、", r) }) {
			add(name)
		}
	}
	if len(out) == 0 {
		return []string{"未知歌手"}
	}
	return out
}
func durationMillis(v any, seconds bool) *int64 {
	s := valueText(v)
	var n float64
	var e error
	if strings.Contains(s, ":") {
		for _, part := range strings.Split(s, ":") {
			p, err := strconv.ParseFloat(part, 64)
			if err != nil {
				return nil
			}
			n = n*60 + p
		}
		n *= 1000
	} else {
		n, e = strconv.ParseFloat(s, 64)
		if e != nil {
			return nil
		}
		if seconds {
			n *= 1000
		}
	}
	if math.IsNaN(n) || math.IsInf(n, 0) || n <= 0 || n >= 604800000 {
		return nil
	}
	ms := int64(n)
	return &ms
}
func quality(rows *[]any, kind string, size any, hash string) {
	n := integer(size, 0)
	if n <= 0 && hash == "" {
		return
	}
	q := map[string]any{"type": kind}
	if n > 0 {
		q["size"] = n
	}
	if hash != "" && hash != "present" {
		q["hash"] = hash
	}
	*rows = append(*rows, q)
}
func mapCatalogTrack(p string, row map[string]any) (Track, error) {
	var id, title, album, cover, albumID string
	var singers any
	var duration *int64
	meta := map[string]any{}
	qs := []any{}
	switch p {
	case "wy":
		id = catalogText(row, "id")
		title = catalogText(row, "name")
		singers = first(row, "artists", "ar")
		al := first(row, "album", "al")
		album = catalogText(al, "name")
		albumID = catalogText(al, "id")
		cover = catalogText(al, "picUrl", "blurPicUrl")
		duration = durationMillis(first(row, "dt", "duration"), false)
		meta["songId"] = row["id"]
	case "tx":
		id = catalogText(row, "songmid", "mid")
		title = catalogText(row, "songname", "title", "name")
		singers = row["singer"]
		al := row["album"]
		album = catalogText(row, "albumname")
		if album == "" {
			album = catalogText(al, "name")
		}
		albumID = catalogText(row, "albummid")
		if albumID == "" {
			albumID = catalogText(al, "mid")
		}
		if albumID != "" {
			cover = "https://y.gtimg.cn/music/photo_new/T002R300x300M000" + albumID + ".jpg"
		}
		duration = durationMillis(row["interval"], true)
		meta["songId"] = first(row, "songid", "id")
		meta["albumMid"] = albumID
		media := catalogText(row, "strMediaMid", "media_mid")
		if media == "" {
			media = catalogText(row["file"], "media_mid")
		}
		meta["strMediaMid"] = media
		file := any(row)
		if row["file"] != nil {
			file = row["file"]
		}
		quality(&qs, "128k", first(file, "size128", "size_128mp3"), "")
		quality(&qs, "320k", first(file, "size320", "size_320mp3"), "")
		quality(&qs, "flac", first(file, "sizeflac", "size_flac"), "")
	case "kg":
		id = strings.ToUpper(catalogText(row, "FileHash", "hash"))
		if !regexp.MustCompile(`^[A-F0-9]{32}$`).MatchString(id) {
			return Track{}, errors.New("invalid hash")
		}
		filename := catalogText(row, "FileName", "filename")
		title = catalogText(row, "SongName", "songname", "audio_name")
		artist := catalogText(row, "SingerName", "singername", "author_name")
		if title == "" {
			parts := strings.SplitN(filename, " - ", 2)
			title = filename
			if len(parts) == 2 {
				title = parts[1]
				if artist == "" {
					artist = parts[0]
				}
			}
		}
		singers = artist
		album = catalogText(row, "AlbumName", "album_name")
		albumID = catalogText(row, "AlbumID", "album_id")
		cover = strings.ReplaceAll(catalogText(row, "imgurl"), "{size}", "400")
		duration = durationMillis(first(row, "Duration", "duration", "timelength"), row["timelength"] == nil)
		meta["hash"] = id
		quality(&qs, "128k", first(row, "FileSize", "size"), id)
		quality(&qs, "320k", first(row, "HQFileSize", "320filesize"), catalogText(row, "HQFileHash", "hash_320", "320hash"))
		quality(&qs, "flac", first(row, "SQFileSize", "sqfilesize"), catalogText(row, "SQFileHash", "hash_flac", "sqhash"))
	case "kw":
		id = strings.TrimPrefix(catalogText(row, "MUSICRID", "musicrid", "rid", "id"), "MUSIC_")
		title = catalogText(row, "SONGNAME", "name", "songname")
		singers = first(row, "ARTIST", "artist", "singer")
		album = catalogText(row, "ALBUM", "album")
		albumID = catalogText(row, "ALBUMID", "albumid", "albumId")
		cover = catalogText(row, "albumpic", "pic", "img")
		duration = durationMillis(first(row, "DURATION", "duration"), true)
		meta["songId"] = id
		formats := catalogText(row, "FORMATS", "formats", "KFORMATS")
		for _, q := range []struct{ format, kind string }{{"MP3128", "128k"}, {"MP3H", "320k"}, {"FLAC", "flac"}} {
			if strings.Contains(formats, q.format) {
				quality(&qs, q.kind, nil, "present")
			}
		}
	case "mg":
		id = catalogText(row, "songId", "id")
		copyright := catalogText(row, "copyrightId")
		if copyright == "" {
			return Track{}, errors.New("missing copyright id")
		}
		title = catalogText(row, "songName", "name")
		singers = first(row, "singerList", "singers", "singer")
		var al any
		albums := asArray(row["albums"])
		if len(albums) > 0 {
			al = albums[0]
		}
		album = catalogText(row, "album", "albumName")
		if album == "" {
			album = catalogText(al, "name")
		}
		albumID = catalogText(row, "albumId")
		if albumID == "" {
			albumID = catalogText(al, "id")
		}
		cover = catalogText(row, "img1", "img", "imageUrl", "largePic")
		images := asArray(row["imgItems"])
		if cover == "" && len(images) > 0 {
			cover = catalogText(images[0], "img", "imgUrl")
		}
		if strings.HasPrefix(cover, "/") && !strings.HasPrefix(cover, "//") {
			cover = "https://d.musicapp.migu.cn" + cover
		}
		duration = durationMillis(row["duration"], true)
		meta["songId"] = id
		meta["copyrightId"] = copyright
		meta["lrcUrl"] = catalogText(row, "lrcUrl", "lyricUrl")
		for _, format := range asArray(first(row, "audioFormats", "newRateFormats", "rateFormats")) {
			q := map[string]string{"PQ": "128k", "HQ": "320k", "SQ": "flac", "ZQ": "flac24bit", "ZQ24": "flac24bit"}[catalogText(format, "formatType")]
			if q != "" {
				quality(&qs, q, asObject(format)["size"], "present")
			}
		}
	default:
		return Track{}, errors.New("unknown platform")
	}
	names := catalogArtists(singers)
	title = cleanCatalog(title)
	if strings.TrimSpace(id) == "" || len(id) > 200 || title == "" {
		return Track{}, errors.New("missing track identity")
	}
	meta["albumName"] = cleanCatalog(album)
	meta["albumId"] = albumID
	meta["qualitys"] = qs
	qualityMap := map[string]any{}
	for _, q := range qs {
		m := asObject(q)
		qualityMap[valueText(m["type"])] = q
	}
	meta["_qualitys"] = qualityMap
	music := map[string]any{}
	for k, v := range row {
		music[k] = v
	}
	music["source"] = p
	music["songmid"] = id
	music["name"] = title
	music["singer"] = strings.Join(names, " / ")
	music["meta"] = meta
	if p == "kg" {
		music["hash"] = id
	}
	if p == "tx" {
		music["strMediaMid"] = meta["strMediaMid"]
	}
	return Track{hash(p + ":" + id), p, id, title, names, cleanCatalog(album), duration, safeCatalogCover(cover), music}, nil
}
func catalogPage(platform string, raw any, total int64, more bool, warning string) (CatalogPage, error) {
	rows, ok := raw.([]any)
	if !ok && total > 0 {
		return CatalogPage{}, unavailable("平台响应发生变化，请稍后重试")
	}
	tracks := []Track{}
	seen := map[string]bool{}
	skipped := 0
	for _, row := range rows {
		track, e := mapCatalogTrack(platform, asObject(row))
		if e != nil {
			skipped++
			continue
		}
		if !seen[track.ID] {
			seen[track.ID] = true
			tracks = append(tracks, track)
		}
	}
	if skipped > 0 {
		if warning != "" {
			warning += "；"
		}
		warning += fmt.Sprintf("%d 首缺少必要的歌曲信息，已略过", skipped)
	}
	if len(rows) > 0 && len(tracks) == 0 {
		return CatalogPage{}, unavailable("平台没有返回可解析的歌曲信息，请稍后再试")
	}
	return CatalogPage{tracks, more, warning}, nil
}

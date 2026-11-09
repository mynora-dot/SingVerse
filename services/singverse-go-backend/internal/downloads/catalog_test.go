package downloads

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/url"
	"strings"
	"testing"
	"time"
)

type catalogFixture struct {
	route  func(*url.URL) (any, error)
	last   *url.URL
	method string
	body   string
	calls  int
}

func (n *catalogFixture) Request(ctx context.Context, address, method string, headers map[string]string, body []byte, limit int, httpsOnly bool) (Reply, error) {
	u, e := url.Parse(address)
	if e != nil {
		return Reply{}, e
	}
	n.last, n.method, n.body = u, method, string(body)
	n.calls++
	v, e := n.route(u)
	if e != nil {
		return Reply{}, e
	}
	if r, ok := v.(Reply); ok {
		return r, nil
	}
	if raw, ok := v.(string); ok {
		return Reply{Status: 200, Bytes: []byte(raw), URL: address}, nil
	}
	b, _ := json.Marshal(v)
	return Reply{Status: 200, Bytes: b, URL: address}, nil
}
func catalogRow(platform string) map[string]any {
	raw := map[string]string{"wy": `{"id":101,"name":"测试歌曲","ar":[{"id":2,"name":"测试歌手"}],"al":{"id":3,"name":"测试专辑"},"dt":180000}`, "tx": `{"songid":101,"songmid":"001SongMid","songname":"测试歌曲","singer":[{"id":2,"name":"测试歌手"}],"albummid":"001AlbumMid","albumname":"测试专辑","interval":180,"strMediaMid":"003RealMedia","size128":1000,"size320":2000,"sizeflac":3000}`, "kg": `{"hash":"0123456789ABCDEF0123456789ABCDEF","filename":"测试歌手 - 测试歌曲","album_name":"测试专辑","duration":180,"320hash":"1123456789ABCDEF0123456789ABCDEF","sqhash":"2123456789ABCDEF0123456789ABCDEF","filesize":1000}`, "kw": `{"MUSICRID":"MUSIC_101","SONGNAME":"测试歌曲","ARTIST":"测试歌手","ALBUM":"测试专辑","DURATION":"180","FORMATS":"MP3128|MP3H|FLAC"}`, "mg": `{"songId":"101","copyrightId":"600000001","songName":"测试歌曲","singers":[{"id":"2","name":"测试歌手"}],"albumName":"测试专辑","img1":"/data/fixture.webp","duration":"03:00","newRateFormats":[{"formatType":"SQ","size":3000}],"lyricUrl":"https://music.migu.cn/test.lrc"}`}[platform]
	var row map[string]any
	d := json.NewDecoder(strings.NewReader(raw))
	d.UseNumber()
	d.Decode(&row)
	return row
}
func catalogWrap(p string, row map[string]any, action string) any {
	rows := []any{row}
	switch p {
	case "wy":
		switch action {
		case "search":
			return map[string]any{"code": 200, "result": map[string]any{"songs": rows, "songCount": 1}}
		case "detail":
			return map[string]any{"code": 200, "songs": rows}
		case "album":
			return map[string]any{"code": 200, "album": map[string]any{"name": "测试专辑", "size": 1}, "songs": rows}
		default:
			return map[string]any{"code": 200, "playlist": map[string]any{"name": "测试歌单", "trackCount": 1, "trackIds": []any{map[string]any{"id": 101}}}}
		}
	case "tx":
		switch action {
		case "search":
			return map[string]any{"code": 0, "data": map[string]any{"song": map[string]any{"list": rows, "totalnum": 1}}}
		case "detail":
			return map[string]any{"code": 0, "data": rows}
		case "album":
			return map[string]any{"code": 0, "data": map[string]any{"list": rows, "name": "测试专辑", "mid": "001AlbumMid", "total": 1}}
		default:
			return map[string]any{"code": 0, "cdlist": []any{map[string]any{"songlist": rows, "dissname": "测试歌单", "total_song_num": 1}}}
		}
	case "kg":
		if action == "search" {
			return map[string]any{"status": 1, "data": map[string]any{"lists": rows, "total": 1}}
		}
		if action == "detail" {
			return map[string]any{"status": 1, "data": row}
		}
		return map[string]any{"status": 1, "data": map[string]any{"info": rows, "specialname": "测试歌单", "albumname": "测试专辑", "total": 1, "songcount": 1}}
	case "kw":
		if action == "search" {
			return map[string]any{"abslist": rows, "TOTAL": 1}
		}
		return map[string]any{"musiclist": rows, "name": "测试专辑", "title": "测试歌单", "total": 1}
	case "mg":
		if action == "search" {
			return map[string]any{"code": "000000", "songResultData": map[string]any{"result": []any{rows}, "totalCount": 1}}
		}
		if action == "detail" {
			return map[string]any{"code": "000000", "data": rows}
		}
		return map[string]any{"code": "000000", "data": map[string]any{"title": "测试歌单", "songList": rows, "totalCount": 1}}
	}
	return nil
}
func TestCatalogFivePlatformSearchDetailAndCollections(t *testing.T) {
	for _, p := range []string{"wy", "tx", "kg", "kw", "mg"} {
		t.Run(p, func(t *testing.T) {
			row := catalogRow(p)
			network := &catalogFixture{route: func(u *url.URL) (any, error) { return catalogWrap(p, row, "search"), nil }}
			c := &publicCatalog{network}
			found, e := c.Search(context.Background(), p, "测试", 1)
			if e != nil || len(found.Tracks) != 1 {
				t.Fatal(found, e)
			}
			track := found.Tracks[0]
			if track.Title != "测试歌曲" || len(track.Artists) != 1 || track.Artists[0] != "测试歌手" || track.Album != "测试专辑" || track.DurationMS == nil || *track.DurationMS != 180000 {
				t.Fatal(track)
			}
			if p == "wy" && (network.method != "POST" || !strings.Contains(network.body, "type=1")) {
				t.Fatal(network.method, network.body)
			}
			if p == "kg" && catalogText(at(track.MusicInfo, "meta", "_qualitys", "flac"), "hash") != "2123456789ABCDEF0123456789ABCDEF" {
				t.Fatal(track.MusicInfo)
			}
			if p == "mg" && (catalogText(track.MusicInfo["meta"], "copyrightId") != "600000001" || track.CoverURL != "https://d.musicapp.migu.cn/data/fixture.webp") {
				t.Fatal(track)
			}
			for _, kind := range []string{"album", "playlist"} {
				network.route = func(u *url.URL) (any, error) {
					if p == "wy" && strings.Contains(u.Path, "song/detail") {
						return catalogWrap(p, row, "detail"), nil
					}
					return catalogWrap(p, row, kind), nil
				}
				result, e := c.Collection(context.Background(), CatalogReference{p, kind, "123"}, 1)
				if e != nil || len(result.Tracks) != 1 || result.HasMore {
					t.Fatal(kind, result, e)
				}
			}
			network.route = func(*url.URL) (any, error) { return catalogWrap(p, row, "detail"), nil }
			detail, e := c.Detail(context.Background(), track)
			if e != nil || detail.ID != track.ID {
				t.Fatal(detail, e)
			}
			if p == "kw" && (network.last.Query().Get("rid") != "MUSIC_101" || network.last.Query().Get("ft") != "music") {
				t.Fatal(network.last)
			}
		})
	}
}
func TestCatalogRestrictedPartialCollectionsAndVersions(t *testing.T) {
	rows := []any{}
	for i := 0; i < 23; i++ {
		row := catalogRow("tx")
		row["songmid"] = fmt.Sprintf("mid%d", i)
		rows = append(rows, row)
	}
	network := &catalogFixture{route: func(*url.URL) (any, error) {
		return map[string]any{"code": 0, "data": map[string]any{"list": rows, "name": "专辑", "total": 30}}, nil
	}}
	c := &publicCatalog{network}
	first, e := c.Collection(context.Background(), CatalogReference{"tx", "album", "123"}, 1)
	if e != nil || len(first.Tracks) != 20 || !first.HasMore {
		t.Fatal(first, e)
	}
	second, e := c.Collection(context.Background(), CatalogReference{"tx", "album", "123"}, 2)
	if e != nil || len(second.Tracks) != 3 || second.HasMore || second.Total != 30 || !strings.Contains(second.Warning, "23 / 30") {
		t.Fatal(second, e)
	}
	network.route = func(*url.URL) (any, error) { return map[string]any{"code": 301}, nil }
	if _, e = c.Collection(context.Background(), CatalogReference{"wy", "playlist", "123"}, 1); e == nil {
		t.Fatal("restricted directory accepted")
	}
	network.route = func(*url.URL) (any, error) {
		return map[string]any{"musiclist": []any{}, "title": "歌单", "total": 100}, nil
	}
	partial, e := c.Collection(context.Background(), CatalogReference{"kw", "playlist", "123"}, 1)
	if e != nil || !strings.Contains(partial.Warning, "提前停止") {
		t.Fatal(partial, e)
	}
	if _, e = mapCatalogTrack("mg", map[string]any{"id": "1", "name": "无版权编号"}); e == nil {
		t.Fatal("missing copyright accepted")
	}
	kg := catalogRow("kg")
	variant := catalogRow("kg")
	variant["hash"] = "1123456789ABCDEF0123456789ABCDEF"
	kg["Grp"] = []any{variant}
	network.route = func(*url.URL) (any, error) {
		return map[string]any{"status": 1, "data": map[string]any{"lists": []any{kg}, "total": 1}}, nil
	}
	versions, e := c.Search(context.Background(), "kg", "测试", 1)
	if e != nil || len(versions.Tracks) != 2 {
		t.Fatal(versions, e)
	}
}
func TestCatalogOfficialSharesShortLinksAndUntrustedHosts(t *testing.T) {
	network := &catalogFixture{route: func(*url.URL) (any, error) { return nil, fmt.Errorf("unexpected network") }}
	c := &publicCatalog{network}
	for address, want := range map[string]string{"https://music.163.com/#/playlist?id=123": "wy:playlist:123", "https://music.163.com/album?id=123": "wy:album:123", "https://y.qq.com/n/ryqq/albumDetail/001AlbumMid": "tx:album:001AlbumMid", "https://y.qq.com/n/ryqq/playlist/123": "tx:playlist:123", "https://www.kugou.com/yy/special/single/123.html": "kg:playlist:123", "https://m.kugou.com/album/info/123": "kg:album:123", "https://www.kuwo.cn/playlist_detail/123": "kw:playlist:123", "https://www.kuwo.cn/album_detail/123": "kw:album:123", "https://music.migu.cn/v3/music/playlist/123": "mg:playlist:123", "https://music.migu.cn/v3/music/album/123": "mg:album:123"} {
		ref, e := c.Reference(context.Background(), "分享给你 "+address)
		if e != nil || ref.Platform+":"+ref.Kind+":"+ref.ID != want {
			t.Fatal(address, ref, e)
		}
	}
	if network.calls != 0 {
		t.Fatal("known shares fetched network")
	}
	for _, address := range []string{"https://music.163.com.attacker.example/playlist?id=123", "http://127.0.0.1/playlist?id=123", "https://user:pass@music.163.com/playlist?id=123", "https://music.163.com:8443/playlist?id=123"} {
		if _, e := c.Reference(context.Background(), address); e == nil {
			t.Fatal("untrusted share accepted", address)
		}
	}
	network.route = func(u *url.URL) (any, error) {
		return Reply{Status: 200, URL: u.String(), Bytes: []byte(`<a href="https://y.qq.com/n/ryqq/playlist/456">open</a>`)}, nil
	}
	ref, e := c.Reference(context.Background(), "https://qm.qq.com/q/short")
	if e != nil || ref.ID != "456" {
		t.Fatal(ref, e)
	}
	network.route = func(*url.URL) (any, error) {
		return Reply{Status: 200, URL: "https://music.163.com/playlist?id=123"}, nil
	}
	if _, e = c.Reference(context.Background(), "https://qm.qq.com/q/short"); e == nil {
		t.Fatal("cross platform redirect accepted")
	}
}
func TestCatalogParsesDataOnlyAndLyrics(t *testing.T) {
	network := &catalogFixture{route: func(*url.URL) (any, error) {
		return `{'TOTAL':'1','abslist':[{'MUSICRID':'MUSIC_101','SONGNAME':'晴天','ARTIST':'周杰伦','ALBUM':'叶惠美','DURATION':'269'}]}`, nil
	}}
	c := &publicCatalog{network}
	page, e := c.Search(context.Background(), "kw", "晴天", 1)
	if e != nil || len(page.Tracks) != 1 || page.Tracks[0].Title != "晴天" || *page.Tracks[0].DurationMS != 269000 {
		t.Fatal(page, e)
	}
	for _, code := range []string{`{'TOTAL':(function(){throw 'evil'})()}`, `alert({"TOTAL":1}) trailing`, `{"TOTAL":1};process.exit()`} {
		network.route = func(*url.URL) (any, error) { return code, nil }
		if _, e = c.Search(context.Background(), "kw", "晴天", 1); e == nil {
			t.Fatal("provider code accepted", code)
		}
	}
	for _, p := range []string{"wy", "tx", "kw", "kg", "mg"} {
		network.route = func(u *url.URL) (any, error) {
			switch p {
			case "wy":
				return map[string]any{"code": 200, "lrc": map[string]any{"lyric": "[00:01.00]line"}}, nil
			case "tx":
				return map[string]any{"code": 0, "lyric": "[00:01.00]line"}, nil
			case "kw":
				return map[string]any{"data": map[string]any{"lrclist": []any{map[string]any{"time": 1, "lineLyric": "line"}}}}, nil
			case "kg":
				if strings.Contains(u.Path, "search") {
					return map[string]any{"candidates": []any{map[string]any{"id": "1", "accesskey": "key"}}}, nil
				}
				return map[string]any{"content": base64.StdEncoding.EncodeToString([]byte("[00:01.00]line"))}, nil
			case "mg":
				return "[00:01.00]line", nil
			}
			return nil, nil
		}
		track, e := mapCatalogTrack(p, catalogRow(p))
		if e != nil {
			t.Fatal(e)
		}
		text, e := c.Lyrics(context.Background(), track)
		if e != nil || strings.TrimSpace(text) != "[00:01.00]line" {
			t.Fatal(p, text, e)
		}
	}
}
func TestCoverStillWebPBoundsReferencesAndExpiry(t *testing.T) {
	valid, _ := base64.StdEncoding.DecodeString("UklGRh4AAABXRUJQVlA4TBEAAAAvAAAAAAdQqFIUr/+BiOh/AAA=")
	ext, e := validateCover(valid)
	if e != nil || ext != "webp" {
		t.Fatal(ext, e)
	}
	for _, mutate := range []func([]byte) []byte{func(b []byte) []byte { return b[:len(b)-1] }, func(b []byte) []byte { b[21] = 255; b[22] = 255; return b }, func(b []byte) []byte { copy(b[12:16], "ANIM"); return b }} {
		if _, e = validateCover(mutate(append([]byte{}, valid...))); e == nil {
			t.Fatal("invalid webp accepted")
		}
	}
	network := &catalogFixture{route: func(u *url.URL) (any, error) { return Reply{Status: 200, Bytes: valid, URL: u.String()}, nil }}
	clock := time.Now()
	covers := &coverStore{network: network, refs: map[string]coverReference{}, cache: map[string]coverEntry{}, clock: func() time.Time { return clock }}
	if covers.Reference("http://127.0.0.1/a.jpg") != nil {
		t.Fatal("private cover accepted")
	}
	ref := covers.Reference("https://example.org/a.webp")
	if ref == nil {
		t.Fatal("reference absent")
	}
	id := strings.TrimPrefix(*ref, "/api/v1/lx-downloads/covers/")
	for i := 0; i < 2; i++ {
		b, mime, e := covers.Get(context.Background(), id)
		if e != nil || mime != "image/webp" || string(b) != string(valid) {
			t.Fatal(mime, e)
		}
	}
	if network.calls != 1 {
		t.Fatal("cache miss")
	}
	clock = clock.Add(2 * time.Hour)
	if _, _, e = covers.Get(context.Background(), id); e == nil {
		t.Fatal("expired reference accepted")
	}
}

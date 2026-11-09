package downloads

import (
	"context"
	"crypto/md5"
	"encoding/hex"
	"fmt"
	"regexp"
	"sort"
	"strconv"
	"strings"
)

func (c *publicCatalog) Collection(ctx context.Context, ref CatalogReference, page int) (Collection, error) {
	if page < 1 || page > 500 {
		return Collection{}, bad("每次最多读取 10000 首歌曲，请拆分后导入")
	}
	if platforms[ref.Platform] == "" || !contains([]string{"album", "playlist"}, ref.Kind) || parseCatalogReference("https://"+map[string]string{"wy": "music.163.com", "tx": "y.qq.com", "kg": "kugou.com", "kw": "kuwo.cn", "mg": "migu.cn"}[ref.Platform]+"/"+ref.Kind+"?id="+ref.ID, ref.Platform) == nil {
		return Collection{}, bad("目录编号无效")
	}
	p, id := ref.Platform, ref.ID
	album := ref.Kind == "album"
	name, cover, warning := "", "", ""
	var total int64
	var rows any
	var root map[string]any
	var e error
	sliced := false
	switch p {
	case "wy":
		address := catalogURL("https://music.163.com/api/v6/playlist/detail", "id", id, "n", 100000, "s", 0)
		key := "playlist"
		if album {
			address = "https://music.163.com/api/album/" + id
			key = "album"
		}
		root, e = c.get(ctx, address, p)
		if e != nil {
			return Collection{}, e
		}
		info := asObject(root[key])
		name = catalogText(info, "name")
		cover = catalogText(info, "coverImgUrl")
		if album {
			cover = catalogText(info, "picUrl")
			rows = root["songs"]
			if _, ok := rows.([]any); !ok {
				rows = info["songs"]
			}
			total = integer(info["size"], int64(len(asArray(rows))))
			sliced = true
		} else {
			ids, ok := info["trackIds"].([]any)
			total = integer(info["trackCount"], int64(len(ids)))
			if ok {
				selected := []any{}
				start := (page - 1) * 20
				for i := start; i < min(start+20, len(ids)); i++ {
					selected = append(selected, map[string]any{"id": asObject(ids[i])["id"]})
				}
				if len(selected) == 0 {
					rows = []any{}
				} else {
					var detail map[string]any
					detail, e = c.get(ctx, catalogURL("https://music.163.com/api/v3/song/detail", "c", encode(selected)), p)
					if e != nil {
						return Collection{}, e
					}
					rows = detail["songs"]
				}
				if total > int64(len(ids)) {
					warning = "平台只公开了部分曲目，受限歌曲需要在原平台查看权限"
				}
			} else {
				rows = info["tracks"]
				sliced = true
				if total > int64(len(asArray(rows))) {
					warning = "平台只返回部分歌曲，部分曲目可能需要登录"
				}
			}
		}
	case "tx":
		if album {
			key := "albummid"
			if numeric(id) {
				key = "albumid"
			}
			root, e = c.get(ctx, catalogURL("https://c.y.qq.com/v8/fcg-bin/fcg_v8_album_info_cp.fcg", key, id, "format", "json"), p)
			if e != nil {
				return Collection{}, e
			}
			data := asObject(root["data"])
			rows = data["list"]
			name = catalogText(data, "name")
			mid := catalogText(data, "mid")
			if mid != "" {
				cover = "https://y.gtimg.cn/music/photo_new/T002R300x300M000" + mid + ".jpg"
			}
			total = integer(data["total"], int64(len(asArray(rows))))
			sliced = true
		} else {
			root, e = c.get(ctx, catalogURL("https://c.y.qq.com/v8/fcg-bin/fcg_v8_playlist_cp.fcg", "id", id, "newsong", 1, "format", "json"), p)
			if e != nil {
				return Collection{}, e
			}
			cds := asArray(root["cdlist"])
			if len(cds) == 0 {
				return Collection{}, unavailable("该歌单不可读取，请检查是否为公开歌单")
			}
			data := asObject(cds[0])
			rows = data["songlist"]
			name = catalogText(data, "dissname")
			cover = catalogText(data, "logo")
			total = integer(data["total_song_num"], integer(data["songnum"], int64(len(asArray(rows)))))
			sliced = true
		}
	case "kg":
		if album {
			infoRoot, err := c.get(ctx, catalogURL("http://mobilecdn.kugou.com/api/v3/album/info", "albumid", id), p)
			if err != nil {
				return Collection{}, err
			}
			info := asObject(infoRoot["data"])
			name = catalogText(info, "albumname")
			cover = strings.ReplaceAll(catalogText(info, "imgurl"), "{size}", "400")
			root, e = c.get(ctx, catalogURL("http://mobilecdn.kugou.com/api/v3/album/song", "albumid", id, "page", page, "pagesize", 20), p)
			if e != nil {
				return Collection{}, e
			}
			data := asObject(root["data"])
			rows = data["info"]
			total = integer(data["total"], integer(info["songcount"], 0))
			for _, raw := range asArray(rows) {
				r := asObject(raw)
				if r == nil {
					continue
				}
				if catalogText(r, "AlbumName", "album_name") == "" {
					r["album_name"] = name
				}
				if catalogText(r, "AlbumID", "album_id") == "" {
					r["album_id"] = id
				}
				if catalogText(r, "imgurl") == "" {
					r["imgurl"] = cover
				}
			}
		} else {
			if !numeric(id) {
				return c.kugouCollection(ctx, id, page)
			}
			infoRoot, err := c.get(ctx, catalogURL("http://mobilecdnbj.kugou.com/api/v5/special/info", "specialid", id), p)
			if err != nil {
				return Collection{}, err
			}
			info := asObject(infoRoot["data"])
			name = catalogText(info, "specialname")
			cover = strings.ReplaceAll(catalogText(info, "imgurl"), "{size}", "400")
			root, e = c.get(ctx, catalogURL("http://mobilecdnbj.kugou.com/api/v3/special/song", "specialid", id, "page", page, "pagesize", 20), p)
			if e != nil {
				return Collection{}, e
			}
			data := asObject(root["data"])
			rows = data["info"]
			total = integer(data["total"], integer(info["songcount"], 0))
		}
	case "kw":
		if album {
			root, e = c.get(ctx, catalogURL("https://search.kuwo.cn/r.s", "stype", "albuminfo", "albumid", id, "pn", page-1, "rn", 20, "alflac", 1, "show_copyright_off", 1, "rformat", "json", "encoding", "utf8"), p)
			if e != nil {
				return Collection{}, e
			}
			rows = first(root, "musiclist", "songlist")
			name = catalogText(root, "name", "album")
			cover = catalogText(root, "pic", "img")
			total = integer(first(root, "total", "musicnum", "totalnum", "songnum"), 0)
		} else {
			root, e = c.get(ctx, catalogURL("http://nplserver.kuwo.cn/pl.svc", "op", "getlistinfo", "pid", id, "pn", page-1, "rn", 20, "encode", "utf8", "keyset", "pl2012", "identity", "kuwo", "pcmp4", 1, "vipver", "MUSIC_9.0.5.0_W1", "newver", 1), p)
			if e != nil {
				return Collection{}, e
			}
			rows = root["musiclist"]
			name = catalogText(root, "title")
			cover = catalogText(root, "pic")
			total = integer(root["total"], 0)
		}
	case "mg":
		if album {
			infoRoot, err := c.get(ctx, catalogURL("https://app.c.nf.migu.cn/MIGUM3.0/resource/album/v2.0", "albumId", id), p)
			if err != nil {
				return Collection{}, err
			}
			info := asObject(infoRoot["data"])
			name = catalogText(info, "title", "albumName", "name")
			images := asArray(info["imgItems"])
			if len(images) > 0 {
				cover = catalogText(images[0], "img", "imgUrl")
			}
			root, e = c.get(ctx, catalogURL("https://app.c.nf.migu.cn/MIGUM3.0/resource/album/song/v2.0", "albumId", id, "pageNo", page, "pageSize", 20), p)
			if e != nil {
				return Collection{}, e
			}
			data := asObject(root["data"])
			rows = data["songList"]
			total = integer(data["totalCount"], integer(info["totalCount"], int64(len(asArray(rows)))))
		} else {
			infoRoot, err := c.get(ctx, catalogURL("https://c.musicapp.migu.cn/MIGUM3.0/resource/playlist/v2.0", "playlistId", id), p)
			if err != nil {
				return Collection{}, err
			}
			info := asObject(infoRoot["data"])
			name = catalogText(info, "title", "playlistName", "name")
			cover = catalogText(info, "image", "img", "imageUrl")
			root, e = c.get(ctx, catalogURL("https://app.c.nf.migu.cn/MIGUM3.0/resource/playlist/song/v2.0", "playlistId", id, "pageNo", page, "pageSize", 20), p)
			if e != nil {
				return Collection{}, e
			}
			data := asObject(root["data"])
			rows = data["songList"]
			total = integer(data["totalCount"], 0)
		}
	}
	all, ok := rows.([]any)
	if !ok {
		return Collection{}, unavailable("平台未返回歌曲列表，目录可能受限，请检查公开权限后重试")
	}
	readable := total
	if sliced {
		readable = int64(len(all))
		if total > int64(len(all)) {
			warning = fmt.Sprintf("平台只返回 %d / %d 首，部分歌曲需要在原平台查看权限", len(all), total)
		}
		start := min((page-1)*20, len(all))
		rows = all[start:min(start+20, len(all))]
	}
	more := int64(page*20) < readable && len(asArray(rows)) > 0
	if !sliced && len(all) == 0 && int64((page-1)*20) < total {
		warning = "平台提前停止返回歌曲，已读取部分目录；请检查公开权限或稍后重试"
	}
	if page >= 500 && more {
		more = false
		warning = "已达到单次读取 10000 首上限，请拆分歌单后继续"
	}
	result, e := catalogPage(p, rows, total, more, warning)
	if e != nil {
		return Collection{}, e
	}
	if strings.TrimSpace(name) == "" {
		name = "歌单 " + id
		if album {
			name = "专辑 " + id
		}
	}
	if total < int64(len(result.Tracks)) {
		total = int64(len(result.Tracks))
	}
	return Collection{p, ref.Kind, cleanCatalog(name), safeCatalogCover(cover), result.Tracks, page, total, result.HasMore, result.Warning}, nil
}
func numeric(v string) bool {
	if v == "" {
		return false
	}
	for _, r := range v {
		if r < '0' || r > '9' {
			return false
		}
	}
	return true
}
func signedCatalog(base string, params map[string]string, body *string) string {
	salt := "NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt"
	if body != nil {
		salt = "OIlwieks28dk2k092lksi2UIkp"
	}
	keys := make([]string, 0, len(params))
	for k := range params {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	var text strings.Builder
	text.WriteString(salt)
	for _, k := range keys {
		text.WriteString(k + "=" + params[k])
	}
	if body != nil {
		text.WriteString(*body)
	}
	text.WriteString(salt)
	digest := md5.Sum([]byte(text.String()))
	args := []any{}
	for _, k := range keys {
		args = append(args, k, params[k])
	}
	args = append(args, "signature", hex.EncodeToString(digest[:]))
	return catalogURL(base, args...)
}
func (c *publicCatalog) kugouCollection(ctx context.Context, id string, page int) (Collection, error) {
	params := map[string]string{"appid": "1058", "specialid": "0", "global_specialid": id, "srcappid": "2919", "clientver": "20000", "clienttime": "1586163263991", "mid": "1586163263991", "uuid": "1586163263991", "dfid": "-"}
	if strings.HasPrefix(id, "gcid_") {
		body := encode(map[string]any{"ret_info": 1, "data": []any{map[string]any{"id": id, "id_type": 2}}})
		address := signedCatalog("https://t.kugou.com/v1/songlist/batch_decode", map[string]string{"dfid": "-", "appid": "1005", "mid": "0", "clientver": "20109", "clienttime": "640612895", "uuid": "-"}, &body)
		decoded, e := c.send(ctx, address, "kg", "POST", map[string]string{"Content-Type": "application/json"}, []byte(body))
		if e != nil {
			return Collection{}, e
		}
		rows := asArray(at(decoded, "data", "list"))
		if len(rows) == 0 {
			return Collection{}, unavailable("酷狗未返回公开歌单编号，请重新复制分享链接")
		}
		id = catalogText(rows[0], "global_collection_id")
		if !strings.HasPrefix(id, "collection_") || len(id) > 111 || !collectionID.MatchString(id) {
			return Collection{}, unavailable("酷狗未返回公开歌单编号，请重新复制分享链接")
		}
		params["global_specialid"] = id
	}
	infoRoot, e := c.get(ctx, signedCatalog("https://mobiles.kugou.com/api/v5/special/info_v2", params, nil), "kg")
	if e != nil {
		return Collection{}, e
	}
	info := asObject(infoRoot["data"])
	params["page"] = strconv.Itoa(page)
	params["pagesize"] = "20"
	params["plat"] = "0"
	params["version"] = "8000"
	root, e := c.get(ctx, signedCatalog("https://mobiles.kugou.com/api/v5/special/song_v2", params, nil), "kg")
	if e != nil {
		return Collection{}, e
	}
	data := asObject(root["data"])
	total := integer(data["total"], integer(info["songcount"], 0))
	result, e := catalogPage("kg", data["info"], total, int64(page*20) < total, "")
	if e != nil {
		return Collection{}, e
	}
	if page >= 500 && result.HasMore {
		result.HasMore = false
		result.Warning = "已达到单次读取 10000 首上限，请拆分歌单后继续"
	}
	if total < int64(len(result.Tracks)) {
		total = int64(len(result.Tracks))
	}
	return Collection{"kg", "playlist", cleanCatalog(catalogText(info, "specialname")), safeCatalogCover(strings.ReplaceAll(catalogText(info, "imgurl"), "{size}", "400")), result.Tracks, page, total, result.HasMore, result.Warning}, nil
}

var collectionID = regexp.MustCompile(`^collection_[A-Za-z0-9_]{1,100}$`)

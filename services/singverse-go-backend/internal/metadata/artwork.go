package metadata

import (
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"golang.org/x/text/unicode/norm"
	"math/big"
	"net/url"
	"regexp"
	"strings"
	"unicode"
)

type Artwork struct {
	Provider   string
	ExternalID string
	Portrait   string
	Background string
}

func artistCanonical(v string) string {
	v = norm.NFKC.String(strings.TrimSpace(v))
	return strings.ToLower(strings.Join(strings.FieldsFunc(v, unicode.IsSpace), " "))
}

var qqID = regexp.MustCompile(`^[A-Za-z0-9]{1,120}$`)
var neteaseID = regexp.MustCompile(`^[0-9]{1,20}$`)

func (s *Service) ArtistArtwork(ctx context.Context, name string, titles []string, refs []ArtistRef) (*Artwork, error) {
	if _, e := clean(name, 200); e != nil {
		return nil, e
	}
	cfg, e := s.settings(ctx, s.db, false)
	if e != nil {
		return nil, e
	}
	if !cfg.Enabled {
		return nil, nil
	}
	var failure error
	for _, provider := range []string{"NETEASE", "QQ"} {
		if !contains(cfg.Providers, provider) {
			continue
		}
		ids := []string{}
		for _, r := range refs {
			if r.Provider == provider && artistCanonical(r.Name) == artistCanonical(name) && !contains(ids, r.ID) {
				ids = append(ids, r.ID)
			}
		}
		if len(ids) > 1 {
			return nil, nil
		}
		if len(ids) == 1 {
			art, e := s.artistArtwork(ctx, provider, ids[0], name)
			if e == nil && art != nil {
				return art, nil
			}
			if e != nil {
				failure = e
				continue
			}
		}
		matches := []string{}
		for _, title := range titles[:min(3, len(titles))] {
			p := &musicProvider{service: s, name: provider}
			rows, e := guarded(ctx, s, provider, func(callCtx context.Context) (map[string]any, error) {
				if provider == "QQ" {
					q := url.Values{"format": {"json"}, "w": {name + " " + title}, "n": {"20"}, "p": {"1"}}
					return p.request(callCtx, "GET", "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?"+q.Encode(), nil, nil)
				}
				data := map[string]any{"s": name + " " + title, "type": 1, "limit": 20, "offset": 0, "total": true, "header": map[string]any{"os": "pc", "appver": "3.1.0"}}
				return p.request(callCtx, "POST", "https://interfacepc.music.163.com/eapi/cloudsearch/pc", url.Values{"params": {eapi("/api/cloudsearch/pc", encode(data))}}, map[string]string{"Referer": "https://music.163.com/"})
			})
			if e != nil {
				failure = e
				break
			}
			codeExpected := int64(0)
			songs := path(rows, "data", "song", "list")
			if provider == "NETEASE" {
				codeExpected = 200
				songs = path(rows, "result", "songs")
			}
			if number(rows["code"]) != codeExpected {
				failure = &problem{503, "歌手图片搜索不可用"}
				break
			}
			for _, id := range matchedArtistIDs(array(songs), provider, name, title) {
				if !contains(matches, id) {
					matches = append(matches, id)
				}
			}
		}
		if len(matches) > 1 {
			return nil, nil
		}
		if len(matches) == 1 {
			art, e := s.artistArtwork(ctx, provider, matches[0], name)
			if e == nil && art != nil {
				return art, nil
			}
			if e != nil {
				failure = e
			}
		}
	}
	return nil, failure
}
func matchedArtistIDs(rows []any, provider, name, title string) []string {
	out := []string{}
	for _, raw := range rows {
		row, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		field, idField, artistField := "songname", "mid", "singer"
		if provider == "NETEASE" {
			field, idField, artistField = "name", "id", "ar"
			if row[artistField] == nil {
				artistField = "artists"
			}
		}
		if artistCanonical(text(row[field])) != artistCanonical(title) {
			continue
		}
		for _, rawArtist := range array(row[artistField]) {
			artist, ok := rawArtist.(map[string]any)
			if ok && artistCanonical(text(artist["name"])) == artistCanonical(name) {
				id := text(artist[idField])
				if id != "" && !contains(out, id) {
					out = append(out, id)
				}
			}
		}
	}
	return out
}
func (s *Service) artistArtwork(ctx context.Context, provider, id, name string) (*Artwork, error) {
	cfg, e := s.settings(ctx, s.db, true)
	if e != nil {
		return nil, e
	}
	if !contains(cfg.Providers, provider) {
		return nil, conflict("平台已停用")
	}
	if provider == "QQ" {
		if !qqID.MatchString(id) {
			return nil, nil
		}
		return &Artwork{Provider: "QQ", ExternalID: id, Portrait: "https://y.gtimg.cn/music/photo_new/T001R800x800M000" + id + ".jpg"}, nil
	}
	if !neteaseID.MatchString(id) {
		return nil, nil
	}
	p := &musicProvider{service: s, name: provider}
	root, e := guarded(ctx, s, provider, func(callCtx context.Context) (map[string]any, error) {
		params, key, e := weapi("{}")
		if e != nil {
			return nil, e
		}
		return p.request(callCtx, "POST", "https://music.163.com/weapi/v1/artist/"+id, url.Values{"params": {params}, "encSecKey": {key}}, map[string]string{"Referer": "https://music.163.com/"})
	})
	if e != nil {
		return nil, e
	}
	if number(root["code"]) != 200 {
		return nil, &problem{503, "歌手详情不可用"}
	}
	artist, _ := root["artist"].(map[string]any)
	if artistCanonical(text(artist["name"])) != artistCanonical(name) {
		return nil, nil
	}
	portrait := text(artist["img1v1Url"])
	if portrait == "" {
		portrait = text(artist["picUrl"])
	}
	if portrait == "" || strings.Contains(portrait, "5639395138885805") {
		return nil, nil
	}
	checked := checkedCover("NETEASE", &portrait)
	if checked == nil {
		return nil, nil
	}
	u, _ := url.Parse(*checked)
	query := u.Query()
	// Fetch a portrait-sized image rather than a multi-megabyte original.
	// Keep the same download/decode limits as other external artwork.
	query.Set("param", "640y640")
	u.RawQuery = query.Encode()
	return &Artwork{Provider: "NETEASE", ExternalID: id, Portrait: u.String()}, nil
}
func weapi(body string) (string, string, error) {
	const publicKey = "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDgtQn2JZ34ZC28NWYpAUd98iZ37BUrX/aKzmFbt7clFSs6sXqHauqKWqdtLkF2KexO40H1YTX8z2lSgBBOAxLsvaklV8k4cBFK9snQXE9/DDaFt6Rr7iVZMldczhC0JNgTz+SHXT6CBHuX3e9SdB1Ua44oncaTWz7OBGLbCiK45wIDAQAB"
	const alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
	secret := make([]byte, 16)
	for i := range secret {
		n, e := rand.Int(rand.Reader, big.NewInt(int64(len(alphabet))))
		if e != nil {
			return "", "", e
		}
		secret[i] = alphabet[n.Int64()]
	}
	params, e := weapiCBC(body, []byte("0CoJUm6Qyw8W8jud"))
	if e != nil {
		return "", "", e
	}
	params, e = weapiCBC(params, secret)
	if e != nil {
		return "", "", e
	}
	der, _ := base64.StdEncoding.DecodeString(publicKey)
	public, e := x509.ParsePKIXPublicKey(der)
	if e != nil {
		return "", "", e
	}
	key := public.(*rsa.PublicKey)
	reversed := make([]byte, len(secret))
	for i, v := range secret {
		reversed[len(secret)-1-i] = v
	}
	encrypted := new(big.Int).Exp(new(big.Int).SetBytes(reversed), big.NewInt(int64(key.E)), key.N)
	padded := make([]byte, key.Size())
	encrypted.FillBytes(padded)
	return params, hex.EncodeToString(padded), nil
}
func weapiCBC(value string, key []byte) (string, error) {
	block, e := aes.NewCipher(key)
	if e != nil {
		return "", e
	}
	data := []byte(value)
	padding := aes.BlockSize - len(data)%aes.BlockSize
	for i := 0; i < padding; i++ {
		data = append(data, byte(padding))
	}
	out := make([]byte, len(data))
	cipher.NewCBCEncrypter(block, []byte("0102030405060708")).CryptBlocks(out, data)
	return base64.StdEncoding.EncodeToString(out), nil
}

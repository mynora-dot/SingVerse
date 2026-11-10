package metadata

import (
	"bytes"
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"github.com/gin-gonic/gin"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
	"image"
	"image/jpeg"
	"image/png"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"time"
)

var coverHosts = map[string][]string{"NETEASE": {"music.126.net"}, "QQ": {"y.gtimg.cn"}, "KUGOU": {"kugou.com", "kugoucdn.com", "kgimg.com"}, "KUWO": {"img1.kuwo.cn", "img2.kuwo.cn", "img3.kuwo.cn", "img4.kuwo.cn"}, "QISHUI": {"p3-luna.douyinpic.com", "p6-luna.douyinpic.com"}}

type Cover struct {
	Bytes         []byte
	MIME          string
	Width, Height int
}

func validateCover(b []byte) (Cover, error) {
	if len(b) == 0 || len(b) > 2_000_000 {
		return Cover{}, missing("没有可用的封面")
	}
	cfg, format, e := image.DecodeConfig(bytes.NewReader(b))
	if e != nil || (format != "jpeg" && format != "png") || cfg.Width < 1 || cfg.Height < 1 || cfg.Width > 4096 || cfg.Height > 4096 {
		return Cover{}, missing("没有可用的封面")
	}
	mime := "image/" + format
	return Cover{b, mime, cfg.Width, cfg.Height}, nil
}
func reencodeCover(b []byte) (Cover, error) {
	c, e := validateCover(b)
	if e != nil {
		return c, e
	}
	decoded, format, e := image.Decode(bytes.NewReader(b))
	if e != nil {
		return Cover{}, bad("图片无法解码")
	}
	var out bytes.Buffer
	if format == "png" {
		e = png.Encode(&out, decoded)
	} else {
		e = jpeg.Encode(&out, decoded, &jpeg.Options{Quality: 90})
	}
	if e != nil || out.Len() > 2_000_000 {
		return Cover{}, bad("图片重新编码后超过 2 MB，请压缩后重试")
	}
	c.Bytes = out.Bytes()
	return c, nil
}
func atomicWrite(target string, b []byte) error {
	dir := filepath.Dir(target)
	if e := os.MkdirAll(dir, 0700); e != nil {
		return e
	}
	temp, e := os.CreateTemp(dir, "metadata-*.tmp")
	if e != nil {
		return e
	}
	name := temp.Name()
	defer os.Remove(name)
	if _, e = temp.Write(b); e != nil {
		temp.Close()
		return e
	}
	if e = temp.Sync(); e != nil {
		temp.Close()
		return e
	}
	if e = temp.Close(); e != nil {
		return e
	}
	return os.Rename(name, target)
}
func readImage(path string) (Cover, error) {
	st, e := os.Lstat(path)
	if e != nil || !st.Mode().IsRegular() || st.Size() > 2_000_000 {
		return Cover{}, missing("封面文件不可用")
	}
	f, e := os.Open(path)
	if e != nil {
		return Cover{}, missing("封面文件不可用")
	}
	defer f.Close()
	b, e := io.ReadAll(io.LimitReader(f, 2_000_001))
	if e != nil {
		return Cover{}, missing("封面文件不可用")
	}
	return validateCover(b)
}
func (s *Service) asset(ctx context.Context, id string) (Cover, error) {
	if !uuidPattern.MatchString(id) {
		return Cover{}, bad("封面资源标识无效")
	}
	if _, e := s.row(ctx, s.db, "review-asset", map[string]any{"id": id}); e != nil {
		return Cover{}, absent(e, "封面资源不存在或已清理")
	}
	return s.uploadedCover(id)
}
func imageResponse(c *gin.Context, cover Cover) {
	c.Header("X-Content-Type-Options", "nosniff")
	c.Header("Cache-Control", "no-store")
	c.Header("X-SingVerse-Image-Width", fmt.Sprint(cover.Width))
	c.Header("X-SingVerse-Image-Height", fmt.Sprint(cover.Height))
	c.Data(200, cover.MIME, cover.Bytes)
}
func (s *Service) assetImage(c *gin.Context) {
	cover, e := s.asset(c.Request.Context(), c.Param("id"))
	if e != nil {
		s.fail(c, e)
		return
	}
	imageResponse(c, cover)
}
func (s *Service) upload(c *gin.Context) {
	ctx := c.Request.Context()
	c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, 2_100_000)
	if e := c.Request.ParseMultipartForm(2_100_000); e != nil {
		s.fail(c, bad("请选择 2 MB 以内的 JPEG 或 PNG 图片"))
		return
	}
	if c.Request.MultipartForm != nil {
		defer c.Request.MultipartForm.RemoveAll()
	}
	id := c.Param("songId")
	song, e := s.song(ctx, s.db, id)
	if e != nil {
		s.fail(c, e)
		return
	}
	fp := c.Request.FormValue("fingerprint")
	if fp != fingerprint(song) {
		s.fail(c, conflict("歌曲文件已变化，请重新加载"))
		return
	}
	file, header, e := c.Request.FormFile("file")
	if e != nil {
		s.fail(c, bad("请选择封面图片"))
		return
	}
	defer file.Close()
	mime := header.Header.Get("Content-Type")
	if header.Size == 0 || header.Size > 2_000_000 || (mime != "image/png" && mime != "image/jpeg") {
		s.fail(c, bad("请选择 2 MB 以内的 JPEG 或 PNG 图片"))
		return
	}
	b, e := io.ReadAll(io.LimitReader(file, 2_000_001))
	if e != nil {
		s.fail(c, bad("封面读取失败"))
		return
	}
	cover, e := reencodeCover(b)
	if e != nil {
		s.fail(c, bad("图片内容无效，请上传可解码的 JPEG 或 PNG"))
		return
	}
	if cover.MIME != mime {
		s.fail(c, bad("图片类型与实际内容不一致"))
		return
	}
	assetID := uuid()
	target := filepath.Join(s.cfg.MetadataPath, "uploads", assetID+".image")
	if e = atomicWrite(target, cover.Bytes); e != nil {
		s.fail(c, bad("封面上传失败，请检查存储权限"))
		return
	}
	at := now()
	e = s.transaction(ctx, func(tx store.DBTX) error {
		song, e := s.song(ctx, tx, id)
		if e != nil {
			return e
		}
		if fingerprint(song) != fp {
			return conflict("上传期间文件已变化，请重新加载")
		}
		_, e = s.exec(ctx, tx, "review-assetSave", map[string]any{"id": assetID, "songId": id, "fingerprint": fp, "mime": mime, "width": cover.Width, "height": cover.Height, "now": at})
		return e
	})
	if e != nil {
		_ = os.Remove(target)
		s.fail(c, e)
		return
	}
	c.JSON(201, map[string]any{"id": assetID, "mime": mime, "width": cover.Width, "height": cover.Height, "createdAt": at})
}
func (s *Service) coverSearch(c *gin.Context) {
	var in SearchInput
	if !s.decode(c, &in) {
		return
	}
	ctx := c.Request.Context()
	song, e := s.song(ctx, s.db, c.Param("songId"))
	if e != nil {
		s.fail(c, e)
		return
	}
	cfg, e := s.settings(ctx, s.db, true)
	if e != nil {
		s.fail(c, e)
		return
	}
	result, e := s.search(ctx, song, in, cfg.Providers)
	if e != nil {
		s.fail(c, e)
		return
	}
	items := []map[string]any{}
	for _, candidate := range result.Candidates {
		t := candidate.Track
		if t.CoverURL == nil {
			continue
		}
		items = append(items, map[string]any{"candidateId": candidate.ID, "provider": t.Provider, "externalId": t.ExternalID, "title": t.Title, "artists": t.Artists, "album": t.Album, "coverUrl": t.CoverURL, "width": nil, "height": nil})
	}
	c.JSON(200, map[string]any{"items": items, "errors": result.Errors})
}
func publicIP(ip net.IP) bool {
	if ip == nil || !ip.IsGlobalUnicast() || ip.IsPrivate() || ip.IsLoopback() || ip.IsLinkLocalUnicast() || ip.IsLinkLocalMulticast() || ip.IsMulticast() || ip.IsUnspecified() {
		return false
	}
	for _, raw := range []string{"0.0.0.0/8", "100.64.0.0/10", "192.0.0.0/24", "192.0.2.0/24", "198.18.0.0/15", "198.51.100.0/24", "203.0.113.0/24", "240.0.0.0/4", "2001:db8::/32"} {
		_, sub, _ := net.ParseCIDR(raw)
		if sub.Contains(ip) {
			return false
		}
	}
	return true
}
func safeClient(ctx context.Context, u *url.URL, allowPrivate bool) (*http.Client, error) {
	addresses, e := net.DefaultResolver.LookupIPAddr(ctx, u.Hostname())
	if e != nil || len(addresses) == 0 {
		return nil, &problem{502, "下载目标不可解析"}
	}
	for _, a := range addresses {
		if !publicIP(a.IP) && !allowPrivate {
			return nil, &problem{502, "下载目标地址被拒绝"}
		}
	}
	port := u.Port()
	if port == "" {
		port = "443"
		if u.Scheme == "http" {
			port = "80"
		}
	}
	dialer := &net.Dialer{Timeout: 3 * time.Second}
	tr := &http.Transport{Proxy: nil, TLSClientConfig: &tls.Config{MinVersion: tls.VersionTLS12, ServerName: u.Hostname()}, ResponseHeaderTimeout: 10 * time.Second, DisableKeepAlives: true}
	tr.DialContext = func(callCtx context.Context, network, address string) (net.Conn, error) {
		host, p, e := net.SplitHostPort(address)
		if e != nil || !strings.EqualFold(host, u.Hostname()) || p != port {
			return nil, errors.New("unexpected network destination")
		}
		var last error
		for _, a := range addresses {
			conn, e := dialer.DialContext(callCtx, "tcp", net.JoinHostPort(a.IP.String(), port))
			if e == nil {
				return conn, nil
			}
			last = e
		}
		return nil, last
	}
	return &http.Client{Transport: tr, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}, nil
}
func (s *Service) loadCover(ctx context.Context, provider *string, raw *string) (Cover, error) {
	if provider == nil || raw == nil || checkedCover(*provider, raw) == nil {
		return Cover{}, missing("没有可用的封面")
	}
	u, _ := url.Parse(*checkedCover(*provider, raw))
	path := filepath.Join(s.cfg.MetadataCachePath, hash(*provider+":"+u.String())+".image")
	if cover, e := readRemoteCover(path); e == nil {
		return cover, nil
	}
	cfg, e := s.settings(ctx, s.db, true)
	if e != nil {
		return Cover{}, e
	}
	if !contains(cfg.Providers, *provider) {
		return Cover{}, conflict("该平台已停用，请启用平台或选择其他封面")
	}
	fetchCtx, cancel := context.WithTimeout(ctx, 20*time.Second)
	defer cancel()
	client, e := safeClient(fetchCtx, u, false)
	if e != nil {
		return Cover{}, e
	}
	defer client.CloseIdleConnections()
	cover, e := fetchCover(fetchCtx, client, u.String())
	if e != nil {
		return cover, e
	}
	if e = atomicWrite(path, cover.Bytes); e != nil {
		return Cover{}, &problem{502, "封面缓存不可用"}
	}
	return cover, nil
}

func fetchCover(fetchCtx context.Context, client *http.Client, address string) (Cover, error) {
	req, _ := http.NewRequestWithContext(fetchCtx, "GET", address, nil)
	req.Header.Set("Accept", "image/jpeg,image/png")
	req.Header.Set("User-Agent", "SingVerse/0.1 metadata-cover")
	resp, e := client.Do(req)
	if e != nil {
		return Cover{}, &problem{502, "封面暂时不可用"}
	}
	defer resp.Body.Close()
	if resp.StatusCode == 404 || resp.StatusCode == 410 {
		return Cover{}, missing("平台上的这张封面已不可用，可重新搜索或更换平台")
	}
	if resp.StatusCode != 200 {
		return Cover{}, &problem{422, "平台暂未允许读取这张封面，可重试或更换平台"}
	}
	b, e := io.ReadAll(io.LimitReader(resp.Body, remoteCoverLimit+1))
	if e != nil {
		return Cover{}, &problem{502, "封面暂时不可用"}
	}
	return validateRemoteCover(b)
}
func (s *Service) candidateCover(c *gin.Context) {
	quality := c.DefaultQuery("quality", "ORIGINAL")
	if !validCoverQuality(quality) {
		s.fail(c, bad("请选择原图或高清封面"))
		return
	}
	_, track, e := s.candidate(c.Request.Context(), s.db, c.Param("id"))
	if e != nil {
		s.fail(c, e)
		return
	}
	cover, e := s.loadCover(c.Request.Context(), track.Provider, coverVariant(track.Provider, track.CoverURL, quality))
	if e != nil {
		s.fail(c, e)
		return
	}
	imageResponse(c, cover)
}
func (s *Service) SongCover(ctx context.Context, id string) (Cover, error) {
	song, e := s.song(ctx, s.db, id)
	if e != nil {
		return Cover{}, e
	}
	a := metaOf(song)
	if a == nil || !contains(a.Fields, "cover") || a.Track.CoverURL == nil {
		return Cover{}, missing("没有可用的封面")
	}
	raw := *a.Track.CoverURL
	if a.Origins["cover"].Kind == "DOWNLOAD" {
		s.hookMu.RLock()
		fn := s.downloadCover
		s.hookMu.RUnlock()
		if fn != nil {
			return fn(ctx, id)
		}
	}
	if strings.HasPrefix(raw, "asset:") {
		return s.asset(ctx, strings.TrimPrefix(raw, "asset:"))
	}
	if strings.HasPrefix(raw, "embedded:") {
		b, e := s.embeddedCover(ctx, id)
		if e != nil {
			return Cover{}, e
		}
		return validateCover(b)
	}
	if cover, err := s.savedCover(ctx, s.db, id, raw); err == nil {
		return cover, nil
	}
	return s.loadCover(ctx, a.Origins["cover"].Provider, a.Track.CoverURL)
}

func (s *Service) savedCover(ctx context.Context, tx store.DBTX, id, raw string) (Cover, error) {
	rows, err := s.rows(ctx, tx, "files-previous", map[string]any{"songId": id})
	if err != nil {
		return Cover{}, err
	}
	if len(rows) > 0 {
		var snapshot map[string]any
		if decode(rows[0]["result_json"], &snapshot) == nil && text(snapshot["coverKey"]) == hash(raw) {
			if b, err := s.SharedCover(text(snapshot["cover"])); err == nil {
				return validateCover(b)
			}
		}
	}
	return Cover{}, missing("没有已保存的封面")
}
func (s *Service) songCover(c *gin.Context) {
	cover, e := s.SongCover(c.Request.Context(), c.Param("songId"))
	if e != nil {
		s.fail(c, e)
		return
	}
	imageResponse(c, cover)
}

func (s *Service) uploadedCover(id string) (Cover, error) {
	cover, e := readImage(filepath.Join(s.cfg.MetadataPath, "uploads", id+".image"))
	if e == nil {
		return cover, nil
	}
	return readImage(filepath.Join(s.cfg.MetadataCachePath, "uploads", id+".image"))
}

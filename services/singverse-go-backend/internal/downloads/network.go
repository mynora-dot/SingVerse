package downloads

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/downloads/lxruntime"
	"golang.org/x/sys/unix"
)

const maxAudio int64 = 512 * 1024 * 1024

type Reply struct {
	Status  int
	Headers map[string]string
	Bytes   []byte
	URL     string
}
type Download struct {
	Size            int64
	Hash, Extension string
}
type Network struct{ client *http.Client }

func NewNetwork() *Network {
	return &Network{client: &http.Client{Transport: lxruntime.PublicTransport(), CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}}
}
func (n *Network) Close() { n.client.CloseIdleConnections() }
func (n *Network) perform(ctx context.Context, address, method string, headers map[string]string, body []byte, httpsOnly bool, reader func(*http.Response) error) error {
	u, err := lxruntime.CheckPublicURL(address, httpsOnly)
	if err != nil {
		return bad("请输入有效的公网 HTTP(S) 地址")
	}
	safe := http.Header{}
	safe.Set("User-Agent", "SingVerse LX/1.0")
	for k, v := range headers {
		switch strings.ToLower(k) {
		case "host", "connection", "content-length", "proxy-authorization", "transfer-encoding":
			continue
		}
		safe.Set(k, v)
	}
	for hop := 0; hop <= 5; hop++ {
		req, e := http.NewRequestWithContext(ctx, method, u.String(), bytes.NewReader(body))
		if e != nil {
			return bad("请求参数无效")
		}
		req.Header = safe.Clone()
		response, e := n.client.Do(req)
		if e != nil {
			if ctx.Err() != nil {
				return ctx.Err()
			}
			return bad("连接失败或响应无效，请检查音源和网络")
		}
		if response.StatusCode == 301 || response.StatusCode == 302 || response.StatusCode == 303 || response.StatusCode == 307 || response.StatusCode == 308 {
			response.Body.Close()
			if hop == 5 {
				return bad("下载跳转次数过多")
			}
			next, e := u.Parse(response.Header.Get("Location"))
			if e != nil || response.Header.Get("Location") == "" {
				return bad("下载跳转地址无效")
			}
			next, e = lxruntime.CheckPublicURL(next.String(), httpsOnly)
			if e != nil {
				return bad("下载跳转地址无效")
			}
			if u.Scheme == "https" && next.Scheme != "https" {
				return bad("拒绝 HTTPS 降级跳转")
			}
			if !sameOrigin(u, next) {
				for _, k := range []string{"Authorization", "Cookie", "Referer"} {
					safe.Del(k)
				}
			}
			if response.StatusCode == 303 || (response.StatusCode == 301 || response.StatusCode == 302) && method == "POST" {
				method = "GET"
				body = nil
				safe.Del("Content-Type")
			}
			u = next
			continue
		}
		err = reader(response)
		response.Body.Close()
		return err
	}
	return bad("下载跳转次数过多")
}
func sameOrigin(a, b *url.URL) bool { return a.Scheme == b.Scheme && strings.EqualFold(a.Host, b.Host) }
func (n *Network) Request(ctx context.Context, address, method string, headers map[string]string, body []byte, limit int, httpsOnly bool) (Reply, error) {
	ctx, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()
	var out Reply
	err := n.perform(ctx, address, method, headers, body, httpsOnly, func(r *http.Response) error {
		if r.ContentLength > int64(limit) {
			return bad("响应超过大小限制")
		}
		data, e := io.ReadAll(io.LimitReader(r.Body, int64(limit)+1))
		if e != nil {
			return bad("连接失败或响应无效，请检查音源和网络")
		}
		if len(data) > limit {
			return bad("响应超过大小限制")
		}
		out = Reply{Status: r.StatusCode, Headers: map[string]string{}, Bytes: data, URL: r.Request.URL.String()}
		for k := range r.Header {
			out.Headers[strings.ToLower(k)] = r.Header.Get(k)
		}
		return nil
	})
	return out, err
}
func (n *Network) Download(ctx context.Context, address, temporary string, progress func(int64, *int64) error) (Download, error) {
	ctx, cancel := context.WithTimeout(ctx, 5*time.Minute)
	defer cancel()
	var result Download
	err := n.perform(ctx, address, "GET", map[string]string{"Accept-Encoding": "identity"}, nil, false, func(response *http.Response) error {
		if response.StatusCode != 200 {
			return bad(fmt.Sprintf("音频下载返回 HTTP %d，请重新解析或更换音源", response.StatusCode))
		}
		declared := response.ContentLength
		if declared > maxAudio || declared == 0 {
			return bad("音频大小无效或超过 512 MiB")
		}
		reserve := declared
		if reserve < 0 {
			reserve = 16 * 1024 * 1024
		}
		if e := freeSpace(filepath.Dir(temporary), reserve+16*1024*1024); e != nil {
			return e
		}
		file, e := os.OpenFile(temporary, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
		if e != nil {
			return bad("无法写入下载文件，请检查挂载、目录权限和剩余空间")
		}
		closed := false
		defer func() {
			if !closed {
				file.Close()
			}
		}()
		digest := sha256.New()
		var received int64
		var prefix []byte
		buffer := make([]byte, 65536)
		last := time.Time{}
		var total *int64
		if declared >= 0 {
			total = &declared
		}
		for {
			if e := ctx.Err(); e != nil {
				return e
			}
			count, readErr := response.Body.Read(buffer)
			if count > 0 {
				received += int64(count)
				if received > maxAudio {
					return bad("音频超过 512 MiB")
				}
				if len(prefix) < 32 {
					prefix = append(prefix, buffer[:min(count, 32-len(prefix))]...)
				}
				if _, e = file.Write(buffer[:count]); e != nil {
					return bad("无法写入下载文件，请检查挂载、目录权限和剩余空间")
				}
				_, _ = digest.Write(buffer[:count])
				if time.Since(last) >= time.Second {
					if e = progress(received, total); e != nil {
						return e
					}
					last = time.Now()
				}
			}
			if readErr == io.EOF {
				break
			}
			if readErr != nil {
				if ctx.Err() != nil {
					return ctx.Err()
				}
				return bad("音频未完整传输，请重试")
			}
		}
		if received < 16 || declared >= 0 && received != declared {
			return bad("音频未完整传输，请重试")
		}
		if e = file.Sync(); e != nil {
			return bad("无法保存下载文件，请检查剩余空间")
		}
		if e = file.Close(); e != nil {
			return e
		}
		closed = true
		extension, e := audioExtension(prefix)
		if e != nil {
			return e
		}
		if e = validateAudio(ctx, temporary, extension); e != nil {
			return bad("返回内容不是完整的音频文件，请重新解析或更换音源")
		}
		if e = progress(received, total); e != nil {
			return e
		}
		result = Download{received, hex.EncodeToString(digest.Sum(nil)), extension}
		return nil
	})
	return result, err
}
func freeSpace(path string, required int64) error {
	var info unix.Statfs_t
	if err := unix.Statfs(path, &info); err != nil {
		return bad("无法读取下载目录剩余空间")
	}
	available := uint64(info.Bavail) * uint64(info.Bsize)
	if available < uint64(max(0, required)) {
		return conflict("下载目录空间不足，请清理空间后重试")
	}
	return nil
}
func audioExtension(b []byte) (string, error) {
	if len(b) < 12 {
		return "", bad("音频文件无效")
	}
	v := string(b[:12])
	switch {
	case strings.HasPrefix(v, "fLaC"):
		return "flac", nil
	case strings.HasPrefix(v, "ID3"):
		return "mp3", nil
	case strings.HasPrefix(v, "OggS"):
		return "ogg", nil
	case strings.HasPrefix(v, "RIFF") && v[8:] == "WAVE":
		return "wav", nil
	case v[4:8] == "ftyp":
		return "m4a", nil
	case b[0] == 255 && b[1]&0xf6 == 0xf0:
		return "aac", nil
	case b[0] == 255 && b[1]&0xe0 == 0xe0 && b[1]&6 != 0:
		return "mp3", nil
	}
	return "", bad("返回内容不是支持的音频文件")
}

var errIncompleteAudio = errors.New("音频容器或帧不完整")

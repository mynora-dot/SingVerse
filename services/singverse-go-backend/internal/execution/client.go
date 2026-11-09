package execution

import (
	"bytes"
	"context"
	"crypto/tls"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"regexp"
	"strings"
	"time"
	"unicode"

	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/compute"
)

var nodeResource = regexp.MustCompile(`^/internal/v1/(?:transcoding|audio-roles)/[a-zA-Z0-9/_.-]+$`)
var modelResource = regexp.MustCompile(`^/internal/v1/audio-roles/models/(?:download|import)\?model=(?:panns-cnn6|htdemucs|htdemucs-openvino|uvr-mdxnet-9482)$`)
var byteRange = regexp.MustCompile(`^bytes=(?:[0-9]+-[0-9]*|-[0-9]+)$`)

type idleConn struct{ net.Conn }

func (c idleConn) Read(b []byte) (int, error) {
	_ = c.Conn.SetReadDeadline(time.Now().Add(30 * time.Second))
	return c.Conn.Read(b)
}

type responseBody struct {
	io.ReadCloser
	cancel    context.CancelFunc
	transport *http.Transport
}

func (b *responseBody) Close() error {
	err := b.ReadCloser.Close()
	b.cancel()
	b.transport.CloseIdleConnections()
	return err
}

func allowedIP(ip netip.Addr, scheme string) bool {
	ip = ip.Unmap()
	if ip.Zone() != "" || ip.IsUnspecified() || ip.IsMulticast() || ip.IsLinkLocalUnicast() || ip.IsLinkLocalMulticast() {
		return false
	}
	return scheme == "https" || ip.IsLoopback() || ip.IsPrivate()
}

// remote pins all resolved addresses, disables proxy discovery, and never follows
// redirects. Streaming responses have a per-read idle deadline rather than a
// whole-file timeout, so slow but progressing media remains readable.
func (s *Service) remote(ctx context.Context, c *compute.Connection, path, method string, body io.Reader, length int64, contentType, rangeValue string, timeout time.Duration) (*http.Response, error) {
	if c == nil || c.Token == "" {
		return nil, fail(503, "计算节点缺少认证凭据")
	}
	if path != "/internal/v1/status" && !nodeResource.MatchString(path) && path != "/internal/v1/audio-roles/models?refresh=true" && !modelResource.MatchString(path) {
		return nil, fail(400, "无效的计算资源")
	}
	if strings.Contains(path, "..") || strings.Contains(path, "//") {
		return nil, fail(400, "无效的计算资源")
	}
	if rangeValue != "" && !byteRange.MatchString(rangeValue) {
		return nil, fail(400, "无效的字节范围")
	}
	u, err := url.Parse(c.Origin)
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Hostname() == "" || u.User != nil || u.Path != "" || u.RawQuery != "" || u.Fragment != "" {
		return nil, fail(400, "计算节点地址无效")
	}
	ctx, cancel := context.WithTimeout(ctx, timeout)
	dnsCtx, dnsCancel := context.WithTimeout(ctx, 3*time.Second)
	addresses, err := net.DefaultResolver.LookupNetIP(dnsCtx, "ip", u.Hostname())
	dnsCancel()
	if err != nil || len(addresses) == 0 {
		cancel()
		return nil, fail(503, "计算节点地址无法解析")
	}
	for _, ip := range addresses {
		if !allowedIP(ip, u.Scheme) {
			cancel()
			return nil, fail(400, "公网节点必须使用HTTPS，HTTP仅用于本机或受限内网")
		}
	}
	port := u.Port()
	if port == "" {
		port = "80"
		if u.Scheme == "https" {
			port = "443"
		}
	}
	dialer := net.Dialer{Timeout: 3 * time.Second}
	transport := &http.Transport{Proxy: nil, DisableKeepAlives: true, MaxResponseHeaderBytes: 32768, TLSHandshakeTimeout: 3 * time.Second, ResponseHeaderTimeout: 30 * time.Second, TLSClientConfig: &tls.Config{MinVersion: tls.VersionTLS12, ServerName: u.Hostname()}}
	transport.DialContext = func(dialCtx context.Context, network, address string) (net.Conn, error) {
		var last error
		for _, ip := range addresses {
			conn, e := dialer.DialContext(dialCtx, "tcp", net.JoinHostPort(ip.String(), port))
			if e == nil {
				return idleConn{conn}, nil
			}
			last = e
		}
		return nil, last
	}
	req, err := http.NewRequestWithContext(ctx, method, c.Origin+path, body)
	if err != nil {
		cancel()
		return nil, fail(400, "计算请求无效")
	}
	req.ContentLength = length
	req.Header.Set("Authorization", "Bearer "+c.Token)
	req.Header.Set("Accept", "application/json")
	if contentType != "" {
		req.Header.Set("Content-Type", contentType)
	}
	if rangeValue != "" {
		req.Header.Set("Range", rangeValue)
	}
	client := http.Client{Transport: transport, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	response, err := client.Do(req)
	if err != nil {
		cancel()
		transport.CloseIdleConnections()
		return nil, fail(503, "计算节点暂时无法连接")
	}
	response.Body = &responseBody{response.Body, cancel, transport}
	return response, nil
}

func (s *Service) call(ctx context.Context, c *compute.Connection, path, method string, payload map[string]any) (map[string]any, error) {
	if payload != nil {
		payload = copyMap(payload)
		if payload["reference"] != nil {
			callback, err := s.compute.CallbackOrigin(c)
			if err != nil {
				return nil, fail(503, "业务回源地址不可用")
			}
			payload["callbackOrigin"] = callback
		}
	}
	var body io.Reader
	length := int64(0)
	if payload != nil {
		b, err := json.Marshal(payload)
		if err != nil || len(b) > 1<<20 {
			return nil, fail(400, "计算任务参数无效")
		}
		body = bytes.NewReader(b)
		length = int64(len(b))
	}
	timeout := 30 * time.Second
	if strings.HasSuffix(path, "/export") {
		timeout = 300 * time.Second
	} else if strings.HasSuffix(path, "/capabilities") {
		timeout = 60 * time.Second
	}
	response, err := s.remote(ctx, c, path, method, body, length, "application/json", "", timeout)
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	b, err := io.ReadAll(io.LimitReader(response.Body, (1<<20)+1))
	if err != nil || len(b) > 1<<20 {
		return nil, fail(502, "计算节点响应无效或超过限制")
	}
	var value map[string]any
	if len(b) > 0 {
		if err = json.Unmarshal(b, &value); err != nil || value == nil {
			return nil, fail(502, "计算节点响应格式无效")
		}
	} else {
		value = map[string]any{}
	}
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		status := response.StatusCode
		if !oneOfInt(status, 400, 404, 409, 422, 429, 507) {
			status = 502
		}
		message := "计算任务请求失败"
		switch status {
		case 409:
			message = "配置或执行状态已变化，请重新读取"
		case 507:
			message = "转码缓存或磁盘空间不足，请清理缓存或调整容量"
		case 422:
			message = "所选加速方式或媒体任务测试未通过，请检查驱动和设备权限"
			detail := str(value, "title")
			if detail != "" && len(detail) <= 256 && !strings.Contains(detail, "://") && !strings.Contains(detail, c.Token) && strings.IndexFunc(detail, unicode.IsControl) < 0 {
				message = detail
			}
		}
		return nil, fail(status, message)
	}
	return scrub(value, c.Token).(map[string]any), nil
}

func oneOfInt(v int, values ...int) bool {
	for _, n := range values {
		if v == n {
			return true
		}
	}
	return false
}

func scrub(v any, secret string) any {
	switch value := v.(type) {
	case string:
		if secret != "" && strings.Contains(value, secret) {
			return strings.ReplaceAll(value, secret, "[redacted]")
		}
		return value
	case map[string]any:
		clean := make(map[string]any, len(value))
		for key, item := range value {
			if secret != "" {
				key = strings.ReplaceAll(key, secret, "[redacted]")
			}
			clean[key] = scrub(item, secret)
		}
		return clean
	case []any:
		for i, item := range value {
			value[i] = scrub(item, secret)
		}
		return value
	default:
		return v
	}
}

func (s *Service) artifactResponse(ctx context.Context, c *compute.Connection, path, rangeValue string) (*http.Response, error) {
	response, err := s.remote(ctx, c, path, "GET", nil, 0, "", rangeValue, 24*time.Hour)
	if err != nil {
		return nil, err
	}
	if !oneOfInt(response.StatusCode, 200, 206, 416) {
		response.Body.Close()
		return nil, fail(502, "转码产物暂时不可读取")
	}
	return response, nil
}

func (s *Service) proxyArtifact(ctx context.Context, w http.ResponseWriter, r *http.Request, c *compute.Connection, path string) error {
	response, err := s.artifactResponse(ctx, c, path, r.Header.Get("Range"))
	if err != nil {
		return err
	}
	defer response.Body.Close()
	w.Header().Set("Cache-Control", "private, no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	for _, name := range []string{"Content-Type", "Content-Length", "Content-Range", "Accept-Ranges"} {
		if value := response.Header.Get(name); value != "" {
			w.Header().Set(name, value)
		}
	}
	w.WriteHeader(response.StatusCode)
	if r.Method == "HEAD" {
		return nil
	}
	_, err = io.Copy(w, response.Body)
	return err
}

func (s *Service) nodeCall(ctx context.Context, path, method string, body map[string]any) (map[string]any, error) {
	connection, err := s.connection(ctx, "")
	if err != nil {
		return nil, err
	}
	return s.call(ctx, connection, path, method, body)
}

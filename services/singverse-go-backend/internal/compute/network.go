package compute

import (
	"bytes"
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"strconv"
	"strings"
	"time"
)

func parsePort(value string) (int, error) {
	n, err := strconv.Atoi(value)
	if err != nil || n < 1 || n > 65535 {
		return 0, errors.New("invalid port")
	}
	return n, nil
}
func privateAddress(ip netip.Addr) bool {
	ip = ip.Unmap()
	return ip.Zone() == "" && (ip.IsLoopback() || ip.IsPrivate()) && !ip.IsLinkLocalUnicast()
}
func requirePrivate(endpoint string) error {
	u, err := url.Parse(endpoint)
	if err != nil {
		return reject(400, "自动配对仅支持本机或受限局域网地址")
	}
	ip, err := netip.ParseAddr(u.Hostname())
	if err != nil || !privateAddress(ip) {
		return reject(400, "自动配对仅支持本机或受限局域网地址")
	}
	return nil
}
func networkAllowed(ip netip.Addr, scheme string) bool {
	ip = ip.Unmap()
	if !ip.IsValid() || ip.Zone() != "" || ip.IsUnspecified() || ip.IsMulticast() || ip.IsLinkLocalUnicast() || ip.IsLinkLocalMulticast() {
		return false
	}
	return scheme == "https" || privateAddress(ip)
}

// request resolves every address before dialing and pins the approved answers to
// this request. Redirects and environment proxies can never receive the Bearer.
func request(ctx context.Context, endpoint, path, token string, payload any, timeout time.Duration, limit int64) (int, []byte, error) {
	canonical, err := origin(endpoint)
	if err != nil {
		return 0, nil, err
	}
	u, _ := url.Parse(canonical)
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	var addresses []netip.Addr
	if ip, e := netip.ParseAddr(u.Hostname()); e == nil {
		addresses = []netip.Addr{ip}
	} else {
		addresses, err = net.DefaultResolver.LookupNetIP(ctx, "ip", u.Hostname())
		if err != nil {
			return 0, nil, errors.New("compute DNS lookup failed")
		}
	}
	if len(addresses) == 0 {
		return 0, nil, errors.New("compute DNS returned no addresses")
	}
	for _, ip := range addresses {
		if !networkAllowed(ip, u.Scheme) {
			return 0, nil, reject(400, "公网节点必须使用HTTPS，HTTP仅用于本机或受限内网")
		}
	}
	port := u.Port()
	if port == "" {
		port = "80"
		if u.Scheme == "https" {
			port = "443"
		}
	}
	dialer := net.Dialer{Timeout: 2 * time.Second}
	transport := &http.Transport{Proxy: nil, DisableKeepAlives: true, MaxResponseHeaderBytes: 16384, TLSHandshakeTimeout: 2 * time.Second, ResponseHeaderTimeout: timeout, TLSClientConfig: &tls.Config{MinVersion: tls.VersionTLS12, ServerName: u.Hostname()}}
	transport.DialContext = func(dialCtx context.Context, network, address string) (net.Conn, error) {
		var last error
		for _, ip := range addresses {
			conn, e := dialer.DialContext(dialCtx, "tcp", net.JoinHostPort(ip.String(), port))
			if e == nil {
				return conn, nil
			}
			last = e
		}
		return nil, last
	}
	defer transport.CloseIdleConnections()
	var body io.Reader
	method := http.MethodGet
	if payload != nil {
		b, e := json.Marshal(payload)
		if e != nil {
			return 0, nil, errors.New("invalid compute payload")
		}
		if len(b) > 65536 {
			return 0, nil, errors.New("compute payload too large")
		}
		body = bytes.NewReader(b)
		method = http.MethodPost
	}
	req, err := http.NewRequestWithContext(ctx, method, canonical+path, body)
	if err != nil {
		return 0, nil, errors.New("invalid compute request")
	}
	req.Header.Set("Accept", "application/json")
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	if payload != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	client := http.Client{Transport: transport, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	response, err := client.Do(req)
	if err != nil {
		return 0, nil, errors.New("compute request failed")
	}
	defer response.Body.Close()
	b, err := io.ReadAll(io.LimitReader(response.Body, limit+1))
	if err != nil || int64(len(b)) > limit {
		return 0, nil, errors.New("compute response exceeded limit")
	}
	return response.StatusCode, b, nil
}
func jsonObject(b []byte) (map[string]any, error) {
	var value map[string]any
	if err := json.Unmarshal(b, &value); err != nil || value == nil {
		return nil, errors.New("invalid compute response")
	}
	return value, nil
}
func text(v any) string { s, _ := v.(string); return s }

// Call exposes the pinned network boundary for metadata extraction. The adapter
// owns the short-lived input grant and validates the returned metadata schema.
func (s *Service) Call(ctx context.Context, connection *Connection, path string, payload any, timeout time.Duration, limit int64) (map[string]any, error) {
	if connection == nil || connection.Token == "" {
		return nil, reject(503, "计算节点未配置可用凭据")
	}
	if path != "/internal/v1/media/remote-tags" {
		return nil, reject(400, "计算资源无效")
	}
	if timeout <= 0 || timeout > 190*time.Second {
		timeout = 30 * time.Second
	}
	if limit <= 0 || limit > 4<<20 {
		limit = 4 << 20
	}
	status, body, err := request(ctx, connection.Origin, path, connection.Token, payload, timeout, limit)
	if err != nil {
		var f *failure
		if errors.As(err, &f) {
			return nil, err
		}
		return nil, reject(503, "计算节点暂时无法连接")
	}
	if status < 200 || status >= 300 {
		if status != 400 && status != 404 && status != 409 && status != 422 && status != 429 {
			status = 502
		}
		return nil, reject(status, "计算节点未完成元数据读取")
	}
	value, err := jsonObject(body)
	if err != nil {
		return nil, reject(502, "计算节点元数据响应无效")
	}
	var sanitize func(any) any
	sanitize = func(v any) any {
		switch item := v.(type) {
		case string:
			return strings.ReplaceAll(item, connection.Token, "[redacted]")
		case map[string]any:
			clean := make(map[string]any, len(item))
			for key, value := range item {
				clean[strings.ReplaceAll(key, connection.Token, "[redacted]")] = sanitize(value)
			}
			return clean
		case []any:
			for i, value := range item {
				item[i] = sanitize(value)
			}
		}
		return v
	}
	return sanitize(value).(map[string]any), nil
}
func (s *Service) Probe(ctx context.Context, connection *Connection) (Check, error) {
	start := time.Now()
	result := func(status, message string, caps []string) Check {
		if caps == nil {
			caps = []string{}
		}
		return Check{status, message, caps, time.Since(start).Milliseconds()}
	}
	if connection == nil {
		return result("DISABLED", "未启用节点，或没有发现部署配置", nil), nil
	}
	if connection.Token == "" {
		return result("MISSING_TOKEN", "已发现节点地址，尚未配置可用的认证凭据", nil), nil
	}
	status, b, err := request(ctx, connection.Origin, "/internal/v1/status", connection.Token, nil, 4*time.Second, 65536)
	if err != nil {
		var f *failure
		if errors.As(err, &f) {
			return Check{}, err
		}
		return result("UNAVAILABLE", "节点连接失败或超时，请检查地址、服务及网络", nil), nil
	}
	if status == 401 || status == 403 {
		return result("AUTH_FAILED", "连接到了节点，但认证凭据不匹配", nil), nil
	}
	if status == 503 {
		return result("UNAVAILABLE", "计算节点暂不可用，请检查节点服务、凭据文件挂载及读取权限", nil), nil
	}
	if status != 200 {
		return result("UNAVAILABLE", "节点未就绪或版本不支持认证状态检查", nil), nil
	}
	value, err := jsonObject(b)
	if err != nil || text(value["service"]) != "singverse-compute" || text(value["apiVersion"]) != "1" {
		return result("INCOMPATIBLE", "地址可以访问，但不是兼容的SingVerse计算服务", nil), nil
	}
	list, ok := value["capabilities"].([]any)
	if !ok || len(list) > 32 {
		return result("INCOMPATIBLE", "节点能力响应无效", nil), nil
	}
	caps := make([]string, 0, len(list))
	for _, v := range list {
		str, ok := v.(string)
		if !ok || strings.TrimSpace(str) == "" || len(str) > 100 {
			return result("INCOMPATIBLE", "节点能力响应无效", nil), nil
		}
		caps = append(caps, str)
	}
	callback, err := s.CallbackOrigin(connection)
	if err != nil {
		return result("CALLBACK_FAILED", "节点连接与认证成功，但无法确定业务回源地址，请通过节点可达的业务地址打开管理页", caps), nil
	}
	reference, err := s.authorize(connection.Token)
	if err != nil {
		return Check{}, err
	}
	defer s.revoke(reference)
	status, b, err = request(ctx, connection.Origin, "/internal/v1/callback/check", connection.Token, map[string]string{"reference": reference, "callbackOrigin": callback}, 7*time.Second, 4096)
	back, decodeErr := jsonObject(b)
	available, _ := back["available"].(bool)
	if err != nil || decodeErr != nil || status != 200 || !available {
		return result("CALLBACK_FAILED", callbackFailure(callback, status), caps), nil
	}
	message := "连接与认证成功，节点暂未开放媒体能力"
	for _, capability := range caps {
		if capability == "media-remote-tags" {
			message = "节点连接、认证与业务回源成功，媒体读取可用"
			break
		}
	}
	return result("AVAILABLE", message, caps), nil
}
func callbackFailure(origin string, status int) string {
	reason := "请检查反向代理与网络，确保计算节点可以访问该业务入口"
	if status == 404 {
		reason = "计算节点不支持回源检查，请同步升级计算服务"
	} else {
		u, _ := url.Parse(origin)
		host := strings.ToLower(u.Hostname())
		if host == "localhost" || host == "127.0.0.1" || host == "::1" {
			reason = "localhost／回环地址指向计算节点自身；独立节点请使用业务机器的局域网地址，或在业务服务配置 SINGVERSE_CALLBACK_ORIGIN"
		}
	}
	return "节点连接与认证成功，但业务回源不可用（回源地址：" + origin + "）；" + reason
}

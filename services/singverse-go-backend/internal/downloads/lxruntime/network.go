package lxruntime

import (
	"bytes"
	"context"
	"encoding/base64"
	"io"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"strings"
	"time"
)

type network struct {
	client *http.Client
	lookup func(context.Context, string) ([]net.IPAddr, error)
	dial   func(context.Context, string, string) (net.Conn, error)
}

func newNetwork() *network {
	dialer := &net.Dialer{Timeout: 10 * time.Second, KeepAlive: -1}
	n := &network{lookup: net.DefaultResolver.LookupIPAddr, dial: dialer.DialContext}
	transport := &http.Transport{
		Proxy:                  nil,
		DialContext:            n.dialPublic,
		DisableKeepAlives:      true,
		TLSHandshakeTimeout:    10 * time.Second,
		ResponseHeaderTimeout:  10 * time.Second,
		MaxResponseHeaderBytes: 64 * 1024,
	}
	n.client = &http.Client{
		Transport: transport, Timeout: 15 * time.Second,
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}
	return n
}

// All DNS answers are validated before dialing one of those exact addresses.
// The HTTP transport retains the original host for TLS SNI and verification.
func (n *network) dialPublic(ctx context.Context, networkName, address string) (net.Conn, error) {
	host, port, err := net.SplitHostPort(address)
	if err != nil {
		return nil, ErrNetwork
	}
	addresses, err := n.lookup(ctx, host)
	if err != nil || len(addresses) == 0 || len(addresses) > 64 {
		return nil, ErrNetwork
	}
	for _, ip := range addresses {
		parsed, ok := netip.AddrFromSlice(ip.IP)
		if !ok || ip.Zone != "" || !publicAddress(parsed) {
			return nil, ErrNetwork
		}
	}
	for _, ip := range addresses {
		if ctx.Err() != nil {
			break
		}
		conn, err := n.dial(ctx, networkName, net.JoinHostPort(ip.IP.String(), port))
		if err == nil {
			return conn, nil
		}
	}
	return nil, ErrNetwork
}

func checkedURL(value string, httpsOnly bool) (*url.URL, error) {
	if value == "" || len(value) > 2048 || strings.ContainsAny(value, "\x00\r\n\\") {
		return nil, ErrNetwork
	}
	u, err := url.Parse(value)
	if err != nil || u.Host == "" || u.Opaque != "" || u.User != nil || u.Fragment != "" || (u.Scheme != "http" && u.Scheme != "https") || (httpsOnly && u.Scheme != "https") {
		return nil, ErrNetwork
	}
	host := strings.TrimSuffix(strings.ToLower(u.Hostname()), ".")
	if host == "" || host == "localhost" || strings.HasSuffix(host, ".localhost") || strings.Contains(host, "%") {
		return nil, ErrNetwork
	}
	if ip, err := netip.ParseAddr(host); err == nil {
		if !publicAddress(ip) {
			return nil, ErrNetwork
		}
	} else if strings.Contains(host, ":") || strings.Trim(host, "0123456789.") == "" {
		return nil, ErrNetwork
	}
	if u.Port() != "" {
		port, err := net.LookupPort("tcp", u.Port())
		if err != nil || port < 1 || port > 65535 {
			return nil, ErrNetwork
		}
	}
	return u, nil
}

func publicAddress(address netip.Addr) bool {
	if !address.IsValid() || address.Zone() != "" {
		return false
	}
	address = address.Unmap()
	if !address.IsGlobalUnicast() || address.IsPrivate() || address.IsLoopback() || address.IsLinkLocalUnicast() || address.IsMulticast() {
		return false
	}
	if address.Is4() {
		b := address.As4()
		a, c, d := b[0], b[1], b[2]
		return !(a == 0 || a == 10 || a == 127 || a >= 224 ||
			a == 100 && c >= 64 && c <= 127 || a == 169 && c == 254 ||
			a == 172 && c >= 16 && c <= 31 ||
			a == 192 && (c == 168 || c == 0 && d == 0 || c == 0 && d == 2 || c == 88 && d == 99) ||
			a == 198 && (c == 18 || c == 19 || c == 51 && d == 100) || a == 203 && c == 0 && d == 113)
	}
	b := address.As16()
	if b[0]&0xe0 != 0x20 {
		return false
	}
	if b[0] == 0x20 && b[1] == 0x01 {
		if b[2] == 0x0d && b[3] == 0xb8 {
			return false
		}
		if b[2] == 0 && (b[3] == 0 || b[3] == 2 || b[3] == 0x10 || b[3] == 0x20) {
			return false
		}
	}
	if b[0] == 0x20 && b[1] == 0x02 {
		return publicAddress(netip.AddrFrom4([4]byte{b[2], b[3], b[4], b[5]}))
	}
	return true
}

func (n *network) request(ctx context.Context, f frame) (frame, error) {
	current, err := checkedURL(f.URL, false)
	if err != nil {
		return frame{}, ErrNetwork
	}
	if !contains([]string{"GET", "HEAD", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"}, f.Method) {
		return frame{}, ErrNetwork
	}
	var body []byte
	if f.Body != nil {
		if len(*f.Body) > maxFrame {
			return frame{}, ErrNetwork
		}
		if f.Binary {
			body, err = base64.StdEncoding.DecodeString(*f.Body)
		} else {
			body = []byte(*f.Body)
		}
		if err != nil || len(body) > maxBody {
			return frame{}, ErrNetwork
		}
	}
	headers := http.Header{}
	var headerSize int
	for key, value := range f.Headers {
		headerSize += len(key) + len(value)
		if headerSize > 16*1024 || len(f.Headers) > 128 {
			return frame{}, ErrNetwork
		}
		if !contains([]string{"host", "connection", "content-length", "proxy-authorization", "proxy-connection", "transfer-encoding", "upgrade", "te", "trailer"}, strings.ToLower(key)) {
			headers.Set(key, value)
		}
	}
	headers.Set("User-Agent", "SingVerse LX/1.0")
	for hop := 0; hop <= 5; hop++ {
		var input io.Reader
		if f.Method != "GET" && f.Method != "HEAD" {
			input = bytes.NewReader(body)
		}
		request, err := http.NewRequestWithContext(ctx, f.Method, current.String(), input)
		if err != nil {
			return frame{}, ErrNetwork
		}
		request.Header = headers.Clone()
		response, err := n.client.Do(request)
		if err != nil {
			return frame{}, ErrNetwork
		}
		if response.StatusCode == 301 || response.StatusCode == 302 || response.StatusCode == 303 || response.StatusCode == 307 || response.StatusCode == 308 {
			location := response.Header.Get("Location")
			response.Body.Close()
			if hop == 5 || location == "" {
				return frame{}, ErrNetwork
			}
			next, err := current.Parse(location)
			if err != nil {
				return frame{}, ErrNetwork
			}
			next, err = checkedURL(next.String(), false)
			if err != nil || (current.Scheme == "https" && next.Scheme != "https") {
				return frame{}, ErrNetwork
			}
			if origin(current) != origin(next) {
				headers.Del("Authorization")
				headers.Del("Cookie")
				headers.Del("Referer")
			}
			if response.StatusCode == 303 || ((response.StatusCode == 301 || response.StatusCode == 302) && f.Method == "POST") {
				f.Method = "GET"
				body = nil
				headers.Del("Content-Type")
			}
			current = next
			continue
		}
		data, err := io.ReadAll(io.LimitReader(response.Body, maxBody+1))
		response.Body.Close()
		if err != nil || len(data) > maxBody {
			return frame{}, ErrNetwork
		}
		replyHeaders := map[string]string{}
		for key, values := range response.Header {
			replyHeaders[strings.ToLower(key)] = strings.Join(values, ", ")
		}
		encoded := base64.StdEncoding.EncodeToString(data)
		return frame{Kind: "httpReply", ID: f.ID, Status: response.StatusCode, Headers: replyHeaders, Body: &encoded, URL: current.String()}, nil
	}
	return frame{}, ErrNetwork
}
func origin(u *url.URL) string {
	port := u.Port()
	if port == "" {
		if u.Scheme == "https" {
			port = "443"
		} else {
			port = "80"
		}
	}
	return strings.ToLower(u.Scheme + "://" + net.JoinHostPort(u.Hostname(), port))
}

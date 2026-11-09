package lxruntime

import (
	"context"
	"encoding/base64"
	"io"
	"net"
	"net/http"
	"net/netip"
	"strings"
	"testing"
)

func TestPublicAddressAndURLPolicies(t *testing.T) {
	for _, s := range []string{"0.0.0.0", "10.1.2.3", "127.0.0.1", "169.254.169.254", "172.16.0.1", "192.168.1.1", "100.64.0.1", "192.0.2.1", "198.18.1.1", "198.51.100.1", "203.0.113.1", "224.0.0.1", "255.255.255.255", "::1", "::", "::ffff:127.0.0.1", "fe80::1", "fc00::1", "2001:db8::1", "2002:7f00:1::1"} {
		if publicAddress(netip.MustParseAddr(s)) {
			t.Errorf("accepted nonpublic %s", s)
		}
	}
	for _, s := range []string{"8.8.8.8", "1.1.1.1", "2606:4700:4700::1111"} {
		if !publicAddress(netip.MustParseAddr(s)) {
			t.Errorf("rejected public %s", s)
		}
	}
	for _, s := range []string{"file:///tmp/source.js", "http://user:secret@example.com", "http://localhost/", "http://localhost./", "http://test.localhost/", "http://127.0.0.1/", "https://example.com/#fragment", "http://[fe80::1%25en0]/", "http://127.1/", "http://example.com:0/"} {
		if _, err := checkedURL(s, false); err == nil {
			t.Errorf("accepted %s", s)
		}
	}
	if _, err := checkedURL("http://example.com/source.js", true); err == nil {
		t.Fatal("accepted insecure import")
	}
	if _, err := checkedURL("https://example.com/source.js?key=opaque", true); err != nil {
		t.Fatal(err)
	}
}

func TestDNSAnswersAreAllCheckedAndDialUsesPinnedAddress(t *testing.T) {
	n := newNetwork()
	var dialed string
	n.lookup = func(context.Context, string) ([]net.IPAddr, error) {
		return []net.IPAddr{{IP: net.ParseIP("8.8.8.8")}, {IP: net.ParseIP("127.0.0.1")}}, nil
	}
	n.dial = func(ctx context.Context, network, address string) (net.Conn, error) {
		dialed = address
		a, b := net.Pipe()
		b.Close()
		return a, nil
	}
	if _, err := n.dialPublic(t.Context(), "tcp", "example.com:443"); err == nil || dialed != "" {
		t.Fatal("mixed DNS answers bypassed policy")
	}
	n.lookup = func(context.Context, string) ([]net.IPAddr, error) {
		return []net.IPAddr{{IP: net.ParseIP("8.8.8.8")}}, nil
	}
	conn, err := n.dialPublic(t.Context(), "tcp", "example.com:443")
	if err != nil {
		t.Fatal(err)
	}
	conn.Close()
	if dialed != "8.8.8.8:443" {
		t.Fatalf("DNS was resolved again: %s", dialed)
	}
	transport := n.client.Transport.(*http.Transport)
	if transport.Proxy != nil || transport.TLSClientConfig != nil && transport.TLSClientConfig.InsecureSkipVerify {
		t.Fatal("unsafe transport defaults")
	}
}

type roundTrip func(*http.Request) (*http.Response, error)

func (f roundTrip) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }
func response(code int, headers http.Header, body string) *http.Response {
	return &http.Response{StatusCode: code, Header: headers, Body: io.NopCloser(strings.NewReader(body))}
}

func TestRedirectsStripCredentialsAndRecheckDestinations(t *testing.T) {
	n := newNetwork()
	var calls int
	n.client.Transport = roundTrip(func(r *http.Request) (*http.Response, error) {
		calls++
		if calls == 1 {
			if r.Header.Get("Authorization") != "secret" || r.Header.Get("Host") != "" {
				t.Fatal("headers not normalized")
			}
			return response(302, http.Header{"Location": {"https://other.example/audio"}}, ""), nil
		}
		if r.Header.Get("Authorization") != "" || r.Header.Get("Cookie") != "" || r.Header.Get("Referer") != "" || r.Method != "GET" {
			t.Fatal("cross-origin credential/body leakage")
		}
		return response(200, http.Header{"Content-Type": {"audio/flac"}}, "audio"), nil
	})
	body := "payload"
	got, err := n.request(t.Context(), frame{ID: 1, URL: "https://example.com/resolve", Method: "POST", Body: &body, Headers: map[string]string{"Authorization": "secret", "Cookie": "credential", "Referer": "private", "Host": "ignored"}})
	if err != nil {
		t.Fatal(err)
	}
	decoded, _ := base64.StdEncoding.DecodeString(*got.Body)
	if calls != 2 || got.URL != "https://other.example/audio" || string(decoded) != "audio" {
		t.Fatalf("unexpected reply: %+v", got)
	}
	for _, location := range []string{"http://example.com/insecure", "https://127.0.0.1/private", "https://user:secret@example.com/"} {
		n.client.Transport = roundTrip(func(r *http.Request) (*http.Response, error) {
			return response(302, http.Header{"Location": {location}}, ""), nil
		})
		if _, err := n.request(t.Context(), frame{ID: 1, URL: "https://example.com/resolve", Method: "GET"}); err == nil {
			t.Fatalf("accepted redirect %s", location)
		}
	}
}

func TestHTTPBodyAndMethodLimits(t *testing.T) {
	n := newNetwork()
	n.client.Transport = roundTrip(func(r *http.Request) (*http.Response, error) {
		return response(200, http.Header{}, strings.Repeat("x", maxBody+1)), nil
	})
	if _, err := n.request(t.Context(), frame{ID: 1, URL: "https://example.com/", Method: "GET"}); err == nil {
		t.Fatal("accepted oversized response")
	}
	if _, err := n.request(t.Context(), frame{ID: 1, URL: "https://example.com/", Method: "CONNECT"}); err == nil {
		t.Fatal("accepted CONNECT")
	}
	body := strings.Repeat("x", maxBody+1)
	if _, err := n.request(t.Context(), frame{ID: 1, URL: "https://example.com/", Method: "POST", Body: &body}); err == nil {
		t.Fatal("accepted oversized body")
	}
}

package metadata

import (
	"context"
	"io"
	"net/http"
	"net/url"
	"strings"
	"testing"
)

type artworkTransport func(*http.Request) (*http.Response, error)

func (f artworkTransport) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }

func TestArtistArtworkSkipsDisabledProviders(t *testing.T) {
	for _, tc := range []struct {
		name       string
		enabled    bool
		status     int
		wantStatus int
		wantCalls  int
	}{
		{"disabled scraping is optional", false, 200, 0, 0},
		{"no match does not retry a disabled fallback", true, 200, 0, 1},
		{"disabled fallback preserves active source failure", true, 503, 503, 1},
	} {
		t.Run(tc.name, func(t *testing.T) {
			f := setup(t)
			if tc.enabled {
				f.settings(t, false)
			}
			calls := 0
			f.s.client.Transport = artworkTransport(func(r *http.Request) (*http.Response, error) {
				calls++
				if r.URL.Hostname() != "interfacepc.music.163.com" {
					t.Fatalf("queried a disabled source: %s", r.URL.Hostname())
				}
				return &http.Response{StatusCode: tc.status, Header: make(http.Header), Body: io.NopCloser(strings.NewReader(`{"code":200,"result":{"songs":[]}}`))}, nil
			})
			art, err := f.s.ArtistArtwork(context.Background(), "Artist", []string{"Song"}, []ArtistRef{{"QQ", "disabled-mid", "Artist"}})
			status := 0
			if err != nil {
				status = code(err)
			}
			if art != nil || status != tc.wantStatus || calls != tc.wantCalls {
				t.Fatalf("art=%v error=%v calls=%d", art, err, calls)
			}
		})
	}
}

func TestArtistArtworkRequestsBoundedHTTPSPortrait(t *testing.T) {
	f := setup(t)
	f.settings(t, false)
	calls := 0
	f.s.client.Transport = artworkTransport(func(r *http.Request) (*http.Response, error) {
		calls++
		if r.Method != http.MethodPost || r.URL.Hostname() != "music.163.com" || r.URL.Path != "/weapi/v1/artist/7" {
			t.Fatalf("unexpected artist identity lookup: %s %s", r.Method, r.URL)
		}
		return &http.Response{StatusCode: 200, Header: make(http.Header), Body: io.NopCloser(strings.NewReader(`{"code":200,"artist":{"name":"Artist","img1v1Url":"http://p1.music.126.net/portrait.jpg?other=keep&param=4096y4096"}}`))}, nil
	})
	art, err := f.s.ArtistArtwork(context.Background(), "Artist", nil, []ArtistRef{{"NETEASE", "7", "Artist"}})
	if err != nil || art == nil || calls != 1 {
		t.Fatalf("art=%v error=%v calls=%d", art, err, calls)
	}
	u, err := url.Parse(art.Portrait)
	if err != nil || u.Scheme != "https" || u.Hostname() != "p1.music.126.net" || u.Query().Get("param") != "640y640" || u.Query().Get("other") != "keep" {
		t.Fatalf("unbounded or invalid portrait URL: %s", art.Portrait)
	}
}

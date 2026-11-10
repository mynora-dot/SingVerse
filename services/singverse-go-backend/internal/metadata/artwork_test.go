package metadata

import (
	"context"
	"encoding/json"
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
			} else {
				request(t, f.router, "PUT", "/api/v1/metadata/settings", Settings{Enabled: false, Providers: []string{"NETEASE"}, Threshold: .95}, 200)
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

func TestKugouArtistArtworkIdentityAndCandidates(t *testing.T) {
	for _, mode := range []string{"confirmed ID", "matching song", "unverified name", "conflicting IDs", "wrong name"} {
		t.Run(mode, func(t *testing.T) {
			f := setup(t)
			request(t, f.router, "PUT", "/api/v1/metadata/settings", Settings{Enabled: true, Providers: []string{"KUGOU"}, Threshold: .9}, 200)
			calls := 0
			f.s.client.Transport = artworkTransport(func(r *http.Request) (*http.Response, error) {
				calls++
				if r.URL.Hostname() != "gateway.kugou.com" || r.Header.Get("x-router") != "complexsearch.kugou.com" || r.URL.Query().Get("signature") != kugouSignature(r.URL.Query()) {
					t.Fatal("unsigned or unexpected request", r.URL)
				}
				body := `{"status":1,"error_code":0,"data":{"lists":[{"AuthorId":3060,"AuthorName":"Artist","Avatar":"http://singerimg.kugou.com/uploadpic/softhead/240/portrait.jpg"},{"AuthorId":759405,"AuthorName":"Artist","Avatar":"https://singerimg.kugou.com/uploadpic/softhead/240/other.jpg"},{"AuthorId":4,"AuthorName":"Someone else","Avatar":"https://singerimg.kugou.com/other.jpg"}]}}`
				if r.URL.Path == "/v3/search/song" {
					body = `{"status":1,"error_code":0,"data":{"lists":[{"FileHash":"097E777171F692855EAE98B6965F9477","OriSongName":"Song","SingerName":"Artist","Singers":[{"id":3060,"name":"Artist"}],"Image":"https://imge.kugou.com/album.jpg"}]}}`
				}
				return &http.Response{StatusCode: 200, Header: make(http.Header), Body: io.NopCloser(strings.NewReader(body))}, nil
			})
			var refs []ArtistRef
			var titles []string
			name := "Artist"
			switch mode {
			case "confirmed ID":
				refs = []ArtistRef{{"KUGOU", "3060", "Artist"}}
			case "matching song":
				titles = []string{"Song"}
			case "conflicting IDs":
				refs = []ArtistRef{{"KUGOU", "3060", "Artist"}, {"KUGOU", "759405", "Artist"}}
			case "wrong name":
				name = "Unrelated"
			}
			art, err := f.s.ArtistArtworkFrom(context.Background(), name, titles, refs, "KUGOU")
			if err != nil {
				t.Fatal(err)
			}
			if mode == "wrong name" {
				if art != nil {
					t.Fatal("wrong identity accepted", art)
				}
				return
			}
			if art == nil {
				t.Fatal("missing artist result")
			}
			if mode == "confirmed ID" || mode == "matching song" {
				if art.ExternalID != "3060" || art.Portrait != "https://singerimg.kugou.com/uploadpic/softhead/240/portrait.jpg" || len(art.Candidates) != 0 {
					t.Fatal("artist portrait or identity lost", art)
				}
			} else if art.Portrait != "" || len(art.Candidates) != 2 {
				t.Fatal("unverified name bypassed confirmation", art)
			}
			if calls > 2 {
				t.Fatal("unexpected fallback", calls)
			}
			enabled, err := f.s.ArtistArtworkProviders(context.Background(), f.s.db)
			if err != nil || len(enabled) != 1 || enabled[0] != "KUGOU" {
				t.Fatal(enabled, err)
			}
		})
	}
}

func TestKugouSongArtistRefsKeepNumericIdentity(t *testing.T) {
	var root map[string]any
	d := json.NewDecoder(strings.NewReader(`{"songs":[{"FileHash":"097E777171F692855EAE98B6965F9477","OriSongName":"Song","SingerName":"Artist","Singers":[{"id":6807264292040083458,"name":"Artist"}]}]}`))
	d.UseNumber()
	if err := d.Decode(&root); err != nil {
		t.Fatal(err)
	}
	tracks := parseKugou(array(root["songs"]), 20)
	if len(tracks) != 1 || *tracks[0].Title != "Song" || len(tracks[0].ArtistRefs) != 1 || tracks[0].ArtistRefs[0].ID != "6807264292040083458" {
		t.Fatal(tracks)
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

func TestArtistArtworkExplicitPlatformDoesNotFallback(t *testing.T) {
	f := setup(t)
	f.settings(t, false)
	calls := 0
	f.s.client.Transport = artworkTransport(func(r *http.Request) (*http.Response, error) {
		calls++
		if r.URL.Hostname() != "interfacepc.music.163.com" {
			t.Fatal("explicit provider fell back", r.URL.Hostname())
		}
		return &http.Response{StatusCode: 200, Header: make(http.Header), Body: io.NopCloser(strings.NewReader(`{"code":200,"result":{"songs":[]}}`))}, nil
	})
	art, err := f.s.ArtistArtworkFrom(context.Background(), "Artist", []string{"Song"}, []ArtistRef{{"QQ", "other", "Artist"}}, "NETEASE")
	if err != nil || art != nil || calls != 1 {
		t.Fatal(art, err, calls)
	}
	_, err = f.s.ArtistArtworkFrom(context.Background(), "Artist", []string{"Song"}, nil, "QQ")
	if err == nil || code(err) != 409 || calls != 1 {
		t.Fatal("disabled platform was used", err, calls)
	}
	_, err = f.s.ArtistArtworkFrom(context.Background(), "Artist", nil, nil, "INVALID")
	if err == nil || code(err) != 400 {
		t.Fatal("invalid platform accepted", err)
	}
	enabled, err := f.s.ArtistArtworkProviders(context.Background(), f.s.db)
	if err != nil || len(enabled) != 1 || enabled[0] != "NETEASE" {
		t.Fatal(enabled, err)
	}
}

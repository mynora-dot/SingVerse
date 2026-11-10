package metadata

import (
	"context"
	"io"
	"net/http"
	"strings"
	"testing"
)

func TestPublicSongProviderProtocols(t *testing.T) {
	for _, provider := range []string{"KUWO", "QISHUI"} {
		t.Run(provider, func(t *testing.T) {
			f := setup(t)
			calls := 0
			f.s.client.Transport = artworkTransport(func(r *http.Request) (*http.Response, error) {
				calls++
				body := ""
				if provider == "KUWO" {
					if r.URL.Hostname() != "search.kuwo.cn" || r.URL.Path != "/r.s" || r.URL.Query().Get("ft") != "music" || r.URL.Query().Get("all") != "Song Artist" {
						t.Fatal("wrong Kuwo catalogue request", r.URL)
					}
					body = `{'TOTAL':'1','abslist':[{'MUSICRID':'MUSIC_145389372','SONGNAME':'Song&nbsp;(Live)','ARTIST':'Artist','ARTISTID':'947','ALBUM':'Album','DURATION':'257','web_timingonline':'2020-07-17&nbsp;00:00:00','web_albumpic_short':'120/64/30/2753403960.jpg'}]}`
				} else {
					if r.URL.Hostname() != "api.qishui.com" || r.URL.Path != "/luna/search/track" || r.URL.Query().Get("q") != "Song Artist" || !strings.HasPrefix(r.Header.Get("User-Agent"), "Luna/") {
						t.Fatal("wrong Qishui catalogue request", r.URL)
					}
					body = `{"status_info":{},"result_groups":[{"data":[{"entity":{"track":{"id":6850116360324810753,"name":"Song","artists":[{"id":6807264292040083458,"name":"Artist"}],"duration":257254,"album":{"name":"Album","release_date":1594944000,"url_cover":{"uri":"tos-cn-v-2774c002/cover","urls":["https://p3-luna.douyinpic.com/img/"],"template_prefix":"tplv-b829550vbb"}}}}}]}]}`
				}
				return &http.Response{StatusCode: 200, Header: make(http.Header), Body: io.NopCloser(strings.NewReader(body))}, nil
			})
			p := &musicProvider{service: f.s, name: provider}
			tracks, err := p.Search(context.Background(), "Song Artist", 8)
			if err != nil || len(tracks) != 1 || calls != 1 || *tracks[0].Provider != provider || tracks[0].CoverURL == nil {
				t.Fatalf("tracks=%+v calls=%d error=%v", tracks, calls, err)
			}
			track := tracks[0]
			if provider == "KUWO" && (*track.Title != "Song (Live)" || *track.DurationMs != 257000 || *track.ReleaseDate != "2020-07-17" || *track.CoverURL != "https://img1.kuwo.cn/star/albumcover/0/64/30/2753403960.jpg") {
				t.Fatalf("Kuwo metadata lost or thumbnail substituted: %+v", track)
			}
			if provider == "QISHUI" && (*track.ExternalID != "6850116360324810753" || track.ArtistRefs[0].ID != "6807264292040083458" || *track.DurationMs != 257254 || !strings.HasSuffix(*track.CoverURL, "-image.image")) {
				t.Fatalf("Qishui ID precision, duration or original lost: %+v", track)
			}
		})
	}
}

func TestPublicProviderFailuresRemainDistinctFromNoMatches(t *testing.T) {
	for _, tc := range []struct {
		provider, body string
		wantError      bool
	}{
		{"KUWO", `{'TOTAL':'0','abslist':[]}`, false},
		{"QISHUI", `{"status_info":{},"result_groups":[]}`, false},
		{"KUWO", `null`, true},
		{"KUWO", `{'TOTAL':'0','abslist':[]}; alert(1)`, true},
		{"QISHUI", `{"status_info":{"code":429}}`, true},
		{"QISHUI", `{"result_groups":[]} {}`, true},
	} {
		t.Run(tc.provider+tc.body[:min(12, len(tc.body))], func(t *testing.T) {
			f := setup(t)
			f.s.client.Transport = artworkTransport(func(*http.Request) (*http.Response, error) {
				return &http.Response{StatusCode: 200, Header: make(http.Header), Body: io.NopCloser(strings.NewReader(tc.body))}, nil
			})
			tracks, err := (&musicProvider{service: f.s, name: tc.provider}).Search(context.Background(), "Song", 8)
			if (err != nil) != tc.wantError || (!tc.wantError && len(tracks) != 0) {
				t.Fatal("no results and failed source conflated", tracks, err)
			}
		})
	}
	if cover := kuwoAlbumCover(map[string]any{"web_artistpic_short": "120/artist.jpg", "MVPIC": "120/video.jpg"}); cover != nil {
		t.Fatal("artist or video image substituted for album cover")
	}
	for _, uri := range []string{"../private", "https://evil.invalid/x", "//evil.invalid/x"} {
		if cover := qishuiAlbumCover(map[string]any{"uri": uri, "urls": []any{"https://p3-luna.douyinpic.com/img/"}, "template_prefix": "tplv-test"}); cover != nil {
			t.Fatal("unsafe Qishui image template accepted", uri)
		}
	}
}

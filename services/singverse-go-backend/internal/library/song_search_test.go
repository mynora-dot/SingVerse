package library

import (
	"context"
	"fmt"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestIdentitySearchBeforePaginationAndLegacyPath(t *testing.T) {
	s, router := newTestService(t)
	dir := t.TempDir()
	for i := range 25 {
		if err := os.WriteFile(filepath.Join(dir, fmt.Sprintf("存档歌手-存档歌名%02d.mkv", i)), []byte("isolated media"), 0600); err != nil {
			t.Fatal(err)
		}
	}
	source := localSource(t, router, dir)
	sourceID := source["id"].(string)
	requestJSON(t, router, "POST", "/api/v1/media-sources/"+sourceID+"/scan", nil, 200)
	all := requestJSON(t, router, "GET", "/api/v1/media-songs?size=100", nil, 200)["items"].([]any)
	expected := []string{}
	for index, raw := range all {
		id := raw.(map[string]any)["id"].(string)
		if index >= 20 {
			continue
		}
		song, err := s.readSong(context.Background(), s.db, id)
		if err != nil {
			t.Fatal(err)
		}
		track := map[string]any{"title": fmt.Sprintf("收藏·第%02d首", index), "artists": []string{"家里的歌手"}}
		if index == 0 {
			track["title"] = "100%_! LIVE"
		}
		_, err = s.db.ExecContext(context.Background(), "INSERT INTO song_metadata(song_id,source_id,fingerprint,track_json,fields_json,origins_json,applied_at) VALUES(?,?,?,?,?,?,?)", id, sourceID, song.contentFingerprint, encode(track), `["title","artists"]`, `{"title":{"kind":"MANUAL"},"artists":{"kind":"MANUAL"}}`, now())
		if err != nil {
			t.Fatal(err)
		}
		if err = store.WithinTx(context.Background(), s.db, func(tx store.DBTX) error {
			return s.ApplyFormal(context.Background(), tx, id, track, map[string]any{"title": map[string]any{"kind": "MANUAL"}, "artists": map[string]any{"kind": "MANUAL"}})
		}); err != nil {
			t.Fatal(err)
		}
		expected = append(expected, id)
	}
	// Matches live outside the first page and retain the established stable order.
	for page := range 4 {
		path := fmt.Sprintf("/api/v1/media-songs?search=%s&page=%d&size=5", url.QueryEscape("家里的歌手"), page)
		result := requestJSON(t, router, "GET", path, nil, 200)
		items := result["items"].([]any)
		if result["totalElements"] != float64(20) || len(items) != 5 {
			t.Fatal(result)
		}
		for index, item := range items {
			if item.(map[string]any)["id"] != expected[page*5+index] {
				t.Fatal("unstable search order", result)
			}
		}
	}
	for _, tc := range []struct {
		query string
		count float64
	}{
		{"search=" + url.QueryEscape("100%_! live"), 1},
		{"search=" + url.QueryEscape("%_!"), 1},
		{"search=" + url.QueryEscape("收藏·"), 19},
		{"search=" + url.QueryEscape("存档歌手"), 25}, // the relative path remains searchable
		{"q=" + url.QueryEscape("家里的歌手"), 0},
		{"q=" + url.QueryEscape("存档歌名") + "&search=" + url.QueryEscape("家里的歌手"), 20},
		{"q=missing&search=" + url.QueryEscape("家里的歌手"), 0},
		{"sourceId=missing-source&search=" + url.QueryEscape("家里的歌手"), 0},
	} {
		result := requestJSON(t, router, "GET", "/api/v1/media-songs?"+tc.query, nil, 200)
		if result["totalElements"] != tc.count {
			t.Fatalf("%s: %#v", tc.query, result)
		}
	}
	requestJSON(t, router, "GET", "/api/v1/media-songs?search="+url.QueryEscape(strings.Repeat("长", 201)), nil, 400)
	requestJSON(t, router, "GET", "/api/v1/media-songs?search=bad%00value", nil, 400)
	// A file fingerprint change must preserve the independent formal identity.
	if _, err := s.db.ExecContext(context.Background(), "UPDATE song_metadata SET fingerprint=? WHERE song_id=?", "stale", expected[0]); err != nil {
		t.Fatal(err)
	}
	stale := requestJSON(t, router, "GET", "/api/v1/media-songs?search="+url.QueryEscape("100%_! live"), nil, 200)
	if stale["totalElements"] != float64(1) {
		t.Fatal("formal identity lost after file fingerprint changed", stale)
	}
}

func TestIdentitySearchUsesEffectiveFields(t *testing.T) {
	// Values outside the confirmed field list must never replace parsed identity.
	song := Song{RelativePath: "a.mkv", Result: ParseResult{Title: ptr("文件歌名"), Artists: []string{"文件歌手"}}}
	song.Metadata = map[string]any{"stale": false, "fields": []string{"album"}, "track": map[string]any{"title": "未确认歌名", "artists": []any{"未确认歌手"}}}
	if !matchesSong(song, "文件歌手") || matchesSong(song, "未确认") {
		t.Fatal("unconfirmed fields changed search identity")
	}
	song.Metadata = map[string]any{"stale": false, "fields": []string{"title", "artists"}, "track": map[string]any{"title": "已确认歌名", "artists": []any{"已确认歌手"}}}
	if !matchesSong(song, "已确认") || matchesSong(song, "文件歌手") {
		t.Fatal("effective identity not used")
	}
}

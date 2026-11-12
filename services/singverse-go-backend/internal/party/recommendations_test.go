package party

import (
	"context"
	"io"
	"log/slog"
	"strings"
	"testing"

	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/playback"
)

func enablePlaybackReads(t *testing.T, f *fixture) {
	t.Helper()
	activity, err := playback.New(f.db, "sqlite", func(ctx context.Context, tx store.DBTX, id string) (map[string]any, error) {
		song, err := f.lib.SongWith(ctx, tx, id)
		if err != nil {
			return nil, err
		}
		projection := Projection(song)
		projection["versionType"] = projection["version"]
		return projection, nil
	}, slog.New(slog.NewTextHandler(io.Discard, nil)))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { activity.Close() })
	f.s.SetActivity(activity)
}

func TestTVRecommendationsReadActualPlaybackLifecycle(t *testing.T) {
	f := newPartyFixture(t)
	enablePlaybackReads(t, f)
	device, _ := f.device(t)
	session := want(t, f.request(t, "POST", "/api/v1/tv/sessions", nil, device, false, false), 200)
	sid := str(session, "id")
	commands := "/api/v1/tv/sessions/" + sid + "/commands"
	want(t, f.request(t, "POST", commands, document{"requestId": "add", "expectedRevision": 0, "action": "ADD", "songId": "song-a"}, device, false, false), 200)
	started := want(t, f.request(t, "POST", commands, document{"requestId": "start", "expectedRevision": 1, "action": "START"}, device, false, false), 200)
	event := document{"attemptId": object(started, "playback")["attemptId"], "eventSeq": 1, "state": "PLAYING", "positionMs": 0, "actualPlayedMs": 0, "elapsedMs": 0, "durationMs": 1000}
	events := "/api/v1/tv/sessions/" + sid + "/playback-events"
	want(t, f.request(t, "POST", events, event, device, false, false), 200)
	active := want(t, f.request(t, "GET", "/api/v1/tv/playback-history", nil, device, false, false), 200)
	if number(active, "totalElements") != 0 {
		t.Fatal(active)
	}
	event["eventSeq"], event["state"], event["positionMs"], event["actualPlayedMs"], event["elapsedMs"] = 2, "ENDED", 1000, 1000, 1000
	want(t, f.request(t, "POST", events, event, device, false, false), 200)
	want(t, f.request(t, "POST", events, event, device, false, false), 200)
	history := want(t, f.request(t, "GET", "/api/v1/tv/playback-history", nil, device, false, false), 200)
	if number(history, "totalElements") != 1 {
		t.Fatal(history)
	}
	record := array(history, "items")[0].(map[string]any)
	if str(record, "title") != "晴天" || str(record, "status") != "COMPLETED" || !boolean(record, "effective") || !boolean(record, "available") {
		t.Fatal(record)
	}
	home := want(t, f.request(t, "GET", "/api/v1/tv/home", nil, device, false, false), 200)
	popular, recent := array(home, "popularSongs"), array(home, "recentlyPlayedSongs")
	if len(popular) != 1 || number(popular[0].(map[string]any), "effectivePlayCount") != 1 || len(recent) != 1 || str(recent[0].(map[string]any), "lastHistoryId") != str(record, "id") {
		t.Fatal(home)
	}
}

func seedRecommendationHistory(t *testing.T, f *fixture, id, song, session, status string, started int64) {
	t.Helper()
	var ended any
	if status != "PLAYING" {
		ended = started + 30000
	}
	_, err := f.db.Exec(`INSERT INTO playback_history
		(id,song_id,title,artists_json,client_type,session_id,device_name,requested_by,started_at,ended_at,played_ms,duration_ms,status)
		VALUES (?,?,?,'["历史歌手"]','TV',?,'私密设备','私密亲友',?,?,30000,180000,?)`, id, song, "历史标题", session, started, ended, status)
	if err != nil {
		t.Fatal(err)
	}
}

func TestTVRecommendationHTTPDeviceIsolationCurrentSongsAndMissingSnapshots(t *testing.T) {
	f := newPartyFixture(t)
	enablePlaybackReads(t, f)
	device, deviceID := f.device(t)
	other, _ := f.device(t)
	session := want(t, f.request(t, "POST", "/api/v1/tv/sessions", nil, device, false, false), 200)
	otherSession := want(t, f.request(t, "POST", "/api/v1/tv/sessions", nil, other, false, false), 200)
	if _, err := f.db.Exec("INSERT INTO tv_record(id,kind,document) VALUES (?,'session',?)", "prior-session", encode(document{"id": "prior-session", "deviceId": deviceID, "status": "CLOSED"})); err != nil {
		t.Fatal(err)
	}
	now := f.clock.UnixMilli()
	seedRecommendationHistory(t, f, "prior-a", "song-a", "prior-session", "COMPLETED", now-40*86400000)
	seedRecommendationHistory(t, f, "recent-a", "song-a", str(session, "id"), "SKIPPED", now-60000)
	seedRecommendationHistory(t, f, "prior-b", "song-b", "prior-session", "ERROR", now-70000)
	seedRecommendationHistory(t, f, "gone", "removed", "prior-session", "INTERRUPTED", now-65000)
	seedRecommendationHistory(t, f, "active-b", "song-b", str(session, "id"), "PLAYING", now-50000)
	seedRecommendationHistory(t, f, "other-a", "song-a", str(otherSession, "id"), "COMPLETED", now-40000)
	home := want(t, f.request(t, "GET", "/api/v1/tv/home", nil, device, false, false), 200)
	history := array(home, "recentlyPlayedSongs")
	if len(history) != 2 {
		t.Fatal(home)
	}
	first := history[0].(map[string]any)
	if str(first, "id") != "song-a" || str(first, "title") != "晴天" || str(first, "lastPlaybackStatus") != "SKIPPED" || str(first, "lastHistoryId") != "recent-a" {
		t.Fatal(first)
	}
	page := want(t, f.request(t, "GET", "/api/v1/tv/playback-history?size=2", nil, device, false, false), 200)
	if number(page, "totalElements") != 4 || len(array(page, "items")) != 2 {
		t.Fatal(page)
	}
	items := array(page, "items")
	available := items[0].(map[string]any)
	missing := items[1].(map[string]any)
	if str(available, "title") != "历史标题" || str(object(available, "song"), "title") != "晴天" || !boolean(available, "available") {
		t.Fatal(available)
	}
	if str(missing, "id") != "gone" || boolean(missing, "available") || missing["song"] != nil || str(missing, "title") != "历史标题" {
		t.Fatal(missing)
	}
	if body := encode(page); strings.Contains(body, "私密") || strings.Contains(body, "sessionId") || strings.Contains(body, "deviceId") {
		t.Fatalf("history exposed identity: %s", body)
	}
	otherHistory := want(t, f.request(t, "GET", "/api/v1/tv/playback-history", nil, other, false, false), 200)
	if number(otherHistory, "totalElements") != 1 || str(array(otherHistory, "items")[0].(map[string]any), "id") != "other-a" {
		t.Fatal(otherHistory)
	}
	popular := want(t, f.request(t, "GET", "/api/v1/tv/popular?size=1", nil, device, false, false), 200)
	if number(popular, "totalElements") != 2 || len(array(popular, "items")) != 1 || str(array(popular, "items")[0].(map[string]any), "id") != "song-a" {
		t.Fatal(popular)
	}
	f.lib.songs[1]["mediaVerification"] = "MISSING"
	f.s.Invalidate()
	home = want(t, f.request(t, "GET", "/api/v1/tv/home", nil, device, false, false), 200)
	if len(array(home, "recentlyPlayedSongs")) != 1 || len(array(home, "popularSongs")) != 1 {
		t.Fatal(home)
	}
}

func TestTVRecommendationHTTPRequiresDeviceBearerAndValidPaging(t *testing.T) {
	f := newPartyFixture(t)
	enablePlaybackReads(t, f)
	device, deviceID := f.device(t)
	session := want(t, f.request(t, "POST", "/api/v1/tv/sessions", nil, device, false, false), 200)
	invite := want(t, f.request(t, "POST", "/api/v1/tv/sessions/"+str(session, "id")+"/invitations", nil, device, false, false), 200)
	guest := want(t, f.request(t, "POST", "/api/v1/party/join", document{"invitation": invite["invitation"], "name": "客人"}, "", false, false), 200)
	for _, route := range []string{"/api/v1/tv/home", "/api/v1/tv/popular", "/api/v1/tv/playback-history"} {
		want(t, f.request(t, "GET", route, nil, "", false, false), 401)
		want(t, f.request(t, "GET", route, nil, "", true, false), 401)
		want(t, f.request(t, "GET", route, nil, str(guest, "token"), false, false), 403)
		want(t, f.request(t, "GET", route+"?deviceId=other", nil, device, false, false), 400)
		want(t, f.request(t, "GET", route+"?sessionId=other", nil, device, false, false), 400)
		result := f.request(t, "GET", route, nil, device, false, false)
		want(t, result, 200)
		if result.header.Get("Cache-Control") != "no-store" {
			t.Fatal("device history must not be cached")
		}
	}
	for _, route := range []string{"/api/v1/tv/popular", "/api/v1/tv/playback-history"} {
		for _, query := range []string{"size=0", "size=101", "page=-1", "page=1000001", "page=nope"} {
			want(t, f.request(t, "GET", route+"?"+query, nil, device, false, false), 400)
		}
		empty := want(t, f.request(t, "GET", route, nil, device, false, false), 200)
		if number(empty, "totalElements") != 0 || len(array(empty, "items")) != 0 {
			t.Fatal(empty)
		}
	}
	want(t, f.request(t, "DELETE", "/api/v1/admin/tv-devices/"+deviceID, nil, "", true, true), 200)
	for _, route := range []string{"/api/v1/tv/home", "/api/v1/tv/popular", "/api/v1/tv/playback-history"} {
		want(t, f.request(t, "GET", route, nil, device, false, false), 401)
	}
}

package party

import (
	"context"
	"fmt"
	"github.com/gin-gonic/gin"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/library"
	"os"
	"testing"
	"time"
)

// Explicit opt-in keeps a real 100,000-song database exercise out of fast unit runs.
// All identities and list responses still pass through production HTTP handlers.
func TestHundredThousandSongHTTPQueries(t *testing.T) {
	if os.Getenv("SINGVERSE_PERF_100K") != "1" {
		t.Skip("set SINGVERSE_PERF_100K=1 for the isolated 100k-song regression")
	}
	f := newPartyFixture(t)
	lib, e := library.New(f.db, "sqlite", f.cfg, f.s.cipher, f.s.log)
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(func() { lib.Close() })
	f.s.library = lib
	lib.Register(f.server.Config.Handler.(*gin.Engine).Group("/api/v1"))
	const source = "11111111-1111-4111-8111-111111111111"
	at := "2026-11-09T01:00:00Z"
	if _, e = f.db.Exec("INSERT INTO media_source(id,name,source_type,location,location_hash,username,created_at,updated_at) VALUES(?,?,'LOCAL',?,'perf-source','',?,?)", source, "性能曲库", t.TempDir(), at, at); e != nil {
		t.Fatal(e)
	}
	started := time.Now()
	tx, e := f.db.BeginTxx(context.Background(), nil)
	if e != nil {
		t.Fatal(e)
	}
	stmt, e := tx.Prepare("INSERT INTO media_song(id,source_id,relative_path,path_hash,file_size,modified_at,scanned_at,parser_profile_id,parser_revision,result_json,needs_review) VALUES(?,?,?,?,1000,?,?,'default',0,?,0)")
	if e != nil {
		t.Fatal(e)
	}
	for i := 0; i < 100000; i++ {
		title, artist := fmt.Sprintf("测试歌曲%06d", i), fmt.Sprintf("歌手%03d", i%125)
		if i%100 == 0 {
			title = fmt.Sprintf("晴天%06d", i)
			artist = "周杰伦"
		}
		key := fmt.Sprintf("perf-%06d", i)
		raw := encode(document{"title": title, "artists": []string{artist}, "language": "MANDARIN", "versionType": "ORIGINAL", "qualityTags": []string{}, "extension": "mp4", "songCode": fmt.Sprintf("%06d", i)})
		if _, e = stmt.Exec(key, source, title+".mp4", hash(key), at, at, raw); e != nil {
			stmt.Close()
			tx.Rollback()
			t.Fatal(e)
		}
	}
	stmt.Close()
	if e = tx.Commit(); e != nil {
		t.Fatal(e)
	}
	t.Logf("seeded 100000 real SQLite songs in %s", time.Since(started))
	device, _ := f.device(t)
	session := want(t, f.request(t, "POST", "/api/v1/tv/sessions", nil, device, false, false), 200)
	invite := want(t, f.request(t, "POST", "/api/v1/tv/sessions/"+str(session, "id")+"/invitations", nil, device, false, false), 200)
	guest := want(t, f.request(t, "POST", "/api/v1/party/join", document{"invitation": invite["invitation"], "name": "性能亲友"}, "", false, false), 200)
	guestToken := str(guest, "token")
	type query struct {
		name, path, token string
		admin             bool
		total             int
	}
	queries := []query{{"admin first page", "/api/v1/media-songs?page=0&size=20", "", true, 100000}, {"admin text search", "/api/v1/media-songs?q=晴天&page=0&size=20", "", true, 1000}, {"TV cold index", "/api/v1/tv/library/songs?page=0&size=20", device, false, 100000}, {"TV warm pinyin", "/api/v1/tv/library/songs?q=zhoujielun&page=1&size=20", device, false, 1000}, {"H5 warm pinyin", "/api/v1/party/library/songs?q=zhoujielun&page=2&size=20", guestToken, false, 1000}, {"TV numeric code", "/api/v1/tv/library/songs?q=099900&page=0&size=20", device, false, 1}, {"TV last page", "/api/v1/tv/library/songs?page=4999&size=20", device, false, 100000}}
	seen := map[string]bool{}
	for _, q := range queries {
		start := time.Now()
		out := want(t, f.request(t, "GET", q.path, nil, q.token, q.admin, false), 200)
		elapsed := time.Since(start)
		items := array(out, "items")
		if number(out, "totalElements") != int64(q.total) || len(items) != min(20, q.total) {
			t.Fatalf("%s returned wrong page: total=%d items=%d", q.name, number(out, "totalElements"), len(items))
		}
		if q.name == "TV warm pinyin" || q.name == "H5 warm pinyin" {
			for _, v := range items {
				id := str(v.(map[string]any), "id")
				if seen[id] {
					t.Fatalf("duplicate song across pages %s", id)
				}
				seen[id] = true
			}
		}
		if q.name == "TV numeric code" && str(items[0].(map[string]any), "id") != "perf-099900" {
			t.Fatal("song code search did not match exact identity")
		}
		t.Logf("%s: %s (%d matching, %d delivered)", q.name, elapsed, q.total, len(items))
	}
}

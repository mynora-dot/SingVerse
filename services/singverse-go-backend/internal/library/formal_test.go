package library

import (
	"context"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/metastandard"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/metastore"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

func TestFormalDecisionsRenameMissingDetachAndRebind(t *testing.T) {
	s, r := newTestService(t)
	ctx := context.Background()
	root := t.TempDir()
	if e := os.WriteFile(filepath.Join(root, "歌手-错误歌名.mkv"), []byte("original media"), 0600); e != nil {
		t.Fatal(e)
	}
	source := localSource(t, r, root)
	sourceID := source["id"].(string)
	scan := "/api/v1/media-sources/" + sourceID + "/scan"
	requestJSON(t, r, "POST", scan, nil, 200)
	files := requestJSON(t, r, "GET", "/api/v1/media-songs", nil, 200)["items"].([]any)
	mediaID := files[0].(map[string]any)["id"].(string)
	songID, variantID, e := s.FormalIDs(ctx, s.db, mediaID)
	if e != nil {
		t.Fatal(e)
	}
	d, e := s.formal.Read(ctx, s.db, songID)
	if e != nil {
		t.Fatal(e)
	}
	in := metastore.Update{ExpectedRevision: d.Revision, RequestID: metastore.NewID(), Operations: []metastore.Operation{{Field: "title", Op: "SET", Value: "正式歌名"}}}
	u := requestJSON(t, r, "PATCH", "/api/v1/metadata/entities/"+songID, in, 200)
	retry := requestJSON(t, r, "PATCH", "/api/v1/metadata/entities/"+songID, in, 200)
	if retry["revision"] != u["revision"] {
		t.Fatal("retry created another revision")
	}
	in.Operations[0].Value = "不同内容"
	requestJSON(t, r, "PATCH", "/api/v1/metadata/entities/"+songID, in, 409)
	if e = os.Rename(filepath.Join(root, "歌手-错误歌名.mkv"), filepath.Join(root, "改名的文件.mkv")); e != nil {
		t.Fatal(e)
	}
	requestJSON(t, r, "POST", scan, nil, 200)
	if id, e := s.formal.PathMedia(ctx, s.db, sourceID, "改名的文件.mkv"); e != nil || id != mediaID {
		t.Fatal("rename lost media identity", id, e)
	}
	if song, variant, e := s.FormalIDs(ctx, s.db, mediaID); e != nil || song != songID || variant != variantID {
		t.Fatal("rename lost formal hierarchy", e)
	}
	d, e = s.formal.Read(ctx, s.db, songID)
	if e != nil || d.Fields["title"] != "正式歌名" || d.States["title"].Decision != "SET" {
		t.Fatal("rescan overwrote confirmation", d, e)
	}
	if e = os.Remove(filepath.Join(root, "改名的文件.mkv")); e != nil {
		t.Fatal(e)
	}
	requestJSON(t, r, "POST", scan, nil, 200)
	if _, e = s.formal.Read(ctx, s.db, songID); e != nil {
		t.Fatal("missing file deleted metadata", e)
	}
	loc, e := s.formal.Locations(ctx, s.db, mediaID)
	if e != nil || len(loc) != 1 || loc[0].State != "MISSING" {
		t.Fatal(loc, e)
	}
	otherRoot := t.TempDir()
	if e = os.WriteFile(filepath.Join(otherRoot, "中文 空格 100%.mkv"), []byte("original media"), 0600); e != nil {
		t.Fatal(e)
	}
	other := localSource(t, r, otherRoot)
	media, e := s.formal.Read(ctx, s.db, mediaID)
	if e != nil {
		t.Fatal(e)
	}
	binding := map[string]any{"sourceId": other["id"], "relativePath": "中文 空格 100%.mkv", "expectedRevision": media.Revision, "requestId": metastore.NewID()}
	requestJSON(t, r, "POST", "/api/v1/metadata/entities/"+mediaID+"/location", binding, 200)
	requestJSON(t, r, "POST", "/api/v1/metadata/entities/"+mediaID+"/location", binding, 200)
	s.verifyLocations(ctx)
	loc, e = s.formal.Locations(ctx, s.db, mediaID)
	if e != nil || len(loc) != 1 || loc[0].State != "PRESENT" || loc[0].SourceID == nil || *loc[0].SourceID != other["id"] {
		t.Fatal("own location verification failed", loc, e)
	}
	requestJSON(t, r, "DELETE", "/api/v1/media-sources/"+other["id"].(string), nil, 204)
	d, e = s.formal.Read(ctx, s.db, songID)
	if e != nil || d.Fields["title"] != "正式歌名" {
		t.Fatal("source removal deleted metadata", e)
	}
	loc, e = s.formal.Locations(ctx, s.db, mediaID)
	if e != nil || len(loc) != 1 || loc[0].State != "UNBOUND" || loc[0].SourceID != nil {
		t.Fatal(loc, e)
	}
}

func TestFormalCreateReceiptsReferencesAndStrictRequest(t *testing.T) {
	_, r := newTestService(t)
	songID := metastore.NewID()
	input := map[string]any{"requestId": songID, "type": "SONG", "fields": map[string]any{"title": "独立资料"}}
	created := requestJSON(t, r, "POST", "/api/v1/metadata/entities", input, 201)
	again := requestJSON(t, r, "POST", "/api/v1/metadata/entities", input, 201)
	if created["revision"] != again["revision"] {
		t.Fatal("creation retry changed revision")
	}
	input["fields"] = map[string]any{"title": "篡改创建"}
	requestJSON(t, r, "POST", "/api/v1/metadata/entities", input, 409)
	requestJSON(t, r, "POST", "/api/v1/metadata/entities", map[string]any{"requestId": metastore.NewID(), "type": "VARIANT", "parentId": metastore.NewID()}, 400)
	requestJSON(t, r, "PATCH", "/api/v1/metadata/entities/"+songID, metastore.Update{ExpectedRevision: created["revision"].(string), RequestID: metastore.NewID(), Operations: []metastore.Operation{{Field: "workId", Op: "SET", Value: metastore.NewID()}}}, 400)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, httptest.NewRequest("POST", "/api/v1/metadata/entities", strings.NewReader(`{"type":"SONG","type":"MEDIA"}`)))
	if w.Code != 400 {
		t.Fatal("duplicate request keys accepted", w.Code)
	}
	if !metastandard.UUID.MatchString(created["id"].(string)) {
		t.Fatal(created)
	}
}

func TestVariantResetRestoresInheritedDisplay(t *testing.T) {
	s, r := newTestService(t)
	ctx := context.Background()
	root := t.TempDir()
	if err := os.WriteFile(filepath.Join(root, "歌手-歌曲.mkv"), []byte("media"), 0600); err != nil {
		t.Fatal(err)
	}
	source := localSource(t, r, root)
	base := "/api/v1/media-sources/" + source["id"].(string)
	requestJSON(t, r, "POST", base+"/scan", nil, 200)
	files := requestJSON(t, r, "GET", base+"/songs", nil, 200)["items"].([]any)
	mediaID := files[0].(map[string]any)["id"].(string)
	songID, variantID, err := s.FormalIDs(ctx, s.db, mediaID)
	if err != nil {
		t.Fatal(err)
	}
	update := func(id, op string, value any) {
		t.Helper()
		doc, err := s.formal.Read(ctx, s.db, id)
		if err != nil {
			t.Fatal(err)
		}
		requestJSON(t, r, "PATCH", "/api/v1/metadata/entities/"+id, metastore.Update{ExpectedRevision: doc.Revision, RequestID: metastore.NewID(), Operations: []metastore.Operation{{Field: "title", Op: op, Value: value}}}, 200)
	}
	update(songID, "SET", "歌曲正式标题")
	update(variantID, "SET", "现场版标题")
	readTitle := func() any {
		t.Helper()
		files := requestJSON(t, r, "GET", base+"/songs", nil, 200)["items"].([]any)
		return files[0].(map[string]any)["metadata"].(map[string]any)["track"].(map[string]any)["title"]
	}
	if got := readTitle(); got != "现场版标题" {
		t.Fatal("version override missing", got)
	}
	update(variantID, "RESET", nil)
	if got := readTitle(); got != "歌曲正式标题" {
		t.Fatal("reset blocked inherited song title", got)
	}
	update(variantID, "CLEAR", nil)
	if got := readTitle(); got != nil {
		t.Fatal("clear failed to block inheritance", got)
	}
}

func TestFormalSplitReplacementAndPersistedLyrics(t *testing.T) {
	s, r := newTestService(t)
	ctx := context.Background()
	root := t.TempDir()
	if e := os.WriteFile(filepath.Join(root, "歌手-歌曲.mkv"), []byte("first content"), 0600); e != nil {
		t.Fatal(e)
	}
	source := localSource(t, r, root)
	base := "/api/v1/media-sources/" + source["id"].(string)
	requestJSON(t, r, "POST", base+"/scan", nil, 200)
	rows := requestJSON(t, r, "GET", base+"/songs", nil, 200)["items"].([]any)
	media := rows[0].(map[string]any)["id"].(string)
	song, variant, e := s.FormalIDs(ctx, s.db, media)
	if e != nil {
		t.Fatal(e)
	}
	e = s.transaction(ctx, func(tx store.DBTX) error {
		a, e := s.formal.PutAsset(ctx, tx, []byte("[00:01.00]本机歌词\n[00:02.00]离线可读\n"), "text/plain")
		if e != nil {
			return e
		}
		return s.formal.Decide(ctx, tx, variant, map[string]any{"lyricsAsset": metastandard.AssetRef{AssetID: a.ID}}, metastandard.Origin{Kind: "MANUAL"})
	})
	if e != nil {
		t.Fatal(e)
	}
	lyrics := requestJSON(t, r, "GET", "/api/v1/metadata/entities/"+media+"/lyrics", nil, 200)["lyrics"].(map[string]any)
	if lyrics["timing"] != "lrc" || !strings.Contains(lyrics["text"].(string), "本机歌词") {
		t.Fatal(lyrics)
	}
	v, e := s.formal.Read(ctx, s.db, variant)
	if e != nil {
		t.Fatal(e)
	}
	split := map[string]any{"expectedRevision": v.Revision, "requestId": metastore.NewID(), "mediaIds": []string{media}}
	out := requestJSON(t, r, "POST", "/api/v1/metadata/entities/"+variant+"/split", split, 201)
	retry := requestJSON(t, r, "POST", "/api/v1/metadata/entities/"+variant+"/split", split, 201)
	if retry["id"] != out["id"] {
		t.Fatal("split retry duplicated version")
	}
	split["mediaIds"] = []string{metastore.NewID()}
	requestJSON(t, r, "POST", "/api/v1/metadata/entities/"+variant+"/split", split, 409)
	_, newVariant, e := s.FormalIDs(ctx, s.db, media)
	if e != nil || newVariant == variant {
		t.Fatal(newVariant, e)
	}
	if lyrics, e := s.FormalLyrics(ctx, s.db, media); e != nil || lyrics != nil {
		t.Fatal("split incorrectly reused timeline", lyrics, e)
	}
	m, e := s.formal.Read(ctx, s.db, media)
	if e != nil {
		t.Fatal(e)
	}
	replace := map[string]any{"expectedRevision": m.Revision, "requestId": metastore.NewID()}
	newer := requestJSON(t, r, "POST", "/api/v1/metadata/entities/"+media+"/replacement", replace, 201)
	requestJSON(t, r, "POST", "/api/v1/metadata/entities/"+media+"/replacement", replace, 201)
	replacement := newer["id"].(string)
	if replacement == media || newer["parentId"] != newVariant {
		t.Fatal(newer)
	}
	if _, e := s.formal.Read(ctx, s.db, media); e != nil {
		t.Fatal("replaced media was deleted", e)
	}
	if old, e := s.formal.Locations(ctx, s.db, media); e != nil || old[0].SourceID != nil || old[0].State != "REPLACED" {
		t.Fatal(old, e)
	}
	requestJSON(t, r, "POST", base+"/scan", nil, 200)
	if id, e := s.formal.PathMedia(ctx, s.db, source["id"].(string), "歌手-歌曲.mkv"); e != nil || id != replacement {
		t.Fatal("rescan resurrected replaced content", id, e)
	}
	if parent, _, e := s.FormalIDs(ctx, s.db, replacement); e != nil || parent != song {
		t.Fatal("replacement lost song", e)
	}
}

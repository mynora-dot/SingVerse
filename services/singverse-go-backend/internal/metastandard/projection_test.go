package metastandard

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestPersistedLyricAssets(t *testing.T) {
	for _, tc := range []struct {
		mime, text, timing string
		lines              int
	}{
		{"text/plain", "[00:01.20]第一句\n", "lrc", 0},
		{"application/x-subrip", "1\n00:00:01,200 --> 00:00:02,500\n第一句\n第二行\n\n2\n00:00:03,000 --> 00:00:04,000\n下一句\n", "milliseconds", 2},
		{"text/vtt", "WEBVTT\n\n00:01.200 --> 00:02.500\n原文\n", "milliseconds", 1},
	} {
		v, e := LyricsAsset(tc.mime, []byte(tc.text))
		if e != nil || v["timing"] != tc.timing || arrayLength(v["lines"]) != tc.lines {
			t.Fatal(v, e)
		}
	}
	if _, e := LyricsAsset("application/json", []byte(`{"timing":"none","text":"a","text":"b"}`)); e == nil {
		t.Fatal("duplicate lyric keys accepted")
	}
	if _, e := LyricsAsset("image/png", []byte("not lyrics")); e == nil {
		t.Fatal("image accepted as lyrics")
	}
	if _, e := LyricsAsset("text/plain", []byte(strings.Repeat("a", 262145))); e == nil {
		t.Fatal("unbounded playback lyrics")
	}
}
func TestOrderedCreditProjectionAndParentState(t *testing.T) {
	doc := Document{SchemaVersion: Version, ID: "11111111-1111-4111-8111-111111111111", Type: "SONG", Revision: "3", Fields: map[string]any{"artists": []string{"旧标签"}, "credits": []Credit{{ArtistID: "22222222-2222-4222-8222-222222222222", Name: "正式署名"}}}, States: map[string]FieldState{"artists": {Decision: "AUTO", Status: "PRESENT", Revision: "1", Origin: Origin{Kind: "FILE"}}, "credits": {Decision: "SET", Status: "PRESENT", Revision: "2", Origin: Origin{Kind: "MANUAL"}}}}
	names, _, _ := Performers(doc)
	b, _ := json.Marshal(names)
	if string(b) != `["正式署名"]` {
		t.Fatal(names)
	}
	doc.Fields["artists"] = nil
	doc.States["artists"] = FieldState{Decision: "CLEAR", Status: "CLEARED", Revision: "3", Origin: Origin{Kind: "MANUAL"}}
	names, _, _ = Performers(doc)
	if names != nil {
		t.Fatal("later clear did not win", names)
	}
	doc.States["artists"] = FieldState{Decision: "AUTO", Status: "UNREAD", Revision: "3", Origin: Origin{Kind: "UNKNOWN"}}
	names, _, _ = Performers(doc)
	b, _ = json.Marshal(names)
	if string(b) != `["正式署名"]` {
		t.Fatal("unread reset blocked available credits", names)
	}
	doc.Type = "VARIANT"
	doc.ParentID = "33333333-3333-4333-8333-333333333333"
	doc.ParentState = &FieldState{Decision: "invented", Status: "PRESENT", Revision: "3", Origin: Origin{Kind: "UNKNOWN"}}
	if e := Validate(doc); e == nil {
		t.Fatal("invalid parent state accepted")
	}
	if e := ValidateField("MEDIA", "observation", map[string]any{"unsafe": json.Number("9007199254740991.1")}); e == nil {
		t.Fatal("rounded unsafe number accepted")
	}
}

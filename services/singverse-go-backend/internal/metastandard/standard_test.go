package metastandard

import (
	"encoding/json"
	"testing"
)

func TestStrictJSONAndJCSVectors(t *testing.T) {
	for _, raw := range []string{`{"a":1,"\u0061":2}`, `{"a":{"b":1,"b":2}}`, `{"x":"\ud800"}`, `{"x":"\udc00"}`, `{} {}`, `{"x":1e9999}`} {
		if _, e := Canonical([]byte(raw)); e == nil {
			t.Fatalf("accepted %s", raw)
		}
	}
	raw := `{"numbers":[333333333.33333329,1E30,4.50,2e-3,0.000000000000000000000000001,-0],"string":"\u20ac$\u000f\nA'B\"\\\"/"}`
	want := "{\"numbers\":[333333333.3333333,1e+30,4.5,0.002,1e-27,0],\"string\":\"€$\\u000f\\nA'B\\\"\\\\\\\"/\"}"
	b, e := Canonical([]byte(raw))
	if e != nil || string(b) != want {
		t.Fatalf("%s %v, expected %s", b, e, want)
	}
	// UTF-16 ordering differs from UTF-8 and rune ordering for supplementary keys.
	b, e = Canonical([]byte(`{"\ue000":1,"😀":2,"\r":3,"€":4}`))
	if e != nil || string(b) != "{\"\\r\":3,\"€\":4,\"😀\":2,\"\ue000\":1}" {
		t.Fatalf("%s %v", b, e)
	}
}
func TestDocumentFieldOwnershipAndExtensions(t *testing.T) {
	d := Document{SchemaVersion: Version, ID: "f784bc80-5c82-442c-9aeb-6c82af740825", Type: "SONG", Revision: "1", Fields: map[string]any{"title": "测试", "releaseDate": "2000-02"}, States: map[string]FieldState{}, Extensions: map[string]json.RawMessage{"org.example:note": json.RawMessage(`{"value":1}`)}}
	for key := range d.Fields {
		d.States[key] = FieldState{Origin: Origin{Kind: "MANUAL"}, Decision: "SET", Status: "PRESENT", Revision: "1"}
	}
	b, _ := json.Marshal(d)
	if _, e := Decode(b); e != nil {
		t.Fatal(e)
	}
	d.Fields["durationMs"] = 123
	d.States["durationMs"] = d.States["title"]
	if e := Validate(d); e == nil {
		t.Fatal("media duration accepted on song")
	}
	for _, path := range []string{"华语/歌 手/歌曲 100%.mkv", "a/%2F.mkv", "a/#&?.mkv"} {
		if !ValidPath(path) {
			t.Fatal(path)
		}
	}
	for _, path := range []string{"/a.mkv", "a/../b.mkv", "a//b.mkv", "a\\b.mkv", "a/\x00.mkv"} {
		if ValidPath(path) {
			t.Fatal(path)
		}
	}
}

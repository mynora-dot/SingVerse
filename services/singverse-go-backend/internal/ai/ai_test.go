package ai

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"github.com/jmoiron/sqlx"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/config"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/testutil"
	"io"
	"log/slog"
	_ "modernc.org/sqlite"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"testing"
)

type testCipher struct{}

func (testCipher) Encrypt(v, p string) (string, error) { return "sealed:" + p + ":" + v, nil }
func (testCipher) Decrypt(v, p string) (string, error) {
	prefix := "sealed:" + p + ":"
	if !strings.HasPrefix(v, prefix) {
		return "", errors.New("wrong purpose")
	}
	return strings.TrimPrefix(v, prefix), nil
}
func fixture(t *testing.T) *Service {
	t.Helper()
	db, e := sqlx.Open("sqlite", filepath.Join(t.TempDir(), "test.db"))
	if e != nil {
		t.Fatal(e)
	}
	db.SetMaxOpenConns(1)
	t.Cleanup(func() { db.Close() })
	files, _ := filepath.Glob("../../../../database/migrations/sqlite/*.sql")
	sort.Strings(files)
	if len(files) == 0 {
		t.Fatal("missing schema")
	}
	for _, p := range files {
		b, e := os.ReadFile(p)
		if e != nil {
			t.Fatal(e)
		}
		up := strings.Split(strings.SplitN(string(b), "-- migrate:up", 2)[1], "-- migrate:down")[0]
		if _, e = db.Exec(up); e != nil {
			t.Fatal(e)
		}
	}
	s, e := New(db, "sqlite", config.Config{}, testCipher{}, slog.New(slog.NewTextHandler(io.Discard, nil)))
	if e != nil {
		t.Fatal(e)
	}
	return s
}
func TestConfigurationCASReferencesAndCredentialDestination(t *testing.T) {
	testConfigurationCASReferencesAndCredentialDestination(t, fixture(t))
}
func TestMySQLConfigurationCASReferencesAndCredentialDestination(t *testing.T) {
	db, cfg := testutil.MySQLDatabase(t)
	s, err := New(db, "mysql", cfg, testCipher{}, slog.New(slog.NewTextHandler(io.Discard, nil)))
	if err != nil {
		t.Fatal(err)
	}
	testConfigurationCASReferencesAndCredentialDestination(t, s)
}
func testConfigurationCASReferencesAndCredentialDestination(t *testing.T, s *Service) {
	t.Helper()
	ctx := context.Background()
	empty, err := s.List(ctx, "MODEL")
	if err != nil || empty == nil || len(empty) != 0 {
		t.Fatal("empty service list lost its array contract", empty, err)
	}
	features, err := s.Features(ctx)
	if err != nil || len(features) != 3 {
		t.Fatal("feature list mapping failed", features, err)
	}
	for _, feature := range features {
		if feature.ModelServiceID != nil || feature.SearchServiceID != nil || feature.DisplayMode != "PET" || feature.PetID != "music-sprite" {
			t.Fatal("default feature changed nullable bindings or display settings", feature)
		}
	}
	key := "secret"
	v := ServiceInput{Name: "local", Provider: "custom", Protocol: "CHAT_COMPLETIONS", Endpoint: "http://127.0.0.1:1234/v1/chat/completions", Model: "model", OutputMode: "JSON_SCHEMA", APIKey: &key, Enabled: true, TimeoutSeconds: 5, MaxResults: 5}
	first, e := s.Save(ctx, "MODEL", "", v)
	if e != nil {
		t.Fatal(e)
	}
	if !first.HasKey {
		t.Fatal("key missing")
	}
	listed, err := s.List(ctx, "MODEL")
	if err != nil || len(listed) != 1 || listed[0] != first {
		t.Fatal("service list differs from saved public view", listed, err)
	}
	snap, e := s.Snapshot(ctx, first.ID)
	if e != nil {
		t.Fatal(e)
	}
	if plain, e := s.Key(snap); e != nil || plain != key {
		t.Fatal(plain, e)
	}
	v.APIKey = nil
	v.Revision = first.Revision
	v.Endpoint = "http://127.0.0.1:1235/v1/chat/completions"
	if _, e = s.Save(ctx, "MODEL", first.ID, v); e == nil {
		t.Fatal("credential silently moved")
	}
	v.ClearKey = true
	second, e := s.Save(ctx, "MODEL", first.ID, v)
	if e != nil {
		t.Fatal(e)
	}
	if second.HasKey || second.Revision != 2 {
		t.Fatal(second)
	}
	if _, e = s.Save(ctx, "MODEL", first.ID, v); e == nil {
		t.Fatal("stale revision accepted")
	}
	f, e := s.Feature(ctx, "classification")
	if e != nil {
		t.Fatal(e)
	}
	f.ModelServiceID = &first.ID
	f.Enabled = true
	if _, e = s.Bind(ctx, f.ID, f); e != nil {
		t.Fatal(e)
	}
	if e = s.Remove(ctx, "MODEL", first.ID); e == nil {
		t.Fatal("referenced service removed")
	}
	v.Revision = 2
	v.Enabled = false
	if _, e = s.Save(ctx, "MODEL", first.ID, v); e == nil {
		t.Fatal("referenced service disabled")
	}
	data, _ := json.Marshal(second)
	if strings.Contains(string(data), "secret") || strings.Contains(string(data), "credential") {
		t.Fatal("secret response", string(data))
	}
}
func TestModelProtocolsAndRefusal(t *testing.T) {
	for _, protocol := range []string{"CHAT_COMPLETIONS", "RESPONSES", "ANTHROPIC", "GEMINI"} {
		t.Run(protocol, func(t *testing.T) {
			var body map[string]any
			reject := false
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if e := json.NewDecoder(r.Body).Decode(&body); e != nil {
					t.Error(e)
				}
				w.Header().Set("Content-Type", "application/json")
				var response any
				switch protocol {
				case "CHAT_COMPLETIONS":
					reason := "stop"
					if reject {
						reason = "length"
					}
					response = map[string]any{"choices": []any{map[string]any{"finish_reason": reason, "message": map[string]any{"content": "ok"}}}}
				case "RESPONSES":
					response = map[string]any{"status": "completed", "output": []any{map[string]any{"content": []any{map[string]any{"type": "output_text", "text": "ok"}}}}}
					if reject {
						response = map[string]any{"status": "incomplete"}
					}
				case "ANTHROPIC":
					reason := "end_turn"
					if reject {
						reason = "max_tokens"
					}
					response = map[string]any{"stop_reason": reason, "content": []any{map[string]any{"type": "text", "text": "ok"}}}
					if r.Header.Get("anthropic-version") != "2023-06-01" {
						t.Error("missing version")
					}
				case "GEMINI":
					reason := "STOP"
					if reject {
						reason = "MAX_TOKENS"
					}
					response = map[string]any{"candidates": []any{map[string]any{"finishReason": reason, "content": map[string]any{"parts": []any{map[string]any{"thought": true, "text": "hidden"}, map[string]any{"text": "ok"}}}}}}
				}
				json.NewEncoder(w).Encode(response)
			}))
			defer server.Close()
			s := &Service{}
			snap := Snapshot{Protocol: protocol, Endpoint: server.URL, Model: "model", OutputMode: "JSON_SCHEMA", TimeoutSeconds: 5}
			schema := map[string]any{"type": "array", "maxItems": 8, "items": map[string]any{"type": "string"}}
			v, e := s.Generate(context.Background(), snap, "instruction", "input", schema)
			if e != nil || v != "ok" {
				t.Fatal(v, e)
			}
			b, _ := json.Marshal(body)
			if protocol == "ANTHROPIC" && strings.Contains(string(b), "maxItems") {
				t.Fatal("Anthropic unsupported schema", string(b))
			}
			reject = true
			if _, e = s.Generate(context.Background(), snap, "instruction", "input", schema); e == nil {
				t.Fatal("incomplete output accepted")
			}
		})
	}
}
func TestSearchBoundaryAndTransportLimits(t *testing.T) {
	calls := 0
	redirect := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { calls++ }))
	defer redirect.Close()
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/redirect":
			http.Redirect(w, r, redirect.URL, http.StatusFound)
		case "/large":
			fmt.Fprint(w, strings.Repeat("x", 128001))
		default:
			fmt.Fprint(w, `{"results":[{"url":"file:///secret","title":"bad"},{"url":"https://user:pass@example.org/","title":"bad"},{"url":"https://example.org/1","title":"good","snippet":"result"},{"url":"https://example.org/2"}]}`)
		}
	}))
	defer server.Close()
	s := &Service{}
	snap := Snapshot{Protocol: "CUSTOM-SEARCH", Endpoint: server.URL, TimeoutSeconds: 5, MaxResults: 1}
	hits, e := s.Search(context.Background(), snap, "query")
	if e != nil || len(hits) != 1 || hits[0].Title != "good" {
		t.Fatal(hits, e)
	}
	for _, p := range []string{"/redirect", "/large"} {
		snap.Endpoint = server.URL + p
		if _, e = s.Search(context.Background(), snap, "query"); e == nil {
			t.Fatal("unsafe response accepted", p)
		}
	}
	if calls != 0 {
		t.Fatal("redirect followed")
	}
	for _, address := range []string{"http://example.org/api", "https://user:pass@example.org/api", "https://example.org/api?key=secret", "https://example.org/#fragment"} {
		if _, e = Endpoint(context.Background(), address); e == nil {
			t.Fatal("invalid endpoint accepted", address)
		}
	}
}

package app

import (
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"

	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/config"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/testutil"
)

func application(t *testing.T) *Application {
	t.Helper()
	dir := t.TempDir()
	c, e := config.Parse(func(k string) string {
		switch k {
		case "SQLITE_PATH":
			return filepath.Join(dir, "database.sqlite")
		case "SINGVERSE_AUTH_KEY_FILE":
			return filepath.Join(dir, "master.key")
		case "WEBDAV_METADATA_PATH", "METADATA_CACHE_PATH", "TRANSCODE_VERSION_PATH", "SINGVERSE_SONG_CACHE_PATH", "AUDIO_DERIVED_PATH":
			return filepath.Join(dir, k)
		}
		return ""
	})
	if e != nil {
		t.Fatal(e)
	}
	a, e := New(c, testutil.Database(t), slog.New(slog.NewTextHandler(io.Discard, nil)))
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(func() { a.Close() })
	return a
}
func TestIdentityBoundariesAndFrontend(t *testing.T) {
	a := application(t)
	for _, tc := range []struct {
		method, path string
		status       int
	}{{"GET", "/api/health", 200}, {"GET", "/", 200}, {"GET", "/admin/", 200}, {"GET", "/party/", 200}, {"GET", "/api/v1/server-info", 200}, {"GET", "/api/v1/artists/artwork-platforms", 401}, {"GET", "/api/v1/artists/artwork-batches/latest", 401}, {"POST", "/api/v1/artists/artwork-batches", 403}, {"POST", "/api/v1/artists/categories", 403}, {"GET", "/api/v1/media-sources", 401}, {"GET", "/api/v1/admin/overview", 401}, {"GET", "/api/v1/mobile/library/songs", 401}, {"GET", "/api/v1/tv/library/songs", 401}, {"GET", "/api/v1/party/library/songs", 401}, {"GET", "/api/v1/internal/compute-callback/unknown", 401}, {"POST", "/api/v1/auth/login", 403}} {
		t.Run(tc.method+tc.path, func(t *testing.T) {
			w := httptest.NewRecorder()
			r := httptest.NewRequest(tc.method, "http://localhost"+tc.path, strings.NewReader(`{}`))
			r.Header.Set("Content-Type", "application/json")
			a.Router.ServeHTTP(w, r)
			if w.Code != tc.status {
				t.Fatal(w.Code, w.Body.String())
			}
			if w.Header().Get("X-Request-ID") == "" {
				t.Fatal("missing request identifier")
			}
			if tc.status == 401 || tc.status == 403 {
				if !strings.HasPrefix(w.Header().Get("Content-Type"), "application/problem+json") {
					t.Fatal(w.Header())
				}
			}
		})
	}
}
func TestAdministratorCookieCannotBecomeDevice(t *testing.T) {
	a := application(t)
	cookies := map[string]*http.Cookie{}
	csrf := ""
	call := func(method, path, body string) *httptest.ResponseRecorder {
		w := httptest.NewRecorder()
		r := httptest.NewRequest(method, "http://localhost"+path, strings.NewReader(body))
		r.RemoteAddr = "127.0.0.1:1234"
		r.Header.Set("Content-Type", "application/json")
		for _, c := range cookies {
			r.AddCookie(c)
		}
		if csrf != "" {
			r.Header.Set("X-CSRF-Token", csrf)
		}
		a.Router.ServeHTTP(w, r)
		for _, c := range w.Result().Cookies() {
			cookies[c.Name] = c
		}
		return w
	}
	w := call("GET", "/api/v1/auth/csrf", "")
	if w.Code != 200 {
		t.Fatal(w.Code, w.Body.String())
	}
	v := map[string]any{}
	if e := json.Unmarshal(w.Body.Bytes(), &v); e != nil {
		t.Fatal(e)
	}
	csrf = v["token"].(string)
	w = call("POST", "/api/v1/auth/login", `{"username":"admin","password":"admin"}`)
	if w.Code != 200 {
		t.Fatal(w.Code, w.Body.String())
	}
	w = call("GET", "/api/v1/media-sources", "")
	if w.Code != 200 {
		t.Fatal(w.Code, w.Body.String())
	}
	w = call("GET", "/api/v1/tv/library/songs", "")
	if w.Code != 401 {
		t.Fatal(w.Code, w.Body.String())
	}
	w = call("GET", "/api/v1/mobile/library/songs", "")
	if w.Code != 200 {
		t.Fatal(w.Code, w.Body.String())
	}
	w = call("GET", "/api/v1/auth/csrf", "")
	var csrfResponse struct {
		Token string `json:"token"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &csrfResponse); err != nil || csrfResponse.Token == "" {
		t.Fatal(w.Code, w.Body.String(), err)
	}
	csrf = csrfResponse.Token
	for _, method := range []string{http.MethodGet, http.MethodPost} {
		w = call(method, "/api/v1/__missing_contract_resource__", "")
		if w.Code != http.StatusNotFound || !strings.HasPrefix(w.Header().Get("Content-Type"), "application/problem+json") {
			t.Fatal(method, w.Code, w.Header(), w.Body.String())
		}
		var problem map[string]any
		if err := json.Unmarshal(w.Body.Bytes(), &problem); err != nil || problem["status"] != float64(404) || problem["instance"] != "/api/v1/__missing_contract_resource__" {
			t.Fatal(method, problem, err)
		}
	}
}

// TestAdminOverviewIdentityBoundary proves the admin home aggregation stays
// behind the administrator Cookie/CSRF boundary: valid TV and party Bearer
// tokens are rejected with 401 problem+json, while an authenticated
// administrator Cookie reaches the endpoint.
func TestAdminOverviewIdentityBoundary(t *testing.T) {
	a := application(t)
	c := newBusinessClient(t, a)
	c.call("POST", "/api/v1/auth/login", map[string]any{"username": "admin", "password": "admin"}, "", 200)
	device := c.call("POST", "/api/v1/device-authorizations", map[string]any{"name": "总览电视"}, "", 200)
	authorizationID := stringField(device, "id")
	c.call("POST", "/api/v1/admin/device-authorizations/"+authorizationID+"/approve", map[string]any{"code": device["code"]}, "", 200)
	grant := c.call("POST", "/api/v1/device-authorizations/"+authorizationID+"/poll", map[string]any{"pollSecret": device["pollSecret"]}, "", 200)
	tvToken := stringField(grant, "token")
	session := c.call("POST", "/api/v1/tv/sessions", map[string]any{}, tvToken, 200)
	sessionID := stringField(session, "id")
	invite := c.call("POST", "/api/v1/tv/sessions/"+sessionID+"/invitations", map[string]any{}, tvToken, 200)
	guest := c.call("POST", "/api/v1/party/join", map[string]any{"invitation": invite["invitation"], "name": "亲友"}, "", 200)
	partyToken := stringField(guest, "token")

	// A client without the administrator cookie jar carries only the device or
	// party Bearer token, exactly like a real TV or H5 client.
	unprivileged := &businessClient{t: t, origin: c.origin, client: &http.Client{}}
	for _, bearer := range []string{tvToken, partyToken} {
		unprivileged.call("GET", "/api/v1/admin/overview", nil, bearer, 401)
	}
	// The authenticated administrator reaches the aggregation endpoint.
	overview := c.call("GET", "/api/v1/admin/overview", nil, "", 200)
	if _, ok := overview["errors"]; !ok {
		t.Fatal("overview response missing errors map", overview)
	}
}

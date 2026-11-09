package auth

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/cookiejar"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/jmoiron/sqlx"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/config"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/testutil"
	_ "modernc.org/sqlite"
)

type authFixture struct {
	db      *sqlx.DB
	service *Service
	server  *httptest.Server
	client  *http.Client
	now     int64
	cfg     config.Config
}

func newFixture(t *testing.T) *authFixture {
	t.Helper()
	gin.SetMode(gin.TestMode)
	dir := t.TempDir()
	db, err := sqlx.Open("sqlite", filepath.Join(dir, "auth.db"))
	if err != nil {
		t.Fatal(err)
	}
	db.SetMaxOpenConns(1)
	t.Cleanup(func() { db.Close() })
	files, err := filepath.Glob("../../../../database/migrations/sqlite/*.sql")
	if err != nil || len(files) == 0 {
		t.Fatal("migrations not found", err)
	}
	sort.Strings(files)
	for _, path := range files {
		raw, e := os.ReadFile(path)
		if e != nil {
			t.Fatal(e)
		}
		up := strings.Split(strings.SplitN(string(raw), "-- migrate:up", 2)[1], "-- migrate:down")[0]
		if _, e = db.Exec(up); e != nil {
			t.Fatalf("migration %s: %v", path, e)
		}
	}
	cfg := config.Config{AuthKeyFile: filepath.Join(dir, "auth/master.key")}
	return newDatabaseFixture(t, db, "sqlite", cfg)
}

func newDatabaseFixture(t *testing.T, db *sqlx.DB, dialect string, cfg config.Config) *authFixture {
	t.Helper()
	gin.SetMode(gin.TestMode)
	s, err := New(db, dialect, cfg, slog.New(slog.NewTextHandler(io.Discard, nil)))
	if err != nil {
		t.Fatal(err)
	}
	f := &authFixture{db: db, service: s, now: time.Now().UnixMilli(), cfg: cfg}
	s.now = func() int64 { return f.now }
	router := gin.New()
	router.Use(s.RequireAdmin())
	s.Register(router)
	router.GET("/api/v1/protected", func(c *gin.Context) { c.JSON(200, gin.H{"ok": true}) })
	router.POST("/api/v1/protected", func(c *gin.Context) { c.Status(200) })
	f.server = httptest.NewServer(router)
	t.Cleanup(f.server.Close)
	jar, _ := cookiejar.New(nil)
	f.client = &http.Client{Jar: jar}
	return f
}

func (f *authFixture) request(t *testing.T, method, path string, body any, csrf bool) (*http.Response, map[string]any) {
	t.Helper()
	var data io.Reader
	if body != nil {
		raw, err := json.Marshal(body)
		if err != nil {
			t.Fatal(err)
		}
		data = bytes.NewReader(raw)
	}
	var token string
	if csrf {
		_, v := f.request(t, "GET", "/api/v1/auth/csrf", nil, false)
		token, _ = v["token"].(string)
	}
	r, err := http.NewRequest(method, f.server.URL+path, data)
	if err != nil {
		t.Fatal(err)
	}
	if body != nil {
		r.Header.Set("Content-Type", "application/json")
	}
	if csrf {
		r.Header.Set("X-CSRF-Token", token)
	}
	resp, err := f.client.Do(r)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	raw, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Fatal(err)
	}
	var value map[string]any
	if len(raw) > 0 {
		if err = json.Unmarshal(raw, &value); err != nil {
			t.Fatalf("response %d: %s", resp.StatusCode, raw)
		}
	}
	return resp, value
}
func expect(t *testing.T, resp *http.Response, status int) {
	t.Helper()
	if resp.StatusCode != status {
		t.Fatalf("status=%d, want %d", resp.StatusCode, status)
	}
}
func (f *authFixture) login(t *testing.T, username, password string) map[string]any {
	t.Helper()
	resp, v := f.request(t, "POST", "/api/v1/auth/login", map[string]string{"username": username, "password": password}, true)
	expect(t, resp, 200)
	return v
}

func TestHTTPCSRFSessionUsernamePasswordAndReset(t *testing.T) {
	testHTTPCSRFSessionUsernamePasswordAndReset(t, newFixture(t))
}

func TestMySQLHTTPCSRFSessionUsernamePasswordAndReset(t *testing.T) {
	db, cfg := testutil.MySQLDatabase(t)
	testHTTPCSRFSessionUsernamePasswordAndReset(t, newDatabaseFixture(t, db, "mysql", cfg))
}

func testHTTPCSRFSessionUsernamePasswordAndReset(t *testing.T, f *authFixture) {
	t.Helper()
	r, v := f.request(t, "GET", "/api/v1/protected", nil, false)
	expect(t, r, 401)
	if v["type"] != sessionRequired {
		t.Fatalf("session problem %v", v)
	}
	r, _ = f.request(t, "POST", "/api/v1/auth/login", map[string]string{"username": "admin", "password": "admin"}, false)
	expect(t, r, 403)
	if state := f.login(t, "admin", "admin")["state"]; state != "authenticated" {
		t.Fatal(state)
	}
	r, v = f.request(t, "GET", "/api/v1/auth/session", nil, false)
	expect(t, r, 200)
	if v["defaultPassword"] != true || v["username"] != "admin" {
		t.Fatal(v)
	}
	for _, c := range r.Cookies() {
		if c.Domain != "" || c.Path != "/" || !c.HttpOnly {
			t.Fatal("unsafe cookie", c)
		}
	}
	r, _ = f.request(t, "POST", "/api/v1/protected", nil, false)
	expect(t, r, 403)
	r, _ = f.request(t, "POST", "/api/v1/auth/username", map[string]string{"password": "wrong", "newUsername": "family"}, true)
	expect(t, r, 401)
	r, _ = f.request(t, "POST", "/api/v1/auth/username", map[string]string{"password": "admin", "newUsername": "family"}, true)
	expect(t, r, 200)
	r, _ = f.request(t, "GET", "/api/v1/auth/session", nil, false)
	expect(t, r, 401)
	f.login(t, "family", "admin")
	// Keep another authenticated browser alive across the password change. Its
	// rejection must come from persisted revocation, not a cleared response cookie.
	other := *f
	jar, _ := cookiejar.New(nil)
	other.client = &http.Client{Jar: jar}
	other.login(t, "family", "admin")
	password := "This is a better password 🎵"
	r, _ = f.request(t, "POST", "/api/v1/auth/password", map[string]string{"password": "admin", "newPassword": password}, true)
	expect(t, r, 200)
	r, _ = other.request(t, "GET", "/api/v1/protected", nil, false)
	expect(t, r, 401)
	r, _ = f.request(t, "POST", "/api/v1/auth/login", map[string]string{"username": "family", "password": "admin"}, true)
	expect(t, r, 401)
	f.login(t, "family", password)
	r, v = f.request(t, "GET", "/api/v1/auth/session", nil, false)
	expect(t, r, 200)
	if v["defaultPassword"] != false {
		t.Fatal(v)
	}
	if err := f.service.ResetAdmin(context.Background(), "Another local password"); err != nil {
		t.Fatal(err)
	}
	r, _ = f.request(t, "GET", "/api/v1/auth/session", nil, false)
	expect(t, r, 401)
	f.login(t, "family", "Another local password")
	probe, _ := http.NewRequest("GET", f.server.URL+"/api/v1/protected", nil)
	var oldSession *http.Cookie
	for _, value := range f.client.Jar.Cookies(probe.URL) {
		if value.Name == SessionCookie {
			oldSession = value
		}
	}
	if oldSession == nil {
		t.Fatal("authenticated session cookie missing")
	}
	r, _ = f.request(t, "POST", "/api/v1/auth/logout", nil, true)
	expect(t, r, 200)
	probe.AddCookie(oldSession)
	replayed, err := (&http.Client{Timeout: 5 * time.Second}).Do(probe)
	if err != nil {
		t.Fatal(err)
	}
	expect(t, replayed, 401)
	replayed.Body.Close()
	r, _ = f.request(t, "GET", "/api/v1/protected", nil, false)
	expect(t, r, 401)
}

func TestHTTPMFAChallengeReplayRecoveryAndRotation(t *testing.T) {
	testHTTPMFAChallengeReplayRecoveryAndRotation(t, newFixture(t))
}

func TestMySQLHTTPMFAChallengeReplayRecoveryAndRotation(t *testing.T) {
	db, cfg := testutil.MySQLDatabase(t)
	testHTTPMFAChallengeReplayRecoveryAndRotation(t, newDatabaseFixture(t, db, "mysql", cfg))
}

func testHTTPMFAChallengeReplayRecoveryAndRotation(t *testing.T, f *authFixture) {
	t.Helper()
	f.login(t, "admin", "admin")
	r, v := f.request(t, "POST", "/api/v1/system/parameters/mfa/setup", map[string]string{"password": "admin"}, true)
	expect(t, r, 200)
	secret := v["secret"].(string)
	if !strings.HasPrefix(v["qrDataUrl"].(string), "data:image/png;base64,") {
		t.Fatal(v)
	}
	code, err := totp(secret, f.now/30000, 6)
	if err != nil {
		t.Fatal(err)
	}
	r, v = f.request(t, "POST", "/api/v1/system/parameters/mfa/confirm", map[string]string{"code": code}, true)
	expect(t, r, 200)
	codes := v["recoveryCodes"].([]any)
	if len(codes) != 10 {
		t.Fatal(v)
	}
	r, _ = f.request(t, "GET", "/api/v1/auth/session", nil, false)
	expect(t, r, 401)
	if f.login(t, "admin", "admin")["state"] != "mfaRequired" {
		t.Fatal("MFA bypass")
	}
	r, _ = f.request(t, "GET", "/api/v1/protected", nil, false)
	expect(t, r, 401)
	r, _ = f.request(t, "POST", "/api/v1/auth/mfa/verify", map[string]string{"code": code}, true)
	expect(t, r, 401)
	f.now += 30000
	code, _ = totp(secret, f.now/30000, 6)
	r, _ = f.request(t, "POST", "/api/v1/auth/mfa/verify", map[string]string{"code": code}, true)
	expect(t, r, 200)
	r, _ = f.request(t, "POST", "/api/v1/auth/logout", nil, true)
	expect(t, r, 200)
	f.login(t, "admin", "admin")
	r, _ = f.request(t, "POST", "/api/v1/auth/mfa/verify", map[string]string{"code": codes[0].(string)}, true)
	expect(t, r, 200)
	r, v = f.request(t, "GET", "/api/v1/system/parameters", nil, false)
	expect(t, r, 200)
	if v["recoveryCodesRemaining"] != float64(9) {
		t.Fatal(v)
	}
	r, v = f.request(t, "POST", "/api/v1/system/parameters/mfa/recovery-codes", map[string]string{"password": "admin", "code": codes[1].(string)}, true)
	expect(t, r, 200)
	newCodes := v["recoveryCodes"].([]any)
	f.login(t, "admin", "admin")
	r, _ = f.request(t, "POST", "/api/v1/auth/mfa/verify", map[string]string{"code": codes[2].(string)}, true)
	expect(t, r, 401)
	r, _ = f.request(t, "POST", "/api/v1/auth/mfa/verify", map[string]string{"code": newCodes[0].(string)}, true)
	expect(t, r, 200)
	r, _ = f.request(t, "POST", "/api/v1/system/parameters/mfa/disable", map[string]string{"password": "admin", "code": newCodes[1].(string)}, true)
	expect(t, r, 200)
	if f.login(t, "admin", "admin")["state"] != "authenticated" {
		t.Fatal("MFA not disabled")
	}
}

func TestHTTPThrottleExpiryAndSessionDeadlines(t *testing.T) {
	f := newFixture(t)
	for i := 0; i < 5; i++ {
		r, _ := f.request(t, "POST", "/api/v1/auth/login", map[string]string{"username": "admin", "password": "wrong"}, true)
		expect(t, r, 401)
	}
	r, _ := f.request(t, "POST", "/api/v1/auth/login", map[string]string{"username": "admin", "password": "admin"}, true)
	expect(t, r, 429)
	if r.Header.Get("Retry-After") != "1" {
		t.Fatal(r.Header)
	}
	f.now += 1001
	f.login(t, "admin", "admin")
	f.now += 1800001
	r, _ = f.request(t, "GET", "/api/v1/protected", nil, false)
	expect(t, r, 401)
	f.login(t, "admin", "admin")
	for i := 0; i < 16; i++ {
		f.now += 1700000
		r, _ = f.request(t, "GET", "/api/v1/protected", nil, false)
		expect(t, r, 200)
	}
	f.now += 1700000
	r, _ = f.request(t, "GET", "/api/v1/protected", nil, false)
	expect(t, r, 401)
}

func TestHTTPSetupAttemptLimitAndChallengeExpiry(t *testing.T) {
	f := newFixture(t)
	f.login(t, "admin", "admin")
	r, _ := f.request(t, "POST", "/api/v1/system/parameters/mfa/setup", map[string]string{"password": "admin"}, true)
	expect(t, r, 200)
	for i := 0; i < 5; i++ {
		r, _ = f.request(t, "POST", "/api/v1/system/parameters/mfa/confirm", map[string]string{"code": "bad"}, true)
		expect(t, r, 401)
	}
	f.now += 1001
	r, _ = f.request(t, "POST", "/api/v1/system/parameters/mfa/confirm", map[string]string{"code": "000000"}, true)
	expect(t, r, 409)
	r, v := f.request(t, "POST", "/api/v1/system/parameters/mfa/setup", map[string]string{"password": "admin"}, true)
	expect(t, r, 200)
	code, _ := totp(v["secret"].(string), f.now/30000, 6)
	r, _ = f.request(t, "POST", "/api/v1/system/parameters/mfa/confirm", map[string]string{"code": code}, true)
	expect(t, r, 200)
	f.login(t, "admin", "admin")
	f.now += 300001
	r, _ = f.request(t, "POST", "/api/v1/auth/mfa/verify", map[string]string{"code": "000000"}, true)
	expect(t, r, 401)
}

func TestHTTPCrossOriginAndTrustedHTTPS(t *testing.T) {
	f := newFixture(t)
	req, _ := http.NewRequest("GET", f.server.URL+"/api/v1/auth/csrf", nil)
	req.Header.Set("Origin", "https://evil.example")
	resp, err := f.client.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	expect(t, resp, 403)
	resp.Body.Close()
	for _, origin := range []string{"http://example.com", "https://example.com/", "https://u:p@example.com", "https://example.com?", "https://example.com#x"} {
		if _, err := newOriginPolicy(origin, nil); err == nil {
			t.Fatal("invalid public origin accepted", origin)
		}
	}
	if _, err := newOriginPolicy("", []string{"0.0.0.0/0"}); err == nil {
		t.Fatal("wildcard trust accepted")
	}
	p, err := newOriginPolicy("https://ktv.example:8443", []string{"127.0.0.1/32"})
	if err != nil {
		t.Fatal(err)
	}
	f.service.policy = p
	req, _ = http.NewRequest("GET", f.server.URL+"/api/v1/auth/csrf", nil)
	req.Host = "ktv.example:8443"
	req.Header.Set("X-Forwarded-Proto", "https")
	req.Header.Set("Origin", "https://ktv.example:8443")
	resp, err = f.client.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	expect(t, resp, 200)
	resp.Body.Close()
	found := false
	for _, c := range resp.Cookies() {
		if c.Name == AnonymousCookie {
			found = true
			if !c.Secure || !c.HttpOnly || c.SameSite != http.SameSiteLaxMode || c.Path != "/" || c.Domain != "" {
				t.Fatal("cookie attributes", c)
			}
		}
	}
	if !found {
		t.Fatal("anonymous cookie not issued")
	}
}

func TestStartupRejectsWrongOrMissingKeyAndResetRetainsOtherSecrets(t *testing.T) {
	f := newFixture(t)
	encrypted, err := f.service.Cipher.Encrypt("public-test-secret", "singverse:webdav:v1")
	if err != nil {
		t.Fatal(err)
	}
	_, err = f.db.Exec("INSERT INTO media_source(id,name,source_type,location,location_hash,username,password_encrypted,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)", "source", "test", "WEBDAV", "https://webdav.example", "hash", "user", encrypted, "2026-11-09T00:00:00Z", "2026-11-09T00:00:00Z")
	if err != nil {
		t.Fatal(err)
	}
	if err = os.Remove(f.cfg.AuthKeyFile); err != nil {
		t.Fatal(err)
	}
	if _, err = New(f.db, "sqlite", f.cfg, nil); err == nil {
		t.Fatal("missing key regenerated despite existing media password")
	}
	if err = ResetAdministrator(context.Background(), f.db, "Local replacement password"); err != nil {
		t.Fatal(err)
	}
	var retained string
	if err = f.db.QueryRow("SELECT password_encrypted FROM media_source WHERE id=?", "source").Scan(&retained); err != nil || retained != encrypted {
		t.Fatalf("media secret was changed %v", err)
	}
	if err = os.WriteFile(f.cfg.AuthKeyFile, []byte("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err = New(f.db, "sqlite", f.cfg, nil); err == nil {
		t.Fatal("wrong key accepted")
	}
}

func TestConcurrentRecoveryCodeConsumedOnce(t *testing.T) {
	f := newFixture(t)
	f.login(t, "admin", "admin")
	r, v := f.request(t, "POST", "/api/v1/system/parameters/mfa/setup", map[string]string{"password": "admin"}, true)
	expect(t, r, 200)
	code, _ := totp(v["secret"].(string), f.now/30000, 6)
	r, v = f.request(t, "POST", "/api/v1/system/parameters/mfa/confirm", map[string]string{"code": code}, true)
	expect(t, r, 200)
	recovery := v["recoveryCodes"].([]any)[0].(string)
	results := make(chan bool, 2)
	for i := 0; i < 2; i++ {
		go func() {
			value, err := f.service.transaction(context.Background(), func(tx *sqlx.Tx) (any, error) {
				a, e := readAccount(context.Background(), tx)
				if e != nil {
					return nil, e
				}
				return f.service.verifyFactor(context.Background(), tx, recovery, a)
			})
			results <- err == nil && value == true
		}()
	}
	a, b := <-results, <-results
	if a == b {
		t.Fatalf("recovery results %v %v", a, b)
	}
}

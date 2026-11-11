package compute

import (
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/jmoiron/sqlx"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/config"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/testutil"
)

const fixtureToken = "isolated-compute-fixture-12345678901234567890"

type testCipher struct{ aead cipher.AEAD }

func makeCipher(t *testing.T) *testCipher {
	t.Helper()
	key := make([]byte, 32)
	if _, err := rand.Read(key); err != nil {
		t.Fatal(err)
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		t.Fatal(err)
	}
	aead, err := cipher.NewGCM(block)
	if err != nil {
		t.Fatal(err)
	}
	return &testCipher{aead}
}
func (c *testCipher) Encrypt(value, purpose string) (string, error) {
	nonce := make([]byte, c.aead.NonceSize())
	if _, err := rand.Read(nonce); err != nil {
		return "", err
	}
	return base64.StdEncoding.EncodeToString(c.aead.Seal(nonce, nonce, []byte(value), []byte(purpose))), nil
}
func (c *testCipher) Decrypt(value, purpose string) (string, error) {
	b, err := base64.StdEncoding.DecodeString(value)
	if err != nil || len(b) < c.aead.NonceSize() {
		return "", errors.New("invalid ciphertext")
	}
	p, err := c.aead.Open(nil, b[:c.aead.NonceSize()], b[c.aead.NonceSize():], []byte(purpose))
	return string(p), err
}
func newFixture(t *testing.T, cfg config.Config) (*Service, *sqlx.DB, *testCipher) {
	t.Helper()
	db := testutil.Database(t)
	cipher := makeCipher(t)
	s, err := New(db, "sqlite", cfg, cipher, nil)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(s.Close)
	return s, db, cipher
}
func requireStatus(t *testing.T, err error, status int) {
	t.Helper()
	var f *failure
	if !errors.As(err, &f) || f.status != status {
		t.Fatalf("want status %d, got %v", status, err)
	}
}
func manual(endpoint string, revision int64) Save {
	return Save{Mode: "MANUAL", Endpoint: endpoint, Token: fixtureToken, Revision: revision, Mappings: []PathMapping{{"/local", "/media"}, {"/local/special", "D:\\special"}}}
}

func TestSettingsCredentialRetentionCASAndSnapshot(t *testing.T) {
	s, db, _ := newFixture(t, config.Config{})
	ctx := context.Background()
	v, err := s.View(ctx)
	if err != nil || v.Mode != "AUTO" || v.Revision != 0 || v.Mappings == nil {
		t.Fatal(v, err)
	}
	saved, err := s.Save(ctx, manual("http://127.0.0.1:8090/", 0))
	if err != nil || !saved.HasToken || saved.Revision != 1 {
		t.Fatal(saved, err)
	}
	var encrypted string
	if err = db.QueryRow("SELECT secret_encrypted FROM compute_node_settings WHERE id=1").Scan(&encrypted); err != nil || strings.Contains(encrypted, fixtureToken) {
		t.Fatal("credential was not sealed", err)
	}
	connection, err := s.Snapshot(ctx)
	if err != nil || connection.Token != fixtureToken || connection.Origin != "http://127.0.0.1:8090" {
		t.Fatal(connection, err)
	}
	if got := connection.MediaPath("/local/special/a.mp4"); got != "D:\\special\\a.mp4" {
		t.Fatal(got)
	}
	if got := connection.MediaPath("/local/album/a.mp4"); got != "/media/album/a.mp4" {
		t.Fatal(got)
	}
	if got := connection.MediaPath("/locality/a.mp4"); got != "/locality/a.mp4" {
		t.Fatal(got)
	}
	for _, dto := range []any{saved, connection} {
		b, _ := json.Marshal(dto)
		if strings.Contains(string(b), fixtureToken) || strings.Contains(string(b), encrypted) {
			t.Fatal("secret escaped DTO")
		}
	}
	edit := manual(connection.Origin, 1)
	edit.Token = ""
	if _, err = s.Save(ctx, edit); err != nil {
		t.Fatal(err)
	}
	_, err = s.Save(ctx, edit)
	requireStatus(t, err, 409)
	edit.Revision = 2
	edit.Endpoint = "http://127.0.0.1:8091"
	_, err = s.Draft(ctx, edit)
	requireStatus(t, err, 400)
	_, err = s.Save(ctx, edit)
	requireStatus(t, err, 400)
	if _, err = s.Save(ctx, Save{Mode: "DISABLED", Revision: 2}); err != nil {
		t.Fatal(err)
	}
	current, err := s.Snapshot(ctx)
	if err != nil || current != nil || connection.Token != fixtureToken {
		t.Fatal(current, err)
	}
	v, err = s.View(ctx)
	if err != nil || !v.HasToken || len(v.Mappings) != 2 {
		t.Fatal(v, err)
	}
	edit.Revision = 3
	edit.ClearToken = true
	v, err = s.Save(ctx, edit)
	if err != nil || v.HasToken {
		t.Fatal(v, err)
	}
	current, err = s.Snapshot(ctx)
	if err != nil || current.Token != "" {
		t.Fatal(current, err)
	}
	check, err := s.Probe(ctx, current)
	if err != nil || check.Status != "MISSING_TOKEN" {
		t.Fatal(check, err)
	}
}
func TestAutomaticAndCallbackOriginPrecedence(t *testing.T) {
	file := filepath.Join(t.TempDir(), "compute.token")
	if err := os.WriteFile(file, []byte(fixtureToken+"\n"), 0600); err != nil {
		t.Fatal(err)
	}
	s, _, _ := newFixture(t, config.Config{ComputeURL: "http://127.0.0.1:18090", ComputeTokenFile: file, AutoCallbackOrigin: "http://business:18080"})
	s.ObserveOrigin("http://192.168.1.2:8080")
	ctx := context.Background()
	connection, err := s.Snapshot(ctx)
	if err != nil || connection.Token != fixtureToken || len(connection.Mappings) != 0 {
		t.Fatal(connection, err)
	}
	got, err := s.CallbackOrigin(connection)
	if err != nil || got != "http://business:18080" {
		t.Fatal(got, err)
	}
	got, err = s.CallbackOrigin(&Connection{Origin: "http://127.0.0.1:8090"})
	if err != nil || got != "http://192.168.1.2:8080" {
		t.Fatal(got, err)
	}
	if err = os.WriteFile(file, []byte(strings.Repeat("x", 258)), 0600); err != nil {
		t.Fatal(err)
	}
	view, err := s.View(ctx)
	if err != nil || view.AutomaticHasToken {
		t.Fatal(view, err)
	}
	s.configuredOrigin = "https://nas.example"
	s.ObserveOrigin("http://attacker")
	got, err = s.CallbackOrigin(connection)
	if err != nil || got != "https://nas.example" {
		t.Fatal(got, err)
	}
}

func TestProbeDistinguishesNodeUnavailableFromRejectedCredentials(t *testing.T) {
	s, _, _ := newFixture(t, config.Config{})
	for _, status := range []int{401, 403, 503} {
		t.Run(http.StatusText(status), func(t *testing.T) {
			node := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				w.WriteHeader(status)
				_, _ = w.Write([]byte("private filesystem detail " + fixtureToken))
			}))
			defer node.Close()
			check, err := s.Probe(context.Background(), &Connection{Origin: node.URL, Token: fixtureToken})
			want := "AUTH_FAILED"
			if status == 503 {
				want = "UNAVAILABLE"
				if !strings.Contains(check.Message, "凭据文件挂载及读取权限") {
					t.Fatal("missing node credential diagnostics", check.Message)
				}
			}
			if err != nil || check.Status != want || len(check.Capabilities) != 0 {
				t.Fatal(check, err)
			}
			if strings.Contains(check.Message, fixtureToken) || strings.Contains(check.Message, "private filesystem detail") {
				t.Fatal("upstream diagnostics escaped probe")
			}
		})
	}
}
func TestValidationBoundaries(t *testing.T) {
	for _, v := range []string{"file:///etc/passwd", "http://user:pass@localhost", "http://localhost/a", "http://localhost?", "http://localhost#", "http://localhost:0", "http://localhost:65536", "http://[fe80::1%25eth0]"} {
		if _, err := origin(v); err == nil {
			t.Errorf("accepted origin %q", v)
		}
	}
	for _, v := range [][]PathMapping{{{"relative", "/media"}}, {{"/local", "relative"}}, {{"/local", "/media/../x"}}, {{"/local", "D:/media/./x"}}, {{"/local", "/media"}, {"/local/", "/other"}}, {{"/local\n", "/media"}}} {
		if _, err := validatedMappings(v); err == nil {
			t.Errorf("accepted mappings %v", v)
		}
	}
	for _, v := range []string{"192.168.1.0/23", "8.8.8.8/32", "127.0.0.0/24", "169.254.1.1/32", "010.0.0.1/32", "::1/128"} {
		if _, err := subnet(v); err == nil {
			t.Errorf("accepted scan %q", v)
		}
	}
	addresses, err := subnet("192.168.1.19/24")
	if err != nil || len(addresses) != 256 || addresses[0].String() != "192.168.1.0" {
		t.Fatal(addresses, err)
	}
	for _, v := range []string{"http://localhost:8090", "http://8.8.8.8", "http://169.254.169.254", "http://[ff02::1]", "http://[fe80::1]"} {
		if err := requirePrivate(v); err == nil {
			t.Errorf("accepted pairing origin %q", v)
		}
	}
	_, _, err = request(context.Background(), "http://8.8.8.8", "/", fixtureToken, nil, 50*time.Millisecond, 1024)
	requireStatus(t, err, 400)
}
func TestNetworkNeverRedirectsAndBoundsResponses(t *testing.T) {
	var leaked bool
	sink := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { leaked = true; w.WriteHeader(200) }))
	defer sink.Close()
	node := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/large" {
			_, _ = io.WriteString(w, strings.Repeat("x", 1025))
			return
		}
		w.Header().Set("Location", sink.URL)
		w.WriteHeader(307)
	}))
	defer node.Close()
	status, _, err := request(context.Background(), node.URL, "/redirect", fixtureToken, map[string]string{"x": "y"}, time.Second, 1024)
	if err != nil || status != 307 || leaked {
		t.Fatal(status, err, leaked)
	}
	_, _, err = request(context.Background(), node.URL, "/large", fixtureToken, nil, time.Second, 1024)
	if err == nil {
		t.Fatal("oversized node response accepted")
	}
	tls := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { t.Error("untrusted TLS accepted") }))
	defer tls.Close()
	_, _, err = request(context.Background(), tls.URL, "/", fixtureToken, nil, time.Second, 1024)
	if err == nil {
		t.Fatal("TLS verification disabled")
	}
}
func TestCallbackGrantRejectsUnknownExpiredAndWrongBearer(t *testing.T) {
	s, _, _ := newFixture(t, config.Config{})
	engine := gin.New()
	s.RegisterPublic(engine)
	server := httptest.NewServer(engine)
	defer server.Close()
	id, err := s.authorize(fixtureToken)
	if err != nil {
		t.Fatal(err)
	}
	get := func(id, token string) int {
		r, _ := http.NewRequest("GET", server.URL+"/api/v1/internal/compute-callback/"+id, nil)
		r.Header.Set("Authorization", token)
		v, e := http.DefaultClient.Do(r)
		if e != nil {
			t.Fatal(e)
		}
		defer v.Body.Close()
		if v.Header.Get("Cache-Control") != "no-store" {
			t.Fatal("callback may be cached")
		}
		return v.StatusCode
	}
	if get(id, "Bearer wrong") != 401 || get("missing", "Bearer "+fixtureToken) != 401 || get(id, "Bearer "+fixtureToken) != 200 {
		t.Fatal("callback grant authentication failed")
	}
	s.mu.Lock()
	s.grants[id] = grant{fixtureToken, time.Now().Add(-time.Second)}
	s.mu.Unlock()
	if get(id, "Bearer "+fixtureToken) != 401 {
		t.Fatal("expired callback accepted")
	}
	s.revoke(id)
	if get(id, "Bearer "+fixtureToken) != 401 {
		t.Fatal("revoked callback accepted")
	}
}

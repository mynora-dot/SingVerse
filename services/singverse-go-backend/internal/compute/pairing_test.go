package compute

import (
	"context"
	"crypto"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/config"
)

// nodeFixture speaks the Python LAN protocol and independently verifies the
// cryptographic proof, then makes an actual authenticated HTTP callback.
type nodeFixture struct {
	mu                sync.Mutex
	nodeID, owner     string
	callbackAllowed   bool
	status            int
	capabilities      any
	proofs            int
	callbackReference string
}

func (f *nodeFixture) serve(t *testing.T, w http.ResponseWriter, r *http.Request) {
	t.Helper()
	f.mu.Lock()
	nodeID, owner, callbackAllowed, status, caps := f.nodeID, f.owner, f.callbackAllowed, f.status, f.capabilities
	f.mu.Unlock()
	w.Header().Set("Content-Type", "application/json")
	write := func(v any) {
		if err := json.NewEncoder(w).Encode(v); err != nil {
			t.Error(err)
		}
	}
	decode := func() map[string]string {
		var v map[string]string
		if err := json.NewDecoder(io.LimitReader(r.Body, 8193)).Decode(&v); err != nil {
			t.Error(err)
		}
		return v
	}
	switch r.URL.Path {
	case "/internal/v1/lan/discovery":
		if r.Header.Get("Authorization") != "" {
			t.Error("Bearer was sent to LAN discovery")
		}
		write(map[string]any{"service": "singverse-compute", "apiVersion": "1", "nodeId": nodeID, "name": "家庭计算节点", "ownerId": owner, "pairingEnabled": true})
	case "/internal/v1/lan/challenge":
		v := decode()
		if v["nodeId"] != nodeID || !hex64Pattern.MatchString(v["clientId"]) {
			t.Error("invalid challenge request")
		}
		write(map[string]string{"nodeId": nodeID, "challenge": strings.Repeat("a", 64)})
	case "/internal/v1/lan/pair":
		if r.Header.Get("Authorization") != "" {
			t.Error("Bearer was sent to pairing")
		}
		v := decode()
		pubDER, err := base64.StdEncoding.DecodeString(v["publicKey"])
		if err != nil {
			t.Error(err)
			w.WriteHeader(400)
			return
		}
		pub, err := x509.ParsePKIXPublicKey(pubDER)
		if err != nil {
			t.Error(err)
			w.WriteHeader(400)
			return
		}
		key, ok := pub.(*rsa.PublicKey)
		if !ok || key.N.BitLen() != 3072 {
			t.Error("pairing key is not RSA3072")
			w.WriteHeader(400)
			return
		}
		hash := sha256.Sum256(pubDER)
		if hex.EncodeToString(hash[:]) != v["clientId"] {
			t.Error("client identity mismatch")
		}
		digest := sha256.Sum256([]byte("singverse-compute-lan-v1\n" + nodeID + "\n" + v["challenge"] + "\n" + v["clientId"]))
		signature, err := base64.StdEncoding.DecodeString(v["signature"])
		if err != nil || rsa.VerifyPSS(key, crypto.SHA256, digest[:], signature, &rsa.PSSOptions{SaltLength: 32, Hash: crypto.SHA256}) != nil {
			t.Error("invalid RSA-PSS signature")
			w.WriteHeader(400)
			return
		}
		sealed, err := rsa.EncryptOAEP(sha256.New(), rand.Reader, key, []byte(fixtureToken), []byte("singverse-compute-lan-v1:"+nodeID+":"+v["challenge"]))
		if err != nil {
			t.Error(err)
			return
		}
		f.mu.Lock()
		f.owner = v["clientId"]
		f.proofs++
		f.mu.Unlock()
		write(map[string]string{"nodeId": nodeID, "challenge": v["challenge"], "encryptedToken": base64.StdEncoding.EncodeToString(sealed)})
	case "/internal/v1/status":
		if r.Header.Get("Authorization") != "Bearer "+fixtureToken {
			w.WriteHeader(401)
			return
		}
		if status != 200 {
			w.WriteHeader(status)
			return
		}
		write(map[string]any{"service": "singverse-compute", "apiVersion": "1", "capabilities": caps})
	case "/internal/v1/callback/check":
		if r.Header.Get("Authorization") != "Bearer "+fixtureToken {
			w.WriteHeader(401)
			return
		}
		v := decode()
		f.mu.Lock()
		f.callbackReference = v["reference"]
		f.mu.Unlock()
		req, _ := http.NewRequestWithContext(r.Context(), "GET", v["callbackOrigin"]+"/api/v1/internal/compute-callback/"+v["reference"], nil)
		req.Header.Set("Authorization", "Bearer "+fixtureToken)
		back, err := http.DefaultClient.Do(req)
		available := false
		if err == nil {
			b, _ := io.ReadAll(back.Body)
			_ = back.Body.Close()
			available = back.StatusCode == 200 && string(b) == v["reference"] && callbackAllowed
		}
		write(map[string]bool{"available": available})
	default:
		w.WriteHeader(404)
	}
}
func TestDiscoveryPairingCryptographyCallbackAndRestore(t *testing.T) {
	s, db, cipher := newFixture(t, config.Config{})
	engine := gin.New()
	s.RegisterPublic(engine)
	business := httptest.NewServer(engine)
	defer business.Close()
	s.ObserveOrigin(business.URL)
	f := &nodeFixture{nodeID: "12345678-1234-1234-1234-123456789abc", callbackAllowed: true, status: 200, capabilities: []string{"media-remote-tags"}}
	node := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { f.serve(t, w, r) }))
	defer node.Close()
	_, portText, _ := net.SplitHostPort(node.Listener.Addr().String())
	port, _ := strconv.Atoi(portText)
	ctx := context.Background()
	result, err := s.Discover(ctx, Scan{Network: "127.0.0.1/32", Port: &port})
	if err != nil || len(result.Items) != 1 || !result.Items[0].PairingAvailable {
		t.Fatal(result, err)
	}
	saved, err := s.Pair(ctx, Pair{DiscoveryID: result.Items[0].ID, Revision: 0})
	if err != nil || saved.Mode != "MANUAL" || saved.Endpoint != node.URL || !saved.HasToken || saved.Revision != 1 {
		t.Fatal(saved, err)
	}
	connection, err := s.Snapshot(ctx)
	if err != nil || connection.Token != fixtureToken {
		t.Fatal(connection, err)
	}
	f.mu.Lock()
	reference, proofs := f.callbackReference, f.proofs
	f.mu.Unlock()
	if proofs != 1 || s.verify(reference, "Bearer "+fixtureToken) {
		t.Fatal("pairing or callback revocation failed")
	}
	// Restarting the business service retains exactly the same server identity.
	restarted, err := New(db, "sqlite", config.Config{}, cipher, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer restarted.Close()
	id, err := s.clientID(ctx)
	if err != nil {
		t.Fatal(err)
	}
	sameID, err := restarted.clientID(ctx)
	if err != nil || sameID != id {
		t.Fatal("identity changed", err)
	}
	var sealed string
	if err = db.QueryRow("SELECT private_key_encrypted FROM compute_pairing_identity WHERE id=1").Scan(&sealed); err != nil {
		t.Fatal(err)
	}
	plain, err := cipher.Decrypt(sealed, credentialPurpose)
	if err != nil || sealed == plain || strings.Contains(sealed, "PRIVATE KEY") {
		t.Fatal("pairing identity was not encrypted", err)
	}
	wrong, err := New(db, "sqlite", config.Config{}, makeCipher(t), nil)
	if err != nil {
		t.Fatal(err)
	}
	defer wrong.Close()
	if _, err = wrong.clientID(ctx); err == nil {
		t.Fatal("wrong master key silently replaced identity")
	}
	result, err = s.Discover(ctx, Scan{Network: "127.0.0.1/32", Port: &port})
	if err != nil || len(result.Items) != 1 || !strings.Contains(result.Items[0].Message, "恢复") {
		t.Fatal(result, err)
	}
	// A failed reverse connection must preserve the previously saved configuration.
	f.mu.Lock()
	f.callbackAllowed = false
	f.mu.Unlock()
	_, err = s.Pair(ctx, Pair{DiscoveryID: result.Items[0].ID, Revision: 1})
	requireStatus(t, err, 422)
	current, err := s.View(ctx)
	if err != nil || current.Revision != 1 || current.Endpoint != node.URL {
		t.Fatal(current, err)
	}
	f.mu.Lock()
	f.nodeID = "87654321-1234-1234-1234-123456789abc"
	f.mu.Unlock()
	_, err = s.Pair(ctx, Pair{DiscoveryID: result.Items[0].ID, Revision: 1})
	requireStatus(t, err, 409)
	_, err = s.Pair(ctx, Pair{DiscoveryID: "http://127.0.0.1:1234", Revision: 1})
	requireStatus(t, err, 404)
}
func TestProbeStatesWithoutModifyingSettings(t *testing.T) {
	s, _, _ := newFixture(t, config.Config{})
	engine := gin.New()
	s.RegisterPublic(engine)
	business := httptest.NewServer(engine)
	defer business.Close()
	s.ObserveOrigin(business.URL)
	f := &nodeFixture{nodeID: "12345678-1234-1234-1234-123456789abc", callbackAllowed: true, status: 200, capabilities: []string{"media-remote-tags"}}
	node := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { f.serve(t, w, r) }))
	defer node.Close()
	ctx := context.Background()
	check := func(connection *Connection, want string) {
		t.Helper()
		v, err := s.Probe(ctx, connection)
		if err != nil || v.Status != want || v.Capabilities == nil {
			t.Fatalf("want %s, got %v %v", want, v, err)
		}
		b, _ := json.Marshal(v)
		if strings.Contains(string(b), fixtureToken) {
			t.Fatal("probe leaked Bearer")
		}
	}
	check(nil, "DISABLED")
	check(&Connection{Origin: node.URL}, "MISSING_TOKEN")
	check(&Connection{Origin: node.URL, Token: strings.Repeat("x", 32)}, "AUTH_FAILED")
	connection := &Connection{Origin: node.URL, Token: fixtureToken}
	check(connection, "AVAILABLE")
	f.mu.Lock()
	f.status = 302
	f.mu.Unlock()
	check(connection, "UNAVAILABLE")
	f.mu.Lock()
	f.status = 200
	f.capabilities = []any{"media-remote-tags", 42}
	f.mu.Unlock()
	check(connection, "INCOMPATIBLE")
	f.mu.Lock()
	f.capabilities = []string{}
	f.callbackAllowed = false
	f.mu.Unlock()
	check(connection, "CALLBACK_FAILED")
	v, err := s.View(ctx)
	if err != nil || v.Revision != 0 || v.Mode != "AUTO" {
		t.Fatal("draft probe modified settings", v, err)
	}
}
func TestPairingRejectsWrongOAEPLabeledCiphertextAndExpiredCandidates(t *testing.T) {
	s, _, _ := newFixture(t, config.Config{})
	ctx := context.Background()
	key, _, err := s.identity(ctx)
	if err != nil {
		t.Fatal(err)
	}
	ciphertext, err := rsa.EncryptOAEP(sha256.New(), rand.Reader, &key.PublicKey, []byte(fixtureToken), []byte("wrong-label"))
	if err != nil {
		t.Fatal(err)
	}
	_, err = s.decryptPairingToken(ctx, "node", "challenge", base64.StdEncoding.EncodeToString(ciphertext))
	requireStatus(t, err, 502)
	s.mu.Lock()
	s.candidates["expired"] = candidate{"http://127.0.0.1:8090", "node", time.Now().Add(-time.Second)}
	s.mu.Unlock()
	_, err = s.Pair(ctx, Pair{DiscoveryID: "expired"})
	requireStatus(t, err, 404)
	s.scanning.Store(true)
	_, err = s.Discover(ctx, Scan{})
	requireStatus(t, err, 429)
	s.scanning.Store(false)
}

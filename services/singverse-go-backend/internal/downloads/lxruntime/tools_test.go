package lxruntime

import (
	"bytes"
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"math/big"
	"testing"
)

func TestRSAUtilitiesRawAndLegacy(t *testing.T) {
	key, err := rsa.GenerateKey(rand.Reader, 1024)
	if err != nil {
		t.Fatal(err)
	}
	der, err := x509.MarshalPKIXPublicKey(&key.PublicKey)
	if err != nil {
		t.Fatal(err)
	}
	keyText := string(pem.EncodeToMemory(&pem.Block{Type: "PUBLIC KEY", Bytes: der}))
	plain := []byte("hello")
	raw, err := toolRSA(map[string]any{"key": keyText, "value": "hello"})
	if err != nil {
		t.Fatal(err)
	}
	expected := new(big.Int).Exp(new(big.Int).SetBytes(plain), big.NewInt(int64(key.E)), key.N).FillBytes(make([]byte, key.Size()))
	if !bytes.Equal(raw, expected) {
		t.Fatal("RSA NoPadding differs")
	}
	legacy, err := toolRSA(map[string]any{"key": keyText, "value": "hello", "legacy": true})
	if err != nil {
		t.Fatal(err)
	}
	//lint:ignore SA1019 Verify the PKCS#1 v1.5 ciphertext required by the legacy LX protocol.
	decoded, err := rsa.DecryptPKCS1v15(rand.Reader, key, legacy)
	if err != nil || !bytes.Equal(decoded, plain) {
		t.Fatalf("legacy RSA: %q %v", decoded, err)
	}
}

func TestToolEncodingAndInvalidInputs(t *testing.T) {
	encoded, err := tool("from", `{"value":{"data":"00ff41","encoding":"hex"}}`)
	if err != nil {
		t.Fatal(err)
	}
	var text string
	if json.Unmarshal([]byte(encoded), &text) != nil || text != base64.StdEncoding.EncodeToString([]byte{0, 255, 65}) {
		t.Fatalf("binary encoding: %s", encoded)
	}
	for _, test := range []struct{ operation, input string }{
		{"unknown", "{}"},
		{"random", `{"size":1048577}`},
		{"random", `{"size":0.5}`},
		{"from", `{"value":"zz","encoding":"hex"}`},
		{"aes", `{"value":"hello","key":"short","iv":"0123456789abcdef"}`},
		{"aes", `{"value":"hello","key":"0123456789abcdef","mode":"aes-256-cbc","iv":"0123456789abcdef"}`},
		{"rsa", `{"value":"hello","key":"private-credential"}`},
	} {
		if _, err := tool(test.operation, test.input); err != ErrInvalid {
			t.Fatalf("%s did not return a scrubbed error: %v", test.operation, err)
		}
	}
}

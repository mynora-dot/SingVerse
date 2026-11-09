package auth

import (
	"encoding/base64"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestPublicCryptoVectors(t *testing.T) {
	data, err := os.ReadFile("../../../../contracts/openapi/auth-test-vectors.json")
	if err != nil {
		t.Fatal(err)
	}
	var v struct {
		Password  string `json:"password"`
		PHC       string `json:"argon2idPhc"`
		Key       string `json:"aes256KeyBase64"`
		Secret    string `json:"totpSecret"`
		Encrypted string `json:"totpEncrypted"`
		RFC       struct {
			Secret  string `json:"secretBase32"`
			Seconds int64  `json:"unixSeconds"`
			Code    string `json:"sha1Digits8"`
		} `json:"rfc6238"`
	}
	if err = json.Unmarshal(data, &v); err != nil {
		t.Fatal(err)
	}
	if !matchesPassword(v.Password, v.PHC) || matchesPassword("wrong", v.PHC) {
		t.Fatal("Argon2id cross-language vector mismatch")
	}
	key, err := base64.StdEncoding.DecodeString(v.Key)
	if err != nil {
		t.Fatal(err)
	}
	c := &Cipher{key: key}
	got, err := c.Decrypt(v.Encrypted, totpPurpose)
	if err != nil || got != v.Secret {
		t.Fatalf("AES vector: %q %v", got, err)
	}
	if _, err = c.Decrypt(v.Encrypted, "singverse:webdav:v1"); err == nil {
		t.Fatal("purpose boundary accepted")
	}
	code, err := totp(v.RFC.Secret, v.RFC.Seconds/30, 8)
	if err != nil || code != v.RFC.Code {
		t.Fatalf("RFC 6238 vector %s %v", code, err)
	}
	encrypted, err := c.Encrypt("云端凭据", totpPurpose)
	if err != nil {
		t.Fatal(err)
	}
	plain, err := c.Decrypt(encrypted, totpPurpose)
	if err != nil || plain != "云端凭据" {
		t.Fatalf("roundtrip %s %v", plain, err)
	}
	for _, value := range []string{"", "v1:a:b", "v2:AA==:AA==", encrypted[:len(encrypted)-3] + "AAA"} {
		if _, err = c.Decrypt(value, totpPurpose); err == nil {
			t.Fatalf("accepted corrupt ciphertext %q", value)
		}
	}
}

func TestMasterKeyPermissionsAndMissingCredentials(t *testing.T) {
	path := filepath.Join(t.TempDir(), "secrets/master.key")
	if _, err := loadCipher(path, true); err == nil {
		t.Fatal("missing credential key was replaced")
	}
	c, err := loadCipher(path, false)
	if err != nil {
		t.Fatal(err)
	}
	info, err := os.Stat(path)
	if err != nil || info.Mode().Perm() != 0600 {
		t.Fatalf("permissions %v %v", info, err)
	}
	loaded, err := loadCipher(path, true)
	if err != nil || string(c.key) != string(loaded.key) {
		t.Fatal("key did not survive restart")
	}
	if err = os.Chmod(path, 0644); err != nil {
		t.Fatal(err)
	}
	if _, err = loadCipher(path, true); err == nil {
		t.Fatal("insecure key accepted")
	}
	if err = os.Chmod(path, 0600); err != nil {
		t.Fatal(err)
	}
	if err = os.WriteFile(path, []byte("bad-key"), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err = loadCipher(path, false); err == nil {
		t.Fatal("malformed key accepted")
	}
}

func TestPasswordBoundsAndPHC(t *testing.T) {
	for _, value := range []string{"short", strings.Repeat("字", 129), string([]byte{0xff})} {
		if ValidatePassword(value) == nil {
			t.Fatal("invalid password accepted")
		}
	}
	password := strings.Repeat("字", 15)
	if err := ValidatePassword(password); err != nil {
		t.Fatal(err)
	}
	phc, err := encodePassword(password)
	if err != nil || !matchesPassword(password, phc) {
		t.Fatalf("PHC failed %v", err)
	}
	if matchesPassword(password, "$argon2id$v=19$m=4000000000,t=2,p=1$AAAAAAAAAAA$AAAAAAAAAAAAAAAAAAAAAA") {
		t.Fatal("unbounded PHC accepted")
	}
}

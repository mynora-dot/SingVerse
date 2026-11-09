package auth

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha1"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base32"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"unicode/utf8"

	"golang.org/x/crypto/argon2"
)

const totpPurpose = "singverse:totp:v1"

// Cipher seals credentials with a purpose-specific authenticated-data boundary.
// The key never appears in an HTTP response or a log.
type Cipher struct{ key []byte }

func randomBytes(n int) ([]byte, error) { b := make([]byte, n); _, err := rand.Read(b); return b, err }
func randomToken() (string, error) {
	b, err := randomBytes(32)
	return base64.RawURLEncoding.EncodeToString(b), err
}
func digest(v string) string { h := sha256.Sum256([]byte(v)); return hex.EncodeToString(h[:]) }

func loadCipher(path string, existing bool) (*Cipher, error) {
	if path == "" {
		return nil, errors.New("authentication key file must be configured")
	}
	data, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		if existing {
			return nil, errors.New("credential master key missing; restore matching key backup")
		}
		if err = os.MkdirAll(filepath.Dir(path), 0700); err != nil {
			return nil, err
		}
		key, e := randomBytes(32)
		if e != nil {
			return nil, e
		}
		f, e := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
		if e != nil {
			return nil, fmt.Errorf("create credential key: %w", e)
		}
		// NAS ACL inheritance may override creation mode.
		if e = f.Chmod(0600); e == nil {
			_, e = f.WriteString(base64.StdEncoding.EncodeToString(key))
		}
		if e == nil {
			e = f.Sync()
		}
		closeErr := f.Close()
		if e == nil {
			e = closeErr
		}
		if e != nil {
			return nil, fmt.Errorf("write credential key: %w", e)
		}
		return &Cipher{key: key}, nil
	}
	if err != nil {
		return nil, fmt.Errorf("read credential key: %w", err)
	}
	info, err := os.Lstat(path)
	if err != nil {
		return nil, err
	}
	if !info.Mode().IsRegular() || info.Mode().Perm() != 0600 {
		return nil, errors.New("credential master key must be a regular file with permissions 0600")
	}
	key, err := base64.StdEncoding.DecodeString(strings.TrimSpace(string(data)))
	if err != nil || len(key) != 32 {
		return nil, errors.New("invalid credential master key")
	}
	return &Cipher{key: key}, nil
}

func (c *Cipher) Encrypt(value, purpose string) (string, error) {
	block, err := aes.NewCipher(c.key)
	if err != nil {
		return "", err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return "", err
	}
	nonce, err := randomBytes(gcm.NonceSize())
	if err != nil {
		return "", err
	}
	sealed := gcm.Seal(nil, nonce, []byte(value), []byte(purpose))
	return "v1:" + base64.StdEncoding.EncodeToString(nonce) + ":" + base64.StdEncoding.EncodeToString(sealed), nil
}

func (c *Cipher) Decrypt(value, purpose string) (string, error) {
	parts := strings.Split(value, ":")
	invalid := errors.New("credential master key does not match stored data")
	if len(parts) != 3 || parts[0] != "v1" {
		return "", invalid
	}
	nonce, err := base64.StdEncoding.DecodeString(parts[1])
	if err != nil {
		return "", invalid
	}
	data, err := base64.StdEncoding.DecodeString(parts[2])
	if err != nil {
		return "", invalid
	}
	block, err := aes.NewCipher(c.key)
	if err != nil {
		return "", invalid
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil || len(nonce) != gcm.NonceSize() {
		return "", invalid
	}
	plain, err := gcm.Open(nil, nonce, data, []byte(purpose))
	if err != nil || !utf8.Valid(plain) {
		return "", invalid
	}
	return string(plain), nil
}

func encodePassword(password string) (string, error) {
	salt, err := randomBytes(16)
	if err != nil {
		return "", err
	}
	hash := argon2.IDKey([]byte(password), salt, 2, 19456, 1, 32)
	return "$argon2id$v=19$m=19456,t=2,p=1$" + base64.RawStdEncoding.EncodeToString(salt) + "$" + base64.RawStdEncoding.EncodeToString(hash), nil
}

func matchesPassword(password, phc string) bool {
	if len(password) > 1024 || !utf8.ValidString(password) {
		return false
	}
	p := strings.Split(phc, "$")
	if len(p) != 6 || p[1] != "argon2id" || p[2] != "v=19" {
		return false
	}
	var memory, iterations, parallel uint32
	if n, err := fmt.Sscanf(p[3], "m=%d,t=%d,p=%d", &memory, &iterations, &parallel); err != nil || n != 3 {
		return false
	}
	// Bound stored parameters too: corrupted data must not exhaust the service.
	if memory < 8 || memory > 262144 || iterations < 1 || iterations > 10 || parallel < 1 || parallel > 16 {
		return false
	}
	salt, err := base64.RawStdEncoding.DecodeString(p[4])
	if err != nil || len(salt) < 8 || len(salt) > 64 {
		return false
	}
	want, err := base64.RawStdEncoding.DecodeString(p[5])
	if err != nil || len(want) < 16 || len(want) > 64 {
		return false
	}
	got := argon2.IDKey([]byte(password), salt, iterations, memory, uint8(parallel), uint32(len(want)))
	return subtle.ConstantTimeCompare(got, want) == 1
}

func ValidatePassword(value string) error {
	n := utf8.RuneCountInString(value)
	if !utf8.ValidString(value) || n < 15 || n > 128 {
		return &Failure{Status: 400, Detail: "新密码需要 15～128 个字符"}
	}
	return nil
}

func totp(secret string, step int64, digits int) (string, error) {
	if step < 0 || (digits != 6 && digits != 8) {
		return "", errors.New("invalid TOTP parameters")
	}
	key, err := base32.StdEncoding.WithPadding(base32.NoPadding).DecodeString(strings.ToUpper(strings.TrimRight(secret, "=")))
	if err != nil {
		return "", err
	}
	var b [8]byte
	binary.BigEndian.PutUint64(b[:], uint64(step))
	m := hmac.New(sha1.New, key)
	_, _ = m.Write(b[:])
	h := m.Sum(nil)
	off := h[len(h)-1] & 15
	n := binary.BigEndian.Uint32(h[off:off+4]) & 0x7fffffff
	mod := uint32(1000000)
	if digits == 8 {
		mod = 100000000
	}
	value := strconv.FormatUint(uint64(n%mod), 10)
	return strings.Repeat("0", digits-len(value)) + value, nil
}

func matchStep(secret, code string, now int64) (int64, error) {
	if len(code) != 6 {
		return -1, nil
	}
	for _, r := range code {
		if r < '0' || r > '9' {
			return -1, nil
		}
	}
	for step := now/30000 - 1; step <= now/30000+1; step++ {
		expected, err := totp(secret, step, 6)
		if err != nil {
			return -1, err
		}
		if subtle.ConstantTimeCompare([]byte(code), []byte(expected)) == 1 {
			return step, nil
		}
	}
	return -1, nil
}

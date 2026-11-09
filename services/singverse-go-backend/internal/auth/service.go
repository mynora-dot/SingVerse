package auth

import (
	"context"
	"database/sql"
	"encoding/base32"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"log/slog"
	"net/url"
	"regexp"
	"sync"
	"time"
	"unicode/utf16"

	"github.com/jmoiron/sqlx"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/config"
	qrcode "github.com/skip2/go-qrcode"
)

// Failure is an expected client rejection. Its transaction commits so failed
// attempts, consumed factors and rate limits cannot be bypassed with retries.
type Failure struct {
	Status     int
	Detail     string
	RetryAfter int
	Type       string
}

func (e *Failure) Error() string             { return e.Detail }
func reject(status int, detail string) error { return &Failure{Status: status, Detail: detail} }

type Service struct {
	db     *sqlx.DB
	Cipher *Cipher
	log    *slog.Logger
	policy originPolicy
	mu     sync.Mutex
	now    func() int64
}

func timeNow() int64 { return time.Now().UnixMilli() }

func New(db *sqlx.DB, dialect string, cfg config.Config, log *slog.Logger) (*Service, error) {
	if db == nil {
		return nil, errors.New("authentication database missing")
	}
	if dialect != "sqlite" && dialect != "mysql" {
		return nil, fmt.Errorf("unsupported authentication database %q", dialect)
	}
	policy, err := newOriginPolicy(cfg.PublicOrigin, cfg.TrustedProxies)
	if err != nil {
		return nil, err
	}
	ctx := context.Background()
	_, err = readAccount(ctx, db)
	if errors.Is(err, sql.ErrNoRows) {
		var hash string
		hash, err = encodePassword("admin")
		if err == nil {
			_, err = exec(ctx, db, "bootstrap", hash)
		}
	}
	if err != nil {
		return nil, fmt.Errorf("initialize administrator: %w", err)
	}
	credentials, err := encryptedCredentials(ctx, db)
	if err != nil {
		return nil, fmt.Errorf("inspect credential state: %w", err)
	}
	crypto, err := loadCipher(cfg.AuthKeyFile, len(credentials) > 0)
	if err != nil {
		return nil, err
	}
	for _, v := range credentials {
		if _, err = crypto.Decrypt(v.value, v.purpose); err != nil {
			return nil, err
		}
	}
	if log == nil {
		log = slog.Default()
	}
	return &Service{db: db, Cipher: crypto, log: log, policy: policy, now: timeNow}, nil
}

func (s *Service) ResetAdmin(ctx context.Context, password string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	return ResetAdministrator(ctx, s.db, password)
}

func (s *Service) transaction(ctx context.Context, fn func(*sqlx.Tx) (any, error)) (any, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	tx, err := s.db.BeginTxx(ctx, nil)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	result, err := fn(tx)
	var failure *Failure
	if err != nil && !errors.As(err, &failure) {
		return nil, err
	}
	if commitErr := tx.Commit(); commitErr != nil {
		return nil, commitErr
	}
	return result, err
}

type issued struct{ token, kind string }
type tokenRow struct {
	csrf     string
	attempts int
}

func (s *Service) issue(ctx context.Context, q dbtx, kind string) (issued, error) {
	now := s.now()
	if _, err := exec(ctx, q, "expire_tokens", now, now-1800000); err != nil {
		return issued{}, err
	}
	a, err := readAccount(ctx, q)
	if err != nil {
		return issued{}, err
	}
	token, err := randomToken()
	if err != nil {
		return issued{}, err
	}
	csrf, err := randomToken()
	if err != nil {
		return issued{}, err
	}
	ttl := int64(300000)
	if kind == "session" {
		ttl = 28800000
	}
	_, err = exec(ctx, q, "issue", digest(token), kind, csrf, now, now, now+ttl, a.Revision)
	return issued{token, kind}, err
}

func (s *Service) lookup(ctx context.Context, q dbtx, value, kind string) (*tokenRow, error) {
	if len(value) != 43 {
		return nil, nil
	}
	a, err := readAccount(ctx, q)
	if err != nil {
		return nil, err
	}
	var token tokenRow
	err = q.QueryRowContext(ctx, statements["find_token"], digest(value), kind, s.now(), s.now()-1800000, a.Revision).Scan(&token.csrf, &token.attempts)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	return &token, err
}

func (s *Service) revoke(ctx context.Context, q dbtx, value string) error {
	if value == "" {
		return nil
	}
	_, err := exec(ctx, q, "revoke", digest(value))
	return err
}

func buckets(address string) []string { return []string{"account:admin", "ip:" + digest(address)} }
func (s *Service) checkLimit(ctx context.Context, q dbtx, address string) error {
	for _, bucket := range buckets(address) {
		var count int
		var blocked, updated int64
		err := q.QueryRowContext(ctx, statements["throttle"], bucket).Scan(&count, &blocked, &updated)
		if errors.Is(err, sql.ErrNoRows) {
			continue
		}
		if err != nil {
			return err
		}
		if remaining := blocked - s.now(); remaining > 0 {
			return &Failure{Status: 429, Detail: "尝试过于频繁，请稍后重试", RetryAfter: int((remaining + 999) / 1000)}
		}
	}
	return nil
}
func (s *Service) failure(ctx context.Context, q dbtx, address string) error {
	now := s.now()
	for _, bucket := range buckets(address) {
		var count int
		var blocked, updated int64
		err := q.QueryRowContext(ctx, statements["throttle"], bucket).Scan(&count, &blocked, &updated)
		missing := errors.Is(err, sql.ErrNoRows)
		if err != nil && !missing {
			return err
		}
		if missing || updated < now-3600000 {
			count = 1
		} else {
			count++
		}
		delay := int64(0)
		if count >= 5 {
			shift := min(count-5, 10)
			delay = min(int64(900000), int64(1000)<<shift)
		}
		if missing {
			_, err = exec(ctx, q, "insert_throttle", bucket, count, now+delay, now)
		} else {
			_, err = exec(ctx, q, "update_throttle", count, now+delay, now, bucket)
		}
		if err != nil {
			return err
		}
	}
	return recordEvent(ctx, q, "authentication_failed", now)
}
func (s *Service) success(ctx context.Context, q dbtx, address string) error {
	b := buckets(address)
	_, err := exec(ctx, q, "clear_limits", b[0], b[1])
	return err
}
func (s *Service) credentialsRejected(ctx context.Context, q dbtx, address string) error {
	if err := s.failure(ctx, q, address); err != nil {
		return err
	}
	return reject(401, "认证失败，请检查凭据")
}

func passwordValid(value string, a account) bool {
	return len(utf16.Encode([]rune(value))) <= 256 && matchesPassword(value, a.PasswordHash)
}

func (s *Service) login(ctx context.Context, q dbtx, username, password, address string) (issued, error) {
	if err := s.checkLimit(ctx, q, address); err != nil {
		return issued{}, err
	}
	a, err := readAccount(ctx, q)
	if err != nil {
		return issued{}, err
	}
	// Always calculate Argon2 before comparing the username.
	valid := passwordValid(password, a)
	if a.Username != username || !valid {
		return issued{}, s.credentialsRejected(ctx, q, address)
	}
	if a.Secret.Valid {
		return s.issue(ctx, q, "challenge")
	}
	if err = s.success(ctx, q, address); err != nil {
		return issued{}, err
	}
	if err = recordEvent(ctx, q, "login_succeeded", s.now()); err != nil {
		return issued{}, err
	}
	return s.issue(ctx, q, "session")
}

func (s *Service) verifyFactor(ctx context.Context, q dbtx, code string, a account) (bool, error) {
	if code == "" || len(code) > 128 || !a.Secret.Valid {
		return false, nil
	}
	if regexp.MustCompile(`^[0-9]{6}$`).MatchString(code) {
		secret, err := s.Cipher.Decrypt(a.Secret.String, totpPurpose)
		if err != nil {
			return false, err
		}
		step, err := matchStep(secret, code, s.now())
		if err != nil || step < 0 {
			return false, err
		}
		n, err := exec(ctx, q, "accept_step", step, step, a.Revision)
		return n == 1, err
	}
	n, err := exec(ctx, q, "consume_recovery", digest(code))
	return n == 1, err
}

func (s *Service) complete(ctx context.Context, q dbtx, token, code, address string) (issued, error) {
	if err := s.checkLimit(ctx, q, address); err != nil {
		return issued{}, err
	}
	row, err := s.lookup(ctx, q, token, "challenge")
	if err != nil {
		return issued{}, err
	}
	if row == nil {
		return issued{}, reject(401, "认证已过期，请重新登录")
	}
	if _, err = exec(ctx, q, "attempt", digest(token)); err != nil {
		return issued{}, err
	}
	a, err := readAccount(ctx, q)
	if err != nil {
		return issued{}, err
	}
	valid, err := s.verifyFactor(ctx, q, code, a)
	if err != nil {
		return issued{}, err
	}
	if !valid {
		if row.attempts+1 >= 5 {
			if err = s.revoke(ctx, q, token); err != nil {
				return issued{}, err
			}
		}
		return issued{}, s.credentialsRejected(ctx, q, address)
	}
	if err = s.revoke(ctx, q, token); err != nil {
		return issued{}, err
	}
	if err = s.success(ctx, q, address); err != nil {
		return issued{}, err
	}
	if err = recordEvent(ctx, q, "mfa_login_succeeded", s.now()); err != nil {
		return issued{}, err
	}
	return s.issue(ctx, q, "session")
}

func (s *Service) reauthenticate(ctx context.Context, q dbtx, password, code, address string) error {
	if err := s.checkLimit(ctx, q, address); err != nil {
		return err
	}
	a, err := readAccount(ctx, q)
	if err != nil {
		return err
	}
	valid := passwordValid(password, a)
	if valid && a.Secret.Valid {
		valid, err = s.verifyFactor(ctx, q, code, a)
		if err != nil {
			return err
		}
	}
	if !valid {
		return s.credentialsRejected(ctx, q, address)
	}
	return s.success(ctx, q, address)
}

func (s *Service) status(ctx context.Context, q dbtx) (any, error) {
	a, err := readAccount(ctx, q)
	if err != nil {
		return nil, err
	}
	var count int
	if err = q.QueryRowContext(ctx, statements["count_recovery"]).Scan(&count); err != nil {
		return nil, err
	}
	return map[string]any{"username": a.Username, "defaultPassword": a.DefaultPassword == 1, "mfaEnabled": a.Secret.Valid, "recoveryCodesRemaining": count}, nil
}

func (s *Service) setup(ctx context.Context, q dbtx, password, code, address string) (any, error) {
	if err := s.reauthenticate(ctx, q, password, code, address); err != nil {
		return nil, err
	}
	key, err := randomBytes(20)
	if err != nil {
		return nil, err
	}
	secret := base32.StdEncoding.WithPadding(base32.NoPadding).EncodeToString(key)
	encrypted, err := s.Cipher.Encrypt(secret, totpPurpose)
	if err != nil {
		return nil, err
	}
	expires := s.now() + 300000
	if _, err = exec(ctx, q, "setup", encrypted, expires); err != nil {
		return nil, err
	}
	a, err := readAccount(ctx, q)
	if err != nil {
		return nil, err
	}
	u := url.URL{Scheme: "otpauth", Host: "totp", Path: "/SingVerse:" + a.Username}
	params := url.Values{"secret": {secret}, "issuer": {"SingVerse"}, "algorithm": {"SHA1"}, "digits": {"6"}, "period": {"30"}}
	u.RawQuery = params.Encode()
	png, err := qrcode.Encode(u.String(), qrcode.Medium, 256)
	if err != nil {
		return nil, err
	}
	if err = recordEvent(ctx, q, "mfa_setup_started", s.now()); err != nil {
		return nil, err
	}
	return map[string]any{"secret": secret, "qrDataUrl": "data:image/png;base64," + base64.StdEncoding.EncodeToString(png), "expiresAt": time.UnixMilli(expires).UTC().Format(time.RFC3339Nano)}, nil
}

func (s *Service) newRecovery(ctx context.Context, q dbtx) ([]string, error) {
	if _, err := exec(ctx, q, "clear_recovery"); err != nil {
		return nil, err
	}
	codes := make([]string, 10)
	for i := range codes {
		b, err := randomBytes(16)
		if err != nil {
			return nil, err
		}
		codes[i] = hex.EncodeToString(b)
		if _, err = exec(ctx, q, "insert_recovery", digest(codes[i])); err != nil {
			return nil, err
		}
	}
	return codes, nil
}
func (s *Service) invalidate(ctx context.Context, q dbtx) error {
	if _, err := exec(ctx, q, "invalidate"); err != nil {
		return err
	}
	_, err := exec(ctx, q, "revoke_all")
	return err
}

func (s *Service) confirm(ctx context.Context, q dbtx, code, address string) (any, error) {
	if err := s.checkLimit(ctx, q, address); err != nil {
		return nil, err
	}
	a, err := readAccount(ctx, q)
	if err != nil {
		return nil, err
	}
	if !a.Pending.Valid || a.PendingExpires < s.now() {
		if _, err = exec(ctx, q, "cancel_setup"); err != nil {
			return nil, err
		}
		return nil, reject(409, "绑定已过期，请重新开始")
	}
	secret, err := s.Cipher.Decrypt(a.Pending.String, totpPurpose)
	if err != nil {
		return nil, err
	}
	step, err := matchStep(secret, code, s.now())
	if err != nil {
		return nil, err
	}
	if step < 0 {
		if _, err = exec(ctx, q, "setup_attempt"); err != nil {
			return nil, err
		}
		if a.PendingAttempts+1 >= 5 {
			if _, err = exec(ctx, q, "cancel_setup"); err != nil {
				return nil, err
			}
		}
		return nil, s.credentialsRejected(ctx, q, address)
	}
	if _, err = exec(ctx, q, "confirm", step); err != nil {
		return nil, err
	}
	codes, err := s.newRecovery(ctx, q)
	if err != nil {
		return nil, err
	}
	if err = s.invalidate(ctx, q); err != nil {
		return nil, err
	}
	if err = s.success(ctx, q, address); err != nil {
		return nil, err
	}
	if err = recordEvent(ctx, q, "mfa_enabled", s.now()); err != nil {
		return nil, err
	}
	return map[string]any{"recoveryCodes": codes}, nil
}

func (s *Service) regenerate(ctx context.Context, q dbtx, password, code, address string) (any, error) {
	if err := s.reauthenticate(ctx, q, password, code, address); err != nil {
		return nil, err
	}
	a, err := readAccount(ctx, q)
	if err != nil {
		return nil, err
	}
	if !a.Secret.Valid {
		return nil, reject(409, "尚未开启二次验证")
	}
	codes, err := s.newRecovery(ctx, q)
	if err != nil {
		return nil, err
	}
	if err = s.invalidate(ctx, q); err != nil {
		return nil, err
	}
	if err = recordEvent(ctx, q, "recovery_codes_regenerated", s.now()); err != nil {
		return nil, err
	}
	return map[string]any{"recoveryCodes": codes}, nil
}

func (s *Service) disable(ctx context.Context, q dbtx, password, code, address string) error {
	if err := s.reauthenticate(ctx, q, password, code, address); err != nil {
		return err
	}
	for _, name := range []string{"disable", "clear_recovery"} {
		if _, err := exec(ctx, q, name); err != nil {
			return err
		}
	}
	if err := s.invalidate(ctx, q); err != nil {
		return err
	}
	return recordEvent(ctx, q, "mfa_disabled", s.now())
}

var usernamePattern = regexp.MustCompile(`^[A-Za-z0-9_][A-Za-z0-9_.-]{2,31}$`)

func (s *Service) change(ctx context.Context, q dbtx, kind, password, code, next, address string) error {
	if kind == "password" {
		if err := ValidatePassword(next); err != nil {
			return err
		}
	} else {
		if !usernamePattern.MatchString(next) {
			return reject(400, "登录账号需要 3～32 个字母、数字、下划线、点或短横线，且以字母、数字或下划线开头")
		}
		a, err := readAccount(ctx, q)
		if err != nil {
			return err
		}
		if next == a.Username {
			return reject(400, "新登录账号不能与当前账号相同")
		}
	}
	if err := s.reauthenticate(ctx, q, password, code, address); err != nil {
		return err
	}
	value := next
	if kind == "password" {
		var err error
		value, err = encodePassword(next)
		if err != nil {
			return err
		}
	}
	if _, err := exec(ctx, q, kind, value); err != nil {
		return err
	}
	if err := s.invalidate(ctx, q); err != nil {
		return err
	}
	return recordEvent(ctx, q, kind+"_changed", s.now())
}

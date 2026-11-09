// Package compute owns the optional internal compute connection and its pairing
// boundary. A node is never a dependency of the business service health check.
package compute

import (
	"context"
	"crypto/rsa"
	"database/sql"
	"embed"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"
	"unicode"

	"github.com/jmoiron/sqlx"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/config"
)

const credentialPurpose = "singverse:compute-node:v1"

//go:embed sql/*.sql
var queries embed.FS

func query(name string) string { b, _ := queries.ReadFile("sql/" + name + ".sql"); return string(b) }

type Cipher interface {
	Encrypt(value, purpose string) (string, error)
	Decrypt(value, purpose string) (string, error)
}

type PathMapping struct {
	LocalRoot  string `json:"localRoot"`
	RemoteRoot string `json:"remoteRoot"`
}
type Settings struct {
	Mode              string        `json:"mode"`
	Endpoint          string        `json:"endpoint"`
	HasToken          bool          `json:"hasToken"`
	Mappings          []PathMapping `json:"mappings"`
	AutomaticEndpoint string        `json:"automaticEndpoint"`
	AutomaticHasToken bool          `json:"automaticHasToken"`
	Revision          int64         `json:"revision"`
}
type Save struct {
	Mode       string        `json:"mode"`
	Endpoint   string        `json:"endpoint"`
	Token      string        `json:"token"`
	ClearToken bool          `json:"clearToken"`
	Mappings   []PathMapping `json:"mappings"`
	Revision   int64         `json:"revision"`
}
type Check struct {
	Status       string   `json:"status"`
	Message      string   `json:"message"`
	Capabilities []string `json:"capabilities"`
	ElapsedMs    int64    `json:"elapsedMs"`
}

// Connection is an immutable snapshot for a single operation. Tokens must only
// travel in the server-to-node Authorization header, never in browser DTOs.
type Connection struct {
	Origin   string        `json:"origin"`
	Token    string        `json:"-"`
	Mappings []PathMapping `json:"mappings"`
}

func (c *Connection) MediaPath(value string) string {
	local, err := filepath.Abs(value)
	if err != nil {
		return value
	}
	local = filepath.Clean(local)
	for _, mapping := range c.Mappings {
		relative, err := filepath.Rel(mapping.LocalRoot, local)
		if err != nil || relative == ".." || strings.HasPrefix(relative, ".."+string(filepath.Separator)) {
			continue
		}
		if relative == "." {
			relative = ""
		}
		remote := strings.TrimRight(strings.ReplaceAll(mapping.RemoteRoot, "\\", "/"), "/") + "/" + filepath.ToSlash(relative)
		if windowsAbsolute(mapping.RemoteRoot) {
			return strings.ReplaceAll(remote, "/", "\\")
		}
		return remote
	}
	return local
}

type failure struct {
	status int
	detail string
}

func (f *failure) Error() string             { return f.detail }
func (f *failure) StatusCode() int           { return f.status }
func reject(status int, detail string) error { return &failure{status, detail} }

type grant struct {
	token   string
	expires time.Time
}
type candidate struct {
	endpoint, nodeID string
	expires          time.Time
}
type Service struct {
	db                                                              *sqlx.DB
	cipher                                                          Cipher
	log                                                             *slog.Logger
	automaticEndpoint, tokenFile, configuredOrigin, automaticOrigin string
	mu                                                              sync.Mutex
	observed                                                        string
	grants                                                          map[string]grant
	candidates                                                      map[string]candidate
	keysMu                                                          sync.Mutex
	keys                                                            *rsa.PrivateKey
	publicDER                                                       []byte
	scanning                                                        atomic.Bool
	root                                                            context.Context
	cancel                                                          context.CancelFunc
}

func New(db *sqlx.DB, dialect string, cfg config.Config, cipher Cipher, log *slog.Logger) (*Service, error) {
	if db == nil || cipher == nil {
		return nil, errors.New("compute requires database and credential cipher")
	}
	if dialect != "sqlite" && dialect != "mysql" {
		return nil, errors.New("unsupported compute database dialect")
	}
	if log == nil {
		log = slog.Default()
	}
	s := &Service{db: db, cipher: cipher, log: log, tokenFile: cfg.ComputeTokenFile, grants: make(map[string]grant), candidates: make(map[string]candidate)}
	var err error
	for _, entry := range []struct {
		value  string
		target *string
	}{{cfg.ComputeURL, &s.automaticEndpoint}, {cfg.CallbackOrigin, &s.configuredOrigin}, {cfg.AutoCallbackOrigin, &s.automaticOrigin}} {
		if strings.TrimSpace(entry.value) == "" {
			continue
		}
		*entry.target, err = origin(entry.value)
		if err != nil {
			return nil, errors.New("invalid configured compute origin")
		}
	}
	if s.configuredOrigin == "" && cfg.PublicOrigin != "" {
		s.configuredOrigin, err = origin(cfg.PublicOrigin)
		if err != nil {
			return nil, errors.New("invalid configured compute callback origin")
		}
	}
	s.root, s.cancel = context.WithCancel(context.Background())
	return s, nil
}
func (s *Service) Close() {
	s.cancel()
	s.mu.Lock()
	clear(s.grants)
	clear(s.candidates)
	s.mu.Unlock()
}

type settingsRow struct {
	mode, endpoint string
	secret         sql.NullString
	mappings       []PathMapping
	revision       int64
}

func (s *Service) row(ctx context.Context) (settingsRow, error) {
	var r settingsRow
	var mappings string
	err := s.db.QueryRowContext(ctx, query("settings")).Scan(&r.mode, &r.endpoint, &r.secret, &mappings, &r.revision)
	if err != nil {
		return r, err
	}
	if err = json.Unmarshal([]byte(mappings), &r.mappings); err != nil {
		return r, errors.New("invalid stored compute mappings")
	}
	if r.mappings == nil {
		r.mappings = []PathMapping{}
	}
	return r, nil
}
func (s *Service) View(ctx context.Context) (Settings, error) {
	r, err := s.row(ctx)
	if err != nil {
		return Settings{}, err
	}
	return s.view(r), nil
}
func (s *Service) view(r settingsRow) Settings {
	return Settings{r.mode, r.endpoint, r.secret.Valid, r.mappings, s.automaticEndpoint, s.automaticToken() != "", r.revision}
}
func (s *Service) Snapshot(ctx context.Context) (*Connection, error) {
	r, err := s.row(ctx)
	if err != nil {
		return nil, err
	}
	return s.connection(r, nil)
}
func (s *Service) Draft(ctx context.Context, input Save) (*Connection, error) {
	if err := validateSave(input); err != nil {
		return nil, err
	}
	r, err := s.row(ctx)
	if err != nil {
		return nil, err
	}
	if r.revision != input.Revision {
		return nil, reject(409, "配置已被修改，请刷新后重试")
	}
	return s.connection(r, &input)
}
func (s *Service) connection(r settingsRow, input *Save) (*Connection, error) {
	mode := r.mode
	if input != nil {
		mode = input.Mode
	}
	switch mode {
	case "DISABLED":
		return nil, nil
	case "AUTO":
		if s.automaticEndpoint == "" {
			return nil, nil
		}
		return &Connection{s.automaticEndpoint, s.automaticToken(), []PathMapping{}}, nil
	case "MANUAL":
		endpoint, maps, secret := r.endpoint, r.mappings, r.secret
		var err error
		if input != nil {
			endpoint, err = origin(input.Endpoint)
			if err != nil {
				return nil, err
			}
			maps, err = validatedMappings(input.Mappings)
			if err != nil {
				return nil, err
			}
			secret, err = s.credential(r, *input, endpoint)
			if err != nil {
				return nil, err
			}
		}
		endpoint, err = origin(endpoint)
		if err != nil {
			return nil, err
		}
		var token string
		if secret.Valid {
			token, err = s.cipher.Decrypt(secret.String, credentialPurpose)
			if err != nil {
				return nil, errors.New("compute credential cannot be decrypted")
			}
		}
		return &Connection{endpoint, token, maps}, nil
	default:
		return nil, errors.New("invalid stored compute mode")
	}
}
func (s *Service) Save(ctx context.Context, input Save) (Settings, error) {
	if err := validateSave(input); err != nil {
		return Settings{}, err
	}
	r, err := s.row(ctx)
	if err != nil {
		return Settings{}, err
	}
	if r.revision != input.Revision {
		return Settings{}, reject(409, "配置已被修改，请刷新后重试")
	}
	if input.Mode == "MANUAL" {
		prior := r
		r.endpoint, err = origin(input.Endpoint)
		if err != nil {
			return Settings{}, err
		}
		r.mappings, err = validatedMappings(input.Mappings)
		if err != nil {
			return Settings{}, err
		}
		r.secret, err = s.credential(prior, input, r.endpoint)
		if err != nil {
			return Settings{}, err
		}
	}
	maps, err := json.Marshal(r.mappings)
	if err != nil {
		return Settings{}, err
	}
	var secret any
	if r.secret.Valid {
		secret = r.secret.String
	}
	result, err := s.db.ExecContext(ctx, query("save"), input.Mode, r.endpoint, secret, string(maps), input.Revision)
	if err != nil {
		return Settings{}, err
	}
	rows, err := result.RowsAffected()
	if err != nil {
		return Settings{}, err
	}
	if rows != 1 {
		return Settings{}, reject(409, "配置已被修改，请刷新后重试")
	}
	r.mode = input.Mode
	r.revision++
	return s.view(r), nil
}
func (s *Service) credential(r settingsRow, input Save, endpoint string) (sql.NullString, error) {
	value := strings.TrimSpace(input.Token)
	if input.ClearToken && value != "" {
		return sql.NullString{}, reject(400, "重新填写和清除凭据不能同时选择")
	}
	if value != "" {
		if !validToken(value) {
			return sql.NullString{}, reject(400, "认证凭据应为32～256个可见ASCII字符")
		}
		sealed, err := s.cipher.Encrypt(value, credentialPurpose)
		return sql.NullString{String: sealed, Valid: err == nil}, err
	}
	if input.ClearToken {
		return sql.NullString{}, nil
	}
	if endpoint != r.endpoint || !r.secret.Valid {
		return sql.NullString{}, reject(400, "请填写认证凭据；修改节点地址后需要重新填写")
	}
	return r.secret, nil
}
func (s *Service) automaticToken() string {
	if s.tokenFile == "" {
		return ""
	}
	f, err := os.Open(s.tokenFile)
	if err != nil {
		return ""
	}
	defer f.Close()
	b, err := io.ReadAll(io.LimitReader(f, 258))
	if err != nil || len(b) > 257 {
		return ""
	}
	v := strings.TrimSpace(string(b))
	if !validToken(v) {
		return ""
	}
	return v
}
func validToken(v string) bool {
	if len(v) < 32 || len(v) > 256 {
		return false
	}
	for _, b := range []byte(v) {
		if b < 33 || b > 126 {
			return false
		}
	}
	return true
}
func validateSave(v Save) error {
	if v.Mode != "AUTO" && v.Mode != "MANUAL" && v.Mode != "DISABLED" {
		return reject(400, "请选择节点连接方式")
	}
	if v.Revision < 0 {
		return reject(400, "配置版本无效")
	}
	if v.Mode == "MANUAL" {
		if _, err := origin(v.Endpoint); err != nil {
			return err
		}
		_, err := validatedMappings(v.Mappings)
		return err
	}
	return nil
}
func origin(value string) (string, error) {
	v := strings.TrimSpace(value)
	u, err := url.Parse(v)
	if err != nil || len(v) > 512 || u == nil || (u.Scheme != "http" && u.Scheme != "https") || u.Hostname() == "" || u.User != nil || u.Opaque != "" || u.RawQuery != "" || u.ForceQuery || strings.Contains(v, "#") || (u.Path != "" && u.Path != "/") || u.RawPath != "" || strings.Contains(u.Hostname(), "%") {
		return "", reject(400, "节点地址应为HTTP(S)服务根地址，不包含账号、路径或查询参数")
	}
	if u.Port() != "" {
		port, err := parsePort(u.Port())
		if err != nil || port == 0 {
			return "", reject(400, "节点地址端口无效")
		}
	}
	return u.Scheme + "://" + u.Host, nil
}
func hasControl(v string) bool { return strings.IndexFunc(v, unicode.IsControl) >= 0 }
func windowsAbsolute(v string) bool {
	return len(v) >= 3 && ((v[0] >= 'A' && v[0] <= 'Z') || (v[0] >= 'a' && v[0] <= 'z')) && v[1] == ':' && (v[2] == '/' || v[2] == '\\')
}
func validatedMappings(values []PathMapping) ([]PathMapping, error) {
	if len(values) > 16 {
		return nil, reject(400, "最多配置16条媒体路径映射")
	}
	result := make([]PathMapping, 0, len(values))
	roots := map[string]bool{}
	for _, v := range values {
		if len(v.LocalRoot) > 2048 || len(v.RemoteRoot) > 2048 || !filepath.IsAbs(v.LocalRoot) || (!strings.HasPrefix(v.RemoteRoot, "/") && !windowsAbsolute(v.RemoteRoot)) || hasControl(v.LocalRoot) || hasControl(v.RemoteRoot) {
			return nil, reject(400, "路径映射必须使用有效的绝对路径")
		}
		v.LocalRoot = filepath.Clean(v.LocalRoot)
		v.RemoteRoot = strings.ReplaceAll(v.RemoteRoot, "\\", "/")
		for _, part := range strings.Split(v.RemoteRoot, "/") {
			if part == "." || part == ".." {
				return nil, reject(400, "远程路径映射不能包含相对跳转")
			}
		}
		if !windowsAbsolute(v.RemoteRoot) {
			v.RemoteRoot = filepath.Clean(v.RemoteRoot)
		}
		if roots[v.LocalRoot] {
			return nil, reject(400, "本机媒体根目录不能重复")
		}
		roots[v.LocalRoot] = true
		result = append(result, v)
	}
	sort.SliceStable(result, func(i, j int) bool { return len(result[i].LocalRoot) > len(result[j].LocalRoot) })
	return result, nil
}

// Package execution authorizes media execution on optional compute nodes and
// owns the verified derived artifacts stored by the business service.
package execution

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"embed"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
	"unicode/utf16"

	"github.com/gin-gonic/gin"
	"github.com/jmoiron/sqlx"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/compute"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/library"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/config"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/httpx"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

//go:embed sql/*.sql
var statements embed.FS

type Cipher interface {
	Encrypt(string, string) (string, error)
	Decrypt(string, string) (string, error)
}

type Projector func(context.Context, store.DBTX, string, string) error
type Service struct {
	db                 *sqlx.DB
	dialect            string
	library            *library.Service
	compute            *compute.Service
	log                *slog.Logger
	cfg                config.Config
	ctx                context.Context
	cancel             context.CancelFunc
	wg                 sync.WaitGroup
	mu                 sync.Mutex
	projectMu          sync.RWMutex
	project            Projector
	scopes             AudioScopes
	active             map[string]context.CancelFunc
	preloads           map[string]context.CancelFunc
	audioWorking       atomic.Bool
	transcodeWorking   atomic.Bool
	preparing          atomic.Bool
	scanCursor         string
	scopeBatchCursor   string
	scopeSongCursor    string
	discoveryRound     string
	catalogMu          sync.Mutex
	catalog            []map[string]any
	catalogBuilt       atomic.Int64
	cacheGeneration    atomic.Int64
	orphanCacheBytes   atomic.Int64
	cacheMu            sync.Mutex
	cacheReaders       map[string]int
	cacheReservations  map[string]int64
	cacheCaptures      map[string]bool
	prefetchSongs      map[string]string
	prefetchIDs        map[string]string
	prefetchFetch      map[string]context.CancelFunc
	originalFlights    map[string]chan struct{}
	originalForeground atomic.Int64
	localInputs        map[string]*localInputGrant
	cleanupID          string
	cleanupMode        string
	cleanupEntry       string
	cleanupConfirm     bool
	cleanupCancel      context.CancelFunc
}

func New(db *sqlx.DB, dialect string, cfg config.Config, cipher Cipher, librarySvc *library.Service, computeSvc *compute.Service, log *slog.Logger) (*Service, error) {
	if db == nil || librarySvc == nil || computeSvc == nil || cipher == nil {
		return nil, errors.New("execution requires database, library, compute and credential cipher")
	}
	if dialect != "sqlite" && dialect != "mysql" {
		return nil, errors.New("unsupported execution database dialect")
	}
	if log == nil {
		log = slog.Default()
	}
	for _, entry := range []*string{&cfg.AudioDerivedPath, &cfg.TranscodePath, &cfg.SongCachePath} {
		if *entry == "" {
			return nil, errors.New("execution artifact directories must be configured")
		}
		path, err := filepath.Abs(*entry)
		if err != nil {
			return nil, err
		}
		*entry = path
		if err = os.MkdirAll(path, 0700); err != nil {
			return nil, err
		}
		info, err := os.Lstat(path)
		if err != nil || !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
			return nil, errors.New("execution artifact root must be a real directory")
		}
	}
	ctx, cancel := context.WithCancel(context.Background())
	s := &Service{db: db, dialect: dialect, library: librarySvc, compute: computeSvc, cfg: cfg, log: log, ctx: ctx, cancel: cancel, active: map[string]context.CancelFunc{}, preloads: map[string]context.CancelFunc{}, cacheReaders: map[string]int{}, cacheReservations: map[string]int64{}, cacheCaptures: map[string]bool{}, prefetchSongs: map[string]string{}, originalFlights: map[string]chan struct{}{}}
	if cfg.SongCacheBytes > 0 {
		if _, err := s.exec(ctx, db, "cache-initialize", map[string]any{"bytes": cfg.SongCacheBytes}); err != nil {
			cancel()
			return nil, err
		}
	}
	// Pending preparation cannot safely resume an in-memory selection after restart.
	if _, err := s.exec(ctx, db, "batch-recover-preparing", map[string]any{"now": now()}); err != nil {
		cancel()
		return nil, err
	}
	s.localInputs = map[string]*localInputGrant{}
	s.prefetchIDs = map[string]string{}
	s.prefetchFetch = map[string]context.CancelFunc{}
	s.discoveryRound = uuid()
	if err := s.bootstrapFormalAudio(ctx); err != nil {
		cancel()
		return nil, err
	}
	s.recoverCache(ctx)
	s.run()
	return s, nil
}

func (s *Service) SetProjector(project Projector) {
	s.projectMu.Lock()
	s.project = project
	s.projectMu.Unlock()
}

func (s *Service) projectWith(ctx context.Context, tx store.DBTX, kind, id string) error {
	s.projectMu.RLock()
	project := s.project
	s.projectMu.RUnlock()
	if project != nil {
		return project(ctx, tx, kind, id)
	}
	return nil
}

func (s *Service) transaction(ctx context.Context, kind, id string, fn func(*sqlx.Tx) error) error {
	tx, err := s.db.BeginTxx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if err = fn(tx); err != nil {
		return err
	}
	if kind != "" {
		if err = s.projectWith(ctx, tx, kind, id); err != nil {
			return err
		}
	}
	return tx.Commit()
}

func (s *Service) Close() {
	s.cancel()
	s.mu.Lock()
	for _, cancel := range s.active {
		cancel()
	}
	for _, cancel := range s.preloads {
		cancel()
	}
	s.mu.Unlock()
	s.wg.Wait()
}

func (s *Service) run() {
	// Each executor is serial and bounded, while a large artifact transfer cannot
	// delay audio cancellation, discovery, or cache maintenance.
	for _, job := range []struct {
		interval time.Duration
		run      func(context.Context)
	}{
		{2 * time.Second, s.tickTranscodes}, {2 * time.Second, s.tickAudio}, {3 * time.Second, s.tickBatches},
		{10 * time.Second, s.discoverAudio}, {time.Minute, s.maintainCache}, {time.Hour, s.collectAudio},
	} {
		s.wg.Add(1)
		go func() {
			defer s.wg.Done()
			ticker := time.NewTicker(job.interval)
			defer ticker.Stop()
			for {
				select {
				case <-s.ctx.Done():
					return
				case <-ticker.C:
					job.run(s.ctx)
				}
			}
		}()
	}
}

func statement(name string) (string, error) {
	b, err := statements.ReadFile("sql/" + name + ".sql")
	if err != nil {
		return "", fmt.Errorf("execution query %s missing", name)
	}
	return string(b), nil
}

func (s *Service) bound(name string, values map[string]any) (string, []any, error) {
	if s.dialect == "mysql" && (name == "cache-ensure-profile" || name == "metadata-dirty") {
		name += "-mysql"
	}
	q, err := statement(name)
	if err != nil {
		return "", nil, err
	}
	return store.Bind(q, values)
}

func (s *Service) exec(ctx context.Context, db store.DBTX, name string, values map[string]any) (sql.Result, error) {
	q, args, err := s.bound(name, values)
	if err != nil {
		return nil, err
	}
	return db.ExecContext(ctx, q, args...)
}

func (s *Service) maps(ctx context.Context, db store.DBTX, name string, values map[string]any) ([]map[string]any, error) {
	q, args, err := s.bound(name, values)
	if err != nil {
		return nil, err
	}
	return store.Maps(ctx, db, q, args...)
}

func (s *Service) one(ctx context.Context, db store.DBTX, name string, values map[string]any) (map[string]any, error) {
	q, args, err := s.bound(name, values)
	if err != nil {
		return nil, err
	}
	return store.Map(ctx, db, q, args...)
}

func (s *Service) optional(ctx context.Context, db store.DBTX, name string, values map[string]any) (map[string]any, error) {
	v, err := s.one(ctx, db, name, values)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	return v, err
}

type failure struct {
	status int
	detail string
}

func (f *failure) Error() string           { return f.detail }
func (f *failure) StatusCode() int         { return f.status }
func fail(status int, detail string) error { return &failure{status, detail} }
func (s *Service) report(c *gin.Context, err error) {
	var code interface{ StatusCode() int }
	if errors.As(err, &code) {
		httpx.Error(c, code.StatusCode(), "about:blank", err.Error())
		return
	}
	s.log.ErrorContext(c.Request.Context(), "media execution failed", "error_type", fmt.Sprintf("%T", err))
	httpx.Error(c, 500, "about:blank", "媒体处理暂时无法完成")
}

func now() int64 { return time.Now().UnixMilli() }
func str(v map[string]any, key string) string {
	if v == nil || v[key] == nil {
		return ""
	}
	if s, ok := v[key].(string); ok {
		return s
	}
	return fmt.Sprint(v[key])
}

func number(v any) float64 {
	switch n := v.(type) {
	case int:
		return float64(n)
	case int64:
		return float64(n)
	case float64:
		return n
	case json.Number:
		f, _ := n.Float64()
		return f
	}
	return 0
}

func integer(v any) int64 { return int64(number(v)) }
func boolv(v any) bool    { b, _ := v.(bool); return b }
func obj(v any) map[string]any {
	m, _ := v.(map[string]any)
	if m == nil {
		return map[string]any{}
	}
	return m
}

func array(v any) []any { a, _ := v.([]any); return a }
func decoded(v any) map[string]any {
	if m, ok := v.(map[string]any); ok {
		return m
	}
	var m map[string]any
	_ = json.Unmarshal([]byte(fmt.Sprint(v)), &m)
	if m == nil {
		return map[string]any{}
	}
	return m
}

func encoded(v any) string                    { b, _ := json.Marshal(v); return string(b) }
func copyMap(v map[string]any) map[string]any { return decoded(encoded(v)) }
func contains(v any, value string) bool {
	for _, item := range array(v) {
		if item == value {
			return true
		}
	}
	return false
}

func oneOf(v string, values ...string) bool {
	for _, value := range values {
		if v == value {
			return true
		}
	}
	return false
}

func uuid() string {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		panic("secure random source unavailable")
	}
	b[6] = b[6]&15 | 64
	b[8] = b[8]&63 | 128
	v := hex.EncodeToString(b[:])
	return v[:8] + "-" + v[8:12] + "-" + v[12:16] + "-" + v[16:20] + "-" + v[20:]
}

func hash(value string) string {
	sum := sha256.Sum256([]byte(value))
	return hex.EncodeToString(sum[:])
}

func javaString(value any) string {
	if value == nil {
		return "null"
	}
	return fmt.Sprint(value)
}

func digest(values ...any) string {
	var b strings.Builder
	for _, v := range values {
		if v == nil {
			b.WriteString("-1:")
		} else {
			value := fmt.Sprint(v)
			b.WriteString(strconv.Itoa(len(utf16.Encode([]rune(value)))))
			b.WriteByte(':')
			b.WriteString(value)
		}
	}
	return hash(b.String())
}

func (s *Service) song(ctx context.Context, db store.DBTX, id string) (map[string]any, error) {
	row, err := s.one(ctx, db, "song", map[string]any{"id": id})
	if errors.Is(err, sql.ErrNoRows) {
		return nil, fail(404, "歌曲不存在或已从来源中移除")
	}
	return row, err
}

func contentFingerprint(row map[string]any) string {
	size, modified := row["file_size"], row["modified_at"]
	if row["remote_resource_id"] != nil {
		size, modified = row["identity_size"], row["identity_modified"]
	}
	return hash(str(row, "id") + ":" + javaString(size) + ":" + javaString(modified))
}

func playbackFingerprint(row map[string]any) string {
	modified, etag := row["modified_at"], row["content_etag"]
	if str(row, "modified_at") == "" {
		modified = nil
	}
	if str(row, "content_etag") == "" {
		etag = nil
	}
	var scanned any
	if row["file_size"] == nil || integer(row["file_size"]) < 0 || (modified == nil && etag == nil) {
		scanned = row["scanned_at"]
	}
	content := digest("content:v2", row["id"], row["file_size"], modified, etag, scanned)
	configuration := digest("source:v2", row["source_id"], row["source_type"], row["location"], row["username"], row["password_encrypted"])
	return digest("playback:v2", content, row["relative_path"], configuration)
}

func (s *Service) connection(ctx context.Context, expected string) (*compute.Connection, error) {
	c, err := s.compute.Snapshot(ctx)
	if err != nil {
		return nil, err
	}
	if c == nil {
		return nil, fail(503, "计算节点未配置或已停用")
	}
	if c.Token == "" {
		return nil, fail(503, "计算节点缺少认证凭据")
	}
	if expected != "" && c.Origin != expected {
		return nil, fail(409, "计算节点配置已变化，请重建任务")
	}
	return c, nil
}

func regular(path string) bool {
	info, err := os.Lstat(path)
	return err == nil && info.Mode().IsRegular()
}

func streamFile(w http.ResponseWriter, r *http.Request, root, name string) error {
	dir, err := os.OpenRoot(root)
	if err != nil {
		return fail(404, "媒体产物不存在")
	}
	defer dir.Close()
	f, err := dir.Open(name)
	if err != nil {
		return fail(404, "媒体产物不存在")
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil || !info.Mode().IsRegular() {
		return fail(404, "媒体产物不存在")
	}
	w.Header().Set("Cache-Control", "private, no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	http.ServeContent(w, r, name, info.ModTime(), f)
	return nil
}

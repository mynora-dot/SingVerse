// Package catalog owns editable classifications, shared playlists and managed artist profiles.
package catalog

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
	"github.com/gin-gonic/gin"
	"github.com/jmoiron/sqlx"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/ai"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/metadata"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/config"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/httpx"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/search"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
	"log/slog"
	"regexp"
	"strings"
	"sync/atomic"
	"time"
	"unicode"
)

//go:embed sql/*.sql
var files embed.FS

type Library interface {
	Song(context.Context, string) (map[string]any, error)
	SongWith(context.Context, store.DBTX, string) (map[string]any, error)
	LibrarySongs(context.Context, store.DBTX) ([]map[string]any, error)
}
type Invalidator interface{ Invalidate() }
type ArtistProvider interface {
	ArtistArtwork(context.Context, string, []string, []metadata.ArtistRef) (*metadata.Artwork, error)
	ArtistArtworkFrom(context.Context, string, []string, []metadata.ArtistRef, string) (*metadata.Artwork, error)
	ArtistArtworkProviders(context.Context, store.DBTX) ([]string, error)
}
type Service struct {
	db             *sqlx.DB
	q              map[string]string
	library        Library
	ai             *ai.Service
	cipher         ai.Cipher
	devices        Invalidator
	log            *slog.Logger
	project        func(context.Context, store.DBTX, string, string) error
	cancel         context.CancelFunc
	done           chan struct{}
	generating     atomic.Bool
	busy           atomic.Bool
	artistProvider ArtistProvider
	artworkIndex   map[string][]map[string]any
	artworkBuilt   time.Time
	pathChanged    func(context.Context, store.DBTX, string, string) error
}

func New(db *sqlx.DB, dialect string, cfg config.Config, lib Library, models *ai.Service, cipher ai.Cipher, devices Invalidator, log *slog.Logger) (*Service, error) {
	q, e := store.ReadQueries(files, "sql")
	if e != nil {
		return nil, e
	}
	if log == nil {
		log = slog.Default()
	}
	s := &Service{db: db, q: q, library: lib, ai: models, cipher: cipher, devices: devices, log: log, done: make(chan struct{})}
	if e = s.recover(context.Background()); e != nil {
		return nil, e
	}
	models.SetPauseClassification(s.pauseAll)
	models.SetClassificationTester(s.testClassification)
	ctx, cancel := context.WithCancel(context.Background())
	s.cancel = cancel
	go s.loop(ctx)
	return s, nil
}
func (s *Service) SetProjector(fn func(context.Context, store.DBTX, string, string) error) {
	s.project = fn
}
func (s *Service) Close() error {
	if s.cancel != nil {
		s.cancel()
		<-s.done
	}
	return nil
}
func (s *Service) Invalidate() {
	if s.devices != nil {
		s.devices.Invalidate()
	}
}
func fail(code int, message string) error { return &ai.Failure{Status: code, Detail: message} }
func (s *Service) report(c *gin.Context, e error) {
	var f interface{ StatusCode() int }
	if errors.As(e, &f) {
		httpx.Error(c, f.StatusCode(), "about:blank", e.Error())
		return
	}
	s.log.ErrorContext(c.Request.Context(), "catalog operation failed", "type", fmt.Sprintf("%T", e))
	httpx.Error(c, 500, "about:blank", "操作失败，请稍后重试")
}
func (s *Service) reply(c *gin.Context, status int, v any, e error) {
	c.Header("Cache-Control", "no-store")
	if e != nil {
		s.report(c, e)
		return
	}
	if status == 204 {
		c.Status(status)
	} else {
		c.JSON(status, v)
	}
}
func id() string {
	var b [16]byte
	if _, e := rand.Read(b[:]); e != nil {
		panic(e)
	}
	b[6] = (b[6] & 15) | 64
	b[8] = (b[8] & 63) | 128
	h := hex.EncodeToString(b[:])
	return h[:8] + "-" + h[8:12] + "-" + h[12:16] + "-" + h[16:20] + "-" + h[20:]
}
func now() string { return time.Now().UTC().Format(time.RFC3339Nano) }
func encode(v any) string {
	b, e := json.Marshal(v)
	if e != nil {
		panic(e)
	}
	return string(b)
}
func decode[T any](v any) (T, error) {
	var out T
	b := []byte(str(v))
	e := json.Unmarshal(b, &out)
	return out, e
}
func str(v any) string {
	switch t := v.(type) {
	case string:
		return t
	case []byte:
		return string(t)
	}
	return ""
}
func num(v any) int64 {
	switch t := v.(type) {
	case int64:
		return t
	case int:
		return int64(t)
	case json.Number:
		n, _ := t.Int64()
		return n
	case float64:
		return int64(t)
	}
	return 0
}
func obj(v any) map[string]any { m, _ := v.(map[string]any); return m }
func sha(v string) string      { s := sha256.Sum256([]byte(v)); return hex.EncodeToString(s[:]) }
func query(ctx context.Context, tx store.DBTX, sql string, args ...any) ([]map[string]any, error) {
	return store.Maps(ctx, tx, sql, args...)
}
func one(ctx context.Context, tx store.DBTX, sql string, args ...any) (map[string]any, error) {
	r, e := query(ctx, tx, sql, args...)
	if e != nil {
		return nil, e
	}
	if len(r) == 0 {
		return nil, sqlNoRows
	}
	return r[0], nil
}

var sqlNoRows = sql.ErrNoRows

func (s *Service) exec(ctx context.Context, tx store.DBTX, key string, args ...any) error {
	_, e := tx.ExecContext(ctx, s.q[key], args...)
	return e
}
func (s *Service) cas(ctx context.Context, tx store.DBTX, key string, args ...any) error {
	r, e := tx.ExecContext(ctx, s.q[key], args...)
	if e != nil {
		return e
	}
	n, e := r.RowsAffected()
	if e == nil && n != 1 {
		return fail(409, "内容已变化，请刷新后重试")
	}
	return e
}
func (s *Service) tx(ctx context.Context, fn func(store.DBTX) (any, error)) (any, error) {
	var result any
	e := store.WithinTx(ctx, s.db, func(tx store.DBTX) error { var e error; result, e = fn(tx); return e })
	if e == nil {
		s.Invalidate()
	}
	return result, e
}
func (s *Service) lock(ctx context.Context, tx store.DBTX) error { return s.exec(ctx, tx, "c_lock") }
func (s *Service) projectTask(ctx context.Context, tx store.DBTX, id string) error {
	if s.project != nil {
		return s.project(ctx, tx, "CLASSIFICATION", id)
	}
	return nil
}
func textValid(v string, max int, empty bool) bool {
	if !empty && strings.TrimSpace(v) == "" || search.UTF16Length(v) > max {
		return false
	}
	for _, r := range v {
		if unicode.IsControl(r) {
			return false
		}
	}
	return true
}
func has(v string, values ...string) bool {
	for _, s := range values {
		if s == v {
			return true
		}
	}
	return false
}
func page[T any](v []T, p, size int) map[string]any {
	start := int64(p) * int64(size)
	if start > int64(len(v)) {
		start = int64(len(v))
	}
	end := start + int64(size)
	if end > int64(len(v)) {
		end = int64(len(v))
	}
	return map[string]any{"items": v[start:end], "page": p, "size": size, "totalElements": len(v)}
}
func pages(p, size int) error {
	if p < 0 || p > 1000000 || size < 1 || size > 100 {
		return fail(400, "分页参数超出范围")
	}
	return nil
}

var uuidPattern = regexp.MustCompile(`^[a-fA-F0-9-]{36}$`)

func (s *Service) SetArtistProvider(p ArtistProvider) { s.artistProvider = p }

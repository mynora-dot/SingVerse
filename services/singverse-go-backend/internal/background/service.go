// Package background projects business jobs into durable administrator task views.
// Every write uses the caller's transaction; this package never executes the work.
package background

import (
	"context"
	"crypto/md5"
	"crypto/sha256"
	"embed"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/jmoiron/sqlx"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

//go:embed sql/*.sql
var files embed.FS

const retention int64 = 30 * 86400000

var types = []string{"WEBDAV_SCAN", "METADATA", "CLASSIFICATION", "AUDIO_BATCH", "AUDIO_JOB", "TRANSCODE", "CACHE_CLEANUP", "SHARE_EXPORT", "LX_DOWNLOAD", "ARTIST_ARTWORK"}

type doc = map[string]any
type Service struct {
	db      *sqlx.DB
	dialect string
	q       map[string]string
	log     *slog.Logger
	cancel  context.CancelFunc
	wg      sync.WaitGroup
	mu      sync.Mutex
}

func New(db *sqlx.DB, dialect string, log *slog.Logger) (*Service, error) {
	q, e := store.ReadQueries(files, "sql")
	if e != nil {
		return nil, e
	}
	if log == nil {
		log = slog.Default()
	}
	s := &Service{db: db, dialect: dialect, q: q, log: log}
	ctx, cancel := context.WithCancel(context.Background())
	s.cancel = cancel
	if e = s.transact(ctx, func(tx store.DBTX) error {
		if e := s.exec(ctx, tx, "interruptLocal", doc{"now": now(), "expires": now() + retention}); e != nil {
			return e
		}
		return s.exec(ctx, tx, "sealAutomatic", doc{"round": nil})
	}); e != nil {
		cancel()
		return nil, e
	}
	s.wg.Add(1)
	go func() {
		defer s.wg.Done()
		timer := time.NewTimer(time.Second)
		defer timer.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-timer.C:
				if e := s.Maintain(ctx); e != nil && !errors.Is(e, context.Canceled) {
					s.log.Warn("background task reconciliation deferred")
				}
				timer.Reset(time.Minute)
			}
		}
	}()
	return s, nil
}

func (s *Service) Close() error   { s.cancel(); s.wg.Wait(); return nil }
func now() int64                  { return time.Now().UnixMilli() }
func text(r doc, k string) string { v, _ := r[k].(string); return v }
func number(r doc, k string) int64 {
	switch v := r[k].(type) {
	case int64:
		return v
	case int:
		return int64(v)
	case float64:
		return int64(v)
	case json.Number:
		n, _ := v.Int64()
		return n
	case string:
		n, _ := strconv.ParseInt(v, 10, 64)
		return n
	}
	return 0
}

func timestamp(v any) any {
	if v == nil {
		return nil
	}
	switch t := v.(type) {
	case int64:
		return t
	case int:
		return int64(t)
	case float64:
		return int64(t)
	case json.Number:
		n, e := t.Int64()
		if e == nil {
			return n
		}
	case time.Time:
		return t.UnixMilli()
	case string:
		if t == "" {
			return nil
		}
		v, e := time.Parse(time.RFC3339Nano, t)
		if e == nil {
			return v.UnixMilli()
		}
	}
	return nil
}

func stamp(v any) int64 { n, _ := timestamp(v).(int64); return n }
func encode(v any) string {
	b, e := json.Marshal(v)
	if e != nil {
		panic("invalid background projection")
	}
	return string(b)
}

func read(v any) doc {
	out := doc{}
	if v != nil {
		decoder := json.NewDecoder(strings.NewReader(fmt.Sprint(v)))
		decoder.UseNumber()
		_ = decoder.Decode(&out)
	}
	if out == nil {
		return doc{}
	}
	return out
}

func object(r doc, k string) doc {
	v, _ := r[k].(map[string]any)
	if v == nil {
		return doc{}
	}
	return v
}

func first(rows []doc) doc {
	if len(rows) == 0 {
		return doc{}
	}
	return rows[0]
}

func hash(v any) string { b := sha256.Sum256([]byte(encode(v))); return hex.EncodeToString(b[:]) }

// ID matches Java UUID.nameUUIDFromBytes for language-independent task links.
func ID(kind, business string) string {
	b := md5.Sum([]byte(kind + "\x00" + business))
	b[6] = (b[6] & 15) | 48
	b[8] = (b[8] & 63) | 128
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[:4], b[4:6], b[6:8], b[8:10], b[10:])
}

func active(state string) bool {
	switch state {
	case "QUEUED", "RUNNING", "PAUSED", "CANCELLING", "AWAITING_CONFIRMATION":
		return true
	}
	return false
}

func sourceActive(state string) bool {
	switch state {
	case "QUEUED", "RUNNING", "PAUSED", "STARTING", "PREPARING", "PREVIEW", "WAITING", "CANCELLING":
		return true
	}
	return false
}

func normalize(raw string) string {
	switch raw {
	case "PREPARING", "STARTING", "RUNNING", "PROCESSING":
		return "RUNNING"
	case "PREVIEW":
		return "AWAITING_CONFIRMATION"
	case "WAITING", "PENDING", "QUEUED":
		return "QUEUED"
	case "SUCCEEDED", "COMPLETED":
		return "COMPLETED"
	case "DELETING":
		return "CANCELLED"
	case "PAUSED", "CANCELLING", "CANCELLED", "FAILED", "PARTIAL", "INTERRUPTED", "BLOCKED", "EXPIRED":
		return raw
	}
	return "FAILED"
}

func pipeline(r doc) bool {
	k := text(r, "type")
	return k == "LOCAL_SCAN" || k == "WEBDAV_SCAN" || k == "AUDIO_DISCOVERY"
}

func limit(v string, n int) string {
	r := []rune(v)
	if len(r) > n {
		return string(r[:n])
	}
	return v
}

var address = regexp.MustCompile(`(?i)\b(?:https?|file|smb)://[^\s<>"']+|\b(?:[0-9]{1,3}\.){3}[0-9]{1,3}(?::[0-9]{1,5})?\b`)
var credential = regexp.MustCompile(`(?i)(?:authorization\s*[=:]\s*)?bearer\s+[^\s,;"'}]+|(?:password|secret|token|api[_-]?key|authorization)["']?\s*[=:]\s*["']?[^\s,;"'}]+|\bsk-[A-Za-z0-9_-]+`)
var path = regexp.MustCompile(`(?:[A-Za-z]:[\\/]|/)(?:[^\s/\\:]+[/\\])+[^\s,;"']*`)

func Message(value string) string {
	return limit(path.ReplaceAllString(credential.ReplaceAllString(address.ReplaceAllString(value, "[地址已隐藏]"), "[凭据已隐藏]"), "[路径已隐藏]"), 1000)
}

func execution(src doc) doc {
	out := doc{}
	for _, k := range []string{"stage", "backend", "modelVersion", "fallbackReason"} {
		if v, ok := src[k].(string); ok {
			out[k] = Message(limit(v, 200))
		}
	}
	p := object(src, "progress")
	frames := number(p, "frames")
	if frames > 0 {
		out["progress"] = doc{"frames": frames, "completedFrames": max(int64(0), min(frames, number(p, "completedFrames")))}
	}
	timings := doc{}
	for _, k := range []string{"queue", "download", "probe", "decode", "inference", "encode", "total"} {
		v := object(src, "timingsMs")
		if _, ok := v[k]; ok && number(v, k) >= 0 {
			timings[k] = number(v, k)
		}
	}
	if len(timings) > 0 {
		out["timingsMs"] = timings
	}
	return out
}

func (s *Service) statement(name string, v doc) (string, []any, error) {
	switch name {
	case "source", "sourceKeys", "sourceCounts", "sourceItems", "owner":
		name += "-" + text(v, "type")
	case "list", "count":
		name += "-" + text(v, "view")
	}
	q, ok := s.q[name+"-"+s.dialect]
	if !ok {
		return "", nil, fmt.Errorf("unknown background query %s", name)
	}
	return store.Bind(q, v)
}

func (s *Service) exec(ctx context.Context, tx store.DBTX, name string, v doc) error {
	q, a, e := s.statement(name, v)
	if e != nil {
		return e
	}
	_, e = tx.ExecContext(ctx, q, a...)
	return e
}

func (s *Service) rows(ctx context.Context, tx store.DBTX, name string, v doc) ([]doc, error) {
	q, a, e := s.statement(name, v)
	if e != nil {
		return nil, e
	}
	return store.Maps(ctx, tx, q, a...)
}

func (s *Service) row(ctx context.Context, tx store.DBTX, name string, v doc) (doc, error) {
	r, e := s.rows(ctx, tx, name, v)
	return first(r), e
}

func (s *Service) transact(ctx context.Context, fn func(store.DBTX) error) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	return store.WithinTx(ctx, s.db, fn)
}

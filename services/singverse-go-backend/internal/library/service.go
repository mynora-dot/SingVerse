// Package library owns media sources, filename parsing, indexing, and original delivery.
package library

import (
	"context"
	"database/sql"
	"embed"
	"errors"
	"log/slog"
	"net/http"
	"sync"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/jmoiron/sqlx"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/config"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/httpx"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

//go:embed sql/*.sql
var queryFiles embed.FS

type Service struct {
	db             *sqlx.DB
	dialect        string
	cfg            config.Config
	cipher         Cipher
	log            *slog.Logger
	q              map[string]string
	mu             sync.Mutex
	running        map[string]bool
	tasks          map[string]context.CancelFunc
	ctx            context.Context
	cancel         context.CancelFunc
	wg             sync.WaitGroup
	queue          chan scanWork
	client         *http.Client
	hookMu         sync.RWMutex
	projector      func(context.Context, store.DBTX, string, string) error
	onChanged      func()
	localStart     func(context.Context, string) (string, error)
	localProgress  func(context.Context, string, string, int64, int64, *int64, *int64) error
	localFinish    func(context.Context, string, *Scan, error) error
	localScope     func(context.Context, store.DBTX, string, string) error
	originalCache  func(context.Context, http.ResponseWriter, *http.Request, string, func(http.ResponseWriter, *http.Request) error, func() error) (bool, error)
	davAccess      func(context.Context, string, func() error) error
	publicationMu  sync.Mutex
	publications   map[string]chan struct{}
	localTagReader func(context.Context, string, string) (map[string]any, error)
	indexed        func(context.Context, store.DBTX, string) error
}
type scanWork struct {
	id      string
	source  Source
	state   parserState
	profile Profile
	rules   []Rule
	ctx     context.Context
	cancel  context.CancelFunc
}

func New(db *sqlx.DB, dialect string, cfg config.Config, cipher Cipher, log *slog.Logger) (*Service, error) {
	q, err := store.ReadQueries(queryFiles, "sql")
	if err != nil {
		return nil, err
	}
	ctx, cancel := context.WithCancel(context.Background())
	if log == nil {
		log = slog.Default()
	}
	s := &Service{db: db, dialect: dialect, cfg: cfg, cipher: cipher, log: log, q: q, running: map[string]bool{}, tasks: map[string]context.CancelFunc{}, ctx: ctx, cancel: cancel, queue: make(chan scanWork, 32), client: &http.Client{Transport: &http.Transport{Proxy: nil, ResponseHeaderTimeout: 15 * time.Second, IdleConnTimeout: 30 * time.Second}, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}}
	err = store.WithinTx(ctx, db, func(tx store.DBTX) error {
		for _, name := range []string{"scan-recover", "scan-clear-abandoned", "scan-clear-abandoned-dirs"} {
			args := []any{}
			if name == "scan-recover" {
				args = append(args, now())
			}
			if _, e := tx.ExecContext(ctx, q[name], args...); e != nil {
				return e
			}
		}
		return nil
	})
	if err != nil {
		cancel()
		return nil, err
	}
	for i := 0; i < 2; i++ {
		s.wg.Add(1)
		go s.worker()
	}
	s.wg.Add(1)
	go s.schedule()
	return s, nil
}
func (s *Service) Close() error {
	s.cancel()
	s.mu.Lock()
	for _, cancel := range s.tasks {
		cancel()
	}
	s.mu.Unlock()
	s.wg.Wait()
	for {
		select {
		case work := <-s.queue:
			work.cancel()
			s.finishTask(work, "INTERRUPTED", bad("服务已停止，扫描中断；请手动重新扫描"))
		default:
			s.client.CloseIdleConnections()
			return nil
		}
	}
}
func (s *Service) Register(g *gin.RouterGroup) {
	g.GET("/media-sources/local-directories", s.directories)
	g.GET("/media-sources", s.listSources)
	g.POST("/media-sources", s.createSource)
	g.GET("/media-sources/:id", s.getSource)
	g.PUT("/media-sources/:id", s.updateSource)
	g.DELETE("/media-sources/:id", s.deleteSource)
	g.POST("/media-sources/:id/scan", s.scanLocalHTTP)
	g.GET("/media-sources/:id/songs", s.sourceSongs)
	g.POST("/media-sources/:id/scan-tasks", s.createTask)
	g.GET("/media-sources/:id/scan-tasks/latest", s.latestTask)
	g.GET("/media-sources/:id/scan-tasks/:taskId", s.getTask)
	g.POST("/media-sources/:id/scan-tasks/:taskId/cancel", s.cancelTask)
	g.GET("/media-songs", s.listSongs)
	g.GET("/media-songs/:id/video", s.video)
	g.HEAD("/media-songs/:id/video", s.video)
	g.GET("/filename-parser/rules", s.getRules)
	g.PUT("/filename-parser/rules", s.saveRules)
	g.POST("/filename-parser/preview", s.previewRules)
	g.GET("/filename-parser/rule-definitions", s.workspaceHTTP)
	g.GET("/filename-parser/profiles", s.workspaceHTTP)
	g.POST("/filename-parser/rule-definitions", s.writeDefinition)
	g.PUT("/filename-parser/rule-definitions/:id", s.writeDefinition)
	g.DELETE("/filename-parser/rule-definitions/:id", s.deleteDefinition)
	g.POST("/filename-parser/profiles", s.writeProfile)
	g.PUT("/filename-parser/profiles/:id", s.writeProfile)
	g.DELETE("/filename-parser/profiles/:id", s.deleteProfile)
	g.POST("/filename-parser/profiles/preview", s.previewProfile)
}
func (s *Service) fail(c *gin.Context, err error) {
	var p *problem
	if errors.As(err, &p) {
		if len(p.fields) == 0 {
			httpx.Error(c, p.status, "about:blank", p.detail)
		} else {
			v := gin.H{"type": "about:blank", "title": http.StatusText(p.status), "status": p.status, "detail": p.detail, "instance": c.Request.URL.Path}
			for k, f := range p.fields {
				v[k] = f
			}
			c.Header("Content-Type", "application/problem+json")
			c.Abort()
			c.JSON(p.status, v)
		}
		return
	}
	s.log.Error("library operation failed", "operation", c.FullPath())
	httpx.Error(c, 500, "about:blank", "操作失败，请稍后重试")
}

type scanner interface{ StructScan(any) error }

func changed(result sql.Result, err error) error {
	if err != nil {
		return err
	}
	n, err := result.RowsAffected()
	if err != nil {
		return err
	}
	if n != 1 {
		return conflict("配置或媒体来源已修改，请刷新后重试")
	}
	return nil
}
func (s *Service) transaction(ctx context.Context, fn func(store.DBTX) error) error {
	s.mu.Lock()
	err := store.WithinTx(ctx, s.db, fn)
	s.mu.Unlock()
	if err == nil {
		s.notifyChanged()
	}
	return err
}

// SetProjector installs a transactional task projection without opening another connection.
func (s *Service) SetProjector(fn func(context.Context, store.DBTX, string, string) error) {
	s.hookMu.Lock()
	s.projector = fn
	s.hookMu.Unlock()
}
func (s *Service) SetChanged(fn func()) { s.hookMu.Lock(); s.onChanged = fn; s.hookMu.Unlock() }
func (s *Service) SetOriginalCache(fn func(context.Context, http.ResponseWriter, *http.Request, string, func(http.ResponseWriter, *http.Request) error, func() error) (bool, error)) {
	s.hookMu.Lock()
	s.originalCache = fn
	s.hookMu.Unlock()
}
func (s *Service) SetWebDAVAccess(fn func(context.Context, string, func() error) error) {
	s.hookMu.Lock()
	s.davAccess = fn
	s.hookMu.Unlock()
}
func (s *Service) SetLocalTagReader(fn func(context.Context, string, string) (map[string]any, error)) {
	s.hookMu.Lock()
	s.localTagReader = fn
	s.hookMu.Unlock()
}
func (s *Service) SetIndexed(fn func(context.Context, store.DBTX, string) error) {
	s.hookMu.Lock()
	s.indexed = fn
	s.hookMu.Unlock()
}
func (s *Service) indexChanged(ctx context.Context, tx store.DBTX, source string) error {
	s.hookMu.RLock()
	fn := s.indexed
	s.hookMu.RUnlock()
	if fn != nil {
		return fn(ctx, tx, source)
	}
	return nil
}
func (s *Service) SetLocalTracker(start func(context.Context, string) (string, error), progress func(context.Context, string, string, int64, int64, *int64, *int64) error, finish func(context.Context, string, *Scan, error) error, scope func(context.Context, store.DBTX, string, string) error) {
	s.hookMu.Lock()
	s.localStart = start
	s.localProgress = progress
	s.localFinish = finish
	s.localScope = scope
	s.hookMu.Unlock()
}
func (s *Service) notifyChanged() {
	s.hookMu.RLock()
	fn := s.onChanged
	s.hookMu.RUnlock()
	if fn != nil {
		fn()
	}
}
func (s *Service) project(ctx context.Context, tx store.DBTX, id string) error {
	s.hookMu.RLock()
	fn := s.projector
	s.hookMu.RUnlock()
	if fn != nil {
		return fn(ctx, tx, "WEBDAV_SCAN", id)
	}
	return nil
}
func (s *Service) decode(c *gin.Context, v any) bool { return httpx.Decode(c, v, 128*1024) }

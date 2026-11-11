package metadata

import (
	"context"
	"database/sql"
	"embed"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"sync"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/jmoiron/sqlx"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/library"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/config"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/httpx"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

//go:embed sql/*.sql
var queryFiles embed.FS

type Service struct {
	db              *sqlx.DB
	dialect         string
	cfg             config.Config
	cipher          Cipher
	library         *library.Service
	log             *slog.Logger
	q               map[string]string
	ctx             context.Context
	cancel          context.CancelFunc
	wg              sync.WaitGroup
	mu              sync.Mutex
	hookMu          sync.RWMutex
	projector       projectFunc
	onChanged       func()
	appliedHook     func(context.Context, store.DBTX, string) error
	artistHook      func(context.Context, store.DBTX, string, string, []string) error
	providers       map[string]Provider
	guards          map[string]*providerGuard
	client          *http.Client
	deviceID        string
	remote          func(context.Context, map[string]any) (map[string]any, error)
	grants          map[string]*grant
	grantMu         sync.Mutex
	accessMu        sync.Mutex
	accessGates     map[string]chan struct{}
	requestInterval time.Duration
	downloadCover   func(context.Context, string) (Cover, error)
	remoteState     map[string]any
	localInputHas   func(string) bool
	localInputServe func(context.Context, http.ResponseWriter, *http.Request, string) error
	audioRoles      func(context.Context, string) (map[string]any, error)
	audioClaim      func(context.Context, store.DBTX, string, map[string]any) error
	fileMu          sync.Mutex
	shareMu         sync.Mutex
	importMu        sync.Mutex
}

func New(db *sqlx.DB, dialect string, cfg config.Config, cipher Cipher, lib *library.Service, log *slog.Logger) (*Service, error) {
	q, err := store.ReadQueries(queryFiles, "sql")
	if err != nil {
		return nil, err
	}
	if log == nil {
		log = slog.Default()
	}
	ctx, cancel := context.WithCancel(context.Background())
	s := &Service{db: db, dialect: dialect, cfg: cfg, cipher: cipher, library: lib, log: log, q: q, ctx: ctx, cancel: cancel, providers: map[string]Provider{}, guards: map[string]*providerGuard{}, deviceID: hash(uuid())[:32], requestInterval: 2 * time.Second, grants: map[string]*grant{}, client: &http.Client{Transport: &http.Transport{Proxy: nil, ResponseHeaderTimeout: 10 * time.Second, IdleConnTimeout: 30 * time.Second}, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}}
	for _, p := range providers {
		s.guards[p] = &providerGuard{permit: make(chan struct{}, 1)}
		s.providers[p] = &musicProvider{service: s, name: p}
	}
	err = s.transaction(ctx, func(tx store.DBTX) error {
		for _, n := range []string{"meta-pauseActive", "meta-recoverItems", "dav-recover", "dav-recoverResources", "file-enqueueAll"} {
			a := map[string]any{"now": now(), "after": time.Now().UTC().Add(30 * time.Minute).Format(time.RFC3339Nano)}
			if _, e := s.exec(ctx, tx, n, a); e != nil {
				return e
			}
		}
		return nil
	})
	if err != nil {
		cancel()
		return nil, err
	}
	if err = s.bootstrapShared(ctx); err != nil {
		cancel()
		return nil, err
	}
	s.wg.Add(5)
	go s.worker()
	go s.remoteLoop()
	go s.filesLoop()
	go s.shareLoop()
	go s.importLoop()
	return s, nil
}
func (s *Service) Close() error {
	s.cancel()
	s.grantMu.Lock()
	s.grants = map[string]*grant{}
	s.grantMu.Unlock()
	s.wg.Wait()
	s.client.CloseIdleConnections()
	return nil
}
func (s *Service) SetProjector(fn func(context.Context, store.DBTX, string, string) error) {
	s.hookMu.Lock()
	s.projector = fn
	s.hookMu.Unlock()
}
func (s *Service) SetChanged(fn func()) { s.hookMu.Lock(); s.onChanged = fn; s.hookMu.Unlock() }
func (s *Service) SetApplied(fn func(context.Context, store.DBTX, string) error) {
	s.hookMu.Lock()
	s.appliedHook = fn
	s.hookMu.Unlock()
}
func (s *Service) SetArtistEdited(fn func(context.Context, store.DBTX, string, string, []string) error) {
	s.hookMu.Lock()
	s.artistHook = fn
	s.hookMu.Unlock()
}
func (s *Service) SetRemoteExtractor(fn func(context.Context, map[string]any) (map[string]any, error)) {
	s.hookMu.Lock()
	s.remote = fn
	s.hookMu.Unlock()
}
func (s *Service) SetDownloadCover(fn func(context.Context, string) (Cover, error)) {
	s.hookMu.Lock()
	s.downloadCover = fn
	s.hookMu.Unlock()
}
func (s *Service) SetLocalInput(has func(string) bool, serve func(context.Context, http.ResponseWriter, *http.Request, string) error) {
	s.hookMu.Lock()
	s.localInputHas = has
	s.localInputServe = serve
	s.hookMu.Unlock()
}

// RegisterInternal mounts bearer-authenticated compute callbacks outside administrator middleware.
func (s *Service) RegisterInternal(g *gin.RouterGroup) {
	g.GET("/internal/metadata-input/:id", s.inputHTTP)
	g.HEAD("/internal/metadata-input/:id", s.inputHTTP)
}
func (s *Service) inputHTTP(c *gin.Context) {
	s.hookMu.RLock()
	has, serve := s.localInputHas, s.localInputServe
	s.hookMu.RUnlock()
	if has != nil && serve != nil && has(c.Param("id")) {
		if e := serve(c.Request.Context(), c.Writer, c.Request, c.Param("id")); e != nil {
			s.fail(c, e)
		}
		return
	}
	if e := s.ServeInput(c.Request.Context(), c.Writer, c.Request, c.Param("id")); e != nil {
		s.fail(c, e)
	}
}

// SongCoverHandler is intended for an independently authenticated TV route.
func (s *Service) SongCoverHandler(c *gin.Context) {
	id := c.Param("id")
	if id == "" {
		id = c.Param("songId")
	}
	cover, e := s.SongCover(c.Request.Context(), id)
	if e != nil {
		s.fail(c, e)
		return
	}
	if c.Query("variant") == "thumbnail" {
		cover, e = s.thumbnail(cover)
		if e != nil {
			s.fail(c, e)
			return
		}
	}
	imageResponse(c, cover)
}
func (s *Service) SetRemoteAvailability(status string, ready bool, message string) {
	s.hookMu.Lock()
	s.remoteState = map[string]any{"nodeStatus": status, "remoteReady": ready, "nodeMessage": message}
	s.hookMu.Unlock()
}
func (s *Service) project(ctx context.Context, tx store.DBTX, kind, id string) error {
	s.hookMu.RLock()
	fn := s.projector
	s.hookMu.RUnlock()
	if fn != nil {
		return fn(ctx, tx, kind, id)
	}
	return nil
}
func (s *Service) transaction(ctx context.Context, fn func(store.DBTX) error) error {
	s.mu.Lock()
	err := store.WithinTx(ctx, s.db, func(tx store.DBTX) error {
		if _, e := s.exec(ctx, tx, "meta-lock", nil); e != nil {
			return e
		}
		return fn(tx)
	})
	s.mu.Unlock()
	if err == nil {
		s.hookMu.RLock()
		fn := s.onChanged
		s.hookMu.RUnlock()
		if fn != nil {
			fn()
		}
	}
	return err
}
func (s *Service) exec(ctx context.Context, tx store.DBTX, n string, a map[string]any) (sql.Result, error) {
	if s.q[n] == "" {
		return nil, fmt.Errorf("missing embedded query %q", n)
	}
	q, args, e := store.Bind(s.q[n], a)
	if e != nil {
		return nil, e
	}
	return tx.ExecContext(ctx, q, args...)
}
func (s *Service) rows(ctx context.Context, tx store.DBTX, n string, a map[string]any) ([]map[string]any, error) {
	if s.q[n] == "" {
		return nil, fmt.Errorf("missing embedded query %q", n)
	}
	q, args, e := store.Bind(s.q[n], a)
	if e != nil {
		return nil, e
	}
	return store.Maps(ctx, tx, q, args...)
}
func (s *Service) row(ctx context.Context, tx store.DBTX, n string, a map[string]any) (map[string]any, error) {
	if s.q[n] == "" {
		return nil, fmt.Errorf("missing embedded query %q", n)
	}
	q, args, e := store.Bind(s.q[n], a)
	if e != nil {
		return nil, e
	}
	return store.Map(ctx, tx, q, args...)
}
func (s *Service) fail(c *gin.Context, e error) {
	status := code(e)
	detail := e.Error()
	if status >= 500 {
		detail = "操作失败，请稍后重试"
		if status == 503 {
			detail = "平台或计算节点暂不可用，请稍后重试"
		}
		s.log.Error("metadata operation failed", "operation", c.FullPath())
	}
	httpx.Error(c, status, "about:blank", detail)
}
func (s *Service) decode(c *gin.Context, v any) bool { return httpx.Decode(c, v, 128*1024) }
func (s *Service) Register(g *gin.RouterGroup) {
	m := g.Group("/metadata")
	m.GET("/settings", s.getSettings)
	m.PUT("/settings", s.putSettings)
	m.POST("/songs/:songId/search", s.searchHTTP)
	m.POST("/songs/:songId/apply", s.applyHTTP)
	m.GET("/songs/:songId/review", s.reviewHTTP)
	m.POST("/songs/:songId/review/apply", s.reviewApplyHTTP)
	m.POST("/songs/:songId/covers/search", s.coverSearch)
	m.POST("/songs/:songId/covers/upload", s.upload)
	m.GET("/cover-assets/:id", s.assetImage)
	m.GET("/candidates/:id/cover", s.candidateCover)
	m.GET("/songs/:songId/cover", s.songCover)
	m.POST("/batches", s.startHTTP)
	m.GET("/batches/latest", s.latestHTTP)
	m.GET("/batches/:id", s.batchHTTP)
	m.POST("/batches/:id/pause", s.batchAction)
	m.POST("/batches/:id/resume", s.batchAction)
	m.POST("/batches/:id/cancel", s.batchAction)
	m.POST("/batches/:id/items/:itemId/retry", s.retryHTTP)
	m.POST("/batches/:id/items/:itemId/apply", s.applyItemHTTP)
	m.POST("/batches/:id/items/:itemId/review/apply", s.reviewItemHTTP)
	g.GET("/media-sources/:id/embedded-metadata", s.summaryHTTP)
	g.PUT("/media-sources/:id/embedded-metadata", s.configureDAV)
	g.POST("/media-sources/:id/embedded-metadata/confirm-empty", s.confirmEmpty)
	g.GET("/media-songs/:id/embedded-metadata", s.detailHTTP)
	g.GET("/media-songs/:id/embedded-metadata/cover", s.embeddedCoverHTTP)
	g.POST("/media-songs/embedded-metadata/refresh", s.refreshHTTP)
	g.POST("/media-sources/:id/embedded-metadata/share", s.importShare)
	g.POST("/media-sources/:id/rebind", s.rebindSource)
	g.POST("/media-sources/:id/embedded-metadata/import-jobs", s.uploadImport)
	g.GET("/media-sources/:id/embedded-metadata/import-jobs/latest", s.latestImport)
	g.GET("/media-sources/:id/embedded-metadata/import-jobs/:jobId", s.getImport)
	g.PUT("/media-sources/:id/embedded-metadata/import-jobs/:jobId/mapping", s.mapImport)
	g.GET("/media-sources/:id/embedded-metadata/import-jobs/:jobId/differences", s.importDifferences)
	g.POST("/media-sources/:id/embedded-metadata/import-jobs/:jobId/:action", s.importAction)
	g.POST("/media-sources/:id/embedded-metadata/share-jobs", s.createShare)
	g.GET("/media-sources/:id/embedded-metadata/share-jobs/latest", s.latestShare)
	g.GET("/media-sources/:id/embedded-metadata/share-jobs/:jobId", s.getShare)
	g.POST("/media-sources/:id/embedded-metadata/share-jobs/:jobId/resume", s.resumeShare)
	g.GET("/media-sources/:id/embedded-metadata/share-jobs/:jobId/index", s.indexShare)
	g.GET("/media-sources/:id/embedded-metadata/share-jobs/:jobId/parts/:part", s.partShare)
	g.GET("/media-sources/:id/embedded-metadata/share-jobs/:jobId/deletions/:part", s.partShare)
	g.GET("/media-sources/:id/embedded-metadata/share-jobs/:jobId/download", s.downloadShare)
}
func absent(err error, detail string) error {
	if errors.Is(err, sql.ErrNoRows) {
		return missing(detail)
	}
	return err
}

func (s *Service) song(ctx context.Context, db store.DBTX, id string) (map[string]any, error) {
	song, fp, e := s.library.SongIdentity(ctx, db, id)
	if e == nil {
		song["_fingerprint"] = fp
	}
	return song, e
}
func publicSong(song map[string]any) map[string]any {
	out := map[string]any{}
	for k, v := range song {
		if k != "_fingerprint" {
			out[k] = v
		}
	}
	return out
}

package downloads

import (
	"context"
	"database/sql"
	"embed"
	"encoding/json"
	"errors"
	"log/slog"
	"regexp"
	"strings"
	"sync"
	"unicode"
	"unicode/utf8"

	"github.com/jmoiron/sqlx"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/downloads/lxruntime"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/config"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/search"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

//go:embed sql/*.sql
var queryFiles embed.FS

type Service struct {
	db           *sqlx.DB
	dialect      string
	cfg          config.Config
	cipher       Cipher
	library      Library
	log          *slog.Logger
	q            map[string]string
	runtime      ScriptRuntime
	network      *Network
	catalog      Catalog
	covers       CoverStore
	ctx          context.Context
	cancel       context.CancelFunc
	wg           sync.WaitGroup
	startOnce    sync.Once
	startErr     error
	mu           sync.Mutex
	jobsMu       sync.Mutex
	running      map[string]context.CancelFunc
	hookMu       sync.RWMutex
	projector    Projector
	onPublished  func(context.Context, store.DBTX, string) error
	onChanged    func()
	catalogSlots chan struct{}
}

func New(db *sqlx.DB, dialect string, cfg config.Config, cipher Cipher, lib Library, log *slog.Logger) (*Service, error) {
	q, e := store.ReadQueries(queryFiles, "sql")
	if e != nil {
		return nil, e
	}
	ctx, cancel := context.WithCancel(context.Background())
	if log == nil {
		log = slog.Default()
	}
	net := NewNetwork()
	s := &Service{db: db, dialect: dialect, cfg: cfg, cipher: cipher, library: lib, log: log, q: q, ctx: ctx, cancel: cancel, runtime: lxruntime.New(""), network: net, running: map[string]context.CancelFunc{}, catalogSlots: make(chan struct{}, 5)}
	s.catalog = newCatalog(net)
	s.covers = newCovers(net)
	return s, nil
}

// Start is called after the composition root has installed transactional hooks.
// Startup recovery must use the same hooks as a newly completed download.
func (s *Service) Start() error {
	s.startOnce.Do(func() {
		if s.startErr = s.recover(s.ctx); s.startErr == nil {
			s.wg.Add(1)
			go s.schedule()
		}
	})
	return s.startErr
}
func (s *Service) Close() error {
	s.cancel()
	s.jobsMu.Lock()
	for _, cancel := range s.running {
		cancel()
	}
	s.jobsMu.Unlock()
	s.runtime.Close()
	s.wg.Wait()
	s.network.Close()
	return nil
}
func (s *Service) SetProjector(fn Projector) { s.hookMu.Lock(); s.projector = fn; s.hookMu.Unlock() }
func (s *Service) SetPublished(fn func(context.Context, store.DBTX, string) error) {
	s.hookMu.Lock()
	s.onPublished = fn
	s.hookMu.Unlock()
}

func (s *Service) published(ctx context.Context, tx store.DBTX, id string) error {
	s.hookMu.RLock()
	fn := s.onPublished
	s.hookMu.RUnlock()
	if fn != nil {
		return fn(ctx, tx, id)
	}
	return nil
}
func (s *Service) SetChanged(fn func()) { s.hookMu.Lock(); s.onChanged = fn; s.hookMu.Unlock() }
func (s *Service) notify() {
	s.hookMu.RLock()
	fn := s.onChanged
	s.hookMu.RUnlock()
	if fn != nil {
		fn()
	}
}
func (s *Service) project(ctx context.Context, tx store.DBTX, batch string) error {
	s.hookMu.RLock()
	fn := s.projector
	s.hookMu.RUnlock()
	if fn != nil {
		return fn(ctx, tx, "LX_DOWNLOAD", batch)
	}
	return nil
}
func (s *Service) transaction(ctx context.Context, fn func(store.DBTX) error) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	return store.WithinTx(ctx, s.db, fn)
}
func (s *Service) exec(ctx context.Context, tx store.DBTX, name string, p map[string]any) (sql.Result, error) {
	query, args, e := store.Bind(s.q[name], p)
	if e != nil {
		return nil, e
	}
	return tx.ExecContext(ctx, query, args...)
}
func (s *Service) rows(ctx context.Context, tx store.DBTX, name string, p map[string]any) ([]map[string]any, error) {
	query, args, e := store.Bind(s.q[name], p)
	if e != nil {
		return nil, e
	}
	return store.Maps(ctx, tx, query, args...)
}
func (s *Service) row(ctx context.Context, tx store.DBTX, name string, p map[string]any) (map[string]any, error) {
	query, args, e := store.Bind(s.q[name], p)
	if e != nil {
		return nil, e
	}
	row, e := store.Map(ctx, tx, query, args...)
	if errors.Is(e, sql.ErrNoRows) {
		return nil, nil
	}
	return row, e
}
func (s *Service) scalar(ctx context.Context, tx store.DBTX, name string, p map[string]any) (int64, error) {
	query, args, e := store.Bind(s.q[name], p)
	if e != nil {
		return 0, e
	}
	var v int64
	e = tx.QueryRowContext(ctx, query, args...).Scan(&v)
	return v, e
}
func (s *Service) lock(ctx context.Context, tx store.DBTX) error {
	_, e := s.exec(ctx, tx, "lockSettings", nil)
	return e
}
func (s *Service) script(ctx context.Context, tx store.DBTX, id string) (map[string]any, error) {
	v, e := s.row(ctx, tx, "script", map[string]any{"id": id})
	if e != nil {
		return nil, e
	}
	if v == nil || number(v, "deleted") != 0 {
		return nil, bad("音源不存在，请重新选择")
	}
	return v, nil
}
func (s *Service) body(row map[string]any) (string, error) {
	v, e := s.cipher.Decrypt(str(row, "secret_encrypted"), sourcePurpose)
	if e != nil {
		return "", conflict("音源凭据无法读取，请重新导入")
	}
	var doc struct {
		Script string `json:"script"`
	}
	if json.Unmarshal([]byte(v), &doc) != nil || doc.Script == "" {
		return "", conflict("音源数据无效，请重新导入")
	}
	return doc.Script, nil
}
func capabilities(row map[string]any) (map[string][]string, error) {
	var v map[string][]string
	if json.Unmarshal([]byte(str(row, "capabilities_json")), &v) != nil || len(v) == 0 {
		return nil, conflict("音源能力信息无效，请重新导入")
	}
	return v, nil
}
func publicScript(row map[string]any, active string) map[string]any {
	var capabilities any
	_ = json.Unmarshal([]byte(str(row, "capabilities_json")), &capabilities)
	return map[string]any{"id": row["id"], "name": row["name"], "version": row["version"], "environment": row["environment"], "createdAt": row["created_at"], "capabilities": capabilities, "active": str(row, "id") == active}
}
func (s *Service) Status(ctx context.Context) (map[string]any, error) {
	settings, e := s.row(ctx, s.db, "settings", nil)
	if e != nil {
		return nil, e
	}
	reason := ""
	if str(settings, "source_id") == "" || str(settings, "source_type") != "LOCAL" {
		reason = "尚未设置下载保存位置"
	} else if _, e = writable(str(settings, "source_path"), false); e != nil {
		reason = e.Error()
	}
	target := map[string]any{"id": settings["source_id"], "name": settings["source_name"], "path": settings["source_path"], "ready": reason == "", "reason": reason}
	var script any
	id := str(settings, "active_script_id")
	if id != "" {
		row, e := s.row(ctx, s.db, "script", map[string]any{"id": id})
		if e != nil {
			return nil, e
		}
		if row != nil && number(row, "deleted") == 0 {
			script = publicScript(row, id)
		}
	}
	active, e := s.rows(ctx, s.db, "activeBatches", nil)
	if e != nil {
		return nil, e
	}
	return map[string]any{"target": target, "activeSource": script, "ready": reason == "" && script != nil, "platforms": platforms, "activeBatches": len(active)}, nil
}
func (s *Service) Scripts(ctx context.Context) ([]map[string]any, error) {
	settings, e := s.row(ctx, s.db, "settings", nil)
	if e != nil {
		return nil, e
	}
	rows, e := s.rows(ctx, s.db, "scripts", nil)
	if e != nil {
		return nil, e
	}
	out := make([]map[string]any, 0, len(rows))
	for _, row := range rows {
		out = append(out, publicScript(row, str(settings, "active_script_id")))
	}
	return out, nil
}
func (s *Service) Import(ctx context.Context, in SourceInput) (map[string]any, error) {
	script := in.Script
	address := strings.TrimSpace(in.URL)
	if address != "" {
		if strings.TrimSpace(script) != "" {
			return nil, bad("文件与链接请选择一种导入方式")
		}
		if len(address) > 4096 {
			return nil, bad("音源链接过长")
		}
		reply, e := s.network.Request(ctx, address, "GET", nil, nil, maxScript, true)
		if e != nil {
			return nil, e
		}
		if reply.Status != 200 {
			return nil, bad("无法读取音源链接，请检查地址是否有效")
		}
		if !utf8.Valid(reply.Bytes) {
			return nil, bad("音源文件需采用 UTF-8 编码")
		}
		script = string(reply.Bytes)
	}
	if !utf8.ValidString(script) || strings.TrimSpace(script) == "" || len(script) > maxScript {
		return nil, bad("音源需为 UTF-8 JavaScript 文件，最大 9 MB")
	}
	script = strings.TrimPrefix(script, "\ufeff")
	environment := in.Environment
	if environment == "" {
		environment = "mobile"
	}
	if environment != "mobile" && environment != "desktop" {
		return nil, bad("兼容模式请选择 mobile 或 desktop")
	}
	cap, e := s.runtime.Inspect(ctx, script, environment)
	if e != nil {
		return nil, e
	}
	name := strings.TrimSpace(in.Name)
	if name == "" {
		name = header(script, "name", "我的音源")
	}
	if search.UTF16Length(name) > 100 || strings.ContainsFunc(name, unicode.IsControl) {
		return nil, bad("音源名称最多 100 个字，不能包含控制字符")
	}
	secret, e := s.cipher.Encrypt(encode(map[string]any{"script": script, "url": nullable(address)}), sourcePurpose)
	if e != nil {
		return nil, e
	}
	var out map[string]any
	e = s.transaction(ctx, func(tx store.DBTX) error {
		if e := s.lock(ctx, tx); e != nil {
			return e
		}
		same, e := s.row(ctx, tx, "sameScript", map[string]any{"hash": hash(script), "environment": environment})
		if e != nil {
			return e
		}
		id := uuid()
		if same != nil {
			id = str(same, "id")
			_, e = s.exec(ctx, tx, "deleteScript", map[string]any{"id": id, "deleted": 0})
		} else {
			_, e = s.exec(ctx, tx, "insertScript", map[string]any{"id": id, "name": name, "version": header(script, "version", "未标注"), "environment": environment, "hash": hash(script), "secret": secret, "capabilities": encode(cap), "now": now()})
		}
		if e != nil {
			return e
		}
		row, e := s.script(ctx, tx, id)
		if e != nil {
			return e
		}
		settings, e := s.row(ctx, tx, "settings", nil)
		if e != nil {
			return e
		}
		out = publicScript(row, str(settings, "active_script_id"))
		return nil
	})
	return out, e
}
func header(script, key, fallback string) string {
	match := regexp.MustCompile(`(?m)^\s*\*?\s*@` + key + `\s+([^\r\n]+)`).FindStringSubmatch(script)
	v := fallback
	if len(match) > 1 {
		v = strings.TrimSpace(strings.ReplaceAll(match[1], "*/", ""))
	}
	for search.UTF16Length(v) > 100 {
		r := []rune(v)
		v = string(r[:len(r)-1])
	}
	return v
}
func (s *Service) Inspect(ctx context.Context, id string, activate bool) (map[string]any, error) {
	row, e := s.script(ctx, s.db, id)
	if e != nil {
		return nil, e
	}
	body, e := s.body(row)
	if e != nil {
		return nil, e
	}
	cap, e := s.runtime.Inspect(ctx, body, str(row, "environment"))
	if e != nil {
		return nil, e
	}
	if activate {
		e = s.transaction(ctx, func(tx store.DBTX) error {
			if e := s.lock(ctx, tx); e != nil {
				return e
			}
			if _, e := s.script(ctx, tx, id); e != nil {
				return e
			}
			_, e := s.exec(ctx, tx, "activate", map[string]any{"id": id})
			return e
		})
		return map[string]any{"id": id, "capabilities": cap}, e
	}
	return map[string]any{"capabilities": cap, "message": "音源初始化成功，实际歌曲可用性以下载结果为准"}, nil
}
func (s *Service) DeleteScript(ctx context.Context, id string) error {
	return s.transaction(ctx, func(tx store.DBTX) error {
		if e := s.lock(ctx, tx); e != nil {
			return e
		}
		if _, e := s.script(ctx, tx, id); e != nil {
			return e
		}
		n, e := s.scalar(ctx, tx, "activeScript", map[string]any{"id": id})
		if e != nil {
			return e
		}
		if n > 0 {
			return conflict("此版本还有下载任务，请先结束或取消任务")
		}
		settings, e := s.row(ctx, tx, "settings", nil)
		if e != nil {
			return e
		}
		if str(settings, "active_script_id") == id {
			if _, e = s.exec(ctx, tx, "activate", map[string]any{"id": nil}); e != nil {
				return e
			}
		}
		_, e = s.exec(ctx, tx, "deleteScript", map[string]any{"id": id, "deleted": 1})
		return e
	})
}
func (s *Service) requireSource(ctx context.Context, tx store.DBTX) (map[string]any, map[string]any, error) {
	settings, e := s.row(ctx, tx, "settings", nil)
	if e != nil {
		return nil, nil, e
	}
	if str(settings, "source_id") == "" || str(settings, "source_type") != "LOCAL" {
		return nil, nil, conflict("请先在媒体库中设置洛雪下载保存位置")
	}
	if str(settings, "active_script_id") == "" {
		return nil, nil, conflict("请先导入并启用音源")
	}
	script, e := s.script(ctx, tx, str(settings, "active_script_id"))
	return settings, script, e
}

var secretText = regexp.MustCompile(`(?i)(?:token|key|secret|password|authorization|cookie)["']?\s*[=:]\s*["']?[^\s,;"'}]+`)
var urlText = regexp.MustCompile(`(?i)https?://[^\s<>"']+`)

func clean(value string) string {
	v := secretText.ReplaceAllString(urlText.ReplaceAllString(value, "[地址已隐藏]"), "[凭据已隐藏]")
	v = strings.Map(func(r rune) rune {
		if unicode.IsControl(r) {
			return ' '
		}
		return r
	}, v)
	r := []rune(v)
	return string(r[:min(500, len(r))])
}

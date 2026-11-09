package ai

import (
	"context"
	"crypto/rand"
	"database/sql"
	"embed"
	"encoding/hex"
	"errors"
	"fmt"
	"github.com/jmoiron/sqlx"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/config"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/search"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
	"log/slog"
	"strings"
	"sync"
	"time"
	"unicode"
)

//go:embed sql/*.sql
var files embed.FS

type Service struct {
	db        *sqlx.DB
	q         map[string]string
	cipher    Cipher
	log       *slog.Logger
	mu        sync.Mutex
	testing   map[string]bool
	cooldowns map[string]time.Time
	pause     func(context.Context, store.DBTX) error
	tester    func(context.Context, Snapshot) error
}

func New(db *sqlx.DB, dialect string, cfg config.Config, cipher Cipher, log *slog.Logger) (*Service, error) {
	q, e := store.ReadQueries(files, "sql")
	if e != nil {
		return nil, e
	}
	if log == nil {
		log = slog.Default()
	}
	return &Service{db: db, q: q, cipher: cipher, log: log, testing: map[string]bool{}, cooldowns: map[string]time.Time{}}, nil
}
func (s *Service) SetPauseClassification(fn func(context.Context, store.DBTX) error) { s.pause = fn }
func (s *Service) SetClassificationTester(fn func(context.Context, Snapshot) error)  { s.tester = fn }
func identifier() string {
	var b [16]byte
	if _, e := rand.Read(b[:]); e != nil {
		panic(e)
	}
	b[6] = (b[6] & 15) | 64
	b[8] = (b[8] & 63) | 128
	h := hex.EncodeToString(b[:])
	return h[:8] + "-" + h[8:12] + "-" + h[12:16] + "-" + h[16:20] + "-" + h[20:]
}

func (s *Service) required(ctx context.Context, tx store.DBTX, id string) (row, error) {
	var r row
	e := sqlx.GetContext(ctx, tx, &r, s.q["service"], id)
	if errors.Is(e, sql.ErrNoRows) {
		e = failure(404, "AI 服务不存在")
	}
	return r, e
}
func (s *Service) List(ctx context.Context, kind string) ([]ServiceView, error) {
	var stored []row
	if e := s.db.SelectContext(ctx, &stored, s.q["services"], kind); e != nil {
		return nil, e
	}
	out := make([]ServiceView, 0, len(stored))
	for _, r := range stored {
		out = append(out, r.view())
	}
	return out, nil
}
func (s *Service) Snapshot(ctx context.Context, id string) (Snapshot, error) {
	r, e := s.required(ctx, s.db, id)
	return Snapshot{r.ID, r.Protocol, r.Endpoint, r.Model, r.OutputMode, r.Secret, r.Purpose, r.Enabled, r.TimeoutSeconds, r.MaxResults}, e
}
func (s *Service) Key(v Snapshot) (string, error) {
	if v.EncryptedKey == nil {
		return "", nil
	}
	if s.cipher == nil {
		return "", fmt.Errorf("missing credential cipher")
	}
	return s.cipher.Decrypt(*v.EncryptedKey, v.CredentialPurpose)
}
func (s *Service) Feature(ctx context.Context, id string) (Feature, error) {
	return s.FeatureWith(ctx, s.db, id)
}
func (s *Service) Features(ctx context.Context) ([]Feature, error) {
	var stored []featureRow
	if e := s.db.SelectContext(ctx, &stored, s.q["features"]); e != nil {
		return nil, e
	}
	out := make([]Feature, 0, len(stored))
	for _, r := range stored {
		out = append(out, r.view())
	}
	return out, nil
}
func hasControl(v string) bool {
	for _, r := range v {
		if unicode.IsControl(r) {
			return true
		}
	}
	return false
}
func contains(v string, choices ...string) bool {
	for _, c := range choices {
		if v == c {
			return true
		}
	}
	return false
}
func validate(kind string, v ServiceInput) error {
	if !contains(kind, "MODEL", "SEARCH") || strings.TrimSpace(v.Name) == "" || search.UTF16Length(v.Name) > 100 || hasControl(v.Name) || search.UTF16Length(v.Model) > 200 || len(v.OutputMode) > 20 || v.TimeoutSeconds < 5 || v.TimeoutSeconds > 180 || v.MaxResults < 1 || v.MaxResults > 10 || v.APIKey != nil && (search.UTF16Length(*v.APIKey) > 4096 || hasControl(*v.APIKey)) {
		return failure(400, "名称、密钥、超时或结果数量无效")
	}
	known := false
	for _, p := range Providers {
		if p.ID == v.Provider && p.Kind == kind {
			known = true
		}
	}
	if !known {
		return failure(400, "未知服务厂商")
	}
	if _, e := Endpoint(context.Background(), v.Endpoint); e != nil {
		return e
	}
	if kind == "MODEL" {
		if strings.TrimSpace(v.Model) == "" || hasControl(v.Model) || !contains(v.Protocol, "CHAT_COMPLETIONS", "RESPONSES", "ANTHROPIC", "GEMINI") || !contains(v.OutputMode, "JSON_SCHEMA", "JSON_OBJECT") {
			return failure(400, "模型、接口协议或输出模式无效")
		}
		if v.Protocol == "ANTHROPIC" && v.OutputMode != "JSON_SCHEMA" {
			return failure(400, "Anthropic 原生协议使用 JSON Schema 模式")
		}
	} else if v.Protocol != strings.ToUpper(v.Provider) {
		return failure(400, "搜索协议与厂商不匹配")
	}
	return nil
}
func (s *Service) Save(ctx context.Context, kind, id string, v ServiceInput) (ServiceView, error) {
	if e := validate(kind, v); e != nil {
		return ServiceView{}, e
	}
	var result ServiceView
	e := store.WithinTx(ctx, s.db, func(tx store.DBTX) error {
		if _, e := tx.ExecContext(ctx, s.q["lock"]); e != nil {
			return e
		}
		var old row
		var e error
		var refs int
		if id != "" {
			old, e = s.required(ctx, tx, id)
			if e != nil {
				return e
			}
			if old.Kind != kind {
				return failure(400, "服务类型不匹配")
			}
			if e = tx.QueryRowContext(ctx, s.q["references"], id, id).Scan(&refs); e != nil {
				return e
			}
			if !v.Enabled && refs > 0 {
				return failure(409, "服务被功能引用，请先替换或解除绑定再停用")
			}
		} else {
			var count int
			rows, e := tx.QueryContext(ctx, s.q["services"], kind)
			if e != nil {
				return e
			}
			for rows.Next() {
				count++
			}
			e = rows.Err()
			rows.Close()
			if e != nil {
				return e
			}
			if count >= 100 {
				return failure(409, "每类最多配置 100 个服务")
			}
		}
		secret := old.Secret
		fresh := v.APIKey != nil && strings.TrimSpace(*v.APIKey) != ""
		if id != "" && (old.Provider != v.Provider || old.Protocol != v.Protocol || old.Endpoint != v.Endpoint) && secret != nil && !v.ClearKey && !fresh {
			return failure(400, "更换厂商、协议或地址时请重填密钥或明确清除")
		}
		create := id == ""
		if create {
			id = identifier()
			old.Purpose = "singverse:ai:" + id + ":v1"
		}
		if v.ClearKey {
			secret = nil
		}
		if fresh {
			enc, e := s.cipher.Encrypt(*v.APIKey, old.Purpose)
			if e != nil {
				return e
			}
			secret = &enc
		}
		args := []any{v.Name, v.Provider, v.Protocol, v.Endpoint, v.Model, v.OutputMode, secret, old.Purpose, v.Enabled, v.TimeoutSeconds, v.MaxResults}
		if create {
			args = append([]any{id, kind}, args...)
			_, e = tx.ExecContext(ctx, s.q["insert"], args...)
		} else {
			args = append(args, id, v.Revision)
			var r sql.Result
			r, e = tx.ExecContext(ctx, s.q["update"], args...)
			if e == nil {
				n, er := r.RowsAffected()
				if er != nil {
					return er
				}
				if n != 1 {
					return failure(409, "配置已变化，请重新加载后编辑")
				}
			}
			if e == nil && refs > 0 && kind == "MODEL" && s.pause != nil {
				e = s.pause(ctx, tx)
			}
		}
		if e != nil {
			return e
		}
		r, e := s.required(ctx, tx, id)
		result = r.view()
		return e
	})
	return result, e
}
func (s *Service) Remove(ctx context.Context, kind, id string) error {
	return store.WithinTx(ctx, s.db, func(tx store.DBTX) error {
		if _, e := tx.ExecContext(ctx, s.q["lock"]); e != nil {
			return e
		}
		r, e := s.required(ctx, tx, id)
		if e != nil {
			return e
		}
		if r.Kind != kind {
			return failure(400, "服务类型不匹配")
		}
		var n int
		if e = tx.QueryRowContext(ctx, s.q["references"], id, id).Scan(&n); e != nil {
			return e
		}
		if n > 0 {
			return failure(409, "服务被功能引用，请先替换或解除绑定")
		}
		_, e = tx.ExecContext(ctx, s.q["remove"], id)
		return e
	})
}

var pets = []string{"music-sprite", "panda-note", "virtual-singer", "cyber-dj", "star-mage", "fantasy-knight", "android-singer", "vampire-violinist", "service-robot", "industrial-mecha", "cyber-navigator", "ai-core", "space-pilot", "alien-traveler"}

func (s *Service) Bind(ctx context.Context, id string, v Feature) (Feature, error) {
	if id != v.ID || !contains(id, "classification", "default-search", "assistant") {
		return v, failure(400, "未知 AI 功能")
	}
	if v.IntervalSeconds < 2 || v.IntervalSeconds > 300 {
		return v, failure(400, "请求间隔应为 2～300 秒")
	}
	if v.DisplayMode == "" {
		v.DisplayMode = "PET"
	}
	if v.PetID == "" {
		v.PetID = "music-sprite"
	}
	e := store.WithinTx(ctx, s.db, func(tx store.DBTX) error {
		if _, e := tx.ExecContext(ctx, s.q["lock"]); e != nil {
			return e
		}
		switch id {
		case "assistant":
			if v.ModelServiceID != nil || v.SearchServiceID != nil {
				return failure(400, "助手模型与工具能力尚未开放配置")
			}
			if !contains(v.DisplayMode, "PET", "CHAT") || !contains(v.PetID, pets...) {
				return failure(400, "未知助手形态或宠物形象")
			}
		case "classification", "default-search":
			bound, kind := v.ModelServiceID, "MODEL"
			if id == "classification" && v.SearchServiceID != nil {
				return failure(400, "歌曲分类暂不支持联网搜索")
			}
			if id == "default-search" {
				bound, kind = v.SearchServiceID, "SEARCH"
				if v.ModelServiceID != nil {
					return failure(400, "默认搜索不能绑定模型")
				}
			}
			if bound != nil {
				r, e := s.required(ctx, tx, *bound)
				if e != nil {
					return e
				}
				if r.Kind != kind || !r.Enabled {
					return failure(400, "请选择已启用的对应服务")
				}
			} else if v.Enabled {
				return failure(400, "启用功能前请选择服务")
			}
			if id == "classification" && s.pause != nil {
				if e := s.pause(ctx, tx); e != nil {
					return e
				}
			}
		}
		r, e := tx.ExecContext(ctx, s.q["saveFeature"], v.ModelServiceID, v.SearchServiceID, v.Enabled, v.IntervalSeconds, v.DisplayMode, v.PetID, v.ID, v.Revision)
		if e != nil {
			return e
		}
		n, e := r.RowsAffected()
		if e != nil {
			return e
		}
		if n != 1 {
			return failure(409, "功能配置已变化，请重新加载")
		}
		return nil
	})
	if e != nil {
		return v, e
	}
	return s.Feature(ctx, id)
}

func (s *Service) SnapshotWith(ctx context.Context, tx store.DBTX, id string) (Snapshot, error) {
	r, e := s.required(ctx, tx, id)
	return Snapshot{r.ID, r.Protocol, r.Endpoint, r.Model, r.OutputMode, r.Secret, r.Purpose, r.Enabled, r.TimeoutSeconds, r.MaxResults}, e
}
func (s *Service) FeatureWith(ctx context.Context, tx store.DBTX, id string) (Feature, error) {
	var r featureRow
	e := sqlx.GetContext(ctx, tx, &r, s.q["feature"], id)
	if errors.Is(e, sql.ErrNoRows) {
		e = failure(400, "未知 AI 功能")
	}
	return r.view(), e
}
func (s *Service) View(ctx context.Context, id, kind string) (ServiceView, error) {
	r, e := s.required(ctx, s.db, id)
	if e == nil && r.Kind != kind {
		e = failure(400, "服务类型不匹配")
	}
	return r.view(), e
}

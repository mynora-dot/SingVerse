// Package party owns device grants and the persistent single-device party aggregate.
package party

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"embed"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"math/big"
	"net/http"
	"strings"
	"sync"
	"time"
	"unicode"

	"github.com/jmoiron/sqlx"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/library"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/config"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

//go:embed sql/*.sql
var queryFiles embed.FS

type document = map[string]any
type failure struct {
	status int
	detail string
}

func (e *failure) Error() string           { return e.detail }
func fail(status int, detail string) error { return &failure{status, detail} }

type Activity interface {
	StartTV(context.Context, store.DBTX, string, string, string, string, string) error
	FinishTV(context.Context, store.DBTX, string, string, string) error
	ReportTV(context.Context, store.DBTX, string, string, map[string]any) error
}
type Library interface {
	Song(context.Context, string) (map[string]any, error)
	SongWith(context.Context, store.DBTX, string) (map[string]any, error)
	LibrarySongs(context.Context, store.DBTX) ([]map[string]any, error)
	ServeOriginal(context.Context, http.ResponseWriter, *http.Request, string) error
}
type Service struct {
	db          *sqlx.DB
	dialect     string
	cfg         config.Config
	library     Library
	log         *slog.Logger
	q           map[string]string
	mu          sync.Mutex
	activity    Activity
	negotiator  func(context.Context, string, string, map[string]any, string, string) (map[string]any, error)
	now         func() time.Time
	indexMu     sync.Mutex
	index       *readIndex
	cipher      library.Cipher
	postCommit  []func()
	prefetchMu  sync.Mutex
	prefetch    Prefetch
	slots       map[string]*prefetchSlot
	closed      bool
	stop        chan struct{}
	closeOnce   sync.Once
	discoveryMu sync.Mutex
	discovery   interface{ Shutdown() }
}

func New(db *sqlx.DB, dialect string, cfg config.Config, lib Library, log *slog.Logger) (*Service, error) {
	q, err := store.ReadQueries(queryFiles, "sql")
	if err != nil {
		return nil, err
	}
	if log == nil {
		log = slog.Default()
	}
	s := &Service{db: db, dialect: dialect, cfg: cfg, library: lib, log: log, q: q, now: time.Now, slots: map[string]*prefetchSlot{}, stop: make(chan struct{})}
	_, err = s.transact(context.Background(), func(tx store.DBTX) (any, error) {
		if err := s.seedHomeBanners(context.Background(), tx); err != nil {
			return nil, err
		}
		return s.serverInfo(context.Background(), tx)
	})
	if err == nil {
		go s.expirePrefetch()
		s.StartDiscovery()
	}
	return s, err
}
func (s *Service) SetActivity(a Activity)     { s.activity = a }
func (s *Service) SetCipher(c library.Cipher) { s.cipher = c }
func (s *Service) transact(ctx context.Context, fn func(store.DBTX) (any, error)) (any, error) {
	s.mu.Lock()
	s.postCommit = nil
	var result any
	err := store.WithinTx(ctx, s.db, func(tx store.DBTX) error { var err error; result, err = fn(tx); return err })
	actions := s.postCommit
	s.postCommit = nil
	s.mu.Unlock()
	if err == nil {
		for _, action := range actions {
			action()
		}
	}
	return result, err
}
func str(v document, key string) string { s, _ := v[key].(string); return s }
func integer(v any) int64 {
	switch n := v.(type) {
	case int:
		return int64(n)
	case int64:
		return n
	case float64:
		return int64(n)
	case json.Number:
		parsed, _ := n.Int64()
		return parsed
	}
	return 0
}
func number(v document, key string) int64 { return integer(v[key]) }
func boolean(v document, key string) bool { b, _ := v[key].(bool); return b }
func object(v document, key string) document {
	m, _ := v[key].(map[string]any)
	if m == nil {
		return document{}
	}
	return m
}
func array(v document, key string) []any {
	a, _ := v[key].([]any)
	if a == nil {
		return []any{}
	}
	return a
}
func encode(v any) string {
	b, err := json.Marshal(v)
	if err != nil {
		panic(err)
	}
	return string(b)
}
func clone(v document) document {
	var out document
	d := json.NewDecoder(bytes.NewBufferString(encode(v)))
	d.UseNumber()
	if err := d.Decode(&out); err != nil {
		panic(err)
	}
	return out
}
func hash(v string) string { h := sha256.Sum256([]byte(v)); return hex.EncodeToString(h[:]) }
func token() (string, error) {
	var b [32]byte
	_, err := rand.Read(b[:])
	return base64.RawURLEncoding.EncodeToString(b[:]), err
}
func id() string {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		panic(err)
	}
	b[6] = (b[6] & 15) | 64
	b[8] = (b[8] & 63) | 128
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[:4], b[4:6], b[6:8], b[8:10], b[10:])
}
func text(v document, key string, max int) (string, error) {
	value := strings.TrimSpace(str(v, key))
	n := 0
	for _, r := range value {
		if unicode.IsControl(r) {
			return "", fail(400, "字段无效："+key)
		}
		n++
	}
	if n == 0 || n > max {
		return "", fail(400, "字段无效："+key)
	}
	return value, nil
}

type record struct {
	kind     string
	secret   sql.NullString
	doc      document
	revision int64
}
type scanner interface{ Scan(...any) error }

func row(r scanner) (record, error) {
	var v record
	var raw string
	err := r.Scan(&v.kind, &v.secret, &raw, &v.revision)
	if err != nil {
		return v, err
	}
	d := json.NewDecoder(bytes.NewBufferString(raw))
	d.UseNumber()
	if err = d.Decode(&v.doc); err != nil {
		return v, err
	}
	v.doc["revision"] = v.revision
	return v, nil
}
func (s *Service) getRecord(ctx context.Context, tx store.DBTX, id string) (record, error) {
	v, err := row(tx.QueryRowContext(ctx, s.q["find"], id))
	if errors.Is(err, sql.ErrNoRows) {
		return v, fail(404, "记录不存在")
	}
	return v, err
}
func (s *Service) get(ctx context.Context, tx store.DBTX, id string) (document, error) {
	v, err := s.getRecord(ctx, tx, id)
	return v.doc, err
}
func (s *Service) list(ctx context.Context, tx store.DBTX, kind string) ([]document, error) {
	rows, err := tx.QueryContext(ctx, s.q["list"], kind)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []document{}
	for rows.Next() {
		v, err := row(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, v.doc)
	}
	return out, rows.Err()
}
func (s *Service) insert(ctx context.Context, tx store.DBTX, key, kind, secret string, v document) error {
	v["id"] = key
	var hashed any
	if secret != "" {
		hashed = hash(secret)
	}
	_, err := tx.ExecContext(ctx, s.q["insert"], key, kind, hashed, encode(v))
	return err
}
func (s *Service) save(ctx context.Context, tx store.DBTX, v document) error {
	r, err := tx.ExecContext(ctx, s.q["save"], encode(v), str(v, "id"), number(v, "revision"))
	if err != nil {
		return err
	}
	n, err := r.RowsAffected()
	if err != nil {
		return err
	}
	if n != 1 {
		return fail(409, "状态已变化，请刷新后重试")
	}
	v["revision"] = number(v, "revision") + 1
	return nil
}
func (s *Service) lock(ctx context.Context, tx store.DBTX, key string) error {
	_, err := tx.ExecContext(ctx, s.q["lock"], key)
	return err
}
func (s *Service) expired(v document) bool {
	value := str(v, "expiresAt")
	if value == "" {
		return false
	}
	t, err := time.Parse(time.RFC3339Nano, value)
	return err != nil || t.Before(s.now())
}
func (s *Service) serverInfo(ctx context.Context, tx store.DBTX) (document, error) {
	v, err := s.get(ctx, tx, "server-instance")
	if err != nil {
		var missing *failure
		if !errors.As(err, &missing) || missing.status != 404 {
			return nil, err
		}
		v = document{"instanceId": id()}
		if err = s.insert(ctx, tx, "server-instance", "server", "", v); err != nil {
			return nil, err
		}
	}
	return document{"service": "singverse", "name": "SingVerse", "apiVersion": 1, "instanceId": v["instanceId"]}, nil
}
func (s *Service) Authenticate(ctx context.Context, value string) (document, error) {
	var actor document
	_, err := s.transact(ctx, func(tx store.DBTX) (any, error) {
		var err error
		actor, err = s.authenticate(ctx, tx, value)
		return nil, err
	})
	return actor, err
}
func (s *Service) authenticate(ctx context.Context, tx store.DBTX, value string) (document, error) {
	if value == "" || len(value) > 200 {
		return nil, fail(401, "请先连接")
	}
	r, err := row(tx.QueryRowContext(ctx, s.q["secret"], hash(value)))
	if errors.Is(err, sql.ErrNoRows) {
		return nil, fail(401, "连接已失效")
	}
	if err != nil {
		return nil, err
	}
	v := r.doc
	if r.kind == "credential" {
		v, err = s.get(ctx, tx, str(v, "deviceId"))
		if err != nil {
			return nil, err
		}
		if boolean(v, "revoked") {
			return nil, fail(401, "电视已解绑")
		}
		v["kind"] = "device"
		return v, nil
	}
	if (r.kind != "device" && r.kind != "participant") || boolean(v, "revoked") || s.expired(v) {
		return nil, fail(401, "连接已失效")
	}
	v["kind"] = r.kind
	if r.kind == "participant" {
		session, err := s.get(ctx, tx, str(v, "sessionId"))
		if err != nil {
			return nil, err
		}
		if str(session, "status") != "ACTIVE" {
			return nil, fail(401, "聚会已结束")
		}
		device, err := s.get(ctx, tx, str(session, "deviceId"))
		if err != nil {
			return nil, err
		}
		if boolean(device, "revoked") {
			return nil, fail(401, "电视已解绑")
		}
	}
	return v, nil
}
func (s *Service) authorize(ctx context.Context, tx store.DBTX, input document) (document, error) {
	pending, err := s.list(ctx, tx, "authorization")
	if err != nil {
		return nil, err
	}
	recent := 0
	for _, v := range pending {
		if s.expired(v) {
			if _, err = tx.ExecContext(ctx, s.q["delete"], str(v, "id")); err != nil {
				return nil, err
			}
		} else {
			recent++
		}
	}
	if recent >= 30 {
		return nil, fail(429, "授权申请较多，请稍后重试")
	}
	name, err := text(input, "name", 60)
	if err != nil {
		return nil, err
	}
	secret, err := token()
	if err != nil {
		return nil, err
	}
	code, err := rand.Int(rand.Reader, big.NewInt(1000000))
	if err != nil {
		return nil, err
	}
	v := document{"name": name, "code": fmt.Sprintf("%06d", code), "expiresAt": s.now().Add(10 * time.Minute).UTC().Format(time.RFC3339Nano), "status": "PENDING"}
	if err = s.insert(ctx, tx, id(), "authorization", secret, v); err != nil {
		return nil, err
	}
	out := clone(v)
	out["pollSecret"] = secret
	return out, nil
}
func (s *Service) approve(ctx context.Context, tx store.DBTX, key string, input document) (document, error) {
	if err := s.lock(ctx, tx, key); err != nil {
		return nil, err
	}
	r, err := s.getRecord(ctx, tx, key)
	if err != nil {
		return nil, err
	}
	v := r.doc
	if r.kind != "authorization" || s.expired(v) || str(v, "status") != "PENDING" {
		return nil, fail(409, "申请已处理或过期")
	}
	code, err := text(input, "code", 6)
	if err != nil {
		return nil, err
	}
	if code != str(v, "code") {
		return nil, fail(400, "授权码不匹配")
	}
	d := document{"name": v["name"], "revoked": false}
	device := id()
	if err = s.insert(ctx, tx, device, "device", "", d); err != nil {
		return nil, err
	}
	v["deviceId"] = device
	v["status"] = "APPROVED"
	if err = s.save(ctx, tx, v); err != nil {
		return nil, err
	}
	return d, nil
}
func (s *Service) deny(ctx context.Context, tx store.DBTX, key string) error {
	if err := s.lock(ctx, tx, key); err != nil {
		return err
	}
	r, err := s.getRecord(ctx, tx, key)
	if err != nil {
		return err
	}
	if r.kind != "authorization" || str(r.doc, "status") != "PENDING" {
		return fail(409, "申请已处理")
	}
	r.doc["status"] = "DENIED"
	return s.save(ctx, tx, r.doc)
}
func (s *Service) exchange(ctx context.Context, tx store.DBTX, key, secret string) (document, error) {
	r, err := s.getRecord(ctx, tx, key)
	if err != nil {
		return nil, err
	}
	v := r.doc
	if r.kind != "authorization" || secret == "" || hash(secret) != r.secret.String {
		return nil, fail(401, "授权凭据无效")
	}
	if s.expired(v) {
		return nil, fail(410, "授权已过期，请重新申请")
	}
	out := document{"status": v["status"]}
	if str(v, "status") != "APPROVED" {
		return out, nil
	}
	device := str(v, "deviceId")
	if err = s.lock(ctx, tx, device); err != nil {
		return nil, err
	}
	d, err := s.get(ctx, tx, device)
	if err != nil {
		return nil, err
	}
	if boolean(d, "revoked") {
		return nil, fail(401, "设备已撤销")
	}
	credential := hash("singverse:device:" + secret)
	out["deviceId"] = device
	out["token"] = credential
	if str(d, "credentialId") != "" {
		return out, nil
	}
	cid := id()
	if err = s.insert(ctx, tx, cid, "credential", credential, document{"deviceId": device}); err != nil {
		return nil, err
	}
	d["credentialId"] = cid
	if err = s.save(ctx, tx, d); err != nil {
		return nil, err
	}
	return out, nil
}
func (s *Service) revoke(ctx context.Context, tx store.DBTX, key string) error {
	if err := s.lock(ctx, tx, key); err != nil {
		return err
	}
	r, err := s.getRecord(ctx, tx, key)
	if err != nil {
		return err
	}
	if r.kind != "device" {
		return fail(404, "电视不存在")
	}
	v := r.doc
	v["revoked"] = true
	if err = s.save(ctx, tx, v); err != nil {
		return err
	}
	if sid := str(v, "sessionId"); sid != "" {
		session, e := s.get(ctx, tx, sid)
		if e != nil {
			return e
		}
		if err = s.finishActivity(ctx, tx, session, "INTERRUPTED"); err != nil {
			return err
		}
		s.postCommit = append(s.postCommit, func() { s.revokePrefetch(sid) })
		return nil
	}
	return nil
}
func check(actor, session document) error {
	if str(actor, "kind") == "device" {
		if str(session, "deviceId") != str(actor, "id") {
			return fail(403, "不是这台电视的聚会")
		}
	} else if str(session, "id") != str(actor, "sessionId") {
		return fail(403, "不是当前聚会")
	}
	return nil
}
func host(actor document) bool { return str(actor, "kind") == "device" || boolean(actor, "host") }
func snapshot(session, actor document) document {
	v := clone(session)
	delete(v, "receipts")
	v["stateRevision"] = number(session, "revision")
	v["revision"] = number(session, "queueRevision")
	v["actorId"] = actor["id"]
	v["host"] = host(actor)
	if str(actor, "kind") != "device" {
		delete(v, "commands")
	}
	return v
}
func (s *Service) session(ctx context.Context, tx store.DBTX, actor document) (document, error) {
	if str(actor, "kind") != "device" {
		return nil, fail(403, "仅电视可以开场")
	}
	device := str(actor, "id")
	if err := s.lock(ctx, tx, device); err != nil {
		return nil, err
	}
	d, err := s.get(ctx, tx, device)
	if err != nil {
		return nil, err
	}
	if sid := str(d, "sessionId"); sid != "" {
		old, e := s.get(ctx, tx, sid)
		if e != nil {
			return nil, e
		}
		if str(old, "status") == "ACTIVE" {
			return snapshot(old, actor), nil
		}
	}
	sid := id()
	v := document{"deviceId": device, "status": "ACTIVE", "acceptingRequests": true, "revision": int64(0), "queueRevision": int64(0), "queue": []any{}, "commands": []any{}, "receipts": document{}}
	if err = s.insert(ctx, tx, sid, "session", "", v); err != nil {
		return nil, err
	}
	d["sessionId"] = sid
	if err = s.save(ctx, tx, d); err != nil {
		return nil, err
	}
	return snapshot(v, actor), nil
}
func (s *Service) recover(ctx context.Context, tx store.DBTX, actor document, sid string) (document, error) {
	if str(actor, "kind") != "device" {
		return nil, fail(403, "仅电视可恢复")
	}
	if err := s.lock(ctx, tx, sid); err != nil {
		return nil, err
	}
	v, err := s.get(ctx, tx, sid)
	if err != nil {
		return nil, err
	}
	if err = check(actor, v); err != nil {
		return nil, err
	}
	if err = s.finishActivity(ctx, tx, v, "INTERRUPTED"); err != nil {
		return nil, err
	}
	for _, item := range array(v, "queue") {
		q := item.(map[string]any)
		if str(q, "status") == "PLAYING" || str(q, "status") == "HANDOFF" {
			q["status"] = "WAITING"
		}
	}
	delete(v, "playback")
	v["commands"] = []any{}
	v["queueRevision"] = number(v, "queueRevision") + 1
	if err = s.save(ctx, tx, v); err != nil {
		return nil, err
	}
	s.prefetchChanged(v, false, false)
	return snapshot(v, actor), nil
}
func (s *Service) invitation(ctx context.Context, tx store.DBTX, actor document, sid string) (document, error) {
	if err := s.lock(ctx, tx, sid); err != nil {
		return nil, err
	}
	v, err := s.get(ctx, tx, sid)
	if err != nil {
		return nil, err
	}
	if err = check(actor, v); err != nil {
		return nil, err
	}
	if str(actor, "kind") != "device" || str(v, "status") != "ACTIVE" {
		return nil, fail(403, "仅电视主持可刷新邀请")
	}
	secret, err := token()
	if err != nil {
		return nil, err
	}
	invite := document{"sessionId": sid, "expiresAt": s.now().Add(time.Hour).UTC().Format(time.RFC3339Nano)}
	iid := id()
	if err = s.insert(ctx, tx, iid, "invitation", secret, invite); err != nil {
		return nil, err
	}
	v["invitationId"] = iid
	if err = s.save(ctx, tx, v); err != nil {
		return nil, err
	}
	return document{"invitation": secret, "expiresAt": invite["expiresAt"]}, nil
}
func (s *Service) invite(ctx context.Context, tx store.DBTX, secret string) (document, error) {
	r, err := row(tx.QueryRowContext(ctx, s.q["secret"], hash(secret)))
	if errors.Is(err, sql.ErrNoRows) {
		return nil, fail(410, "邀请无效")
	}
	if err != nil {
		return nil, err
	}
	if r.kind != "invitation" {
		return nil, fail(410, "邀请无效")
	}
	v := r.doc
	session, err := s.get(ctx, tx, str(v, "sessionId"))
	if err != nil {
		return nil, err
	}
	if s.expired(v) || str(session, "status") != "ACTIVE" || str(session, "invitationId") != str(v, "id") {
		return nil, fail(410, "邀请已过期或刷新")
	}
	d, err := s.get(ctx, tx, str(session, "deviceId"))
	if err != nil {
		return nil, err
	}
	if boolean(d, "revoked") {
		return nil, fail(410, "电视已解绑")
	}
	return v, nil
}
func (s *Service) join(ctx context.Context, tx store.DBTX, input document) (document, error) {
	all, err := s.list(ctx, tx, "participant")
	if err != nil {
		return nil, err
	}
	count := 0
	for _, old := range all {
		if s.expired(old) {
			if _, err = tx.ExecContext(ctx, s.q["delete"], str(old, "id")); err != nil {
				return nil, err
			}
		} else {
			count++
		}
	}
	secret, err := text(input, "invitation", 200)
	if err != nil {
		return nil, err
	}
	invite, err := s.invite(ctx, tx, secret)
	if err != nil {
		return nil, err
	}
	if count >= 1000 {
		return nil, fail(429, "参与人数已达上限")
	}
	name, err := text(input, "name", 30)
	if err != nil {
		return nil, err
	}
	credential, err := token()
	if err != nil {
		return nil, err
	}
	v := document{"sessionId": invite["sessionId"], "name": name, "host": false, "revoked": false, "expiresAt": s.now().Add(12 * time.Hour).UTC().Format(time.RFC3339Nano)}
	if err = s.insert(ctx, tx, id(), "participant", credential, v); err != nil {
		return nil, err
	}
	out := clone(v)
	out["token"] = credential
	return out, nil
}
func (s *Service) startActivity(ctx context.Context, tx store.DBTX, session document, item document) error {
	if s.activity == nil {
		return nil
	}
	p := object(session, "playback")
	if item == nil {
		for _, candidate := range array(session, "queue") {
			v := candidate.(map[string]any)
			if str(v, "id") == str(p, "itemId") {
				item = v
				break
			}
		}
	}
	device, err := s.get(ctx, tx, str(session, "deviceId"))
	if err != nil {
		return err
	}
	return s.activity.StartTV(ctx, tx, str(p, "attemptId"), str(session, "id"), str(p, "songId"), str(device, "name"), str(item, "ownerName"))
}
func (s *Service) finishActivity(ctx context.Context, tx store.DBTX, session document, state string) error {
	if s.activity == nil {
		return nil
	}
	return s.activity.FinishTV(ctx, tx, str(session, "id"), str(object(session, "playback"), "attemptId"), state)
}

func (s *Service) SetPlaybackNegotiator(fn func(context.Context, string, string, map[string]any, string, string) (map[string]any, error)) {
	s.negotiator = fn
}

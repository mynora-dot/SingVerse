// Package playback owns durable playback clocks, leases, history and popularity.
package playback

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"database/sql"
	"embed"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"math"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode"

	"github.com/jmoiron/sqlx"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/search"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

//go:embed sql/*.sql
var files embed.FS

const leaseMS int64 = 120000
const skewMS int64 = 5000
const maxClock int64 = 30 * 24 * 60 * 60 * 1000

type SongReader func(context.Context, store.DBTX, string) (map[string]any, error)
type Service struct {
	db      *sqlx.DB
	dialect string
	queries map[string]string
	song    SongReader
	now     func() int64
	log     *slog.Logger
	cancel  context.CancelFunc
	wg      sync.WaitGroup
	mu      sync.Mutex
}
type failure struct {
	Status int
	Detail string
}

func (e *failure) Error() string           { return e.Detail }
func (e *failure) StatusCode() int         { return e.Status }
func fail(status int, detail string) error { return &failure{status, detail} }
func New(db *sqlx.DB, dialect string, song SongReader, log *slog.Logger) (*Service, error) {
	q, err := store.ReadQueries(files, "sql")
	if err != nil {
		return nil, err
	}
	if log == nil {
		log = slog.Default()
	}
	s := &Service{db: db, dialect: dialect, queries: q, song: song, now: func() int64 { return time.Now().UnixMilli() }, log: log}
	ctx, cancel := context.WithCancel(context.Background())
	s.cancel = cancel
	s.wg.Add(1)
	go func() {
		defer s.wg.Done()
		timer := time.NewTimer(5 * time.Second)
		defer timer.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-timer.C:
				if err := s.Maintain(ctx); err != nil && !errors.Is(err, context.Canceled) {
					s.log.Warn("playback maintenance deferred")
				}
				timer.Reset(time.Second)
			}
		}
	}()
	return s, nil
}
func (s *Service) Close() error { s.cancel(); s.wg.Wait(); return nil }
func (s *Service) transaction(ctx context.Context, f func(store.DBTX) error) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	return store.WithinTx(ctx, s.db, f)
}
func (s *Service) statement(name string, v map[string]any) (string, []any, error) {
	q, ok := s.queries[name+"-"+s.dialect]
	if !ok {
		q, ok = s.queries[name]
	}
	if !ok {
		return "", nil, fmt.Errorf("unknown playback query %s", name)
	}
	return store.Bind(q, v)
}
func (s *Service) exec(ctx context.Context, tx store.DBTX, name string, v map[string]any) error {
	q, a, e := s.statement(name, v)
	if e != nil {
		return e
	}
	_, e = tx.ExecContext(ctx, q, a...)
	return e
}
func (s *Service) rows(ctx context.Context, tx store.DBTX, name string, v map[string]any) ([]map[string]any, error) {
	q, a, e := s.statement(name, v)
	if e != nil {
		return nil, e
	}
	return store.Maps(ctx, tx, q, a...)
}
func (s *Service) row(ctx context.Context, tx store.DBTX, name string, v map[string]any) (map[string]any, error) {
	rows, e := s.rows(ctx, tx, name, v)
	if e != nil {
		return nil, e
	}
	if len(rows) == 0 {
		return nil, sql.ErrNoRows
	}
	if len(rows) > 1 {
		return nil, errors.New("playback query returned multiple rows")
	}
	return rows[0], nil
}
func n(r map[string]any, k string) int64 {
	switch v := r[k].(type) {
	case int64:
		return v
	case int:
		return int64(v)
	case float64:
		return int64(v)
	case string:
		x, _ := strconv.ParseInt(v, 10, 64)
		return x
	}
	return 0
}
func decimal(r map[string]any, k string) float64 {
	switch v := r[k].(type) {
	case float64:
		return v
	case int64:
		return float64(v)
	case string:
		x, _ := strconv.ParseFloat(v, 64)
		return x
	}
	return 0
}
func text(r map[string]any, k string) string { v, _ := r[k].(string); return v }
func ptr(r map[string]any, k string) *int64 {
	if r[k] == nil {
		return nil
	}
	v := n(r, k)
	return &v
}
func value(v *int64) any {
	if v == nil {
		return nil
	}
	return *v
}
func maxPointer(prior *int64, at int64) int64 {
	if prior == nil {
		return at
	}
	return max(*prior, at)
}
func active(state string) bool {
	switch state {
	case "PREPARING", "BUFFERING", "PLAYING", "PAUSED":
		return true
	}
	return false
}
func validState(state string) bool {
	return active(state) || state == "COMPLETED" || state == "STOPPED" || state == "SKIPPED" || state == "ERROR"
}
func validText(v string, maxLen int, empty bool) bool {
	return (empty || strings.TrimSpace(v) != "") && search.UTF16Length(v) <= maxLen && !strings.ContainsFunc(v, unicode.IsControl)
}
func counter(event map[string]any, key string) (int64, error) {
	var number int64
	switch v := event[key].(type) {
	case json.Number:
		n, e := v.Int64()
		if e != nil {
			return 0, fail(400, "播放字段无效："+key)
		}
		number = n
	case int64:
		number = v
	case int:
		number = int64(v)
	default:
		return 0, fail(400, "播放字段无效："+key)
	}
	if number < 0 {
		return 0, fail(400, "播放字段无效："+key)
	}
	return number, nil
}
func UUID() string {
	var b [16]byte
	if _, e := rand.Read(b[:]); e != nil {
		panic("random source unavailable")
	}
	b[6] = (b[6] & 15) | 64
	b[8] = (b[8] & 63) | 128
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[:4], b[4:6], b[6:8], b[8:10], b[10:])
}
func digest(token string) string { b := sha256.Sum256([]byte(token)); return hex.EncodeToString(b[:]) }
func (s *Service) lockSong(ctx context.Context, tx store.DBTX, song string) error {
	v := map[string]any{"song": song}
	if s.dialect == "mysql" {
		// One atomic upsert acquires the record lock without first taking an
		// absent-key gap lock that can deadlock concurrent first plays.
		return s.exec(ctx, tx, "ensureProfile", v)
	}
	q, a, e := s.statement("lockProfile", v)
	if e != nil {
		return e
	}
	r, e := tx.ExecContext(ctx, q, a...)
	if e != nil {
		return e
	}
	changed, e := r.RowsAffected()
	if e != nil || changed > 0 {
		return e
	}
	if e = s.exec(ctx, tx, "ensureProfile", v); e != nil {
		return e
	}
	return s.exec(ctx, tx, "lockProfile", v)
}
func (s *Service) start(ctx context.Context, tx store.DBTX, id, kind, owner, song, device, requested string, tokenHash any, started *int64) error {
	if e := s.lockSong(ctx, tx, song); e != nil {
		return e
	}
	data, e := s.song(ctx, tx, song)
	if e != nil {
		return e
	}
	artists, e := json.Marshal(data["artists"])
	if e != nil {
		return e
	}
	snapshot := map[string]any{"title": data["title"], "artists_json": string(artists), "version_label": data["versionType"], "source_name": data["sourceName"], "device_name": device, "requested_by": requested}
	if snapshot["title"] == nil {
		snapshot["title"] = ""
	}
	if snapshot["version_label"] == nil {
		snapshot["version_label"] = "UNKNOWN"
	}
	encoded, e := json.Marshal(snapshot)
	if e != nil {
		return e
	}
	now := s.now()
	begin := now
	if started != nil {
		begin = *started
	}
	return s.exec(ctx, tx, "insertAttempt", map[string]any{"id": id, "client_type": kind, "owner_session_id": owner, "token_hash": tokenHash, "song_id": song, "snapshot_json": string(encoded), "started_at": begin, "client_started_at": value(started), "last_event_at": now, "lease_until": now + leaseMS})
}
func (s *Service) Create(ctx context.Context, kind, owner, song, device string, started *int64) (map[string]any, error) {
	if (kind != "ADMIN" && kind != "MOBILE") || !validText(owner, 64, false) || !validText(song, 64, false) || !validText(device, 200, true) {
		return nil, fail(400, "播放字段无效")
	}
	now := s.now()
	if started != nil && (*started < now-86400000 || *started > now+skewMS) {
		return nil, fail(400, "播放开始时间无效")
	}
	id := UUID()
	var b [32]byte
	if _, e := rand.Read(b[:]); e != nil {
		return nil, e
	}
	token := base64.RawURLEncoding.EncodeToString(b[:])
	e := s.transaction(ctx, func(tx store.DBTX) error {
		return s.start(ctx, tx, id, kind, owner, song, device, "", digest(token), started)
	})
	if e != nil {
		return nil, e
	}
	begin := now
	if started != nil {
		begin = *started
	}
	return map[string]any{"attemptId": id, "writeToken": token, "eventSeq": -1, "leaseSeconds": 120, "startedAt": begin, "serverTime": now}, nil
}
func (s *Service) StartTV(ctx context.Context, tx store.DBTX, attempt, session, song, device, requested string) error {
	return s.start(ctx, tx, attempt, "TV", session, song, device, requested, nil, nil)
}
func (s *Service) require(ctx context.Context, tx store.DBTX, id string) (map[string]any, error) {
	if !validText(id, 36, false) {
		return nil, fail(400, "播放字段无效")
	}
	r, e := s.row(ctx, tx, "attempt", map[string]any{"id": id})
	if errors.Is(e, sql.ErrNoRows) {
		return nil, fail(404, "播放实例不存在")
	}
	return r, e
}
func (s *Service) Report(ctx context.Context, kind, owner, id string, event map[string]any) (result map[string]any, err error) {
	err = s.transaction(ctx, func(tx store.DBTX) error {
		r, e := s.require(ctx, tx, id)
		if e != nil {
			return e
		}
		if text(r, "client_type") != kind || text(r, "owner_session_id") != owner {
			return fail(403, "播放实例不属于当前登录会话")
		}
		token := text(event, "writeToken")
		if len(token) != 43 || subtle.ConstantTimeCompare([]byte(digest(token)), []byte(text(r, "token_hash"))) != 1 {
			return fail(403, "播放写入凭据无效")
		}
		if e = s.lockSong(ctx, tx, text(r, "song_id")); e != nil {
			return e
		}
		r, e = s.row(ctx, tx, "currentAttempt", map[string]any{"id": id})
		if e != nil {
			return e
		}
		result, e = s.apply(ctx, tx, r, event, false)
		return e
	})
	return
}
func (s *Service) ReportTV(ctx context.Context, tx store.DBTX, session, id string, event map[string]any) error {
	r, e := s.require(ctx, tx, id)
	if e != nil {
		return e
	}
	if text(r, "client_type") != "TV" || text(r, "owner_session_id") != session {
		return fail(409, "旧播放实例已失效")
	}
	if e = s.lockSong(ctx, tx, text(r, "song_id")); e != nil {
		return e
	}
	r, e = s.row(ctx, tx, "currentAttempt", map[string]any{"id": id})
	if e != nil {
		return e
	}
	state := text(r, "state")
	if state == "INTERRUPTED" || ((active(state) || state == "ERROR") && n(r, "lease_until") <= s.now()) {
		if text(event, "state") == "ENDED" || text(event, "state") == "IDLE" {
			if _, e = counter(event, "eventSeq"); e != nil {
				return e
			}
			if state != "INTERRUPTED" {
				return s.finish(ctx, tx, r, "INTERRUPTED", n(r, "last_event_at"))
			}
			return nil
		}
		return fail(410, "播放报告租约已到期，请重新开始")
	}
	if active(state) || state == "ERROR" {
		_, e = s.apply(ctx, tx, r, event, true)
		return e
	}
	return nil
}
func (s *Service) apply(ctx context.Context, tx store.DBTX, r, event map[string]any, tv bool) (map[string]any, error) {
	seq, e := counter(event, "eventSeq")
	if e != nil {
		return nil, e
	}
	state := text(r, "state")
	now := s.now()
	if state == "INTERRUPTED" || ((active(state) || state == "ERROR") && n(r, "lease_until") <= now) {
		return nil, fail(410, "播放报告租约已到期，请重新开始")
	}
	if seq <= n(r, "event_seq") {
		return view(r), nil
	}
	if !active(state) && state != "ERROR" {
		return nil, fail(409, "播放实例已结束，请重新开始")
	}
	next := text(event, "state")
	if tv {
		if next == "ENDED" {
			next = "COMPLETED"
		}
		if next == "IDLE" {
			next = "STOPPED"
		}
	}
	if !validState(next) {
		return nil, fail(400, "播放状态无效")
	}
	_, a := event["actualPlayedMs"]
	_, b := event["elapsedMs"]
	hasClock := a || b
	if !tv && !hasClock {
		return nil, fail(400, "缺少真实播放时钟")
	}
	actual, elapsed := n(r, "actual_played_ms"), n(r, "elapsed_ms")
	if hasClock {
		actual, e = counter(event, "actualPlayedMs")
		if e != nil {
			return nil, e
		}
		elapsed, e = counter(event, "elapsedMs")
		if e != nil {
			return nil, e
		}
	}
	priorActual, priorElapsed := n(r, "actual_played_ms"), n(r, "elapsed_ms")
	origin := n(r, "started_at")
	if r["client_started_at"] != nil {
		origin = n(r, "client_started_at")
	}
	if _, ok := event["startedAt"]; tv && ok {
		begin, e := counter(event, "startedAt")
		if e != nil {
			return nil, e
		}
		if (r["client_started_at"] != nil && begin != origin) || begin < n(r, "started_at")-skewMS || begin > now+skewMS {
			return nil, fail(400, "播放开始时钟不能改变")
		}
		origin = begin
		r["client_started_at"] = begin
	}
	occurred := now
	if hasClock {
		if elapsed > maxClock {
			return nil, fail(400, "真实播放时钟无效")
		}
		occurred = origin + elapsed
	}
	if _, ok := event["occurredAt"]; ok {
		occurred, e = counter(event, "occurredAt")
		if e != nil {
			return nil, e
		}
	}
	if occurred > now+skewMS || occurred < origin-skewMS || (n(r, "event_seq") >= 0 && occurred < n(r, "last_event_at")) || (hasClock && math.Abs(float64(occurred-origin-elapsed)) > float64(skewMS)) {
		return nil, fail(400, "播放事件发生时间无效")
	}
	if actual < priorActual || elapsed < priorElapsed || actual > maxClock || elapsed > maxClock || actual > elapsed+1000 || actual-priorActual > elapsed-priorElapsed+1000 || elapsed > max(int64(0), now-origin)+skewMS {
		return nil, fail(400, "真实播放时钟无效，定位跳转不能计入播放时长")
	}
	first := r["started_playing_at"] == nil && (next == "PLAYING" || actual > 0)
	activity := first || actual > priorActual || (next == "PLAYING" && state != "PLAYING")
	previous, priorClient := ptr(r, "last_activity_at"), ptr(r, "client_last_activity_at")
	if priorClient == nil {
		priorClient = previous
	}
	clientActivity := priorClient
	if event["lastActivityAt"] != nil {
		v, e := counter(event, "lastActivityAt")
		if e != nil {
			return nil, e
		}
		clientActivity = &v
	} else if activity {
		clientActivity = &occurred
	}
	if clientActivity != nil && (*clientActivity > occurred+skewMS || *clientActivity < origin-skewMS || (priorClient != nil && *clientActivity < *priorClient) || (!activity && (priorClient == nil || *clientActivity > *priorClient))) {
		return nil, fail(400, "实际播放发生时间无效")
	}
	happened := min(now, occurred)
	last := previous
	if activity && clientActivity != nil {
		v := min(happened, *clientActivity)
		last = &v
	}
	duration := ptr(r, "duration_ms")
	if _, ok := event["durationMs"]; ok {
		v, e := counter(event, "durationMs")
		if e != nil {
			return nil, e
		}
		if v <= 0 || v > 86400000 {
			return nil, fail(400, "播放总时长无效")
		}
		duration = &v
	}
	r["duration_ms"] = value(duration)
	r["event_seq"] = seq
	r["actual_played_ms"] = actual
	r["elapsed_ms"] = elapsed
	r["state"] = next
	r["last_event_at"] = happened
	r["lease_until"] = happened
	if active(next) || next == "ERROR" {
		r["lease_until"] = happened + leaseMS
	}
	if first {
		r["started_playing_at"] = min(now, max(origin, *last-actual))
		r["history_id"] = UUID()
		var snapshot map[string]any
		if e = json.Unmarshal([]byte(text(r, "snapshot_json")), &snapshot); e != nil {
			return nil, e
		}
		for k, v := range snapshot {
			r[k] = v
		}
		if e = s.exec(ctx, tx, "insertHistory", r); e != nil {
			return nil, e
		}
	}
	if activity && last != nil {
		r["last_activity_at"] = *last
		r["client_last_activity_at"] = value(clientActivity)
		p, e := s.row(ctx, tx, "currentProfile", map[string]any{"song": r["song_id"]})
		if e != nil {
			return nil, e
		}
		if e = s.exec(ctx, tx, "updateProfile", map[string]any{"song": r["song_id"], "lastPlayed": maxPointer(ptr(p, "last_played_at"), *last), "heat": decimal(p, "heat"), "heatAt": p["heat_updated_at"]}); e != nil {
			return nil, e
		}
	}
	if r["history_id"] != nil {
		if r["effective_at"] == nil && text(r, "client_type") != "ADMIN" && (actual >= 30000 || (duration != nil && *duration < 30000 && next == "COMPLETED" && actual >= *duration)) {
			r["effective_at"] = value(last)
		}
		r["history_status"] = next
		r["ended_at"] = happened
		if active(next) {
			r["history_status"] = "PLAYING"
			r["ended_at"] = nil
		}
		if e = s.exec(ctx, tx, "updateHistory", r); e != nil {
			return nil, e
		}
		checkpoint, e := s.row(ctx, tx, "aggregationState", nil)
		if e != nil {
			return nil, e
		}
		if n(checkpoint, "ready") == 1 {
			history, e := s.row(ctx, tx, "currentHistory", map[string]any{"id": r["history_id"]})
			if e != nil {
				return nil, e
			}
			if e = s.aggregate(ctx, tx, history); e != nil {
				return nil, e
			}
		}
	}
	if e = s.exec(ctx, tx, "updateAttempt", r); e != nil {
		return nil, e
	}
	return view(r), nil
}
func view(r map[string]any) map[string]any {
	return map[string]any{"attemptId": r["id"], "eventSeq": n(r, "event_seq"), "state": r["state"], "effective": r["effective_at"] != nil, "leaseSeconds": 120}
}
func (s *Service) FinishTV(ctx context.Context, tx store.DBTX, session, id, state string) error {
	if strings.TrimSpace(id) == "" {
		return nil
	}
	r, e := s.row(ctx, tx, "attempt", map[string]any{"id": id})
	if errors.Is(e, sql.ErrNoRows) {
		return nil
	}
	if e != nil {
		return e
	}
	if text(r, "client_type") != "TV" || text(r, "owner_session_id") != session {
		return nil
	}
	if e = s.lockSong(ctx, tx, text(r, "song_id")); e != nil {
		return e
	}
	r, e = s.row(ctx, tx, "currentAttempt", map[string]any{"id": id})
	if e != nil {
		return e
	}
	if active(text(r, "state")) || text(r, "state") == "ERROR" {
		return s.finish(ctx, tx, r, state, s.now())
	}
	return nil
}
func (s *Service) finish(ctx context.Context, tx store.DBTX, r map[string]any, state string, ended int64) error {
	r["state"] = state
	r["lease_until"] = s.now()
	r["history_status"] = state
	r["ended_at"] = max(ended, n(r, "started_playing_at"))
	if r["history_id"] != nil {
		if e := s.exec(ctx, tx, "updateHistory", r); e != nil {
			return e
		}
	}
	return s.exec(ctx, tx, "updateAttempt", r)
}
func eligible(r map[string]any) bool {
	return text(r, "client_type") != "ADMIN" && (n(r, "played_ms") >= 30000 || (r["duration_ms"] != nil && n(r, "duration_ms") > 0 && n(r, "duration_ms") < 30000 && text(r, "status") == "COMPLETED" && n(r, "played_ms") >= n(r, "duration_ms")))
}
func (s *Service) effective(r map[string]any) *int64 {
	if r["effective_at"] != nil {
		return ptr(r, "effective_at")
	}
	if !eligible(r) {
		return nil
	}
	at := min(s.now(), n(r, "started_at"))
	return &at
}
func decay(age int64) float64 {
	return math.Pow(2, -float64(max(int64(0), age))/float64(14*24*60*60*1000))
}
func (s *Service) aggregate(ctx context.Context, tx store.DBTX, r map[string]any) error {
	if r == nil || n(r, "heat_applied") == 1 {
		return nil
	}
	song := text(r, "song_id")
	p, e := s.row(ctx, tx, "currentProfile", map[string]any{"song": song})
	if e != nil {
		return e
	}
	effective := s.effective(r)
	at := n(r, "started_at")
	if r["last_activity_at"] != nil {
		at = n(r, "last_activity_at")
	}
	at = min(s.now(), max(n(r, "started_at"), at))
	last := maxPointer(ptr(p, "last_played_at"), at)
	heat, heatAt := decimal(p, "heat"), ptr(p, "heat_updated_at")
	if eligible(r) && effective != nil {
		if heatAt != nil && *effective < *heatAt {
			heat = 0
			heatAt = nil
			times, e := s.rows(ctx, tx, "appliedHeatTimes", map[string]any{"song": song, "current": r["id"]})
			if e != nil {
				return e
			}
			for _, t := range times {
				at := n(t, "effective_at")
				age := int64(0)
				if heatAt != nil {
					age = at - *heatAt
				}
				heat = min(1, heat*decay(age)+.05)
				heatAt = &at
			}
		} else {
			age := int64(0)
			if heatAt != nil {
				age = *effective - *heatAt
			}
			heat = min(1, heat*decay(age)+.05)
			heatAt = effective
		}
	}
	if e = s.exec(ctx, tx, "updateProfile", map[string]any{"song": song, "lastPlayed": last, "heat": heat, "heatAt": value(heatAt)}); e != nil {
		return e
	}
	return s.exec(ctx, tx, "markAggregated", map[string]any{"id": r["id"], "effectiveAt": value(effective)})
}
func (s *Service) Maintain(ctx context.Context) error {
	e := s.transaction(ctx, func(tx store.DBTX) error {
		state, e := s.row(ctx, tx, "aggregationState", nil)
		if e != nil {
			return e
		}
		if text(state, "phase") != "PREPARE" {
			return nil
		}
		rows, e := s.rows(ctx, tx, "unpreparedHistory", map[string]any{"limit": 200})
		if e != nil {
			return e
		}
		var started int64
		id := ""
		for _, r := range rows {
			if e = s.exec(ctx, tx, "prepareHistory", map[string]any{"id": r["id"], "effectiveAt": value(s.effective(r))}); e != nil {
				return e
			}
			started = n(r, "started_at")
			id = text(r, "id")
		}
		phase := "PREPARE"
		if len(rows) < 200 {
			phase = "AGGREGATE"
		}
		return s.exec(ctx, tx, "checkpoint", map[string]any{"ready": 0, "phase": phase, "started": started, "id": id})
	})
	if e != nil {
		return e
	}
	rows, e := s.rows(ctx, s.db, "pendingHistory", map[string]any{"limit": 200})
	if e != nil {
		return e
	}
	for _, r := range rows {
		e = s.transaction(ctx, func(tx store.DBTX) error {
			if e := s.lockSong(ctx, tx, text(r, "song_id")); e != nil {
				return e
			}
			current, e := s.row(ctx, tx, "currentHistory", map[string]any{"id": r["id"]})
			if e != nil {
				return e
			}
			if value(s.effective(current)) != value(s.effective(r)) {
				return nil
			}
			if e = s.aggregate(ctx, tx, current); e != nil {
				return e
			}
			return s.exec(ctx, tx, "checkpoint", map[string]any{"ready": 0, "phase": "AGGREGATE", "started": r["started_at"], "id": r["id"]})
		})
		if e != nil {
			return e
		}
	}
	e = s.transaction(ctx, func(tx store.DBTX) error {
		pending, e := s.rows(ctx, tx, "pendingHistory", map[string]any{"limit": 1})
		if e != nil {
			return e
		}
		state, e := s.row(ctx, tx, "aggregationState", nil)
		if e != nil {
			return e
		}
		ready, phase := 0, "AGGREGATE"
		if len(pending) == 0 && text(state, "phase") != "PREPARE" {
			ready, phase = 1, "READY"
		}
		return s.exec(ctx, tx, "checkpoint", map[string]any{"ready": ready, "phase": phase, "started": n(state, "cursor_started_at"), "id": text(state, "cursor_id")})
	})
	if e != nil {
		return e
	}
	expired, e := s.rows(ctx, s.db, "expired", map[string]any{"now": s.now(), "limit": 200})
	if e != nil {
		return e
	}
	for _, r := range expired {
		e = s.transaction(ctx, func(tx store.DBTX) error {
			if e := s.lockSong(ctx, tx, text(r, "song_id")); e != nil {
				return e
			}
			current, e := s.row(ctx, tx, "currentAttempt", map[string]any{"id": r["id"]})
			if e != nil {
				return e
			}
			state := text(current, "state")
			if (active(state) || state == "ERROR") && n(current, "lease_until") <= s.now() {
				return s.finish(ctx, tx, current, "INTERRUPTED", n(current, "last_event_at"))
			}
			return nil
		})
		if e != nil {
			return e
		}
	}
	return nil
}

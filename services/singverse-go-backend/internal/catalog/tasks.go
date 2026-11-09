package catalog

import (
	"context"
	"database/sql"
	"errors"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/ai"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
	"regexp"
	"strconv"
	"time"
)

var statuses = []string{"PENDING", "PROCESSING", "REVIEW", "APPLIED", "UNCHANGED", "FAILED", "CANCELLED"}

type ModelConfig struct {
	Enabled           bool    `json:"enabled"`
	Endpoint          string  `json:"endpoint"`
	Model             string  `json:"model"`
	EncryptedKey      *string `json:"encryptedKey"`
	TimeoutSeconds    int     `json:"timeoutSeconds"`
	IntervalSeconds   int     `json:"intervalSeconds"`
	Revision          int64   `json:"revision"`
	Protocol          string  `json:"protocol"`
	OutputMode        string  `json:"outputMode"`
	CredentialPurpose string  `json:"credentialPurpose"`
	ServiceID         *string `json:"serviceId"`
}

func (s *Service) modelConfig(ctx context.Context, tx store.DBTX, required bool) (ModelConfig, error) {
	f, e := s.ai.FeatureWith(ctx, tx, "classification")
	if e != nil {
		return ModelConfig{}, e
	}
	c := ModelConfig{TimeoutSeconds: 60, IntervalSeconds: f.IntervalSeconds, Revision: f.Revision, Protocol: "CHAT_COMPLETIONS", OutputMode: "JSON_SCHEMA", CredentialPurpose: "singverse:classification:v1"}
	if f.ModelServiceID != nil {
		m, e := s.ai.SnapshotWith(ctx, tx, *f.ModelServiceID)
		if e != nil {
			return c, e
		}
		c = ModelConfig{f.Enabled && m.Enabled, m.Endpoint, m.Model, m.EncryptedKey, m.TimeoutSeconds, f.IntervalSeconds, f.Revision, m.Protocol, m.OutputMode, m.CredentialPurpose, &m.ID}
	}
	if required && !c.Enabled {
		return c, fail(409, "请先保存并启用分类模型")
	}
	return c, nil
}
func (s *Service) settings(ctx context.Context) (map[string]any, error) {
	c, e := s.modelConfig(ctx, s.db, false)
	if e != nil {
		return nil, e
	}
	name := ""
	if c.ServiceID != nil {
		v, e := s.ai.View(ctx, *c.ServiceID, "MODEL")
		if e != nil {
			return nil, e
		}
		name = v.Name
	}
	return map[string]any{"enabled": c.Enabled, "endpoint": c.Endpoint, "model": c.Model, "hasKey": c.EncryptedKey != nil, "timeoutSeconds": c.TimeoutSeconds, "intervalSeconds": c.IntervalSeconds, "revision": c.Revision, "serviceId": c.ServiceID, "serviceName": name, "protocol": c.Protocol, "outputMode": c.OutputMode}, nil
}
func (s *Service) saveSettings(ctx context.Context, v struct {
	Enabled         bool    `json:"enabled"`
	Endpoint        string  `json:"endpoint"`
	Model           string  `json:"model"`
	APIKey          *string `json:"apiKey"`
	ClearKey        bool    `json:"clearKey"`
	TimeoutSeconds  int     `json:"timeoutSeconds"`
	IntervalSeconds int     `json:"intervalSeconds"`
}) (any, error) {
	if !textValid(v.Model, 200, false) || v.IntervalSeconds < 2 || v.IntervalSeconds > 300 {
		return nil, fail(400, "模型名称、超时或请求间隔无效")
	}
	f, e := s.ai.Feature(ctx, "classification")
	if e != nil {
		return nil, e
	}
	in := ai.ServiceInput{Name: "原歌曲分类模型", Provider: "custom", Protocol: "CHAT_COMPLETIONS", OutputMode: "JSON_SCHEMA", Enabled: true, TimeoutSeconds: v.TimeoutSeconds, MaxResults: 5, Endpoint: v.Endpoint, Model: v.Model, APIKey: v.APIKey, ClearKey: v.ClearKey}
	target := ""
	if f.ModelServiceID != nil {
		target = *f.ModelServiceID
		old, e := s.ai.View(ctx, target, "MODEL")
		if e != nil {
			return nil, e
		}
		in.Name, in.Provider, in.Protocol, in.OutputMode, in.Revision = old.Name, old.Provider, old.Protocol, old.OutputMode, old.Revision
	} else {
		u, e := ai.Endpoint(ctx, v.Endpoint)
		if e != nil {
			return nil, e
		}
		if len(u.Path) < 17 || u.Path[len(u.Path)-17:] != "/chat/completions" {
			return nil, fail(400, "填写完整 chat/completions 地址")
		}
	}
	saved, e := s.ai.Save(ctx, "MODEL", target, in)
	if e != nil {
		return nil, e
	}
	f.ModelServiceID = &saved.ID
	f.Enabled = v.Enabled
	f.IntervalSeconds = v.IntervalSeconds
	if _, e = s.ai.Bind(ctx, "classification", f); e != nil {
		return nil, e
	}
	return s.settings(ctx)
}
func (s *Service) targets(ctx context.Context, tx store.DBTX, v Start) ([]string, error) {
	if e := dimensions(v.Dimensions, true); e != nil {
		return nil, e
	}
	if v.AutoDimensions == nil {
		return nil, fail(400, "自动应用维度必须属于任务维度")
	}
	for _, d := range v.AutoDimensions {
		if !has(d, v.Dimensions...) {
			return nil, fail(400, "自动应用维度必须属于任务维度")
		}
	}
	ids := v.SongIDs
	if v.All {
		rows, e := query(ctx, tx, s.q["c_songIds"], 5001)
		if e != nil {
			return nil, e
		}
		ids = []string{}
		for _, r := range rows {
			ids = append(ids, str(r["id"]))
		}
	}
	if len(ids) == 0 || len(ids) > 5000 {
		return nil, fail(400, "每批请选择 1～5000 首歌曲")
	}
	out := []string{}
	seen := map[string]bool{}
	for _, v := range ids {
		if v == "" {
			return nil, fail(400, "歌曲标识无效")
		}
		if !seen[v] {
			seen[v] = true
			out = append(out, v)
		}
	}
	return out, nil
}
func (s *Service) preview(ctx context.Context, v Start) (any, error) {
	var items []Input
	var total int
	e := store.WithinTx(ctx, s.db, func(tx store.DBTX) error {
		ids, e := s.targets(ctx, tx, v)
		if e != nil {
			return e
		}
		total = len(ids)
		items = []Input{}
		for i, id := range ids {
			if i >= 20 {
				break
			}
			in, _, e := s.input(ctx, tx, id)
			if e != nil {
				return e
			}
			items = append(items, in)
		}
		return nil
	})
	if e != nil {
		return nil, e
	}
	settings, e := s.settings(ctx)
	return map[string]any{"items": items, "total": total, "model": settings}, e
}
func (s *Service) start(ctx context.Context, v Start) (any, error) {
	task := id()
	_, e := s.tx(ctx, func(tx store.DBTX) (any, error) {
		if e := s.lock(ctx, tx); e != nil {
			return nil, e
		}
		c, e := s.modelConfig(ctx, tx, true)
		if e != nil {
			return nil, e
		}
		ids, e := s.targets(ctx, tx, v)
		if e != nil {
			return nil, e
		}
		tags, e := s.tags(ctx, tx)
		if e != nil {
			return nil, e
		}
		frozen := []Tag{}
		for _, t := range tags {
			if t.Active {
				frozen = append(frozen, t)
			}
		}
		at := now()
		if e = s.exec(ctx, tx, "c_insertTask", task, encode(c), encode(frozen), encode(sorted(v.Dimensions)), encode(sorted(v.AutoDimensions)), at, at, at); e != nil {
			return nil, e
		}
		for _, song := range ids {
			in, version, e := s.input(ctx, tx, song)
			if e != nil {
				return nil, e
			}
			state, e := s.stored(ctx, tx, song)
			if e != nil {
				return nil, e
			}
			if e = s.exec(ctx, tx, "c_insertItem", id(), task, song, encode(in), version, state.Revision, at); e != nil {
				return nil, e
			}
		}
		return nil, s.projectTask(ctx, tx, task)
	})
	if e != nil {
		return nil, e
	}
	return s.task(ctx, task, 0, 20, "")
}
func (s *Service) requireTask(ctx context.Context, tx store.DBTX, id string) (map[string]any, error) {
	v, e := one(ctx, tx, s.q["c_task"], id)
	if errors.Is(e, sql.ErrNoRows) {
		e = fail(400, "分类任务不存在")
	}
	return v, e
}
func (s *Service) taskView(ctx context.Context, tx store.DBTX, r map[string]any, p, size int, status string) (map[string]any, error) {
	task := str(r["id"])
	counts := map[string]int64{}
	for _, state := range statuses {
		counts[state] = 0
	}
	rows, e := query(ctx, tx, s.q["c_counts"], task)
	if e != nil {
		return nil, e
	}
	total := int64(0)
	for _, row := range rows {
		n := num(row["n"])
		counts[str(row["status"])] = n
		total += n
	}
	var filter any
	if status != "" {
		filter = status
	}
	var count int64
	if e = tx.QueryRowContext(ctx, s.q["c_itemCount"], task, filter, filter).Scan(&count); e != nil {
		return nil, e
	}
	items := []map[string]any{}
	if size > 0 {
		rows, e = query(ctx, tx, s.q["c_items"], task, filter, filter, size, int64(p)*int64(size))
		if e != nil {
			return nil, e
		}
		for _, row := range rows {
			in, e := decode[Input](row["input_json"])
			if e != nil {
				return nil, e
			}
			var suggestion *Suggestion
			if row["suggestion_json"] != nil {
				v, e := decode[Suggestion](row["suggestion_json"])
				if e != nil {
					return nil, e
				}
				suggestion = &v
			}
			current, e := s.stored(ctx, tx, str(row["song_id"]))
			if e != nil {
				return nil, e
			}
			items = append(items, map[string]any{"id": row["id"], "songId": row["song_id"], "status": row["status"], "input": in, "revision": num(row["song_revision"]), "suggestion": suggestion, "error": row["error"], "current": current})
		}
	}
	dims, e := decode[[]string](r["dimensions_json"])
	if e != nil {
		return nil, e
	}
	automatic, e := decode[[]string](r["automatic_json"])
	if e != nil {
		return nil, e
	}
	c, e := decode[ModelConfig](r["config_json"])
	if e != nil {
		return nil, e
	}
	return map[string]any{"id": task, "status": r["status"], "dimensions": dims, "autoDimensions": automatic, "model": c.Model, "total": total, "completed": total - counts["PENDING"] - counts["PROCESSING"], "counts": counts, "items": items, "page": p, "size": size, "totalElements": count, "createdAt": r["created_at"], "updatedAt": r["updated_at"]}, nil
}
func (s *Service) task(ctx context.Context, id string, p, size int, status string) (any, error) {
	if e := pages(p, size); e != nil {
		return nil, e
	}
	if status != "" && !has(status, statuses...) {
		return nil, fail(400, "任务状态无效")
	}
	var out any
	e := store.WithinTx(ctx, s.db, func(tx store.DBTX) error {
		r, e := s.requireTask(ctx, tx, id)
		if e != nil {
			return e
		}
		out, e = s.taskView(ctx, tx, r, p, size, status)
		return e
	})
	return out, e
}
func (s *Service) tasks(ctx context.Context, p, size int) (any, error) {
	if e := pages(p, size); e != nil {
		return nil, e
	}
	out := []map[string]any{}
	e := store.WithinTx(ctx, s.db, func(tx store.DBTX) error {
		rows, e := query(ctx, tx, s.q["c_tasks"], size, int64(p)*int64(size))
		if e != nil {
			return e
		}
		for _, r := range rows {
			v, e := s.taskView(ctx, tx, r, 0, 0, "")
			if e != nil {
				return e
			}
			out = append(out, v)
		}
		return nil
	})
	return out, e
}
func (s *Service) control(ctx context.Context, id, action string) (any, error) {
	_, e := s.tx(ctx, func(tx store.DBTX) (any, error) {
		if e := s.lock(ctx, tx); e != nil {
			return nil, e
		}
		r, e := s.requireTask(ctx, tx, id)
		if e != nil {
			return nil, e
		}
		state := str(r["status"])
		next := ""
		switch action {
		case "pause":
			if state != "RUNNING" {
				return nil, fail(409, "只有运行中任务可以暂停")
			}
			next = "PAUSED"
		case "resume":
			if state != "PAUSED" {
				return nil, fail(409, "只有暂停任务可以继续")
			}
			if _, e = s.modelConfig(ctx, tx, true); e != nil {
				return nil, e
			}
			next = "RUNNING"
			var n int64
			if e = tx.QueryRowContext(ctx, s.q["c_pendingCount"], id).Scan(&n); e != nil {
				return nil, e
			}
			if n == 0 {
				next = "COMPLETED"
			}
		case "cancel":
			if !has(state, "RUNNING", "PAUSED") {
				return nil, fail(409, "当前任务不能终止")
			}
			if e = s.exec(ctx, tx, "c_cancelItems", now(), id); e != nil {
				return nil, e
			}
			next = "CANCELLED"
		default:
			return nil, fail(400, "任务操作无效")
		}
		if e = s.exec(ctx, tx, "c_taskState", next, r["next_at"], now(), id); e != nil {
			return nil, e
		}
		return nil, s.projectTask(ctx, tx, id)
	})
	if e != nil {
		return nil, e
	}
	return s.task(ctx, id, 0, 20, "")
}
func (s *Service) ownedItem(ctx context.Context, tx store.DBTX, task, item string) (map[string]any, error) {
	r, e := one(ctx, tx, s.q["c_item"], item)
	if errors.Is(e, sql.ErrNoRows) || str(r["task_id"]) != task {
		return nil, fail(400, "歌曲条目不属于当前分类任务")
	}
	return r, e
}
func (s *Service) retry(ctx context.Context, task, item string) (any, error) {
	_, e := s.tx(ctx, func(tx store.DBTX) (any, error) {
		if e := s.lock(ctx, tx); e != nil {
			return nil, e
		}
		t, e := s.requireTask(ctx, tx, task)
		if e != nil {
			return nil, e
		}
		r, e := s.ownedItem(ctx, tx, task, item)
		if e != nil {
			return nil, e
		}
		if str(t["status"]) == "CANCELLED" || !has(str(r["status"]), "FAILED", "REVIEW", "UNCHANGED") {
			return nil, fail(409, "当前条目不可重新生成")
		}
		if _, e = s.modelConfig(ctx, tx, true); e != nil {
			return nil, e
		}
		in, version, e := s.input(ctx, tx, str(r["song_id"]))
		if e != nil {
			return nil, e
		}
		current, e := s.stored(ctx, tx, in.SongID)
		if e != nil {
			return nil, e
		}
		if e = s.exec(ctx, tx, "c_retry", encode(in), version, current.Revision, now(), item); e != nil {
			return nil, e
		}
		next := "RUNNING"
		if str(t["status"]) == "PAUSED" {
			next = "PAUSED"
		}
		if e = s.exec(ctx, tx, "c_taskState", next, t["next_at"], now(), task); e != nil {
			return nil, e
		}
		return nil, s.projectTask(ctx, tx, task)
	})
	if e != nil {
		return nil, e
	}
	return s.task(ctx, task, 0, 20, "")
}
func (s *Service) validateSuggestion(ctx context.Context, tx store.DBTX, item, task map[string]any, v Suggestion) error {
	dims, e := decode[[]string](task["dimensions_json"])
	if e != nil {
		return e
	}
	if len(v.Dimensions) != len(dims) || len(v.Reasons) != len(dims) || v.NewTagSuggestions == nil || len(v.NewTagSuggestions) > 10 {
		return fail(400, "模型输出结构无效")
	}
	for _, d := range dims {
		if _, ok := v.Dimensions[d]; !ok {
			return fail(400, "模型返回了错误的分类维度")
		}
		if _, ok := v.Reasons[d]; !ok {
			return fail(400, "模型依据维度无效")
		}
	}
	if e = s.validateTags(ctx, tx, v.Dimensions); e != nil {
		return e
	}
	frozen, e := decode[[]Tag](task["vocabulary_json"])
	if e != nil {
		return e
	}
	tags, e := s.tags(ctx, tx)
	if e != nil {
		return e
	}
	for dim, ids := range v.Dimensions {
		for _, id := range ids {
			var old, live *Tag
			for _, t := range frozen {
				if t.ID == id && t.Dimension == dim {
					v := t
					old = &v
				}
			}
			for _, t := range tags {
				if t.ID == id {
					v := t
					live = &v
				}
			}
			if old == nil {
				return fail(409, "模型返回词表外标签")
			}
			if live == nil || !live.Active || old.Revision != live.Revision {
				return fail(409, "词表已变化，请重新建立任务")
			}
		}
	}
	_, version, e := s.input(ctx, tx, str(item["song_id"]))
	if e != nil {
		return e
	}
	if version != str(item["input_version"]) {
		return fail(409, "歌曲信息在生成后变化，请重新生成分类")
	}
	return nil
}
func (s *Service) review(ctx context.Context, task, item string, v Review) (any, error) {
	if len(v.Dimensions) == 0 {
		return nil, fail(400, "请选择应用维度")
	}
	return s.tx(ctx, func(tx store.DBTX) (any, error) {
		if e := s.lock(ctx, tx); e != nil {
			return nil, e
		}
		return s.receipt(ctx, tx, v.RequestID, []any{task, item, v}, func() (State, error) {
			r, e := s.ownedItem(ctx, tx, task, item)
			if e != nil {
				return State{}, e
			}
			t, e := s.requireTask(ctx, tx, task)
			if e != nil {
				return State{}, e
			}
			if str(r["status"]) != "REVIEW" {
				return State{}, fail(409, "当前条目不是待审核状态")
			}
			suggestion, e := decode[Suggestion](r["suggestion_json"])
			if e != nil {
				return State{}, e
			}
			if e = s.validateSuggestion(ctx, tx, r, t, suggestion); e != nil {
				return State{}, e
			}
			current, e := s.state(ctx, tx, str(r["song_id"]))
			if e != nil {
				return current, e
			}
			if current.Revision != v.Revision {
				return current, fail(409, "分类已变化，请刷新后审核")
			}
			next := copyValues(current.Dimensions)
			for _, dim := range v.Dimensions {
				ids, ok := suggestion.Dimensions[dim]
				if !ok {
					return current, fail(400, "审核维度不属于当前建议")
				}
				if old, ok := next[dim]; ok && old.Locked {
					return current, fail(409, "该维度已锁定，请在人工编辑中解锁后审核")
				}
				if len(ids) > 0 {
					next[dim] = Value{ids, "AI_REVIEWED", false, true}
				}
			}
			updated, e := s.write(ctx, tx, current, next, "AI_REVIEWED", task)
			if e != nil {
				return updated, e
			}
			if e = s.exec(ctx, tx, "c_itemState", "APPLIED", encode(suggestion), "所选维度已确认，其余维度保留现状", updated.Revision, now(), item); e != nil {
				return updated, e
			}
			return updated, s.finish(ctx, tx, task, 0)
		})
	})
}
func (s *Service) finish(ctx context.Context, tx store.DBTX, task string, delay int) error {
	t, e := s.requireTask(ctx, tx, task)
	if e != nil {
		return e
	}
	state := str(t["status"])
	var n int64
	if e = tx.QueryRowContext(ctx, s.q["c_pendingCount"], task).Scan(&n); e != nil {
		return e
	}
	if state == "RUNNING" && n == 0 {
		state = "COMPLETED"
	}
	if e = s.exec(ctx, tx, "c_taskState", state, time.Now().UTC().Add(time.Duration(delay)*time.Second).Format(time.RFC3339Nano), now(), task); e != nil {
		return e
	}
	return s.projectTask(ctx, tx, task)
}
func (s *Service) pauseAll(ctx context.Context, tx store.DBTX) error {
	if e := s.lock(ctx, tx); e != nil {
		return e
	}
	rows, e := query(ctx, tx, s.q["c_runningTasks"])
	if e != nil {
		return e
	}
	if e = s.exec(ctx, tx, "c_pauseAll", now()); e != nil {
		return e
	}
	for _, r := range rows {
		if e = s.projectTask(ctx, tx, str(r["id"])); e != nil {
			return e
		}
	}
	return nil
}
func (s *Service) recover(ctx context.Context) error {
	return store.WithinTx(ctx, s.db, func(tx store.DBTX) error {
		if e := s.lock(ctx, tx); e != nil {
			return e
		}
		if e := s.exec(ctx, tx, "c_recoverItems", now()); e != nil {
			return e
		}
		return s.exec(ctx, tx, "c_recoverTasks", now())
	})
}

var releasePattern = regexp.MustCompile(`^[12][0-9]{3}(-[0-9]{2})?(-[0-9]{2})?$`)

func facts(v Suggestion, in Input, tags []Tag, dims []string) Suggestion {
	find := func(dim, label string) []string {
		for _, t := range tags {
			if t.Active && t.Dimension == dim && (t.Name == label || has(label, t.Aliases...)) {
				return []string{t.ID}
			}
		}
		return []string{}
	}
	if has("LANGUAGE", dims...) {
		label := map[string]string{"MANDARIN": "国语", "CANTONESE": "粤语", "ENGLISH": "英语", "JAPANESE": "日语", "KOREAN": "韩语", "OTHER": "其他"}[in.Language]
		if label != "" {
			v.Dimensions["LANGUAGE"] = find("LANGUAGE", label)
			v.Reasons["LANGUAGE"] = "沿用文件名解析中的明确语种，未分析音频"
		}
	}
	if has("DECADE", dims...) {
		label := ""
		if in.ReleaseDate != nil && releasePattern.MatchString(*in.ReleaseDate) {
			year, _ := strconv.Atoi((*in.ReleaseDate)[:4])
			if year <= time.Now().Year() {
				label = strconv.Itoa(year/10*10) + " 年代"
			}
		}
		v.Dimensions["DECADE"] = find("DECADE", label)
		v.Reasons["DECADE"] = "没有有效发行日期，保留未知"
		if label != "" {
			v.Reasons["DECADE"] = "按已确认发行日期归组，不代表首次发行或创作年代"
		}
	}
	return v
}

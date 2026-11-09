package execution

import (
	"context"
	"errors"
	"github.com/jmoiron/sqlx"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/background"
	"sort"
	"strings"
)

func audioCategory(status map[string]any, spec audioRecipe) string {
	if str(status, "state") == "CONFIRMED" && str(status, "backingId") != "" {
		return "READY"
	}
	for _, item := range array(status["versions"]) {
		v := obj(item)
		if str(v, "separationProfile") == spec.profile && str(v, "outputFormat") == spec.format && !boolv(v["preview"]) {
			return "READY"
		}
	}
	for _, item := range array(status["jobs"]) {
		v := obj(item)
		if str(v, "kind") == "SEPARATE" && oneOf(str(v, "state"), "QUEUED", "RUNNING") && str(v, "separationProfile") == spec.profile && str(v, "outputFormat") == spec.format && !boolv(v["preview"]) {
			return "PROCESSING"
		}
	}
	if _, ok := status["streams"]; !ok {
		return "UNPROBED"
	}
	if len(array(status["streams"])) == 1 {
		return "ELIGIBLE"
	}
	return "INAPPLICABLE"
}
func matchesAudio(row, filter map[string]any) bool {
	if str(filter, "songId") != "" && str(row, "id") != str(filter, "songId") {
		return false
	}
	if str(filter, "sourceId") != "" && str(row, "sourceId") != str(filter, "sourceId") {
		return false
	}
	if !strings.Contains(strings.ToLower(str(row, "title")), strings.ToLower(strings.TrimSpace(str(filter, "title")))) {
		return false
	}
	artists := []string{}
	for _, value := range array(row["artists"]) {
		if name, ok := value.(string); ok {
			artists = append(artists, name)
		}
	}
	if !strings.Contains(strings.ToLower(strings.Join(artists, " ")), strings.ToLower(strings.TrimSpace(str(filter, "artist")))) {
		return false
	}
	status := obj(row["audio"])
	if filter["streams"] != nil && (status["streams"] == nil || int64(len(array(status["streams"]))) != integer(filter["streams"])) {
		return false
	}
	state := str(filter, "state")
	if state == "FAILED" {
		for _, item := range array(status["jobs"]) {
			v := obj(item)
			if str(v, "kind") == "SEPARATE" && str(v, "state") == "FAILED" {
				return true
			}
		}
		return false
	}
	if state == "UNCONFIRMED" {
		return len(array(status["streams"])) == 2 && str(status, "state") != "CONFIRMED"
	}
	return state == "" || state == str(row, "category")
}

// The index uses bounded recent histories loaded in bulk, so filtering the full
// collection never performs a query for every song.
func (s *Service) audioCatalog(ctx context.Context) ([]map[string]any, error) {
	s.catalogMu.Lock()
	defer s.catalogMu.Unlock()
	if s.catalog != nil && now()-s.catalogBuilt.Load() < 2000 {
		return s.catalog, nil
	}
	songs, err := s.library.LibrarySongs(ctx, s.db)
	if err != nil {
		return nil, err
	}
	raw, err := s.maps(ctx, s.db, "audio-catalog-media", nil)
	if err != nil {
		return nil, err
	}
	media := map[string]map[string]any{}
	for _, row := range raw {
		media[str(row, "id")] = row
	}
	roles, err := s.maps(ctx, s.db, "audio-catalog-roles", nil)
	if err != nil {
		return nil, err
	}
	byRole := map[string]map[string]any{}
	for _, row := range roles {
		byRole[str(row, "song_id")] = row
	}
	history, err := s.maps(ctx, s.db, "audio-catalog-jobs", nil)
	if err != nil {
		return nil, err
	}
	byJobs := map[string][]map[string]any{}
	for _, job := range history {
		if row := media[str(job, "song_id")]; row != nil && str(job, "fingerprint") == contentFingerprint(row) {
			byJobs[str(job, "song_id")] = append(byJobs[str(job, "song_id")], job)
		}
	}
	items := make([]map[string]any, 0, len(songs))
	for _, song := range songs {
		id := str(song, "id")
		raw := media[id]
		if raw == nil {
			continue
		}
		status := roleView(raw, byRole[id])
		jobs, versions := []any{}, []any{}
		for _, job := range byJobs[id] {
			spec := storedRecipe(job)
			v := spec.view()
			v["kind"], v["state"], v["id"] = str(job, "kind"), str(job, "state"), str(job, "id")
			jobs = append(jobs, v)
			data := decoded(job["result_json"])
			if status["streams"] == nil && data["streams"] != nil {
				status["streams"] = data["streams"]
			}
			if str(job, "kind") == "SEPARATE" && str(job, "state") == "SUCCEEDED" && regular(s.audioPath(job)) {
				versions = append(versions, spec.view())
			}
		}
		status["jobs"], status["versions"] = jobs, versions
		if len(array(status["streams"])) == 1 && str(status, "state") != "CONFIRMED" {
			status["state"] = "CONFIRMED"
		}
		title, _ := titleArtist(song)
		artists := obj(song["result"])["artists"]
		metadata := obj(song["metadata"])
		if !boolv(metadata["stale"]) && contains(metadata["fields"], "artists") {
			artists = obj(metadata["track"])["artists"]
		}
		items = append(items, map[string]any{"id": id, "sourceId": str(song, "sourceId"), "sourceName": str(song, "sourceName"), "relativePath": str(song, "relativePath"), "title": title, "artists": artists, "fingerprint": contentFingerprint(raw), "audio": status, "category": audioCategory(status, audioRecipe{profile: legacyProfile, model: "htdemucs", format: "aac"}), "coverAvailable": !boolv(metadata["stale"]) && contains(metadata["fields"], "cover") && str(obj(metadata["track"]), "coverUrl") != ""})
	}
	sort.Slice(items, func(i, j int) bool { return str(items[i], "id") < str(items[j], "id") })
	s.catalog = items
	s.catalogBuilt.Store(now())
	return items, nil
}
func (s *Service) audioSongs(ctx context.Context, filter map[string]any, page, size int) (map[string]any, error) {
	if page < 0 || page > 1000000 || size < 1 || size > 100 {
		return nil, fail(400, "分页参数无效")
	}
	rows, err := s.audioCatalog(ctx)
	if err != nil {
		return nil, err
	}
	items := []any{}
	total := int64(0)
	for _, row := range rows {
		if !matchesAudio(row, filter) {
			continue
		}
		if total >= int64(page)*int64(size) && len(items) < size {
			v := copyMap(row)
			delete(v, "fingerprint")
			status, e := s.audioStatus(ctx, str(row, "id"))
			if e != nil {
				return nil, e
			}
			v["audio"] = status
			items = append(items, v)
		}
		total++
	}
	return map[string]any{"page": page, "size": size, "items": items, "totalElements": total, "generation": s.catalogBuilt.Load(), "building": false}, nil
}
func (s *Service) requireBatch(ctx context.Context, id string) (map[string]any, error) {
	if !idPattern.MatchString(id) {
		return nil, fail(404, "批量任务不存在")
	}
	row, err := s.optional(ctx, s.db, "batch-batch", map[string]any{"id": id})
	if err != nil {
		return nil, err
	}
	if row == nil {
		return nil, fail(404, "批量任务不存在")
	}
	return row, nil
}
func (s *Service) batchView(ctx context.Context, row map[string]any) (map[string]any, error) {
	v := storedRecipe(row).view()
	v["id"], v["state"], v["backend"], v["createdAt"] = str(row, "id"), str(row, "state"), str(row, "backend"), integer(row["created_at"])
	v["total"], v["prepared"], v["selectionTotal"], v["error"] = integer(row["selection_total"]), integer(row["prepared_count"]), integer(row["selection_total"]), str(row, "preparation_error")
	counts, categories := map[string]any{}, map[string]any{}
	rows, err := s.maps(ctx, s.db, "batch-counts", map[string]any{"id": str(row, "id")})
	if err != nil {
		return nil, err
	}
	for _, count := range rows {
		state, category := str(count, "state"), str(count, "category")
		counts[state] = integer(counts[state]) + integer(count["amount"])
		categories[category] = integer(categories[category]) + integer(count["amount"])
	}
	v["counts"], v["categories"] = counts, categories
	return v, nil
}
func (s *Service) batchDetail(ctx context.Context, id string, page, size int) (map[string]any, error) {
	if page < 0 || page > 1000000 || size < 1 || size > 100 {
		return nil, fail(400, "分页参数无效")
	}
	row, err := s.requireBatch(ctx, id)
	if err != nil {
		return nil, err
	}
	v, err := s.batchView(ctx, row)
	if err != nil {
		return nil, err
	}
	rows, err := s.maps(ctx, s.db, "batch-items", map[string]any{"id": id, "limit": size, "offset": int64(page) * int64(size)})
	if err != nil {
		return nil, err
	}
	items := []any{}
	for _, item := range rows {
		if str(item, "execution") != "" {
			item["execution"] = decoded(item["execution"])
		} else {
			delete(item, "execution")
		}
		items = append(items, item)
	}
	v["items"], v["page"], v["size"] = items, page, size
	return v, nil
}
func (s *Service) batches(ctx context.Context, page, size int) (map[string]any, error) {
	if page < 0 || page > 1000000 || size < 1 || size > 100 {
		return nil, fail(400, "分页参数无效")
	}
	count, err := s.one(ctx, s.db, "batch-batch-count", nil)
	if err != nil {
		return nil, err
	}
	rows, err := s.maps(ctx, s.db, "batch-batches", map[string]any{"limit": size, "offset": int64(page) * int64(size)})
	if err != nil {
		return nil, err
	}
	items := []any{}
	for _, row := range rows {
		v, e := s.batchView(ctx, row)
		if e != nil {
			return nil, e
		}
		items = append(items, v)
	}
	var total int64
	for _, n := range count {
		total = integer(n)
	}
	return map[string]any{"totalElements": total, "items": items}, nil
}
func (s *Service) previewBatch(ctx context.Context, input map[string]any) (map[string]any, error) {
	backend := str(input, "backend")
	if backend == "auto" {
		backend = ""
	}
	spec, err := recipe(input)
	if err != nil {
		return nil, err
	}
	if err = spec.validateBackend(backend); err != nil {
		return nil, err
	}
	catalog, err := s.audioCatalog(ctx)
	if err != nil {
		return nil, err
	}
	selected := []map[string]any{}
	if boolv(input["all"]) {
		for _, row := range catalog {
			if matchesAudio(row, obj(input["filter"])) {
				selected = append(selected, copyMap(row))
			}
		}
	} else {
		byID := map[string]map[string]any{}
		for _, row := range catalog {
			byID[str(row, "id")] = row
		}
		seen := map[string]bool{}
		if len(array(input["ids"])) > 100000 {
			return nil, fail(413, "选择歌曲过多")
		}
		for _, value := range array(input["ids"]) {
			id, ok := value.(string)
			if !ok || id == "" {
				return nil, fail(400, "歌曲标识无效")
			}
			if seen[id] {
				continue
			}
			seen[id] = true
			row := byID[id]
			if row == nil {
				return nil, fail(409, "歌曲索引已变化，请刷新列表后重试")
			}
			selected = append(selected, copyMap(row))
		}
	}
	if len(selected) == 0 {
		return nil, fail(400, "没有符合条件的歌曲")
	}
	if !s.preparing.CompareAndSwap(false, true) {
		return nil, fail(429, "正在准备其他批次，请稍后重试")
	}
	id := uuid()
	err = s.transaction(ctx, "AUDIO_BATCH", id, func(tx *sqlx.Tx) error {
		if _, e := s.exec(ctx, tx, "batch-insert-batch", map[string]any{"id": id, "backend": backend, "now": now(), "total": len(selected)}); e != nil {
			return e
		}
		_, e := s.exec(ctx, tx, "batch-set-recipe", map[string]any{"id": id, "recipe": encoded(spec.view())})
		return e
	})
	if err != nil {
		s.preparing.Store(false)
		return nil, err
	}
	s.wg.Add(1)
	go func() { defer s.wg.Done(); defer s.preparing.Store(false); s.prepareBatch(s.ctx, id, selected, spec) }()
	return s.batchDetail(ctx, id, 0, 50)
}
func (s *Service) prepareBatch(ctx context.Context, id string, selected []map[string]any, spec audioRecipe) {
	for offset := 0; offset < len(selected); offset += 200 {
		if ctx.Err() != nil {
			return
		}
		limit := min(offset+200, len(selected))
		items := []map[string]any{}
		for _, summary := range selected[offset:limit] {
			songID := str(summary, "id")
			state, detail, category := "SKIPPED", "", str(summary, "category")
			song, err := s.song(ctx, s.db, songID)
			if err != nil || contentFingerprint(song) != str(summary, "fingerprint") {
				state, detail = "FAILED", "源媒体已变化，请重新选择歌曲"
			} else {
				status, e := s.audioStatus(ctx, songID)
				if e != nil {
					state, detail = "FAILED", "歌曲状态读取失败"
				} else {
					category = audioCategory(status, spec)
					if oneOf(category, "UNPROBED", "ELIGIBLE") {
						state = "PENDING"
					}
				}
			}
			items = append(items, map[string]any{"batch": id, "song": songID, "fingerprint": str(summary, "fingerprint"), "title": str(summary, "title"), "category": category, "state": state, "error": detail, "now": now()})
		}
		continued := false
		err := s.transaction(ctx, "AUDIO_BATCH", id, func(tx *sqlx.Tx) error {
			r, e := s.exec(ctx, tx, "batch-lock-preparing", map[string]any{"id": id, "now": now()})
			if e != nil {
				return e
			}
			count, e := r.RowsAffected()
			if e != nil {
				return e
			}
			if count == 0 {
				return nil
			}
			continued = true
			for _, item := range items {
				if _, e = s.exec(ctx, tx, "batch-insert-item", item); e != nil {
					return e
				}
			}
			_, e = s.exec(ctx, tx, "batch-prepared", map[string]any{"id": id, "amount": len(items), "now": now()})
			return e
		})
		if err != nil {
			_ = s.transaction(context.WithoutCancel(ctx), "AUDIO_BATCH", id, func(tx *sqlx.Tx) error {
				_, e := s.exec(context.WithoutCancel(ctx), tx, "batch-preparation-failed", map[string]any{"id": id, "error": "准备任务被中断，请重新预览", "now": now()})
				return e
			})
			return
		}
		if !continued {
			return
		}
	}
	_ = s.transaction(ctx, "AUDIO_BATCH", id, func(tx *sqlx.Tx) error {
		_, err := s.exec(ctx, tx, "batch-ready", map[string]any{"id": id, "now": now()})
		return err
	})
}
func (s *Service) startBatch(ctx context.Context, id string) (map[string]any, error) {
	row, err := s.requireBatch(ctx, id)
	if err != nil {
		return nil, err
	}
	if str(row, "state") == "PREPARING" {
		return nil, fail(409, "批次仍在准备中，请稍后提交")
	}
	if str(row, "state") != "PREVIEW" {
		return s.batchDetail(ctx, id, 0, 50)
	}
	c, err := s.connection(ctx, "")
	if err != nil {
		return nil, err
	}
	node, err := s.call(ctx, c, "/internal/v1/audio-roles/models", "GET", nil)
	if err != nil {
		return nil, err
	}
	spec := storedRecipe(row)
	if !spec.ready(node, str(row, "backend")) {
		return nil, fail(422, "请先在性能节点配置中安装模型并完成试跑")
	}
	if spec.legacy() && node["resourceReady"] == false {
		return nil, fail(422, "节点当前内存或磁盘空间不足")
	}
	err = s.transaction(ctx, "AUDIO_BATCH", id, func(tx *sqlx.Tx) error {
		for _, name := range []string{"batch-start", "batch-finish"} {
			if _, e := s.exec(ctx, tx, name, map[string]any{"id": id, "now": now()}); e != nil {
				return e
			}
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	return s.batchDetail(ctx, id, 0, 50)
}
func (s *Service) updateBatchItem(ctx context.Context, item map[string]any, state, job, detail string) error {
	if len(detail) > 200 {
		detail = "任务失败，请检查计算节点"
	}
	return s.transaction(ctx, "AUDIO_BATCH", str(item, "batch_id"), func(tx *sqlx.Tx) error {
		var reference any
		if job != "" {
			reference = job
		}
		_, err := s.exec(ctx, tx, "batch-update-item", map[string]any{"batch": str(item, "batch_id"), "song": str(item, "song_id"), "state": state, "job": reference, "error": detail, "now": now()})
		return err
	})
}
func (s *Service) cancelBatchMember(ctx context.Context, item map[string]any) error {
	job := str(item, "job_id")
	if str(item, "state") == "PROCESSING" && job != "" {
		execution, err := s.optional(ctx, s.db, "audio-job", map[string]any{"id": job})
		if err != nil {
			return err
		}
		if str(execution, "state") == "SUCCEEDED" {
			return s.updateBatchItem(ctx, item, "SUCCEEDED", job, "")
		}
		if err = s.CancelAudio(ctx, str(item, "song_id"), job); err != nil {
			return err
		}
	}
	return s.updateBatchItem(ctx, item, "CANCELLED", job, "")
}
func (s *Service) cancelBatch(ctx context.Context, id string) (map[string]any, error) {
	row, err := s.requireBatch(ctx, id)
	if err != nil {
		return nil, err
	}
	err = s.transaction(ctx, "AUDIO_BATCH", id, func(tx *sqlx.Tx) error {
		for _, name := range []string{"batch-cancel", "batch-skip-cancelled"} {
			if _, e := s.exec(ctx, tx, name, map[string]any{"id": id, "now": now()}); e != nil {
				return e
			}
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	if !oneOf(str(row, "state"), "PREPARING", "PREVIEW") {
		for offset := 0; ; offset += 100 {
			items, e := s.maps(ctx, s.db, "batch-items", map[string]any{"id": id, "limit": 100, "offset": offset})
			if e != nil {
				return nil, e
			}
			if len(items) == 0 {
				break
			}
			for _, item := range items {
				if oneOf(str(item, "state"), "PROBING", "PROCESSING") {
					_ = s.cancelBatchMember(ctx, item)
				}
			}
		}
	}
	return s.batchDetail(ctx, id, 0, 50)
}
func (s *Service) cancelBatchItem(ctx context.Context, id, song string) error {
	if _, err := s.requireBatch(ctx, id); err != nil {
		return err
	}
	item, err := s.optional(ctx, s.db, "batch-item", map[string]any{"id": id, "song": song})
	if err != nil {
		return err
	}
	if item == nil {
		return fail(404, "批次中没有此歌曲")
	}
	if err = s.cancelBatchMember(ctx, item); err != nil {
		return err
	}
	return s.transaction(ctx, "AUDIO_BATCH", id, func(tx *sqlx.Tx) error {
		_, err := s.exec(ctx, tx, "batch-finish", map[string]any{"id": id, "now": now()})
		return err
	})
}
func (s *Service) retryBatch(ctx context.Context, id, backend string) (map[string]any, error) {
	row, err := s.requireBatch(ctx, id)
	if err != nil {
		return nil, err
	}
	failed, err := s.maps(ctx, s.db, "batch-failed-songs", map[string]any{"id": id})
	if err != nil {
		return nil, err
	}
	input := storedRecipe(row).view()
	ids := []any{}
	for _, item := range failed {
		ids = append(ids, str(item, "song_id"))
	}
	input["ids"], input["backend"] = ids, backend
	return s.previewBatch(ctx, input)
}
func (s *Service) tickBatches(ctx context.Context) {
	pending, err := s.maps(ctx, s.db, "batch-pending", nil)
	if err != nil {
		return
	}
	touched := map[string]bool{}
	for _, item := range pending {
		if ctx.Err() != nil {
			return
		}
		id, song, job, state := str(item, "batch_id"), str(item, "song_id"), str(item, "job_id"), str(item, "state")
		touched[id] = true
		if str(item, "batch_state") == "CANCELLED" {
			_ = s.cancelBatchMember(ctx, item)
			continue
		}
		current, e := s.song(ctx, s.db, song)
		if e != nil {
			_ = s.updateBatchItem(ctx, item, "FAILED", job, "歌曲已删除")
			continue
		}
		if contentFingerprint(current) != str(item, "fingerprint") {
			if state == "PROCESSING" && job != "" {
				_ = s.CancelAudio(ctx, song, job)
			}
			_ = s.updateBatchItem(ctx, item, "FAILED", job, "源媒体已变化，请重新选择歌曲")
			continue
		}
		if job != "" {
			execution, e := s.optional(ctx, s.db, "audio-job", map[string]any{"id": job})
			if e != nil {
				continue
			}
			if execution == nil {
				_ = s.updateBatchItem(ctx, item, "FAILED", job, "歌曲任务已丢失")
				continue
			}
			phase := str(execution, "state")
			if oneOf(phase, "FAILED", "CANCELLED") {
				_ = s.updateBatchItem(ctx, item, phase, job, str(execution, "error"))
				continue
			}
			if phase != "SUCCEEDED" {
				continue
			}
			if state == "PROCESSING" {
				_ = s.updateBatchItem(ctx, item, "SUCCEEDED", job, "")
				continue
			}
		}
		status, e := s.audioStatus(ctx, song)
		if e != nil {
			continue
		}
		spec := storedRecipe(item)
		category := audioCategory(status, spec)
		if oneOf(category, "READY", "PROCESSING", "INAPPLICABLE") {
			detail := map[string]string{"READY": "已有伴奏", "PROCESSING": "已有其他分离任务", "INAPPLICABLE": "非单音轨，不生成独立伴奏"}[category]
			_ = s.updateBatchItem(ctx, item, "SKIPPED", "", detail)
			continue
		}
		kind, phase, backend := "SEPARATE", "PROCESSING", str(item, "backend")
		options := spec.view()
		if category == "UNPROBED" {
			kind, phase, backend = "PROBE", "PROBING", ""
			options = nil
		}
		result, e := s.submitAudioOrigins(ctx, song, kind, backend, options, []string{background.ID("AUDIO_BATCH", id)})
		if e != nil {
			var f *failure
			if errors.As(e, &f) && f.status == 404 {
				_ = s.updateBatchItem(ctx, item, "FAILED", job, "歌曲已删除")
			} else {
				_ = s.updateBatchItem(ctx, item, state, job, "等待计算节点或运行环境")
			}
			continue
		}
		_ = s.updateBatchItem(ctx, item, phase, str(result, "id"), "")
	}
	for id := range touched {
		_ = s.transaction(ctx, "AUDIO_BATCH", id, func(tx *sqlx.Tx) error {
			_, err := s.exec(ctx, tx, "batch-finish", map[string]any{"id": id, "now": now()})
			return err
		})
	}
}

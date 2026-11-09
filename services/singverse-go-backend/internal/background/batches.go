package background

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"

	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

func (s *Service) classify(ctx context.Context, tx store.DBTX, id string, historical bool) error {
	r, e := s.row(ctx, tx, "find", doc{"id": id})
	if e != nil || len(r) == 0 || text(r, "batch_kind") != "UNCLASSIFIED" {
		return e
	}
	sealed := 1
	if pipeline(r) && !historical {
		sealed = 0
	}
	if e = s.exec(ctx, tx, "initializeBatch", doc{"id": id, "kind": "OPERATION", "sealed": sealed}); e != nil {
		return e
	}
	if parent := text(r, "parent_id"); parent != "" {
		return s.link(ctx, tx, parent, id, false)
	}
	kind := text(r, "type")
	if historical && (kind == "AUDIO_JOB" || kind == "TRANSCODE") {
		source, e := s.row(ctx, tx, "sourceLabel", doc{"sourceId": r["source_id"]})
		if e != nil {
			return e
		}
		label := "音轨任务"
		if kind == "TRANSCODE" {
			label = "离线转码"
		}
		parent, e := s.synthetic(ctx, tx, kind, "legacy:"+text(r, "source_id")+":"+kind, "未记录原始批次 · "+label, text(r, "source_id"), text(source, "name"), "LEGACY", number(r, "created_at"))
		if e != nil {
			return e
		}
		if e = s.link(ctx, tx, parent, id, true); e != nil {
			return e
		}
		return s.aggregate(ctx, tx, parent)
	}
	return nil
}
func base(id, kind, business string) doc {
	clock := now()
	return doc{"id": id, "type": kind, "business_id": business, "parent_id": nil, "name": "", "subject_name": "", "source_id": nil, "song_id": nil, "status": "COMPLETED", "business_status": "COMPLETED", "stage": "COMPLETED", "completed": nil, "total": nil, "progress_unit": "ITEMS", "statistics_json": `{"statistics":{},"execution":{}}`, "error": "", "created_at": clock, "updated_at": clock, "started_at": nil, "finished_at": nil, "expires_at": nil, "items_available": 0, "time_incomplete": 0, "fingerprint": id}
}
func (s *Service) synthetic(ctx context.Context, tx store.DBTX, kind, business, name, source, subject, batchKind string, created int64) (string, error) {
	id := ID(kind, business)
	previous, e := s.row(ctx, tx, "find", doc{"id": id})
	if e != nil || len(previous) > 0 {
		return id, e
	}
	r := base(id, kind, business)
	r["name"] = name
	if subject != "" {
		r["name"] = limit(name+" · "+subject, 512)
	}
	r["subject_name"] = limit(subject, 512)
	if source != "" {
		r["source_id"] = source
	}
	r["created_at"] = created
	r["stage"] = "DISCOVERING"
	sealed := 0
	if batchKind == "LEGACY" {
		r["stage"] = "LEGACY"
		r["time_incomplete"] = 1
		sealed = 1
	}
	if e = s.exec(ctx, tx, "insertProjected", r); e != nil {
		return "", e
	}
	e = s.exec(ctx, tx, "initializeBatch", doc{"id": id, "kind": batchKind, "sealed": sealed})
	return id, e
}
func (s *Service) link(ctx context.Context, tx store.DBTX, batch, task string, newOwner bool) error {
	if batch == task {
		return nil
	}
	v := doc{"batchId": batch, "taskId": task, "now": now()}
	if e := s.exec(ctx, tx, "linkTask", v); e != nil {
		return e
	}
	if newOwner {
		return s.exec(ctx, tx, "assignTask", v)
	}
	return nil
}
func (s *Service) changed(ctx context.Context, tx store.DBTX, id string) error {
	r, e := s.row(ctx, tx, "find", doc{"id": id})
	if e != nil || len(r) == 0 {
		return e
	}
	owners, e := s.rows(ctx, tx, "relationOwners", doc{"id": id})
	if e != nil {
		return e
	}
	unique := map[string]bool{}
	for _, o := range owners {
		unique[text(o, "id")] = true
	}
	if text(r, "type") == "AUDIO_JOB" && !active(text(r, "status")) {
		source, e := s.row(ctx, tx, "source", doc{"type": "AUDIO_JOB", "businessId": r["business_id"]})
		if e != nil {
			return e
		}
		scopeOwners, e := s.rows(ctx, tx, "scopeOwners", doc{"jobId": r["business_id"]})
		if e != nil {
			return e
		}
		for _, o := range scopeOwners {
			unique[text(o, "id")] = true
		}
		state, reason, uncertain := "INTERRUPTED", "原音轨任务已移除", 0
		if len(source) > 0 {
			state = text(r, "status")
			reason = text(r, "error")
			if state == "COMPLETED" {
				state = "DONE"
				if text(source, "kind") == "PROBE" {
					state = "PENDING"
					reason = "等待快速识别阶段判断"
				}
			}
			if text(read(source["result_json"]), "state") == "UNKNOWN" {
				uncertain = 1
			}
		}
		if e = s.exec(ctx, tx, "advanceScope", doc{"jobId": r["business_id"], "state": state, "reason": reason, "uncertain": uncertain, "now": now()}); e != nil {
			return e
		}
	}
	if number(r, "is_batch") != 0 {
		if e = s.aggregate(ctx, tx, id); e != nil {
			return e
		}
	}
	for owner := range unique {
		if owner != id {
			if e = s.aggregate(ctx, tx, owner); e != nil {
				return e
			}
		}
	}
	return nil
}
func (s *Service) aggregate(ctx context.Context, tx store.DBTX, id string) error {
	r, e := s.row(ctx, tx, "find", doc{"id": id})
	if e != nil || len(r) == 0 || number(r, "is_batch") == 0 {
		return e
	}
	kind, own := text(r, "batch_kind"), text(r, "status")
	chain, legacy := pipeline(r), kind == "LEGACY"
	counts := doc{}
	for _, k := range []string{"total", "completed", "queued", "running", "failed", "cancelled", "interrupted", "skipped", "review", "uncertain", "pendingDiscovery"} {
		counts[k] = int64(0)
	}
	states := doc{}
	last, lastExpiry := timestamp(r["finished_at"]), any(nil)
	missingFinished := false
	if chain || legacy {
		groups, e := s.rows(ctx, tx, "relationCounts", doc{"id": id})
		if e != nil {
			return e
		}
		for _, g := range groups {
			amount := number(g, "amount")
			states[text(g, "status")] = amount
			counts["total"] = number(counts, "total") + amount
			counts["uncertain"] = number(counts, "uncertain") + number(g, "uncertain")
			if at := timestamp(g["last_finished"]); at != nil && (last == nil || stamp(at) > stamp(last)) {
				last = at
			}
			if at := timestamp(g["last_expiry"]); at != nil && (lastExpiry == nil || stamp(at) > stamp(lastExpiry)) {
				lastExpiry = at
			}
			missingFinished = missingFinished || number(g, "missing_finished") > 0
		}
		counts["completed"] = number(states, "COMPLETED")
		counts["queued"] = number(states, "QUEUED")
		counts["running"] = number(states, "RUNNING")
		counts["failed"] = number(states, "FAILED") + number(states, "EXPIRED") + number(states, "BLOCKED")
		counts["cancelled"] = number(states, "CANCELLED")
		counts["interrupted"] = number(states, "INTERRUPTED")
		scopes, e := s.rows(ctx, tx, "scopeCounts", doc{"id": id})
		if e != nil {
			return e
		}
		for _, scope := range scopes {
			state, amount := text(scope, "state"), number(scope, "amount")
			switch state {
			case "PENDING":
				counts["pendingDiscovery"] = number(counts, "pendingDiscovery") + amount
			case "SKIPPED":
				counts["skipped"] = number(counts, "skipped") + amount
			case "FAILED":
				counts["failed"] = max(number(counts, "failed"), amount)
			case "INTERRUPTED":
				counts["interrupted"] = max(number(counts, "interrupted"), amount)
			case "PROBING", "FAST":
				if number(counts, "running")+number(counts, "queued") == 0 {
					counts["pendingDiscovery"] = number(counts, "pendingDiscovery") + amount
				}
			}
		}
		counts["queued"] = number(counts, "queued") + number(counts, "pendingDiscovery")
	} else {
		detail := read(r["statistics_json"])
		statistics := object(detail, "statistics")
		native := object(statistics, "counts")
		if number(r, "items_available") != 0 && len(native) > 0 {
			counts["total"] = number(r, "total")
			counts["queued"] = number(statistics, "pending")
			counts["running"] = number(statistics, "processing")
			for _, k := range []string{"failed", "review", "skipped"} {
				counts[k] = number(statistics, k)
			}
			counts["cancelled"] = number(native, "CANCELLED")
			counts["interrupted"] = number(native, "INTERRUPTED")
			counts["completed"] = max(int64(0), number(r, "completed")-number(counts, "failed")-number(counts, "cancelled")-number(counts, "interrupted"))
		} else {
			counts["total"] = int64(1)
			key := ""
			switch own {
			case "RUNNING":
				key = "running"
			case "QUEUED":
				key = "queued"
			case "COMPLETED":
				key = "completed"
			case "FAILED", "BLOCKED", "EXPIRED":
				key = "failed"
			case "CANCELLED":
				key = "cancelled"
			case "INTERRUPTED":
				key = "interrupted"
			}
			if key != "" {
				counts[key] = int64(1)
			}
			if text(r, "type") == "CACHE_CLEANUP" {
				removed, skipped, failed := number(statistics, "removed"), number(statistics, "skipped"), number(statistics, "failed")
				counts["total"] = removed + skipped + failed
				counts["completed"] = removed + skipped
				counts["skipped"] = skipped
				counts["failed"] = failed
			}
			if text(object(detail, "execution"), "resultState") == "UNKNOWN" {
				counts["uncertain"] = int64(1)
			}
		}
	}
	status := own
	if !active(own) && ((own == "COMPLETED" && number(counts, "failed") > 0) || ((own == "FAILED" || own == "CANCELLED" || own == "INTERRUPTED") && number(counts, "completed") > 0)) {
		status = "PARTIAL"
	}
	sealed := number(r, "scope_sealed") != 0
	if chain || legacy {
		switch {
		case number(counts, "running") > 0 || own == "RUNNING":
			status = "RUNNING"
		case states["CANCELLING"] != nil || own == "CANCELLING":
			status = "CANCELLING"
		case number(counts, "queued") > 0 || !sealed || own == "QUEUED":
			status = "QUEUED"
		case states["PAUSED"] != nil || own == "PAUSED":
			status = "PAUSED"
		case states["AWAITING_CONFIRMATION"] != nil || own == "AWAITING_CONFIRMATION":
			status = "AWAITING_CONFIRMATION"
		default:
			outcomes := map[string]bool{}
			if own != "COMPLETED" {
				outcomes[own] = true
			}
			for key, state := range map[string]string{"failed": "FAILED", "cancelled": "CANCELLED", "interrupted": "INTERRUPTED"} {
				if number(counts, key) > 0 {
					outcomes[state] = true
				}
			}
			status = "COMPLETED"
			if len(outcomes) > 0 {
				status = "PARTIAL"
				if len(outcomes) == 1 && number(counts, "completed") == 0 && number(counts, "skipped") == 0 {
					for state := range outcomes {
						status = state
					}
				}
			}
		}
	}
	stage := text(r, "stage")
	if chain && !active(own) && active(status) {
		stage = "WAITING_RECOGNITION"
		if number(counts, "running") > 0 {
			stage = "RECOGNIZING"
		}
	}
	if legacy {
		stage = "LEGACY"
	}
	var finished, expiry any
	if !active(status) {
		if legacy {
			if !missingFinished {
				finished = last
			}
		} else {
			finished = timestamp(r["batch_finished_at"])
		}
		if finished == nil && !legacy {
			finished = last
			if active(text(r, "batch_status")) {
				finished = now()
				if last != nil {
					finished = max(stamp(last), now())
				}
			}
		}
		if finished != nil {
			expiry = stamp(finished) + retention
		} else if legacy && lastExpiry != nil {
			expiry = lastExpiry
		} else if r["expires_at"] != nil {
			expiry = r["expires_at"]
		} else {
			expiry = number(r, "updated_at") + retention
		}
	}
	encoded := encode(counts)
	if status != text(r, "batch_status") || stage != text(r, "batch_stage") || encoded != text(r, "batch_counts_json") || finished != timestamp(r["batch_finished_at"]) || expiry != timestamp(r["batch_expires_at"]) {
		return s.exec(ctx, tx, "updateBatch", doc{"id": id, "batch_status": status, "batch_stage": stage, "batch_counts_json": encoded, "batch_finished_at": finished, "batch_expires_at": expiry, "batch_updated_at": now()})
	}
	return nil
}

// RegisterScanScope runs in the index publication transaction before discovery.
func (s *Service) RegisterScanScope(ctx context.Context, tx store.DBTX, batch, source string) error {
	if batch == "" {
		return nil
	}
	root, e := s.row(ctx, tx, "find", doc{"id": batch})
	if e != nil {
		return e
	}
	var scan any
	if text(root, "type") == "WEBDAV_SCAN" {
		scan = root["business_id"]
	}
	cursor := ""
	for {
		rows, e := s.rows(ctx, tx, "scopeSongs", doc{"sourceId": source, "cursor": cursor, "scanBusiness": scan})
		if e != nil {
			return e
		}
		if len(rows) == 0 {
			break
		}
		for _, r := range rows {
			size, modified := "null", "null"
			if r["file_size"] != nil {
				size = fmt.Sprint(r["file_size"])
			}
			if r["modified_at"] != nil {
				modified = fmt.Sprint(r["modified_at"])
			}
			fingerprint := sha(text(r, "id") + ":" + size + ":" + modified)
			if e = s.exec(ctx, tx, "admitScope", doc{"batchId": batch, "songId": r["id"], "fingerprint": fingerprint, "now": now()}); e != nil {
				return e
			}
		}
		cursor = text(rows[len(rows)-1], "id")
	}
	if e = s.exec(ctx, tx, "sealScope", doc{"id": batch}); e != nil {
		return e
	}
	return s.aggregate(ctx, tx, batch)
}
func sha(v string) string { b := sha256.Sum256([]byte(v)); return hex.EncodeToString(b[:]) }

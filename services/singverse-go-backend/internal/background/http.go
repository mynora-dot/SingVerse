package background

import (
	"context"
	"errors"
	"math"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"time"
	"unicode"

	"github.com/gin-gonic/gin"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/httpx"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/search"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

type problem struct {
	status int
	detail string
}

func (p *problem) Error() string { return p.detail }
func bad(detail string) error    { return &problem{400, detail} }
func (s *Service) report(c *gin.Context, e error) {
	var p *problem
	if errors.As(e, &p) {
		httpx.Error(c, p.status, "about:blank", p.detail)
		return
	}
	s.log.ErrorContext(c.Request.Context(), "background task view failed", "route", c.FullPath())
	httpx.Error(c, 500, "about:blank", "任务暂时无法读取")
}
func validType(v string) bool {
	if v == "" || v == "LOCAL_SCAN" || v == "AUDIO_DISCOVERY" {
		return true
	}
	for _, t := range types {
		if v == t {
			return true
		}
	}
	return false
}
func validStatus(v string) bool {
	return v == "" || active(v) || v == "COMPLETED" || v == "PARTIAL" || v == "FAILED" || v == "CANCELLED" || v == "INTERRUPTED" || v == "BLOCKED" || v == "EXPIRED"
}
func blank(v string) any {
	if strings.TrimSpace(v) == "" {
		return nil
	}
	return v
}
func iso(v any) any {
	at := timestamp(v)
	if at == nil {
		return nil
	}
	return time.UnixMilli(stamp(at)).UTC().Format(time.RFC3339Nano)
}
func observed() string { return time.Now().UTC().Format(time.RFC3339Nano) }
func paging(c *gin.Context) (doc, error) {
	page, e := strconv.Atoi(c.DefaultQuery("page", "0"))
	if e != nil {
		return nil, bad("分页参数无效")
	}
	size, e := strconv.Atoi(c.DefaultQuery("size", "20"))
	if e != nil || page < 0 || page > 1000000 || size < 1 || size > 100 {
		return nil, bad("分页参数无效")
	}
	return doc{"page": page, "size": size, "offset": page * size}, nil
}
func (s *Service) Register(g *gin.RouterGroup) {
	g.GET("/background-tasks", s.listHTTP)
	g.GET("/background-tasks/:id", s.detailHTTP)
	g.GET("/background-tasks/:id/children", s.childrenHTTP)
	g.GET("/background-tasks/:id/items", s.itemsHTTP)
}
func (s *Service) listHTTP(c *gin.Context) {
	filter, e := paging(c)
	if e != nil {
		s.report(c, e)
		return
	}
	view, kind, status, q := c.DefaultQuery("view", "ACTIVE"), c.Query("type"), c.Query("status"), c.Query("q")
	if view != "ACTIVE" && view != "HISTORY" && view != "ALL" {
		s.report(c, bad("任务视图无效"))
		return
	}
	if !validType(kind) || !validStatus(status) || search.UTF16Length(q) > 200 || strings.ContainsFunc(q, unicode.IsControl) {
		s.report(c, bad("任务筛选参数无效"))
		return
	}
	var pattern any
	if strings.TrimSpace(q) != "" {
		pattern = "%" + strings.NewReplacer("!", "!!", "%", "!%", "_", "!_").Replace(strings.TrimSpace(q)) + "%"
	}
	filter["view"], filter["type"], filter["status"], filter["pattern"], filter["now"] = view, blank(kind), blank(status), pattern, now()
	items := []doc{}
	var count int64
	e = store.WithinTx(c.Request.Context(), s.db, func(tx store.DBTX) error {
		rows, e := s.rows(c.Request.Context(), tx, "list", filter)
		if e != nil {
			return e
		}
		for _, r := range rows {
			v, e := s.view(c.Request.Context(), tx, r, false)
			if e != nil {
				return e
			}
			items = append(items, v)
		}
		q, a, e := s.statement("count", filter)
		if e != nil {
			return e
		}
		return tx.QueryRowContext(c.Request.Context(), q, a...).Scan(&count)
	})
	if e != nil {
		s.report(c, e)
		return
	}
	c.JSON(200, doc{"items": items, "page": filter["page"], "size": filter["size"], "totalElements": count, "observedAt": observed()})
}
func (s *Service) require(ctx context.Context, tx store.DBTX, id string) (doc, error) {
	r, e := s.row(ctx, tx, "find", doc{"id": id})
	if e != nil {
		return nil, e
	}
	if len(r) == 0 {
		return nil, &problem{404, "任务记录不存在或已超过保留期限"}
	}
	expiry := r["expires_at"]
	if number(r, "is_batch") != 0 {
		expiry = r["batch_expires_at"]
	}
	if expiry != nil && stamp(expiry) <= now() {
		parents, e := s.rows(ctx, tx, "memberships", doc{"id": id, "now": now()})
		if e != nil {
			return nil, e
		}
		if len(parents) == 0 {
			return nil, &problem{404, "任务记录不存在或已超过保留期限"}
		}
	}
	return r, nil
}
func (s *Service) detailHTTP(c *gin.Context) {
	var result doc
	e := store.WithinTx(c.Request.Context(), s.db, func(tx store.DBTX) error {
		r, e := s.require(c.Request.Context(), tx, c.Param("id"))
		if e != nil {
			return e
		}
		result, e = s.view(c.Request.Context(), tx, r, false)
		return e
	})
	if e != nil {
		s.report(c, e)
		return
	}
	c.JSON(200, result)
}

var itemStatus = regexp.MustCompile(`^[A-Z_]{0,30}$`)

func (s *Service) itemsHTTP(c *gin.Context) {
	f, e := paging(c)
	if e != nil {
		s.report(c, e)
		return
	}
	status := c.Query("status")
	if !itemStatus.MatchString(status) {
		s.report(c, bad("明细状态无效"))
		return
	}
	f["taskId"], f["status"] = c.Param("id"), blank(status)
	items := []doc{}
	counts := doc{}
	var count int64
	e = store.WithinTx(c.Request.Context(), s.db, func(tx store.DBTX) error {
		if _, e := s.require(c.Request.Context(), tx, c.Param("id")); e != nil {
			return e
		}
		groups, e := s.rows(c.Request.Context(), tx, "itemStates", f)
		if e != nil {
			return e
		}
		for _, g := range groups {
			counts[text(g, "status")] = number(g, "amount")
		}
		rows, e := s.rows(c.Request.Context(), tx, "items", f)
		if e != nil {
			return e
		}
		for _, r := range rows {
			items = append(items, doc{"id": r["id"], "songId": r["song_id"], "title": r["title"], "status": r["status"], "message": r["message"], "execution": read(r["execution_json"])})
		}
		q, a, e := s.statement("itemCount", f)
		if e != nil {
			return e
		}
		return tx.QueryRowContext(c.Request.Context(), q, a...).Scan(&count)
	})
	if e != nil {
		s.report(c, e)
		return
	}
	c.JSON(200, doc{"items": items, "page": f["page"], "size": f["size"], "totalElements": count, "counts": counts})
}
func (s *Service) childrenHTTP(c *gin.Context) {
	f, e := paging(c)
	if e != nil {
		s.report(c, e)
		return
	}
	kind, status := c.Query("type"), c.Query("status")
	if !validType(kind) || !validStatus(status) {
		s.report(c, bad("任务筛选参数无效"))
		return
	}
	f["taskId"], f["type"], f["status"] = c.Param("id"), blank(kind), blank(status)
	items, counts := []doc{}, doc{}
	var total int64
	e = store.WithinTx(c.Request.Context(), s.db, func(tx store.DBTX) error {
		root, e := s.require(c.Request.Context(), tx, c.Param("id"))
		if e != nil {
			return e
		}
		groups, e := s.rows(c.Request.Context(), tx, "childStates", f)
		if e != nil {
			return e
		}
		for _, g := range groups {
			counts[text(g, "status")] = number(g, "amount")
		}
		rows, e := s.rows(c.Request.Context(), tx, "children", f)
		if e != nil {
			return e
		}
		q, a, e := s.statement("childCount", f)
		if e != nil {
			return e
		}
		if e = tx.QueryRowContext(c.Request.Context(), q, a...).Scan(&total); e != nil {
			return e
		}
		if len(counts) == 0 && number(root, "items_available") == 0 && !pipeline(root) && text(root, "batch_kind") != "LEGACY" {
			counts[text(root, "status")] = int64(1)
			matches := (kind == "" || kind == text(root, "type")) && (status == "" || status == text(root, "status"))
			rows = []doc{}
			total = 0
			if matches {
				total = 1
				if number(f, "page") == 0 {
					rows = append(rows, root)
				}
			}
		}
		for _, r := range rows {
			v, e := s.view(c.Request.Context(), tx, r, true)
			if e != nil {
				return e
			}
			items = append(items, v)
		}
		return nil
	})
	if e != nil {
		s.report(c, e)
		return
	}
	c.JSON(200, doc{"items": items, "page": f["page"], "size": f["size"], "totalElements": total, "counts": counts, "observedAt": observed()})
}
func (s *Service) view(ctx context.Context, tx store.DBTX, r doc, operationOnly bool) (doc, error) {
	detail := read(r["statistics_json"])
	batch := !operationOnly && number(r, "is_batch") != 0
	chain := pipeline(r) || text(r, "batch_kind") == "LEGACY"
	var percent any
	if r["completed"] != nil && r["total"] != nil && number(r, "total") > 0 {
		percent = math.Min(100, math.Max(0, float64(number(r, "completed"))*100/float64(number(r, "total"))))
	}
	progress := doc{"completed": r["completed"], "total": r["total"], "percent": percent, "unit": r["progress_unit"]}
	original := doc{"status": r["status"], "businessStatus": r["business_status"], "stage": r["stage"], "progress": progress, "finishedAt": iso(r["finished_at"])}
	status, stage, finished := r["status"], r["stage"], r["finished_at"]
	if batch {
		if r["batch_status"] != nil {
			status = r["batch_status"]
		}
		if r["batch_stage"] != nil {
			stage = r["batch_stage"]
		}
		finished = r["batch_finished_at"]
	}
	if batch && chain {
		progress = doc{"completed": nil, "total": nil, "percent": nil, "unit": "ITEMS"}
	}
	parents, e := s.rows(ctx, tx, "memberships", doc{"id": r["id"], "now": now()})
	if e != nil {
		return nil, e
	}
	errorMessage, waiting := text(r, "error"), ""
	if batch && chain {
		reason, e := s.row(ctx, tx, "scopeReason", doc{"id": r["id"]})
		if e != nil {
			return nil, e
		}
		waiting = text(reason, "reason")
		if errorMessage == "" {
			reason, e = s.row(ctx, tx, "scopeFailure", doc{"id": r["id"]})
			if e != nil {
				return nil, e
			}
			errorMessage = text(reason, "reason")
		}
	}
	return doc{"id": r["id"], "type": r["type"], "businessId": r["business_id"], "name": r["name"], "isBatch": batch, "batchKind": r["batch_kind"], "scopeSealed": number(r, "scope_sealed") != 0, "parentId": r["parent_id"], "batches": parents, "childrenCounts": read(r["batch_counts_json"]), "detailMode": map[bool]string{true: "ITEMS", false: "CHILDREN"}[number(r, "items_available") != 0], "operation": original, "subjectName": r["subject_name"], "sourceId": r["source_id"], "songId": r["song_id"], "status": status, "businessStatus": r["business_status"], "stage": stage, "progress": progress, "statistics": object(detail, "statistics"), "execution": object(detail, "execution"), "error": errorMessage, "waitingReason": waiting, "createdAt": iso(r["created_at"]), "updatedAt": iso(max(number(r, "updated_at"), number(r, "batch_updated_at"))), "startedAt": iso(r["started_at"]), "finishedAt": iso(finished), "itemsAvailable": number(r, "items_available") != 0, "timeIncomplete": number(r, "time_incomplete") != 0, "relatedAvailable": number(r, "subject_available") != 0, "relatedPath": related(r), "observedAt": observed()}, nil
}
func related(r doc) string {
	if text(r, "batch_kind") == "LEGACY" {
		if text(r, "type") == "TRANSCODE" {
			return "/library/songs"
		}
		return "/library/audio-processing"
	}
	if text(r, "batch_kind") == "AUTOMATIC" {
		return "/library/audio-processing"
	}
	business, source, song := url.QueryEscape(text(r, "business_id")), url.QueryEscape(text(r, "source_id")), url.QueryEscape(text(r, "song_id"))
	switch text(r, "type") {
	case "METADATA":
		return "/library/songs/metadata?batch=" + business
	case "LX_DOWNLOAD":
		return "/library/downloads?tab=records&batch=" + business
	case "CLASSIFICATION":
		return "/library/classification?task=" + business
	case "AUDIO_BATCH":
		return "/library/audio-processing?tab=tasks&batch=" + business
	case "AUDIO_JOB":
		return "/library/audio-processing?song=" + song
	case "TRANSCODE":
		return "/library/songs?detailSongId=" + song
	case "CACHE_CLEANUP":
		return "/library/cache?job=" + business
	}
	return "/library/media?source=" + source
}

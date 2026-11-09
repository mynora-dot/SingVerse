package background

import (
	"context"

	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

type taskProjection struct {
	row         doc
	counts      doc
	statistics  doc
	diagnostics doc
}

func (s *Service) Project(ctx context.Context, tx store.DBTX, kind, businessID string) error {
	return s.refresh(ctx, tx, kind, businessID, false)
}

func (s *Service) refresh(ctx context.Context, tx store.DBTX, kind, businessID string, historical bool) error {
	if businessID == "" {
		return nil
	}
	taskID := ID(kind, businessID)
	key := doc{"type": kind, "businessId": businessID, "taskId": taskID}
	previous, err := s.row(ctx, tx, "find", doc{"id": taskID})
	if err != nil {
		return err
	}
	source, err := s.row(ctx, tx, "source", key)
	if err != nil {
		return err
	}
	clock := now()
	if len(source) == 0 {
		return s.expireMissingTask(ctx, tx, previous, taskID, clock)
	}
	if kind == "AUDIO_JOB" && number(source, "tracked") == 0 {
		return nil
	}
	times := sourceProjectionTimes(source, clock)
	if len(previous) == 0 && !sourceActive(text(source, "raw_status")) && times.anchor < clock-retention {
		return nil
	}

	projection := newTaskProjection(kind, businessID, source, previous)
	groups, err := s.rows(ctx, tx, "sourceCounts", key)
	if err != nil {
		return err
	}
	projection.applyCounts(kind, source, groups)
	if err = s.applyTaskDetails(ctx, tx, kind, source, projection); err != nil {
		return err
	}
	projection.row["statistics_json"] = encode(doc{"statistics": projection.statistics, "execution": projection.diagnostics})
	projection.applyTimes(source, previous, times, clock, historical)
	if err = s.persistProjection(ctx, tx, projection.row, previous, clock); err != nil {
		return err
	}
	unclassified := historical || (len(previous) > 0 && text(previous, "batch_kind") == "UNCLASSIFIED")
	if err = s.classify(ctx, tx, taskID, unclassified); err != nil {
		return err
	}
	if kind == "WEBDAV_SCAN" && !active(text(projection.row, "status")) && text(source, "raw_status") != "SUCCEEDED" {
		if err = s.exec(ctx, tx, "sealScope", doc{"id": taskID}); err != nil {
			return err
		}
	}
	if err = s.projectSourceItems(ctx, tx, key, historical); err != nil {
		return err
	}
	return s.changed(ctx, tx, taskID)
}

func newTaskProjection(kind, businessID string, source, previous doc) *taskProjection {
	subject := text(source, "subject_name")
	if subject == "" {
		subject = text(read(source["song_json"]), "title")
	}
	if subject == "" {
		subject = text(previous, "subject_name")
	}
	parent := previous["parent_id"]
	if len(previous) == 0 && text(source, "parent_business_id") != "" {
		parent = ID("AUDIO_BATCH", text(source, "parent_business_id"))
	}
	rawStatus := text(source, "raw_status")
	return &taskProjection{
		row: doc{
			"id": ID(kind, businessID), "type": kind, "business_id": businessID,
			"parent_id": parent, "source_id": source["source_id"], "song_id": source["song_id"],
			"subject_name":    limit(subject, 512),
			"business_status": rawStatus, "status": normalize(rawStatus), "stage": rawStatus,
			"completed": nil, "total": nil, "progress_unit": "ITEMS",
			"error": Message(text(source, "error")), "items_available": 0,
		},
		counts: doc{}, statistics: doc{}, diagnostics: doc{},
	}
}

func (s *Service) expireMissingTask(ctx context.Context, tx store.DBTX, previous doc, taskID string, clock int64) error {
	if len(previous) == 0 || !active(text(previous, "status")) {
		return nil
	}
	expired := doc{
		"status": "EXPIRED", "business_status": "EXPIRED", "stage": "EXPIRED",
		"error":      "原业务任务记录已过期或移除",
		"updated_at": clock, "finished_at": clock, "expires_at": clock + retention,
		"fingerprint": hash([]string{taskID, "EXPIRED"}),
	}
	for key, value := range expired {
		previous[key] = value
	}
	if err := s.exec(ctx, tx, "update", previous); err != nil {
		return err
	}
	if err := s.exec(ctx, tx, "sealScope", doc{"id": taskID}); err != nil {
		return err
	}
	return s.changed(ctx, tx, taskID)
}

func (s *Service) persistProjection(ctx context.Context, tx store.DBTX, row, previous doc, clock int64) error {
	comparable := doc{}
	for key, value := range row {
		switch key {
		case "updated_at", "finished_at", "expires_at", "started_at":
		default:
			comparable[key] = value
		}
	}
	row["fingerprint"] = hash(comparable)
	if row["fingerprint"] == previous["fingerprint"] {
		return nil
	}
	if len(previous) > 0 {
		row["updated_at"] = clock
	} else if err := s.exec(ctx, tx, "insertProjected", row); err != nil {
		return err
	}
	return s.exec(ctx, tx, "update", row)
}

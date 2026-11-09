package background

import (
	"context"

	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

func (s *Service) projectSourceItems(ctx context.Context, tx store.DBTX, key doc, historical bool) error {
	kind, taskID := text(key, "type"), text(key, "taskId")
	switch kind {
	case "METADATA", "CLASSIFICATION", "AUDIO_BATCH", "LX_DOWNLOAD":
	default:
		return nil
	}
	cursor := ""
	for {
		key["cursor"] = cursor
		items, err := s.rows(ctx, tx, "sourceItems", key)
		if err != nil {
			return err
		}
		if len(items) == 0 {
			return nil
		}
		for _, item := range items {
			if err = s.saveItem(ctx, tx, taskID, item); err != nil {
				return err
			}
			jobID := text(item, "job_id")
			if kind == "AUDIO_BATCH" && jobID != "" {
				if err = s.refresh(ctx, tx, "AUDIO_JOB", jobID, historical); err != nil {
					return err
				}
				if err = s.link(ctx, tx, taskID, ID("AUDIO_JOB", jobID), false); err != nil {
					return err
				}
			}
		}
		cursor = text(items[len(items)-1], "id")
	}
}

func (s *Service) saveItem(ctx context.Context, tx store.DBTX, taskID string, source doc) error {
	title := text(source, "title")
	if title == "" {
		title = text(read(source["input_json"]), "title")
	}
	if title == "" {
		title = text(read(source["track_json"]), "title")
	}
	diagnostics := execution(read(source["execution"]))
	if source["job_state"] != nil {
		diagnostics["jobState"] = limit(text(source, "job_state"), 30)
	}
	row := doc{
		"task_id": taskID, "id": text(source, "id"), "song_id": source["song_id"],
		"title": limit(title, 2048), "status": limit(text(source, "status"), 30),
		"message": Message(text(source, "message")), "execution_json": encode(diagnostics),
		"source_updated_at": text(source, "source_updated_at"), "execution_updated_at": number(source, "execution_updated_at"),
	}
	if err := s.exec(ctx, tx, "insertProjectedItem", row); err != nil {
		return err
	}
	return s.exec(ctx, tx, "updateItem", row)
}

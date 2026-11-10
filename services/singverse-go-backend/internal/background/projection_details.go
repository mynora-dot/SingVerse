package background

import (
	"context"
	"errors"
	"fmt"

	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

func (s *Service) applyTaskDetails(ctx context.Context, tx store.DBTX, kind string, source doc, projection *taskProjection) error {
	rawStatus := text(source, "raw_status")
	label := ""
	switch kind {
	case "ARTIST_ARTWORK":
		label = "歌手封面获取"
		projection.row["progress_unit"] = "ARTISTS"
		projection.statistics["noMatch"] = number(projection.counts, "NO_MATCH")
		if rawStatus == "RUNNING" {
			projection.row["stage"] = "FETCHING_ARTWORK"
		}
	case "LX_DOWNLOAD":
		label = "歌曲下载"
		if rawStatus == "RUNNING" {
			projection.row["stage"] = "DOWNLOADING"
		}
		projection.statistics["applied"] = number(projection.counts, "SUCCESS")
		projection.statistics["interrupted"] = number(projection.counts, "INTERRUPTED")
		projection.statistics["cancelled"] = number(projection.counts, "CANCELLED")
	case "WEBDAV_SCAN":
		label = "WebDAV 扫描"
		projection.applyScanDetails(source)
	case "METADATA":
		label = "元数据整理"
		if rawStatus == "RUNNING" {
			projection.row["stage"] = "MATCHING"
		}
	case "CLASSIFICATION":
		label = "AI 分类"
		if rawStatus == "RUNNING" {
			projection.row["stage"] = "CLASSIFYING"
		}
	case "AUDIO_BATCH":
		label = "批量伴奏生成"
		projection.applyAudioBatchDetails(source)
	case "AUDIO_JOB", "TRANSCODE":
		label = projection.applyExecutionDetails(kind, source)
	case "CACHE_CLEANUP":
		label = "缓存清理"
		if err := s.applyCleanupDetails(ctx, tx, source, projection); err != nil {
			return err
		}
	case "SHARE_EXPORT":
		label = "分享包导出"
		projection.applyShareDetails(source)
	default:
		return errors.New("unknown background task type")
	}
	projection.row["name"] = label
	if subject := text(projection.row, "subject_name"); subject != "" {
		projection.row["name"] = limit(label+" · "+subject, 512)
	}
	return nil
}

func (p *taskProjection) applyScanDetails(source doc) {
	p.row["stage"] = text(source, "stage")
	if number(source, "cancel_requested") != 0 && sourceActive(text(source, "raw_status")) {
		p.row["status"] = "CANCELLING"
	}
	for _, key := range []string{"directories", "entries", "total_songs", "matched_songs", "review_songs", "skipped_files", "added_songs", "reused_songs", "missing_songs", "removed_songs"} {
		p.statistics[key] = number(source, key)
	}
}

func (p *taskProjection) applyAudioBatchDetails(source doc) {
	p.row["total"] = max(number(source, "selection_total"), number(p.row, "total"))
	p.row["progress_unit"] = "SONGS"
	p.statistics["prepared"] = number(source, "prepared_count")
	switch text(source, "raw_status") {
	case "PREPARING":
		p.row["completed"] = number(source, "prepared_count")
	case "PREVIEW":
		p.row["completed"] = 0
	case "QUEUED":
		if text(p.row, "status") == "RUNNING" {
			p.row["stage"] = "PROCESSING"
		}
	}
	p.row["items_available"] = 1
}

func (p *taskProjection) applyExecutionDetails(kind string, source doc) string {
	result := read(source["result_json"])
	p.diagnostics = execution(result)
	if stage := text(p.diagnostics, "stage"); stage != "" {
		p.row["stage"] = stage
	}
	if kind != "AUDIO_JOB" {
		return "离线转码"
	}
	if reason, ok := result["reason"].(string); ok {
		p.diagnostics["reason"] = Message(reason)
	}
	if state, ok := result["state"].(string); ok {
		p.diagnostics["resultState"] = limit(state, 50)
	}
	switch text(source, "kind") {
	case "SEPARATE":
		return "伴奏生成"
	case "ANALYZE":
		return "音轨深度识别"
	case "FAST":
		return "音轨快速识别"
	default:
		return "音轨探测"
	}
}

func (p *taskProjection) applyShareDetails(source doc) {
	p.statistics["exportedSongs"] = number(source, "processed_count")
	p.statistics["snapshotSongs"] = number(source, "total_count")
	p.statistics["parts"] = number(source, "part_count")
	p.row["completed"] = number(source, "processed_count")
	if number(source, "incremental") == 0 && text(source, "raw_status") != "WAITING" {
		p.row["total"] = number(source, "total_count")
	}
	p.row["progress_unit"] = "SONGS"
}

func (s *Service) applyCleanupDetails(ctx context.Context, tx store.DBTX, source doc, projection *taskProjection) error {
	projection.row["stage"] = text(source, "mode")
	result := read(source["result_json"])
	for _, key := range []string{"removed", "skipped", "failed", "freedBytes", "usageBeforeBytes", "usageAfterBytes", "targetBytes", "shortfallBytes"} {
		if result[key] != nil {
			projection.statistics[key] = max(int64(0), number(result, key))
		}
	}
	for _, key := range []string{"targetReached", "spaceInsufficient"} {
		if value, ok := result[key].(bool); ok {
			projection.statistics[key] = value
		}
	}
	reasons := doc{}
	for reason, count := range object(result, "skippedByReason") {
		if len(reasons) >= 30 {
			break
		}
		reasons[limit(Message(reason), 80)] = count
	}
	if len(reasons) > 0 {
		projection.statistics["skippedByReason"] = reasons
	}
	projection.row["completed"] = number(result, "removed") + number(result, "skipped") + number(result, "failed")
	details, _ := result["errors"].([]any)
	for index, raw := range details {
		if index >= 20 {
			break
		}
		item, _ := raw.(map[string]any)
		if err := s.saveItem(ctx, tx, text(projection.row, "id"), doc{
			"id": fmt.Sprint("error-", index), "title": text(item, "id"), "status": "FAILED",
			"message": text(item, "message"), "source_updated_at": fmt.Sprint(source["updated_at"]),
		}); err != nil {
			return err
		}
	}
	if len(details) > 0 {
		projection.row["items_available"] = 1
	}
	projection.statistics["errorDetailsLimited"] = number(result, "failed") > int64(len(details))
	return nil
}

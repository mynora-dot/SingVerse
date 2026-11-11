package catalog

import (
	"context"

	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

// ClassificationOverview summarizes classification tasks for the admin home page:
// the number of running/paused tasks, the total items awaiting review, and the
// newest task id. It reuses taskView with no item rows.
func (s *Service) ClassificationOverview(ctx context.Context) (map[string]any, error) {
	out := map[string]any{"activeTasks": int64(0), "reviewItems": int64(0), "latestTaskId": ""}
	e := store.WithinTx(ctx, s.db, func(tx store.DBTX) error {
		rows, e := query(ctx, tx, s.q["c_tasks"], 100, int64(0))
		if e != nil {
			return e
		}
		var active, review int64
		latest := ""
		for i, r := range rows {
			v, e := s.taskView(ctx, tx, r, 0, 0, "")
			if e != nil {
				return e
			}
			if i == 0 {
				latest = str(v["id"])
			}
			if status := str(v["status"]); status == "RUNNING" || status == "PAUSED" {
				active++
			}
			if counts, ok := v["counts"].(map[string]int64); ok {
				review += counts["REVIEW"]
			}
		}
		out["activeTasks"] = active
		out["reviewItems"] = review
		out["latestTaskId"] = latest
		return nil
	})
	return out, e
}

// PlaylistSummary returns the three most recently updated playlists with their
// song counts, reusing the playlist list projection.
func (s *Service) PlaylistSummary(ctx context.Context) ([]map[string]any, error) {
	result, e := s.playlistList(ctx, "", "UPDATED_DESC", 0, 3)
	if e != nil {
		return nil, e
	}
	out := []map[string]any{}
	page, _ := result.(map[string]any)
	items, _ := page["items"].([]map[string]any)
	for _, item := range items {
		out = append(out, map[string]any{"id": str(item["id"]), "name": str(item["name"]), "count": num(item["count"])})
	}
	return out, nil
}

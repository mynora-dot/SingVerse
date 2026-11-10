package metadata

import (
	"context"
	"github.com/gin-gonic/gin"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
	"math"
)

func (s *Service) settings(ctx context.Context, db store.DBTX, required bool) (Settings, error) {
	r, e := s.row(ctx, db, "meta-settings", nil)
	if e != nil {
		return Settings{}, e
	}
	v := Settings{Enabled: boolean(r["enabled"]), AutoApply: boolean(r["auto_apply"]), Threshold: float64(number(r["threshold"])), MinIntervalSeconds: ptr(int(number(r["min_interval_seconds"]))), MaxIntervalSeconds: ptr(int(number(r["max_interval_seconds"])))}
	switch f := r["threshold"].(type) {
	case float64:
		v.Threshold = f
	default:
		_ = decode(r["threshold"], &v.Threshold)
	}
	if e = decode(r["providers_json"], &v.Providers); e != nil {
		return v, e
	}
	if required && (!v.Enabled || len(v.Providers) == 0) {
		return v, conflict("请先在设置 → 音乐平台中启用并选择平台")
	}
	return v, nil
}
func (s *Service) getSettings(c *gin.Context) {
	v, e := s.settings(c.Request.Context(), s.db, false)
	if e != nil {
		s.fail(c, e)
		return
	}
	c.JSON(200, v)
}
func (s *Service) putSettings(c *gin.Context) {
	var in Settings
	if !s.decode(c, &in) {
		return
	}
	if in.Providers == nil || len(in.Providers) > len(providers) || math.IsNaN(in.Threshold) || math.IsInf(in.Threshold, 0) || in.Threshold < .8 || in.Threshold > 1 {
		s.fail(c, bad("平台选择无效，自动写入阈值需要在 0.8～1 之间"))
		return
	}
	seen := map[string]bool{}
	for _, p := range in.Providers {
		if !contains(providers, p) || seen[p] {
			s.fail(c, bad("平台选择无效"))
			return
		}
		seen[p] = true
	}
	if in.Enabled && len(in.Providers) == 0 {
		s.fail(c, bad("请至少选择一个元数据平台"))
		return
	}
	if in.MinIntervalSeconds == nil {
		in.MinIntervalSeconds = ptr(3)
	}
	if in.MaxIntervalSeconds == nil {
		in.MaxIntervalSeconds = ptr(8)
	}
	if *in.MinIntervalSeconds < 0 || *in.MaxIntervalSeconds > 300 || *in.MaxIntervalSeconds < *in.MinIntervalSeconds {
		s.fail(c, bad("批量间隔需要在 0～300 秒之间，最大值不能小于最小值"))
		return
	}
	ctx := c.Request.Context()
	e := s.transaction(ctx, func(tx store.DBTX) error {
		active, e := s.rows(ctx, tx, "meta-active", nil)
		if e != nil {
			return e
		}
		if _, e := s.exec(ctx, tx, "meta-pauseActive", map[string]any{"now": now()}); e != nil {
			return e
		}
		for _, row := range active {
			if e = s.project(ctx, tx, "METADATA", text(row["id"])); e != nil {
				return e
			}
		}
		_, e = s.exec(ctx, tx, "meta-saveSettings", map[string]any{"enabled": in.Enabled, "providers": encode(in.Providers), "autoApply": in.AutoApply, "threshold": in.Threshold, "minInterval": *in.MinIntervalSeconds, "maxInterval": *in.MaxIntervalSeconds, "now": now()})
		return e
	})
	if e != nil {
		s.fail(c, e)
		return
	}
	c.JSON(200, in)
}

package party

import (
	"context"
	"crypto/sha256"
	"embed"
	"encoding/hex"
	"fmt"

	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

//go:embed assets/home/*.jpg
var homeImages embed.FS

var homeThemes = []struct{ name, label string }{
	{"01-blue-stage", "蓝调舞台"},
	{"02-acoustic-evening", "暖光吉他"},
	{"03-moonlight-piano", "月光钢琴"},
	{"04-vinyl-memories", "黑胶时光"},
	{"05-neon-rhythm", "霓虹律动"},
	{"06-sunset-melody", "落日旋律"},
	{"07-jazz-night", "爵士之夜"},
	{"08-starlit-echo", "星空回响"},
}

// Seed only untouched settings. A later restart must preserve edits, uploads,
// disabled covers and even a deliberately emptied list.
func (s *Service) seedHomeBanners(ctx context.Context, tx store.DBTX) error {
	if _, err := tx.ExecContext(ctx, s.q["settings_lock"]); err != nil {
		return err
	}
	prior, err := s.settings(ctx, tx, false)
	if err != nil {
		return err
	}
	if prior.Revision != 0 || len(prior.Banners) != 0 {
		return nil
	}
	for i, theme := range homeThemes {
		data, err := homeImages.ReadFile("assets/home/" + theme.name + ".jpg")
		if err != nil {
			return fmt.Errorf("read built-in TV cover: %w", err)
		}
		sum := sha256.Sum256(data)
		if _, err = tx.ExecContext(ctx, s.q["banner_insert"], "builtin-"+theme.name, theme.label, i, hex.EncodeToString(sum[:]), data); err != nil {
			return err
		}
	}
	_, err = tx.ExecContext(ctx, s.q["settings_bump"])
	return err
}

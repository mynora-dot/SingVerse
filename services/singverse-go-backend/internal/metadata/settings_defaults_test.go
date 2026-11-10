package metadata

import (
	"testing"

	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/testutil"
)

func TestDefaultMusicPlatforms(t *testing.T) { exerciseDefaultMusicPlatforms(t, setup(t)) }

func TestMySQLDefaultMusicPlatforms(t *testing.T) {
	db, cfg := testutil.MySQLDatabase(t)
	exerciseDefaultMusicPlatforms(t, setupWithDatabase(t, db, "mysql", cfg))
}

func exerciseDefaultMusicPlatforms(t *testing.T, f *fixture) {
	t.Helper()
	settings := request(t, f.router, "GET", "/api/v1/metadata/settings", nil, 200)
	if !boolean(settings["enabled"]) || encode(stringsOf(settings["providers"])) != encode(providers) || boolean(settings["autoApply"]) || number(settings["minIntervalSeconds"]) != 3 || number(settings["maxIntervalSeconds"]) != 8 {
		t.Fatal("fresh install must enable all platforms without enabling automatic writes", settings)
	}
	input := Settings{Enabled: false, Providers: []string{"QQ"}, AutoApply: true, Threshold: .98, MinIntervalSeconds: ptr(0), MaxIntervalSeconds: ptr(20)}
	request(t, f.router, "PUT", "/api/v1/metadata/settings", input, 200)
	settings = request(t, f.router, "GET", "/api/v1/metadata/settings", nil, 200)
	if boolean(settings["enabled"]) || encode(stringsOf(settings["providers"])) != `["QQ"]` || !boolean(settings["autoApply"]) || number(settings["minIntervalSeconds"]) != 0 || number(settings["maxIntervalSeconds"]) != 20 {
		t.Fatal("reading saved preferences silently restored defaults", settings)
	}
}

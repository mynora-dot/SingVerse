package party

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"image"
	"strconv"
	"testing"

	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

func TestBuiltInHomeBanners(t *testing.T) {
	f := newPartyFixture(t)
	device, _ := f.device(t)
	settings := want(t, f.request(t, "GET", "/api/v1/admin/tv-settings", nil, "", true, false), 200)
	banners := array(settings, "banners")
	if len(banners) != 8 || !boolean(settings, "autoRotate") {
		t.Fatal(settings)
	}
	home := want(t, f.request(t, "GET", "/api/v1/tv/home", nil, device, false, false), 200)
	if len(array(object(home, "appearance"), "banners")) != 8 {
		t.Fatal(home)
	}
	for i, value := range banners {
		banner := value.(map[string]any)
		if str(banner, "label") != homeThemes[i].label || !boolean(banner, "enabled") {
			t.Fatal(banner)
		}
		got := f.request(t, "GET", "/api/v1/tv/home/banners/"+str(banner, "id")+"/image", nil, device, false, false)
		data, ok := got.body.([]byte)
		if got.status != 200 || !ok || got.header.Get("Content-Type") != "image/jpeg" {
			t.Fatal(got)
		}
		cfg, format, err := image.DecodeConfig(bytes.NewReader(data))
		if err != nil || format != "jpeg" || cfg.Width < 1280 || cfg.Width > 4096 || len(data) > 2000000 || abs(cfg.Width*9-cfg.Height*16) > 16 {
			t.Fatalf("invalid built-in image %s: %+v %s %v", str(banner, "id"), cfg, format, err)
		}
		sum := sha256.Sum256(data)
		if str(banner, "version") != hex.EncodeToString(sum[:]) {
			t.Fatal("image version mismatch")
		}
		want(t, f.request(t, "GET", str(banner, "imagePath"), nil, "", true, false), 200)
		want(t, f.request(t, "GET", "/api/v1/tv/home/banners/"+str(banner, "id")+"/image", nil, "", false, false), 401)
	}
	// Simulate the startup transaction again, including after user edits.
	reseed := func() {
		t.Helper()
		if _, err := f.s.transact(context.Background(), func(tx store.DBTX) (any, error) {
			return nil, f.s.seedHomeBanners(context.Background(), tx)
		}); err != nil {
			t.Fatal(err)
		}
	}
	reseed()
	unchanged := want(t, f.request(t, "GET", "/api/v1/admin/tv-settings", nil, "", true, false), 200)
	if encode(unchanged) != encode(settings) {
		t.Fatal("startup changed existing covers")
	}
	first := banners[0].(map[string]any)
	first["label"], first["enabled"] = "自定义名称", false
	settings = want(t, f.request(t, "PUT", "/api/v1/admin/tv-settings", settings, "", true, true), 200)
	reseed()
	home = want(t, f.request(t, "GET", "/api/v1/tv/home", nil, device, false, false), 200)
	if len(array(object(home, "appearance"), "banners")) != 7 {
		t.Fatal("disabled cover returned to TV")
	}
	want(t, f.request(t, "GET", "/api/v1/tv/home/banners/"+str(first, "id")+"/image", nil, device, false, false), 404)
	current := want(t, f.request(t, "GET", "/api/v1/admin/tv-settings", nil, "", true, false), 200)
	if encode(current) != encode(settings) {
		t.Fatal("startup overwrote edits")
	}
	for _, value := range array(settings, "banners") {
		banner := value.(map[string]any)
		settings = want(t, f.request(t, "DELETE", "/api/v1/admin/tv-settings/banners/"+str(banner, "id")+"?revision="+strconv.FormatInt(number(settings, "revision"), 10), nil, "", true, true), 200)
	}
	reseed()
	current = want(t, f.request(t, "GET", "/api/v1/admin/tv-settings", nil, "", true, false), 200)
	if len(array(current, "banners")) != 0 || number(current, "revision") != number(settings, "revision") {
		t.Fatal("startup restored removed covers")
	}
}

func abs(v int) int {
	if v < 0 {
		return -v
	}
	return v
}

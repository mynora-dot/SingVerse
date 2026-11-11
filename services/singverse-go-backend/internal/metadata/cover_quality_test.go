package metadata

import (
	"bytes"
	"context"
	"encoding/binary"
	"hash/crc32"
	"image"
	"image/png"
	"io"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"

	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/metastandard"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/testutil"
)

func TestPlatformOriginalCoverRetainsPixelsAndBytes(t *testing.T) {
	var buf bytes.Buffer
	if err := png.Encode(&buf, image.NewRGBA(image.Rect(0, 5906, 5906, 5908))); err != nil {
		t.Fatal(err)
	}
	// PNG allows trailing bytes; make this valid original larger than the old
	// 2 MB gate too. Do not decode/re-encode or silently substitute a thumbnail.
	original := append(buf.Bytes(), bytes.Repeat([]byte{0}, 2_000_001)...)
	client := &http.Client{Transport: artworkTransport(func(r *http.Request) (*http.Response, error) {
		if r.URL.RawQuery != "" || !strings.HasPrefix(r.Header.Get("User-Agent"), "SingVerse/") {
			t.Fatal("original request was resized or lost its application identity")
		}
		return &http.Response{StatusCode: 200, Header: make(http.Header), Body: io.NopCloser(bytes.NewReader(original))}, nil
	})}
	cover, err := fetchCover(context.Background(), client, "https://p2.music.126.net/original.png")
	if err != nil || cover.Width != 5906 || !bytes.Equal(cover.Bytes, original) {
		t.Fatalf("original changed or rejected: %dx%d %v", cover.Width, cover.Height, err)
	}
	path := filepath.Join(t.TempDir(), "cover.image")
	if err := atomicWrite(path, cover.Bytes); err != nil {
		t.Fatal(err)
	}
	cached, err := readRemoteCover(path)
	if err != nil || !bytes.Equal(cached.Bytes, original) || cached.Width != 5906 {
		t.Fatal("original cache did not retain source bytes", err)
	}
	if saved, err := validateCover(original); err != nil || !bytes.Equal(saved.Bytes, original) {
		t.Fatal("32 MiB original policy is inconsistent with upload", err)
	}
}

func TestPlatformPreviewAndPersistentAssetHaveSamePixelBoundary(t *testing.T) {
	data := append([]byte(nil), tinyPNG(t)...)
	// A valid PNG header lets the boundary reject before allocating the pixels.
	binary.BigEndian.PutUint32(data[16:20], 8193)
	binary.BigEndian.PutUint32(data[20:24], 8193)
	binary.BigEndian.PutUint32(data[29:33], crc32.ChecksumIEEE(data[12:29]))
	if _, err := validateRemoteCover(data); err == nil {
		t.Fatal("preview accepted an original that cannot be persisted")
	}
	if _, err := validateCover(data); err == nil {
		t.Fatal("compatibility storage accepted an oversized original")
	}
	if _, _, err := metastandard.InspectAsset(data, "image/png"); err == nil {
		t.Fatal("formal asset storage accepted an oversized original")
	}
}

func TestCoverQualityVariantsAndBoundaries(t *testing.T) {
	for _, tc := range []struct{ provider, raw, original, high string }{
		{"NETEASE", "http://p2.music.126.net/a.jpg?param=640y640", "https://p2.music.126.net/a.jpg", "https://p2.music.126.net/a.jpg?param=2048y2048"},
		{"QQ", "https://y.gtimg.cn/music/photo_new/T002R300x300M000album.jpg", "https://y.gtimg.cn/music/photo_new/T002M000album.jpg", "https://y.gtimg.cn/music/photo_new/T002R1500x1500M000album.jpg"},
		{"KUGOU", "https://imge.kugou.com/stdmusic/400/a.jpg", "https://imge.kugou.com/stdmusic/0/a.jpg", "https://imge.kugou.com/stdmusic/2048/a.jpg"},
		{"KUWO", "https://img1.kuwo.cn/star/albumcover/120/a.jpg", "https://img1.kuwo.cn/star/albumcover/0/a.jpg", "https://img1.kuwo.cn/star/albumcover/2048/a.jpg"},
		{"QISHUI", "https://p3-luna.douyinpic.com/img/a~tplv-test-resize:640:640.png", "https://p3-luna.douyinpic.com/img/a~tplv-test-image.image", "https://p3-luna.douyinpic.com/img/a~tplv-test-resize:2048:2048.png"},
	} {
		t.Run(tc.provider, func(t *testing.T) {
			if got := coverVariant(&tc.provider, &tc.raw, "ORIGINAL"); got == nil || *got != tc.original {
				t.Fatal("unexpected original", got)
			}
			if got := coverVariant(&tc.provider, &tc.raw, "HIGH"); got == nil || *got != tc.high {
				t.Fatal("unexpected high", got)
			}
			for _, raw := range []string{"https://127.0.0.1/a.png", "https://p3-luna.douyinpic.com.evil.invalid/a.png", "https://img1.kuwo.cn:444/a.png", "https://user@img1.kuwo.cn/a.png"} {
				if coverVariant(&tc.provider, &raw, "ORIGINAL") != nil {
					t.Fatal("unsafe cover accepted", raw)
				}
			}
		})
	}
	for _, status := range []int{403, 404, 429} {
		client := &http.Client{Transport: artworkTransport(func(*http.Request) (*http.Response, error) {
			return &http.Response{StatusCode: status, Header: make(http.Header), Body: io.NopCloser(strings.NewReader(""))}, nil
		})}
		_, err := fetchCover(context.Background(), client, "https://p2.music.126.net/a.jpg")
		if err == nil || strings.Contains(err.Error(), "没有可用的封面") {
			t.Fatal("upstream failure reported as absent artwork", status, err)
		}
	}
}

func TestFiveProviderReviewAndCoverQuality(t *testing.T) { exerciseFiveProviderReview(t, setup(t)) }
func TestMySQLFiveProviderReviewAndCoverQuality(t *testing.T) {
	db, cfg := testutil.MySQLDatabase(t)
	exerciseFiveProviderReview(t, setupWithDatabase(t, db, "mysql", cfg))
}
func exerciseFiveProviderReview(t *testing.T, f *fixture) {
	t.Helper()
	_, ids := f.local(t, "A-Song.mkv")
	id := ids[0]
	request(t, f.router, "PUT", "/api/v1/metadata/settings", Settings{Enabled: true, Providers: append([]string{}, providers...), Threshold: .95}, 200)
	for _, provider := range providers {
		track := testTrack("Song")
		track.Provider = ptr(provider)
		if provider == "QISHUI" {
			track.CoverURL = ptr("https://p3-luna.douyinpic.com/img/a~tplv-test-image.image")
		}
		f.s.providers[provider] = fakeProvider{tracks: []Track{track}}
	}
	result := request(t, f.router, "POST", "/api/v1/metadata/songs/"+id+"/search", SearchInput{Providers: providers}, 200)
	if len(result["candidates"].([]any)) != 5 {
		t.Fatal("not all enabled providers were searched", result)
	}
	candidate := ""
	for _, raw := range result["candidates"].([]any) {
		row := raw.(map[string]any)
		if text(row["track"].(map[string]any)["provider"]) == "QISHUI" {
			candidate = text(row["id"])
		}
	}
	review := request(t, f.router, "GET", "/api/v1/metadata/songs/"+id+"/review", nil, 200)
	if review["values"].(map[string]any)["cover"] != nil {
		t.Fatal("search applied a cover before confirmation")
	}
	input := ReviewApply{RequestID: uuid(), Fingerprint: text(review["fingerprint"]), Version: text(review["version"]), Fields: []string{"cover"}, CoverCandidateID: &candidate, CoverQuality: "HIGH"}
	input.CoverQuality = "THUMBNAIL"
	request(t, f.router, "POST", "/api/v1/metadata/songs/"+id+"/review/apply", input, 400)
	input.CoverQuality = "HIGH"
	response := request(t, f.router, "POST", "/api/v1/metadata/songs/"+id+"/review/apply", input, 200)
	if text(response["after"].(map[string]any)["cover"]) != "https://p3-luna.douyinpic.com/img/a~tplv-test-resize:2048:2048.png" {
		t.Fatal("confirmed quality not stored", response)
	}
	request(t, f.router, "POST", "/api/v1/metadata/songs/"+id+"/review/apply", input, 200)
	w := httptest.NewRecorder()
	f.router.ServeHTTP(w, httptest.NewRequest("GET", "/api/v1/metadata/candidates/"+candidate+"/cover?quality=THUMBNAIL", nil))
	if w.Code != 400 {
		t.Fatal("unsupported image quality accepted", w.Code)
	}
}

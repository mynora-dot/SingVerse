package catalog

import (
	"bytes"
	"context"
	"image"
	"image/color"
	"image/gif"
	"image/jpeg"
	"image/png"
	"io"
	"net/http"
	"strings"
	"testing"
)

type artworkTransport func(*http.Request) (*http.Response, error)

func (f artworkTransport) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }

func TestDownloadArtworkValidatesActualImage(t *testing.T) {
	var pngData, jpegData, gifData, wideData bytes.Buffer
	img := image.NewRGBA(image.Rect(0, 0, 16, 16))
	img.Set(0, 0, color.RGBA{R: 120, G: 90, B: 30, A: 255})
	for _, err := range []error{
		png.Encode(&pngData, img), jpeg.Encode(&jpegData, img, nil),
		gif.Encode(&gifData, img, nil), png.Encode(&wideData, image.NewRGBA(image.Rect(0, 0, 4097, 1))),
	} {
		if err != nil {
			t.Fatal(err)
		}
	}
	oldClient := sourceClient
	t.Cleanup(func() { sourceClient = oldClient })
	for _, tc := range []struct {
		name, declared string
		body           []byte
		status         int
		valid          bool
	}{
		{"PNG mislabeled as JPG", "image/jpg", pngData.Bytes(), 200, true},
		{"PNG mislabeled with charset", "image/jpg; charset=UTF-8", pngData.Bytes(), 200, true},
		{"JPEG alias", "image/jpg", jpegData.Bytes(), 200, true},
		{"HTML disguised as image", "image/png", []byte("<html>upstream failure</html>"), 200, false},
		{"unsupported GIF", "image/jpeg", gifData.Bytes(), 200, false},
		{"broken PNG", "image/png", []byte("\x89PNG\r\n\x1a\nbroken"), 200, false},
		{"oversized image", "image/png", append(pngData.Bytes(), bytes.Repeat([]byte{0}, 2_000_001)...), 200, false},
		{"excessive dimensions", "image/png", wideData.Bytes(), 200, false},
		{"non-success response", "image/png", pngData.Bytes(), 403, false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			sourceClient = &http.Client{Transport: artworkTransport(func(r *http.Request) (*http.Response, error) {
				return &http.Response{StatusCode: tc.status, Header: http.Header{"Content-Type": {tc.declared}}, Body: io.NopCloser(bytes.NewReader(tc.body))}, nil
			})}
			data, err := downloadArtwork(context.Background(), "https://p1.music.126.net/portrait.jpg")
			if !tc.valid {
				if err == nil || data != nil {
					t.Fatal("invalid external artwork accepted")
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			cfg, format, err := image.DecodeConfig(bytes.NewReader(data))
			if err != nil || format != "jpeg" || cfg.Width != 16 || cfg.Height != 16 {
				t.Fatalf("unvalidated stored image: config=%v format=%s error=%v", cfg, format, err)
			}
		})
	}
}

func TestDownloadArtworkKeepsURLRestrictions(t *testing.T) {
	oldClient := sourceClient
	t.Cleanup(func() { sourceClient = oldClient })
	calls := 0
	sourceClient = &http.Client{Transport: artworkTransport(func(*http.Request) (*http.Response, error) {
		calls++
		return &http.Response{StatusCode: 200, Header: make(http.Header), Body: io.NopCloser(strings.NewReader("unexpected"))}, nil
	})}
	for _, address := range []string{
		"http://p1.music.126.net/image.png", "https://127.0.0.1/image.png", "https://evil.example/image.png",
		"https://p1.music.126.net:8443/image.png", "https://user:password@p1.music.126.net/image.png",
	} {
		if _, err := downloadArtwork(context.Background(), address); err == nil {
			t.Fatalf("untrusted address accepted: %s", address)
		}
	}
	if calls != 0 {
		t.Fatalf("performed %d unsafe network calls", calls)
	}
}

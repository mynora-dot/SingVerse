package downloads

import (
	"bytes"
	"context"
	"errors"
	"io"
	"math"
	"net/http"
	"os"
	"path/filepath"
	"testing"
)

func TestDownloadNetworkCancellationTruncationAndDiskGuard(t *testing.T) {
	for _, mode := range []string{"cancel", "truncated", "invalid", "success"} {
		t.Run(mode, func(t *testing.T) {
			network := NewNetwork()
			defer network.Close()
			content := makeWAV()
			declared := int64(len(content))
			if mode == "truncated" {
				declared++
			}
			if mode == "invalid" {
				content = bytes.Repeat([]byte("<html>not audio</html>"), 5)
				declared = int64(len(content))
			}
			network.client.Transport = roundTripFunc(func(r *http.Request) (*http.Response, error) {
				return &http.Response{StatusCode: 200, Body: io.NopCloser(bytes.NewReader(content)), ContentLength: declared, Header: http.Header{}, Request: r}, nil
			})
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			path := filepath.Join(t.TempDir(), "audio.part")
			result, err := network.Download(ctx, "https://audio.example/file", path, func(int64, *int64) error {
				if mode == "cancel" {
					cancel()
					return ctx.Err()
				}
				return nil
			})
			if mode == "success" {
				if err != nil || result.Extension != "wav" || result.Size != int64(len(content)) || len(result.Hash) != 64 {
					t.Fatal(result, err)
				}
				if got, readErr := os.ReadFile(path); readErr != nil || !bytes.Equal(got, content) {
					t.Fatal("completed download differs", readErr)
				}
			} else if err == nil {
				t.Fatal("incomplete or cancelled transfer reported success")
			} else if mode == "cancel" && !errors.Is(err, context.Canceled) {
				t.Fatal(err)
			}
		})
	}
	if err := freeSpace(t.TempDir(), math.MaxInt64); statusError(err) != 409 {
		t.Fatal("insufficient-space guard did not reject allocation", err)
	}
}

func TestDownloadNetworkRejectsPrivateRedirectBeforeRequest(t *testing.T) {
	network := NewNetwork()
	defer network.Close()
	calls := 0
	network.client.Transport = roundTripFunc(func(r *http.Request) (*http.Response, error) {
		calls++
		return &http.Response{StatusCode: http.StatusFound, Body: http.NoBody, Header: http.Header{"Location": {"http://127.0.0.1/secret"}}, Request: r}, nil
	})
	if _, err := network.Request(context.Background(), "https://audio.example/file", "GET", nil, nil, 1000, false); err == nil || calls != 1 {
		t.Fatal("private redirect was requested", calls, err)
	}
}

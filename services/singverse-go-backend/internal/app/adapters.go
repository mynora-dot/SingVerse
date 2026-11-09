package app

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"image"
	"time"

	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/compute"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/downloads"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/execution"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/feedback"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/library"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/metadata"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/party"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/playback"
	_ "golang.org/x/image/webp"
)

func playbackSongReader(media *library.Service) playback.SongReader {
	return func(ctx context.Context, tx store.DBTX, id string) (map[string]any, error) {
		song, err := media.SongWith(ctx, tx, id)
		if err != nil {
			return nil, err
		}
		projection := party.Projection(song)
		projection["versionType"] = projection["version"]
		return projection, nil
	}
}

func feedbackSongReader(media *library.Service) func(context.Context, store.DBTX, string) (feedback.Song, error) {
	return func(ctx context.Context, tx store.DBTX, id string) (feedback.Song, error) {
		song, err := media.SongWith(ctx, tx, id)
		if err != nil {
			return feedback.Song{}, err
		}
		projection := party.Projection(song)
		title, _ := projection["title"].(string)
		artists, _ := projection["artists"].([]string)
		return feedback.Song{Title: title, Artists: artists}, nil
	}
}

func downloadCoverReader(manager *downloads.Service) func(context.Context, string) (metadata.Cover, error) {
	return func(ctx context.Context, id string) (metadata.Cover, error) {
		data, mime, err := manager.SongCover(ctx, id)
		if err != nil {
			return metadata.Cover{}, err
		}
		dimensions, _, err := image.DecodeConfig(bytes.NewReader(data))
		if err != nil {
			return metadata.Cover{}, err
		}
		return metadata.Cover{Bytes: data, MIME: mime, Width: dimensions.Width, Height: dimensions.Height}, nil
	}
}

func localTagReader(media *library.Service, processing *execution.Service) func(context.Context, string, string) (map[string]any, error) {
	return func(ctx context.Context, sourceID, relative string) (map[string]any, error) {
		path, err := media.LocalMediaPath(ctx, sourceID, relative)
		if err != nil {
			return nil, err
		}
		return processing.LocalTags(ctx, path)
	}
}

func remoteTagExtractor(node *compute.Service, tags *metadata.Service) func(context.Context, map[string]any) (map[string]any, error) {
	return func(ctx context.Context, task map[string]any) (map[string]any, error) {
		connection, err := node.Snapshot(ctx)
		if err != nil {
			return nil, err
		}
		if connection == nil || connection.Token == "" {
			return nil, errors.New("compute is not configured")
		}
		deep := fmt.Sprint(task["deep"]) == "1" || task["deep"] == true
		reference, revoke, err := tags.AuthorizeInput(ctx, task, connection.Token, deep)
		if err != nil {
			return nil, err
		}
		defer revoke()
		origin, err := node.CallbackOrigin(connection)
		if err != nil {
			return nil, err
		}
		timeout := 70 * time.Second
		if deep {
			timeout = 190 * time.Second
		}
		request := map[string]any{
			"reference":      reference,
			"callbackOrigin": origin,
			"filename":       task["relative_path"],
			"size":           task["file_size"],
			"deep":           deep,
		}
		return node.Call(ctx, connection, "/internal/v1/media/remote-tags", request, timeout, 4*1024*1024)
	}
}

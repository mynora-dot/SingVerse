// Package overview aggregates a read-only admin home page snapshot. Each section
// is collected independently from its owning domain through a narrow interface;
// a failing section becomes null and is recorded in the top-level errors map.
// The package never writes business data and never streams a full-table search.
package overview

import (
	"context"
	"log/slog"
	"time"

	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/library"
)

// sectionTimeout bounds each independent section collection.
const sectionTimeout = 5 * time.Second

// pinger detects total database unavailability before any section is collected.
type pinger interface {
	PingContext(context.Context) error
}

// LibraryReader exposes the library home-page projections.
type LibraryReader interface {
	OverviewCounts(ctx context.Context) (total, review int64, err error)
	FormalCounts(ctx context.Context) (songs, variants, media int64, err error)
	RecentSongs(ctx context.Context, n int) ([]library.Song, error)
	SourceOverview(ctx context.Context) (library.SourceOverview, error)
}

// MetadataReader exposes the newest metadata batch tally.
type MetadataReader interface {
	LatestBatchSummary(ctx context.Context) (map[string]any, error)
}

// CatalogReader exposes classification and playlist summaries.
type CatalogReader interface {
	ClassificationOverview(ctx context.Context) (map[string]any, error)
	PlaylistSummary(ctx context.Context) ([]map[string]any, error)
}

// PartyReader exposes the TV device summary.
type PartyReader interface {
	DeviceOverview(ctx context.Context) (map[string]any, error)
}

// BackgroundReader exposes the active task snapshot.
type BackgroundReader interface {
	ActiveSnapshot(ctx context.Context, limit int) (map[string]any, error)
}

// PlaybackReader exposes the trailing 30-day playback summary.
type PlaybackReader interface {
	Summary30d(ctx context.Context) (map[string]any, error)
}

// CacheReader exposes cache usage and entry count.
type CacheReader interface {
	CacheUsage(ctx context.Context) (map[string]any, error)
}

// ComputeReader exposes compute configuration state (no network probe).
type ComputeReader interface {
	Readiness(ctx context.Context) (map[string]any, error)
}

// AIReader exposes AI feature configuration state (no network calls).
type AIReader interface {
	Readiness(ctx context.Context) (map[string]any, error)
}

// FeedbackReader exposes the pending missing-song count.
type FeedbackReader interface {
	PendingCount(ctx context.Context) (int64, error)
}

// Dependencies is the explicit narrow dependency graph for the aggregator.
type Dependencies struct {
	DB         pinger
	Library    LibraryReader
	Metadata   MetadataReader
	Catalog    CatalogReader
	Party      PartyReader
	Background BackgroundReader
	Playback   PlaybackReader
	Cache      CacheReader
	Compute    ComputeReader
	AI         AIReader
	Feedback   FeedbackReader
	Log        *slog.Logger
}

// Service collects the admin overview snapshot.
type Service struct {
	deps Dependencies
	log  *slog.Logger
}

// New builds the aggregator from its narrow domain dependencies.
func New(deps Dependencies) *Service {
	log := deps.Log
	if log == nil {
		log = slog.Default()
	}
	return &Service{deps: deps, log: log}
}

// LibrarySection is the song and formal-media count summary.
type LibrarySection struct {
	TotalSongs     int64 `json:"totalSongs"`
	ReviewSongs    int64 `json:"reviewSongs"`
	FormalSongs    int64 `json:"formalSongs"`
	FormalVariants int64 `json:"formalVariants"`
	FormalMedia    int64 `json:"formalMedia"`
}

// ReviewSection groups the metadata batch, classification and missing-feedback
// review state. MetadataBatch is null when no batch exists.
type ReviewSection struct {
	MetadataBatch          map[string]any `json:"metadataBatch"`
	Classification         map[string]any `json:"classification"`
	MissingFeedbackPending int64          `json:"missingFeedbackPending"`
}

// ReadinessSection groups compute and AI configuration state.
type ReadinessSection struct {
	Compute map[string]any `json:"compute"`
	AI      map[string]any `json:"ai"`
}

// Response is the admin overview wire contract. Any section is null when its
// collection fails; the failure is then recorded in Errors. RecentSongs and
// Playlists are arrays (empty when none) on success.
type Response struct {
	Library     any               `json:"library"`
	Sources     any               `json:"sources"`
	Review      any               `json:"review"`
	TV          any               `json:"tv"`
	Tasks       any               `json:"tasks"`
	RecentSongs any               `json:"recentSongs"`
	Playback30d any               `json:"playback30d"`
	Cache       any               `json:"cache"`
	Readiness   any               `json:"readiness"`
	Playlists   any               `json:"playlists"`
	ObservedAt  string            `json:"observedAt"`
	Errors      map[string]string `json:"errors"`
}

// Collect gathers every section independently. It returns an error only when the
// database is entirely unavailable; individual section failures are recovered
// into the response errors map with a null section.
func (s *Service) Collect(ctx context.Context) (*Response, error) {
	pingCtx, cancel := context.WithTimeout(ctx, sectionTimeout)
	err := s.deps.DB.PingContext(pingCtx)
	cancel()
	if err != nil {
		return nil, err
	}
	out := &Response{Errors: map[string]string{}, ObservedAt: time.Now().UTC().Format(time.RFC3339Nano)}

	s.section(ctx, out, "library", &out.Library, func(ctx context.Context) (any, error) {
		total, review, err := s.deps.Library.OverviewCounts(ctx)
		if err != nil {
			return nil, err
		}
		songs, variants, media, err := s.deps.Library.FormalCounts(ctx)
		if err != nil {
			return nil, err
		}
		return LibrarySection{TotalSongs: total, ReviewSongs: review, FormalSongs: songs, FormalVariants: variants, FormalMedia: media}, nil
	})
	s.section(ctx, out, "sources", &out.Sources, func(ctx context.Context) (any, error) {
		overview, err := s.deps.Library.SourceOverview(ctx)
		if err != nil {
			return nil, err
		}
		if len(overview.Failed) > 5 {
			overview.Failed = overview.Failed[:5]
		}
		return overview, nil
	})
	s.section(ctx, out, "review", &out.Review, func(ctx context.Context) (any, error) {
		batch, err := s.deps.Metadata.LatestBatchSummary(ctx)
		if err != nil {
			return nil, err
		}
		classification, err := s.deps.Catalog.ClassificationOverview(ctx)
		if err != nil {
			return nil, err
		}
		pending, err := s.deps.Feedback.PendingCount(ctx)
		if err != nil {
			return nil, err
		}
		return ReviewSection{MetadataBatch: batch, Classification: classification, MissingFeedbackPending: pending}, nil
	})
	s.section(ctx, out, "tv", &out.TV, func(ctx context.Context) (any, error) {
		return s.deps.Party.DeviceOverview(ctx)
	})
	s.section(ctx, out, "tasks", &out.Tasks, func(ctx context.Context) (any, error) {
		return s.deps.Background.ActiveSnapshot(ctx, 3)
	})
	s.section(ctx, out, "recentSongs", &out.RecentSongs, func(ctx context.Context) (any, error) {
		return s.deps.Library.RecentSongs(ctx, 6)
	})
	s.section(ctx, out, "playback30d", &out.Playback30d, func(ctx context.Context) (any, error) {
		return s.deps.Playback.Summary30d(ctx)
	})
	s.section(ctx, out, "cache", &out.Cache, func(ctx context.Context) (any, error) {
		return s.deps.Cache.CacheUsage(ctx)
	})
	s.section(ctx, out, "readiness", &out.Readiness, func(ctx context.Context) (any, error) {
		compute, err := s.deps.Compute.Readiness(ctx)
		if err != nil {
			return nil, err
		}
		ai, err := s.deps.AI.Readiness(ctx)
		if err != nil {
			return nil, err
		}
		return ReadinessSection{Compute: compute, AI: ai}, nil
	})
	s.section(ctx, out, "playlists", &out.Playlists, func(ctx context.Context) (any, error) {
		return s.deps.Catalog.PlaylistSummary(ctx)
	})
	return out, nil
}

// section runs one collection under its own timeout, recovering any failure into
// the errors map and leaving the section null. The internal error is logged but
// never exposed to the client.
func (s *Service) section(ctx context.Context, out *Response, name string, target *any, fn func(context.Context) (any, error)) {
	sectionCtx, cancel := context.WithTimeout(ctx, sectionTimeout)
	defer cancel()
	value, err := fn(sectionCtx)
	if err != nil {
		s.log.WarnContext(ctx, "overview section unavailable", "section", name, "error", err.Error())
		out.Errors[name] = "该部分暂时无法读取"
		return
	}
	*target = value
}

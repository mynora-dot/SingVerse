package app

import (
	"log/slog"

	"github.com/jmoiron/sqlx"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/ai"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/auth"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/background"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/catalog"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/compute"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/downloads"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/execution"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/feedback"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/library"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/metadata"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/party"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/config"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/playback"
)

// applicationServices holds the explicit dependency graph during startup only.
// Business modules receive their dependencies directly, never this graph.
type applicationServices struct {
	accounts     *auth.Service
	media        *library.Service
	activity     *playback.Service
	devices      *party.Service
	tasks        *background.Service
	node         *compute.Service
	models       *ai.Service
	curation     *catalog.Service
	tags         *metadata.Service
	processing   *execution.Service
	downloads    *downloads.Service
	missingSongs *feedback.Service
}

func (a *Application) initializeServices(cfg config.Config, db *sqlx.DB, logger *slog.Logger) (*applicationServices, error) {
	accounts, err := auth.New(db, cfg.Driver, cfg, logger)
	if err != nil {
		return nil, err
	}
	media, err := library.New(db, cfg.Driver, cfg, accounts.Cipher, logger)
	if err != nil {
		return nil, err
	}
	a.closers = append(a.closers, media.Close)
	activity, err := playback.New(db, cfg.Driver, playbackSongReader(media), logger)
	if err != nil {
		return nil, err
	}
	a.closers = append(a.closers, activity.Close)
	devices, err := party.New(db, cfg.Driver, cfg, media, logger)
	if err != nil {
		return nil, err
	}
	a.closers = append(a.closers, devices.Close)
	devices.SetActivity(activity)
	devices.SetCipher(accounts.Cipher)
	tasks, err := background.New(db, cfg.Driver, logger)
	if err != nil {
		return nil, err
	}
	a.tasks = tasks
	a.closers = append(a.closers, tasks.Close)
	media.SetProjector(tasks.Project)
	media.SetChanged(devices.Invalidate)
	media.SetLocalTracker(tasks.StartLocal, tasks.LocalProgress, tasks.FinishLocal, tasks.RegisterScanScope)
	node, err := compute.New(db, cfg.Driver, cfg, accounts.Cipher, logger)
	if err != nil {
		return nil, err
	}
	a.closers = append(a.closers, func() error { node.Close(); return nil })
	models, err := ai.New(db, cfg.Driver, cfg, accounts.Cipher, logger)
	if err != nil {
		return nil, err
	}
	curation, err := catalog.New(db, cfg.Driver, cfg, media, models, accounts.Cipher, devices, logger)
	if err != nil {
		return nil, err
	}
	a.closers = append(a.closers, curation.Close)
	curation.SetProjector(tasks.Project)
	tags, err := metadata.New(db, cfg.Driver, cfg, accounts.Cipher, media, logger)
	if err != nil {
		return nil, err
	}
	a.closers = append(a.closers, tags.Close)
	tags.SetProjector(tasks.Project)
	tags.SetChanged(devices.Invalidate)
	tags.SetArtistEdited(curation.ArtistEdited)
	curation.SetPathChanged(tags.TouchPath)
	curation.SetArtistProvider(tags)
	media.SetIndexed(tags.TouchSource)
	media.SetWebDAVAccess(tags.Access)
	processing, err := execution.New(db, cfg.Driver, cfg, accounts.Cipher, media, node, logger)
	if err != nil {
		return nil, err
	}
	a.closers = append(a.closers, func() error { processing.Close(); return nil })
	processing.SetProjector(tasks.Project)
	processing.SetAudioScopes(tasks)
	media.SetOriginalCache(processing.ServeCachedOriginal)
	devices.SetPrefetch(processing)
	devices.SetPlaybackNegotiator(processing.Negotiate)
	tags.SetLocalInput(processing.HasLocalInput, processing.ServeLocalInputWith)
	tags.SetAudioRoles(processing.PortableAudio)
	tags.SetAudioClaim(processing.ImportAudioClaimWith)
	downloadManager, err := downloads.New(db, cfg.Driver, cfg, accounts.Cipher, media, logger)
	if err != nil {
		return nil, err
	}
	a.closers = append(a.closers, downloadManager.Close)
	downloadManager.SetProjector(tasks.Project)
	downloadManager.SetPublished(tags.Touch)
	downloadManager.SetChanged(devices.Invalidate)
	tags.SetDownloadCover(downloadCoverReader(downloadManager))
	media.SetLocalTagReader(localTagReader(media, processing))
	tags.SetRemoteExtractor(remoteTagExtractor(node, tags))
	missingSongs, err := feedback.New(db, feedbackSongReader(media))
	if err != nil {
		return nil, err
	}
	return &applicationServices{
		accounts:     accounts,
		media:        media,
		activity:     activity,
		devices:      devices,
		tasks:        tasks,
		node:         node,
		models:       models,
		curation:     curation,
		tags:         tags,
		processing:   processing,
		downloads:    downloadManager,
		missingSongs: missingSongs,
	}, nil
}

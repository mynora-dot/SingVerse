package library

import "encoding/json"

// Database rows are separate from public projections and use explicit column tags.
type sourceRecord struct {
	ID              string  `db:"id"`
	Name            string  `db:"name"`
	Type            string  `db:"source_type"`
	Location        string  `db:"location"`
	Username        string  `db:"username"`
	Encrypted       *string `db:"password_encrypted"`
	ParserProfileID string  `db:"parser_profile_id"`
	CreatedAt       string  `db:"created_at"`
	UpdatedAt       string  `db:"updated_at"`
	ScanEnabled     bool    `db:"scan_enabled"`
	ScanCron        *string `db:"scan_cron"`
	NextScanAt      *string `db:"next_scan_at"`
	LastScanAt      *string `db:"last_scan_at"`
	LastScanStatus  *string `db:"last_scan_status"`
	LastScanError   *string `db:"last_scan_error"`
}

func (r sourceRecord) model() Source {
	return Source{
		ID: r.ID, Name: r.Name, Type: r.Type, Location: r.Location, Username: r.Username,
		ParserProfileID: r.ParserProfileID, PasswordConfigured: r.Encrypted != nil,
		CreatedAt: r.CreatedAt, UpdatedAt: r.UpdatedAt, ScanEnabled: r.ScanEnabled,
		ScanCron: r.ScanCron, NextScanAt: r.NextScanAt, LastScanAt: r.LastScanAt,
		LastScanStatus: r.LastScanStatus, LastScanError: r.LastScanError, encrypted: r.Encrypted,
	}
}

type songRecord struct {
	FormalSong             *string `db:"formal_song"`
	FormalVariant          *string `db:"formal_variant"`
	FormalMedia            *string `db:"formal_media"`
	LocationState          string  `db:"location_state"`
	ID                     string  `db:"id"`
	SourceID               string  `db:"source_id"`
	SourceName             string  `db:"source_name"`
	RelativePath           string  `db:"relative_path"`
	PathHash               string  `db:"path_hash"`
	FileSize               *int64  `db:"file_size"`
	ModifiedAt             *string `db:"modified_at"`
	ScannedAt              string  `db:"scanned_at"`
	ParserProfileID        string  `db:"parser_profile_id"`
	ParserRevision         int64   `db:"parser_revision"`
	SelectedRuleID         *string `db:"selected_rule_id"`
	Result                 string  `db:"result_json"`
	Embedded               *string `db:"embedded_tags_json"`
	Track                  *string `db:"metadata_track"`
	Fields                 *string `db:"metadata_fields"`
	Applied                *string `db:"metadata_applied"`
	Origins                *string `db:"metadata_origins"`
	Fingerprint            *string `db:"metadata_fingerprint"`
	Classification         *string `db:"classification_state"`
	ClassificationRevision *int64  `db:"classification_revision"`
	Shared                 *string `db:"shared_snapshot"`
	Verification           *string `db:"media_verification"`
	Remote                 *string `db:"remote_result"`
	RemoteID               *string `db:"remote_song_id"`
	IdentitySize           *int64  `db:"identity_size"`
	IdentityModified       *string `db:"identity_modified"`
	ETag                   *string `db:"etag"`
	OverrideSource         *string `db:"override_source_id"`
	OverrideArtists        *string `db:"override_artists"`
	Download               *string `db:"download_track"`
	DownloadAt             *string `db:"download_created_at"`
}

func (r songRecord) model() (Song, error) {
	song := Song{
		ID: r.ID, SourceID: r.SourceID, SourceName: r.SourceName, RelativePath: r.RelativePath,
		FileSize: r.FileSize, ModifiedAt: r.ModifiedAt, ScannedAt: r.ScannedAt,
		ParserProfileID: r.ParserProfileID, ParserRevision: r.ParserRevision, SelectedRuleID: r.SelectedRuleID,
		pathHash: r.PathHash, embedded: r.Embedded, etag: r.ETag, MediaVerification: r.Verification,
	}
	if r.LocationState != "" && r.LocationState != "PRESENT" {
		song.MediaVerification = &r.LocationState
	}
	if err := json.Unmarshal([]byte(r.Result), &song.Result); err != nil {
		return song, err
	}
	dimensions := map[string]any{}
	if r.Classification != nil {
		if err := json.Unmarshal([]byte(*r.Classification), &dimensions); err != nil {
			return song, err
		}
	}
	revision := int64(0)
	if r.ClassificationRevision != nil {
		revision = *r.ClassificationRevision
	}
	song.Classification = map[string]any{"songId": song.ID, "revision": revision, "dimensions": dimensions}
	song.contentFingerprint = contentIdentity(song.ID, song.FileSize, song.ModifiedAt)
	if r.RemoteID != nil {
		song.contentFingerprint = contentIdentity(song.ID, r.IdentitySize, r.IdentityModified)
	}
	var err error
	song.Metadata, err = effectiveMetadata(song, r.Track, r.Fields, r.Applied, r.Origins, r.Fingerprint, r.Remote, r.OverrideSource, r.OverrideArtists, r.Download, r.DownloadAt)
	if err == nil {
		song.Metadata, err = sharedMetadata(song, song.Metadata, r.Shared)
	}
	if err == nil && r.FormalSong != nil {
		song.Metadata, err = formalProjection(r.FormalSong, r.FormalVariant, r.FormalMedia)
	}
	return song, err
}

type scanRecord struct {
	ID               string  `db:"id"`
	SourceID         string  `db:"source_id"`
	Status           string  `db:"status"`
	Phase            string  `db:"phase"`
	Directories      int64   `db:"directories"`
	Entries          int64   `db:"entries"`
	TotalSongs       int     `db:"total_songs"`
	MatchedSongs     int     `db:"matched_songs"`
	NeedsReviewSongs int     `db:"review_songs"`
	SkippedFiles     int64   `db:"skipped_files"`
	CancelRequested  bool    `db:"cancel_requested"`
	Error            *string `db:"error"`
	CreatedAt        string  `db:"created_at"`
	StartedAt        *string `db:"started_at"`
	FinishedAt       *string `db:"finished_at"`
	AddedSongs       int     `db:"added_songs"`
	ReusedSongs      int     `db:"reused_songs"`
	MissingSongs     int     `db:"missing_songs"`
	RemovedSongs     int     `db:"removed_songs"`
}

func (r scanRecord) model() ScanTask {
	return ScanTask(r)
}

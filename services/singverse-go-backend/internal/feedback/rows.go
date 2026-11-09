package feedback

import "encoding/json"

type feedbackRow struct {
	ID                    string  `db:"id"`
	Title                 string  `db:"title"`
	Artist                string  `db:"artist"`
	VersionLabel          string  `db:"version_label"`
	Description           string  `db:"description"`
	SubmittedBy           string  `db:"submitted_by"`
	Source                string  `db:"source"`
	Status                string  `db:"status"`
	HandlingNote          string  `db:"handling_note"`
	ResolvedSongID        *string `db:"resolved_song_id"`
	ResolvedSongTitle     *string `db:"resolved_song_title"`
	ResolvedSongArtists   string  `db:"resolved_song_artists_json"`
	ResolvedSongAvailable bool    `db:"resolved_song_available"`
	Revision              int64   `db:"revision"`
	CreatedAt             string  `db:"created_at"`
	UpdatedAt             string  `db:"updated_at"`
	ResolvedAt            *string `db:"resolved_at"`
	CreationHash          string  `db:"creation_hash"`
}

func (r feedbackRow) model() (row, error) {
	v := row{Feedback: Feedback{
		ID: r.ID, Title: r.Title, Artist: r.Artist, VersionLabel: r.VersionLabel,
		Description: r.Description, SubmittedBy: r.SubmittedBy, Source: r.Source,
		Status: r.Status, HandlingNote: r.HandlingNote, ResolvedSongID: r.ResolvedSongID,
		ResolvedSongTitle: r.ResolvedSongTitle, ResolvedSongAvailable: r.ResolvedSongAvailable,
		Revision: r.Revision, CreatedAt: r.CreatedAt, UpdatedAt: r.UpdatedAt, ResolvedAt: r.ResolvedAt,
	}, hash: r.CreationHash}
	err := json.Unmarshal([]byte(r.ResolvedSongArtists), &v.ResolvedSongArtists)
	if v.ResolvedSongArtists == nil {
		v.ResolvedSongArtists = []string{}
	}
	return v, err
}

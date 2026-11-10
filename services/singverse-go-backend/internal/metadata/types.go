// Package metadata owns optional platform enrichment and administrator review.
package metadata

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"time"
	"unicode"

	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

type Cipher interface {
	Encrypt(string, string) (string, error)
	Decrypt(string, string) (string, error)
}
type Settings struct {
	Enabled            bool     `json:"enabled"`
	Providers          []string `json:"providers"`
	AutoApply          bool     `json:"autoApply"`
	Threshold          float64  `json:"threshold"`
	MinIntervalSeconds *int     `json:"minIntervalSeconds"`
	MaxIntervalSeconds *int     `json:"maxIntervalSeconds"`
}
type ArtistRef struct {
	Provider string `json:"provider"`
	ID       string `json:"id"`
	Name     string `json:"name"`
}
type Track struct {
	Provider     *string     `json:"provider"`
	ExternalID   *string     `json:"externalId"`
	Title        *string     `json:"title"`
	Artists      []string    `json:"artists"`
	Album        *string     `json:"album"`
	DurationMs   *int        `json:"durationMs"`
	ReleaseDate  *string     `json:"releaseDate"`
	Aliases      []string    `json:"aliases"`
	CoverURL     *string     `json:"coverUrl"`
	Availability string      `json:"availability"`
	FetchedAt    string      `json:"fetchedAt"`
	ArtistRefs   []ArtistRef `json:"artistRefs"`
}
type Origin struct {
	Provider   *string `json:"provider"`
	ExternalID *string `json:"externalId"`
	AppliedAt  string  `json:"appliedAt"`
	Kind       string  `json:"kind"`
}
type Applied struct {
	Track     Track             `json:"track"`
	Fields    []string          `json:"fields"`
	AppliedAt string            `json:"appliedAt"`
	Stale     bool              `json:"stale"`
	Origins   map[string]Origin `json:"origins"`
}
type Candidate struct {
	ID    string  `json:"id"`
	Track Track   `json:"track"`
	Score float64 `json:"score"`
}
type SearchInput struct {
	Keyword   *string  `json:"keyword"`
	Providers []string `json:"providers"`
}
type ProviderError struct {
	Provider string `json:"provider"`
	Message  string `json:"message"`
}
type SearchResult struct {
	Candidates []Candidate     `json:"candidates"`
	Errors     []ProviderError `json:"errors"`
}
type ApplyInput struct {
	CandidateID string   `json:"candidateId"`
	Fields      []string `json:"fields"`
}
type StartInput struct {
	All      bool     `json:"all"`
	SongIDs  []string `json:"songIds"`
	SourceID *string  `json:"sourceId"`
}
type ReviewApply struct {
	RequestID        string         `json:"requestId"`
	Fingerprint      string         `json:"fingerprint"`
	Version          string         `json:"version"`
	CandidateID      *string        `json:"candidateId"`
	Fields           []string       `json:"fields"`
	Edits            map[string]any `json:"edits"`
	CoverCandidateID *string        `json:"coverCandidateId"`
	CoverAssetID     *string        `json:"coverAssetId"`
	CoverQuality     string         `json:"coverQuality,omitempty"`
}
type ReviewResult struct {
	Metadata *Applied       `json:"metadata"`
	Updated  []string       `json:"updated"`
	Skipped  []string       `json:"skipped"`
	Retained []string       `json:"retained"`
	Before   map[string]any `json:"before"`
	After    map[string]any `json:"after"`
}
type Review struct {
	Song        map[string]any `json:"song"`
	Fingerprint string         `json:"fingerprint"`
	Version     string         `json:"version"`
	Values      map[string]any `json:"values"`
}
type Provider interface {
	Search(context.Context, string, int) ([]Track, error)
}
type projectFunc func(context.Context, store.DBTX, string, string) error

var allFields = []string{"title", "artists", "album", "releaseDate", "aliases", "cover", "durationMs"}
var providers = []string{"NETEASE", "QQ", "KUGOU", "KUWO", "QISHUI"}

type problem struct {
	status int
	detail string
}

func (e *problem) Error() string   { return e.detail }
func (e *problem) StatusCode() int { return e.status }
func bad(v string) error           { return &problem{400, v} }
func conflict(v string) error      { return &problem{409, v} }
func missing(v string) error       { return &problem{404, v} }
func code(err error) int {
	var p interface{ StatusCode() int }
	if errors.As(err, &p) {
		return p.StatusCode()
	}
	return 500
}
func now() string          { return time.Now().UTC().Format(time.RFC3339Nano) }
func hash(v string) string { b := sha256.Sum256([]byte(v)); return hex.EncodeToString(b[:]) }
func uuid() string {
	b := make([]byte, 16)
	if _, e := rand.Read(b); e != nil {
		panic(e)
	}
	b[6] = (b[6] & 15) | 64
	b[8] = (b[8] & 63) | 128
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[:4], b[4:6], b[6:8], b[8:10], b[10:])
}
func encode(v any) string {
	b, e := json.Marshal(v)
	if e != nil {
		panic(e)
	}
	return string(b)
}
func decode(v any, to any) error {
	d := json.NewDecoder(strings.NewReader(text(v)))
	d.UseNumber()
	return d.Decode(to)
}
func text(v any) string {
	if v == nil {
		return ""
	}
	if s, ok := v.(string); ok {
		return s
	}
	return fmt.Sprint(v)
}
func number(v any) int64 {
	switch n := v.(type) {
	case int64:
		return n
	case int:
		return int64(n)
	case float64:
		return int64(n)
	case json.Number:
		i, _ := n.Int64()
		return i
	}
	i, _ := strconv.ParseInt(text(v), 10, 64)
	return i
}
func boolean(v any) bool {
	b, ok := v.(bool)
	if ok {
		return b
	}
	return number(v) == 1
}
func ptr[T any](v T) *T { return &v }
func contains(items []string, v string) bool {
	for _, s := range items {
		if s == v {
			return true
		}
	}
	return false
}
func clean(v string, max int) (string, error) {
	if len([]rune(v)) > max {
		return "", bad("字段过长")
	}
	for _, r := range v {
		if unicode.IsControl(r) {
			return "", bad("字段包含控制字符")
		}
	}
	return strings.TrimSpace(v), nil
}
func fingerprint(song map[string]any) string {
	if f, ok := song["_fingerprint"].(string); ok {
		return f
	}
	size, modified := "null", "null"
	if song["fileSize"] != nil {
		size = text(song["fileSize"])
	}
	if song["modifiedAt"] != nil {
		modified = text(song["modifiedAt"])
	}
	return hash(text(song["id"]) + ":" + size + ":" + modified)
}
func metaOf(song map[string]any) *Applied {
	v := song["metadata"]
	if v == nil {
		return nil
	}
	var a Applied
	if decode(encode(v), &a) != nil || a.Stale {
		return nil
	}
	return &a
}
func current(song map[string]any) map[string]any {
	var result map[string]any
	_ = decode(encode(song["result"]), &result)
	v := map[string]any{"title": result["title"], "artists": result["artists"], "album": nil, "releaseDate": nil, "aliases": []string{}, "durationMs": nil, "cover": nil}
	if a := metaOf(song); a != nil {
		tv := trackValues(a.Track)
		for _, f := range a.Fields {
			v[f] = tv[f]
		}
	}
	var normalized map[string]any
	_ = decode(encode(v), &normalized)
	return normalized
}
func trackValues(t Track) map[string]any {
	return map[string]any{"title": t.Title, "artists": t.Artists, "album": t.Album, "releaseDate": t.ReleaseDate, "aliases": t.Aliases, "durationMs": t.DurationMs, "cover": t.CoverURL}
}
func version(song map[string]any) string {
	return hash(fingerprint(song) + ":" + encode(song["metadata"]))
}
func present(v any) bool {
	if v == nil {
		return false
	}
	b := encode(v)
	return b != "null" && b != "\"\"" && b != "[]"
}
func stringsOf(v any) []string {
	var out []string
	_ = decode(encode(v), &out)
	if out == nil {
		out = []string{}
	}
	return out
}
func safeDetail(err error) string {
	if code(err) < 500 {
		return err.Error()
	}
	return http.StatusText(http.StatusServiceUnavailable)
}

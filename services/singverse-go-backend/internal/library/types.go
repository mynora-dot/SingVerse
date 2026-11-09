package library

import (
	"crypto/md5"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"strings"
	"time"
	"unicode"
)

type Cipher interface {
	Encrypt(value, purpose string) (string, error)
	Decrypt(value, purpose string) (string, error)
}
type Source struct {
	ID                 string  `json:"id"`
	Name               string  `json:"name"`
	Type               string  `json:"type"`
	Location           string  `json:"location"`
	Username           string  `json:"username"`
	ParserProfileID    string  `json:"parserProfileId"`
	PasswordConfigured bool    `json:"passwordConfigured"`
	CreatedAt          string  `json:"createdAt"`
	UpdatedAt          string  `json:"updatedAt"`
	ScanEnabled        bool    `json:"scanEnabled"`
	ScanCron           *string `json:"scanCron"`
	NextScanAt         *string `json:"nextScanAt"`
	LastScanAt         *string `json:"lastScanAt"`
	LastScanStatus     *string `json:"lastScanStatus"`
	LastScanError      *string `json:"lastScanError"`
	UseForLxDownloads  bool    `json:"useForLxDownloads"`
	encrypted          *string
}
type SourceInput struct {
	Name              string  `json:"name"`
	Type              string  `json:"type"`
	Location          string  `json:"location"`
	Username          string  `json:"username"`
	Password          *string `json:"password"`
	ParserProfileID   *string `json:"parserProfileId"`
	ScanEnabled       *bool   `json:"scanEnabled"`
	ScanCron          *string `json:"scanCron"`
	UseForLxDownloads *bool   `json:"useForLxDownloads"`
}
type Page[T any] struct {
	Items         []T   `json:"items"`
	Page          int   `json:"page"`
	Size          int   `json:"size"`
	TotalElements int64 `json:"totalElements"`
}
type Song struct {
	ID                 string      `json:"id"`
	SourceID           string      `json:"sourceId"`
	SourceName         string      `json:"sourceName"`
	RelativePath       string      `json:"relativePath"`
	FileSize           *int64      `json:"fileSize"`
	ModifiedAt         *string     `json:"modifiedAt"`
	ScannedAt          string      `json:"scannedAt"`
	ParserProfileID    string      `json:"parserProfileId"`
	ParserRevision     int64       `json:"parserRevision"`
	SelectedRuleID     *string     `json:"selectedRuleId"`
	Result             ParseResult `json:"result"`
	Metadata           any         `json:"metadata"`
	Classification     any         `json:"classification"`
	contentFingerprint string
	pathHash           string
	etag               *string
	embedded           *string
}
type Scan struct {
	SourceID          string `json:"sourceId"`
	ParserProfileID   string `json:"parserProfileId"`
	ParserProfileName string `json:"parserProfileName"`
	ParserRevision    int64  `json:"parserRevision"`
	TotalSongs        int    `json:"totalSongs"`
	MatchedSongs      int    `json:"matchedSongs"`
	NeedsReviewSongs  int    `json:"needsReviewSongs"`
	SkippedFiles      int    `json:"skippedFiles"`
	ScannedAt         string `json:"scannedAt"`
}
type ScanTask struct {
	ID               string  `json:"id"`
	SourceID         string  `json:"sourceId"`
	Status           string  `json:"status"`
	Phase            string  `json:"phase"`
	Directories      int64   `json:"directories"`
	Entries          int64   `json:"entries"`
	TotalSongs       int     `json:"totalSongs"`
	MatchedSongs     int     `json:"matchedSongs"`
	NeedsReviewSongs int     `json:"needsReviewSongs"`
	SkippedFiles     int64   `json:"skippedFiles"`
	CancelRequested  bool    `json:"cancelRequested"`
	Error            *string `json:"error"`
	CreatedAt        string  `json:"createdAt"`
	StartedAt        *string `json:"startedAt"`
	FinishedAt       *string `json:"finishedAt"`
	AddedSongs       int     `json:"addedSongs"`
	ReusedSongs      int     `json:"reusedSongs"`
	MissingSongs     int     `json:"missingSongs"`
	RemovedSongs     int     `json:"removedSongs"`
}
type Rule struct {
	ID         string  `json:"id"`
	Name       string  `json:"name"`
	Type       string  `json:"type"`
	Enabled    *bool   `json:"enabled"`
	Expression *string `json:"expression"`
	BuiltinID  *string `json:"builtinId"`
}
type Definition struct {
	ID         string  `json:"id"`
	Name       string  `json:"name"`
	Type       string  `json:"type"`
	Expression *string `json:"expression"`
	BuiltinID  *string `json:"builtinId"`
}
type Entry struct {
	RuleID  string `json:"ruleId"`
	Enabled *bool  `json:"enabled"`
}
type Profile struct {
	ID      string  `json:"id"`
	Name    string  `json:"name"`
	Entries []Entry `json:"entries"`
}
type Document struct {
	SchemaVersion int          `json:"schemaVersion"`
	Definitions   []Definition `json:"definitions"`
	Profiles      []Profile    `json:"profiles"`
}
type Preset struct {
	ID               string `json:"id"`
	Name             string `json:"name"`
	Type             string `json:"type"`
	Expression       string `json:"expression"`
	EnabledByDefault bool   `json:"enabledByDefault"`
	Example          string `json:"example"`
	Description      string `json:"description"`
}
type Reference struct {
	ID   string `json:"id"`
	Name string `json:"name"`
}
type DefinitionView struct {
	Definition
	ReferencedBy []Reference `json:"referencedBy"`
}
type Workspace struct {
	Revision     int64            `json:"revision"`
	UpdatedAt    *string          `json:"updatedAt"`
	Definitions  []DefinitionView `json:"definitions"`
	Profiles     []Profile        `json:"profiles"`
	BuiltinRules []Preset         `json:"builtinRules"`
	ChangedID    *string          `json:"changedId"`
}
type Configuration struct {
	Revision     int64    `json:"revision"`
	UpdatedAt    *string  `json:"updatedAt"`
	Rules        []Rule   `json:"rules"`
	BuiltinRules []Preset `json:"builtinRules"`
}
type Diagnostic struct {
	Code            string  `json:"code"`
	Detail          string  `json:"detail"`
	ConfidenceDelta float64 `json:"confidenceDelta"`
}
type ParseResult struct {
	OriginalFilename   string       `json:"originalFilename"`
	NormalizedFilename string       `json:"normalizedFilename"`
	Title              *string      `json:"title"`
	Artists            []string     `json:"artists"`
	Language           string       `json:"language"`
	VersionType        string       `json:"versionType"`
	SongCode           *string      `json:"songCode"`
	Extension          *string      `json:"extension"`
	QualityTags        []string     `json:"qualityTags"`
	Confidence         float64      `json:"confidence"`
	ParseStatus        string       `json:"parseStatus"`
	ParserRule         string       `json:"parserRule"`
	UnparsedTokens     []string     `json:"unparsedTokens"`
	NeedsReview        bool         `json:"needsReview"`
	Diagnostics        []Diagnostic `json:"diagnostics"`
}
type Selection struct {
	ID   string `json:"id"`
	Name string `json:"name"`
	Type string `json:"type"`
}
type Attempt struct {
	ID         string              `json:"id"`
	Name       string              `json:"name"`
	Type       string              `json:"type"`
	Status     string              `json:"status"`
	Captures   map[string]string   `json:"captures"`
	Candidates []map[string]string `json:"candidates"`
	Detail     string              `json:"detail"`
}
type Preview struct {
	Result       ParseResult `json:"result"`
	SelectedRule *Selection  `json:"selectedRule"`
	MatchTarget  *string     `json:"matchTarget"`
	Attempts     []Attempt   `json:"attempts"`
}
type problem struct {
	status int
	detail string
	fields map[string]any
}

func (e *problem) Error() string   { return e.detail }
func (e *problem) StatusCode() int { return e.status }
func bad(s string) error           { return &problem{status: 400, detail: s} }
func conflict(s string) error      { return &problem{status: 409, detail: s} }
func missing(s string) error       { return &problem{status: 404, detail: s} }
func invalid(id string, index int, field, s string) error {
	return &problem{status: 400, detail: s, fields: map[string]any{"ruleId": nullable(id), "ruleIndex": index, "field": field}}
}
func nullable(v string) any {
	if v == "" {
		return nil
	}
	return v
}
func ptr[T any](v T) *T { return &v }
func uuid() string {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		panic(err)
	}
	b[6] = (b[6] & 15) | 64
	b[8] = (b[8] & 63) | 128
	return formatUUID(b)
}
func songID(source, path string) string {
	b := md5.Sum([]byte(source + "\x00" + path))
	b[6] = (b[6] & 15) | 48
	b[8] = (b[8] & 63) | 128
	return formatUUID(b)
}
func formatUUID(b [16]byte) string {
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[:4], b[4:6], b[6:8], b[8:10], b[10:])
}
func hash(v string) string { sum := sha256.Sum256([]byte(v)); return hex.EncodeToString(sum[:]) }
func now() string          { return time.Now().UTC().Format(time.RFC3339Nano) }
func text(v string, max int) bool {
	return strings.TrimSpace(v) != "" && len([]rune(strings.TrimSpace(v))) <= max && !controls(v)
}
func controls(v string) bool {
	for _, r := range v {
		if unicode.IsControl(r) {
			return true
		}
	}
	return false
}
func encode(v any) string {
	b, err := json.Marshal(v)
	if err != nil {
		panic(err)
	}
	return string(b)
}

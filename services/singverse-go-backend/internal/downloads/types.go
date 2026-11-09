// Package downloads owns LX sources, catalog selection and durable audio downloads.
package downloads

import (
	"context"
	"crypto/md5"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"strconv"
	"strings"
	"time"

	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

var platforms = map[string]string{"wy": "网易云音乐", "tx": "QQ 音乐", "kg": "酷狗音乐", "kw": "酷我音乐", "mg": "咪咕音乐"}
var qualities = []string{"flac24bit", "flac", "320k", "128k"}

const sourcePurpose = "singverse:lx-source:v1"
const maxScript = 9_000_000

type Track struct {
	ID         string         `json:"id"`
	Platform   string         `json:"platform"`
	RemoteID   string         `json:"remoteId"`
	Title      string         `json:"title"`
	Artists    []string       `json:"artists"`
	Album      string         `json:"album"`
	DurationMS *int64         `json:"durationMs"`
	CoverURL   string         `json:"coverUrl"`
	MusicInfo  map[string]any `json:"musicInfo"`
}
type CatalogPage struct {
	Tracks  []Track
	HasMore bool
	Warning string
}
type CatalogReference struct{ Platform, Kind, ID string }
type Collection struct {
	Platform, Kind, Name, CoverURL string
	Tracks                         []Track
	Page                           int
	Total                          int64
	HasMore                        bool
	Warning                        string
}
type Catalog interface {
	Search(context.Context, string, string, int) (CatalogPage, error)
	Reference(context.Context, string) (CatalogReference, error)
	Collection(context.Context, CatalogReference, int) (Collection, error)
	Detail(context.Context, Track) (Track, error)
	Lyrics(context.Context, Track) (string, error)
}
type CoverStore interface {
	Reference(string) *string
	Get(context.Context, string) ([]byte, string, error)
}
type Cipher interface {
	Encrypt(string, string) (string, error)
	Decrypt(string, string) (string, error)
}
type Library interface {
	Publication(context.Context, string, func() error) error
}
type ScriptRuntime interface {
	Inspect(context.Context, string, string) (map[string][]string, error)
	Resolve(context.Context, string, string, string, string, map[string]any) (string, error)
	Close()
}
type SourceInput struct {
	Name        string `json:"name"`
	Script      string `json:"script"`
	URL         string `json:"url"`
	Environment string `json:"environment"`
}
type BatchInput struct {
	TrackIDs []string `json:"trackIds"`
	Quality  string   `json:"quality"`
	Name     string   `json:"name"`
}
type problem struct {
	status int
	detail string
}

func (p *problem) Error() string   { return p.detail }
func (p *problem) StatusCode() int { return p.status }
func bad(v string) error           { return &problem{400, v} }
func conflict(v string) error      { return &problem{409, v} }
func str(row map[string]any, key string) string {
	if row == nil || row[key] == nil {
		return ""
	}
	return fmt.Sprint(row[key])
}
func number(row map[string]any, key string) int64 {
	switch v := row[key].(type) {
	case int64:
		return v
	case int:
		return int64(v)
	case json.Number:
		n, _ := v.Int64()
		return n
	case float64:
		return int64(v)
	case string:
		n, _ := strconv.ParseInt(v, 10, 64)
		return n
	}
	return 0
}
func encode(v any) string {
	b, e := json.Marshal(v)
	if e != nil {
		panic(e)
	}
	return string(b)
}
func parseTrack(value string) (Track, error) {
	var t Track
	d := json.NewDecoder(strings.NewReader(value))
	d.UseNumber()
	if d.Decode(&t) != nil || t.ID == "" || platforms[t.Platform] == "" {
		return t, bad("歌曲预览信息失效，请重新搜索")
	}
	return t, nil
}
func now() string          { return time.Now().UTC().Format(time.RFC3339Nano) }
func hash(v string) string { b := sha256.Sum256([]byte(v)); return hex.EncodeToString(b[:]) }
func uuid() string {
	var b [16]byte
	if _, e := rand.Read(b[:]); e != nil {
		panic(e)
	}
	b[6] = b[6]&15 | 64
	b[8] = b[8]&63 | 128
	return formatUUID(b)
}
func songID(source, path string) string {
	b := md5.Sum([]byte(source + "\x00" + path))
	b[6] = b[6]&15 | 48
	b[8] = b[8]&63 | 128
	return formatUUID(b)
}
func formatUUID(b [16]byte) string {
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[:4], b[4:6], b[6:8], b[8:10], b[10:])
}
func activeKey(source, track, quality string) string { return source + ":" + track + ":" + quality }
func contains(list []string, v string) bool {
	for _, x := range list {
		if x == v {
			return true
		}
	}
	return false
}
func nullable(v string) any {
	if v == "" {
		return nil
	}
	return v
}
func bounds(page, size int) error {
	if page < 0 || page > 100000 || size < 1 || size > 100 {
		return bad("分页参数无效")
	}
	return nil
}
func statusError(err error) int {
	var p interface{ StatusCode() int }
	if errors.As(err, &p) {
		return p.StatusCode()
	}
	return 500
}

type Projector func(context.Context, store.DBTX, string, string) error

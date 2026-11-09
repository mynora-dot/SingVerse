// Package feedback manages idempotent missing-song submissions and review.
package feedback

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"embed"
	"encoding/hex"
	"encoding/json"
	"errors"
	"regexp"
	"strings"
	"time"
	"unicode"

	"github.com/jmoiron/sqlx"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/search"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

//go:embed sql/*.sql
var files embed.FS

type Song struct {
	Title   string
	Artists []string
}
type SongReader func(context.Context, store.DBTX, string) (Song, error)
type Service struct {
	db    *sqlx.DB
	q     map[string]string
	songs SongReader
}
type failure struct {
	status int
	detail string
}

func (e *failure) Error() string           { return e.detail }
func fail(status int, detail string) error { return &failure{status, detail} }

type Feedback struct {
	ID                    string   `json:"id"`
	Title                 string   `json:"title"`
	Artist                string   `json:"artist"`
	VersionLabel          string   `json:"versionLabel"`
	Description           string   `json:"description"`
	SubmittedBy           string   `json:"submittedBy"`
	Source                string   `json:"source"`
	Status                string   `json:"status"`
	HandlingNote          string   `json:"handlingNote"`
	ResolvedSongID        *string  `json:"resolvedSongId"`
	ResolvedSongTitle     *string  `json:"resolvedSongTitle"`
	ResolvedSongArtists   []string `json:"resolvedSongArtists"`
	ResolvedSongAvailable bool     `json:"resolvedSongAvailable"`
	Revision              int64    `json:"revision"`
	CreatedAt             string   `json:"createdAt"`
	UpdatedAt             string   `json:"updatedAt"`
	ResolvedAt            *string  `json:"resolvedAt"`
}
type Create struct {
	RequestID    string  `json:"requestId"`
	Title        *string `json:"title"`
	Artist       *string `json:"artist"`
	VersionLabel *string `json:"versionLabel"`
	Description  *string `json:"description"`
	SubmittedBy  *string `json:"submittedBy"`
}
type Update struct {
	Revision       int64   `json:"revision"`
	Status         string  `json:"status"`
	HandlingNote   *string `json:"handlingNote"`
	ResolvedSongID *string `json:"resolvedSongId"`
}
type Page struct {
	Items         []Feedback `json:"items"`
	TotalElements int64      `json:"totalElements"`
	Page          int        `json:"page"`
	Size          int        `json:"size"`
}
type row struct {
	Feedback
	hash string
}

var uuid = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)

func New(db *sqlx.DB, songs SongReader) (*Service, error) {
	q, err := store.ReadQueries(files, "sql")
	if err != nil {
		return nil, err
	}
	return &Service{db, q, songs}, nil
}
func validStatus(s string) bool {
	return s == "PENDING" || s == "PROCESSING" || s == "RESOLVED" || s == "CLOSED"
}
func timestamp() string { return time.Now().UTC().Format("2006-01-02T15:04:05.000Z") }
func text(v *string, max int, multiline, required bool) (string, error) {
	if v == nil || search.UTF16Length(*v) > max {
		return "", fail(400, "缺歌信息包含无效内容或超过长度限制")
	}
	for _, c := range *v {
		if unicode.IsControl(c) && !(multiline && (c == '\n' || c == '\r' || c == '\t')) {
			return "", fail(400, "缺歌信息包含无效内容或超过长度限制")
		}
	}
	value := strings.TrimSpace(*v)
	if required && search.Normalize(value) == "" {
		return "", fail(400, "请填写歌曲名称")
	}
	return value, nil
}

func scan(r interface{ StructScan(any) error }) (row, error) {
	var stored feedbackRow
	err := r.StructScan(&stored)
	if err != nil {
		return row{}, err
	}
	return stored.model()
}
func (s *Service) find(ctx context.Context, db store.DBTX, id string) (row, error) {
	v, err := scan(db.QueryRowxContext(ctx, s.q["find"], id))
	if errors.Is(err, sql.ErrNoRows) {
		return v, fail(404, "缺歌反馈不存在")
	}
	return v, err
}
func (s *Service) Get(ctx context.Context, id string) (Feedback, error) {
	v, err := s.find(ctx, s.db, id)
	return v.Feedback, err
}
func (s *Service) List(ctx context.Context, q, status string, page, size int) (Page, error) {
	result := Page{Items: []Feedback{}, Page: page, Size: size}
	if search.UTF16Length(q) > 200 || (!validStatus(status) && status != "ALL") || page < 0 || page > 1000000 || size < 1 || size > 100 {
		return result, fail(400, "筛选条件或分页无效")
	}
	for _, r := range q {
		if unicode.IsControl(r) {
			return result, fail(400, "筛选条件或分页无效")
		}
	}
	needle := search.Normalize(strings.TrimSpace(q))
	query := ""
	if needle != "" {
		query = "%" + strings.NewReplacer("!", "!!", "%", "!%", "_", "!_").Replace(needle) + "%"
	}
	rows, err := s.db.QueryxContext(ctx, s.q["list"], query, query, status, status, size, int64(page)*int64(size))
	if err != nil {
		return result, err
	}
	for rows.Next() {
		v, e := scan(rows)
		if e != nil {
			rows.Close()
			return result, e
		}
		result.Items = append(result.Items, v.Feedback)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return result, err
	}
	err = s.db.QueryRowContext(ctx, s.q["count"], query, query, status, status).Scan(&result.TotalElements)
	return result, err
}
func (s *Service) Create(ctx context.Context, in Create) (Feedback, error) {
	var out Feedback
	if !uuid.MatchString(in.RequestID) {
		return out, fail(400, "登记请求标识无效")
	}
	inputs := []*string{in.Title, in.Artist, in.VersionLabel, in.Description, in.SubmittedBy}
	limits := []int{200, 200, 120, 2000, 80}
	values := make([]string, 5)
	for i, v := range inputs {
		var err error
		values[i], err = text(v, limits[i], i == 3, i == 0)
		if err != nil {
			return out, err
		}
	}
	encoded, _ := json.Marshal(values)
	sum := sha256.Sum256(encoded)
	hash := hex.EncodeToString(sum[:])
	now := timestamp()
	err := store.WithinTx(ctx, s.db, func(tx store.DBTX) error {
		if _, e := tx.ExecContext(ctx, s.q["lock"]); e != nil {
			return e
		}
		old, e := s.find(ctx, tx, in.RequestID)
		if e == nil {
			if old.hash != hash {
				return fail(409, "这个登记请求已用于其他缺歌信息，请重新登记")
			}
			out = old.Feedback
			return nil
		}
		var problem *failure
		if !errors.As(e, &problem) || problem.status != 404 {
			return e
		}
		_, e = tx.ExecContext(ctx, s.q["insert"], in.RequestID, values[0], values[1], values[2], values[3], values[4], search.Searchable(values[0]+" "+values[1]+" "+values[2]), hash, now, now)
		if e != nil {
			return e
		}
		v, e := s.find(ctx, tx, in.RequestID)
		out = v.Feedback
		return e
	})
	return out, err
}
func (s *Service) Update(ctx context.Context, id string, in Update) (Feedback, error) {
	var out Feedback
	if in.Revision < 1 || !validStatus(in.Status) {
		return out, fail(400, "处理状态或修订号无效")
	}
	note, err := text(in.HandlingNote, 2000, true, false)
	if err != nil {
		return out, err
	}
	if (in.Status == "RESOLVED") != (in.ResolvedSongID != nil) {
		return out, fail(400, "仅已补齐反馈必须关联曲库歌曲")
	}
	if in.ResolvedSongID != nil {
		if strings.TrimSpace(*in.ResolvedSongID) == "" || search.UTF16Length(*in.ResolvedSongID) > 64 {
			return out, fail(400, "请选择有效的关联歌曲")
		}
		for _, r := range *in.ResolvedSongID {
			if unicode.IsControl(r) {
				return out, fail(400, "请选择有效的关联歌曲")
			}
		}
	}
	err = store.WithinTx(ctx, s.db, func(tx store.DBTX) error {
		if _, e := tx.ExecContext(ctx, s.q["lock"]); e != nil {
			return e
		}
		before, e := s.find(ctx, tx, id)
		if e != nil {
			return e
		}
		if before.Revision != in.Revision {
			return fail(409, "反馈已被更新，请重新打开后处理")
		}
		var title, at *string
		artists := []string{}
		now := timestamp()
		if in.ResolvedSongID != nil {
			song, e := s.songs(ctx, tx, *in.ResolvedSongID)
			if e != nil {
				return fail(409, "关联歌曲不存在或已下架，请重新选择")
			}
			title = &song.Title
			artists = song.Artists
			at = &now
			if before.Status == "RESOLVED" && before.ResolvedSongID != nil && *before.ResolvedSongID == *in.ResolvedSongID {
				at = before.ResolvedAt
			}
		}
		encoded, _ := json.Marshal(artists)
		res, e := tx.ExecContext(ctx, s.q["update"], in.Status, note, in.ResolvedSongID, title, string(encoded), at, now, id, in.Revision)
		if e != nil {
			return e
		}
		count, e := res.RowsAffected()
		if e != nil {
			return e
		}
		if count != 1 {
			return fail(409, "反馈已被更新，请重新打开后处理")
		}
		v, e := s.find(ctx, tx, id)
		out = v.Feedback
		return e
	})
	return out, err
}

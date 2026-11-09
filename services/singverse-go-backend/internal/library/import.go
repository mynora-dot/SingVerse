package library

import (
	"context"
	"database/sql"
	"errors"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
	"path"
	"strings"
)

// SupportsMedia reports whether a relative filename is accepted by library indexing.
func SupportsMedia(relative string) bool { return supports(relative) }

// ImportSong inserts a portable relative identity under the caller's transaction.
// Existing songs and retained remote tombstones are preserved.
func (s *Service) ImportSong(ctx context.Context, tx store.DBTX, sourceID, relative string, size *int64) (string, bool, error) {
	if relative == "" || len([]rune(relative)) > 2048 || strings.HasPrefix(relative, "/") || strings.ContainsAny(relative, "\\:") || controls(relative) || !supports(relative) || size != nil && *size < 0 {
		return "", false, bad("索引包路径或大小无效")
	}
	for _, segment := range strings.Split(relative, "/") {
		if strings.TrimSpace(segment) == "" || segment == "." || segment == ".." {
			return "", false, bad("索引包路径无效")
		}
	}
	if _, e := tx.ExecContext(ctx, s.q["config-lock"]); e != nil {
		return "", false, e
	}
	src, e := s.findSource(ctx, tx, sourceID)
	if e != nil {
		return "", false, e
	}
	if src.Type != "WEBDAV" {
		return "", false, bad("仅支持 WebDAV 来源导入")
	}
	id := songID(sourceID, relative)
	var existing string
	e = tx.QueryRowContext(ctx, s.q["song-import-existing"], id, id).Scan(&existing)
	if e == nil {
		return id, false, nil
	}
	if !errors.Is(e, sql.ErrNoRows) {
		return "", false, e
	}
	st, e := s.state(ctx, tx)
	if e != nil {
		return "", false, e
	}
	p, e := profile(st, src.ParserProfileID)
	if e != nil {
		return "", false, e
	}
	rules, e := resolve(st.doc, p.Entries)
	if e != nil {
		return "", false, e
	}
	parsed, e := Parse(path.Base(relative), rules)
	if e != nil {
		return "", false, e
	}
	var selected *string
	if parsed.SelectedRule != nil {
		selected = &parsed.SelectedRule.ID
	}
	_, e = tx.ExecContext(ctx, s.q["song-insert"], id, sourceID, relative, hash(relative), size, nil, now(), p.ID, st.revision, selected, encode(parsed.Result), parsed.Result.NeedsReview, nil)
	return id, e == nil, e
}

package library

import (
	"context"
	"net/url"
	"strings"

	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

// MediaURL combines a saved root and an unescaped relative path exactly once.
// Credentials and transient download URLs never form part of portable identity.
func MediaURL(root, relative string) (string, error) {
	u, e := url.Parse(root)
	if e != nil || u.Hostname() == "" || (u.Scheme != "http" && u.Scheme != "https") || u.User != nil || u.RawQuery != "" || u.ForceQuery || u.Fragment != "" || !validRelative(relative) {
		return "", bad("媒体根地址或相对路径无效")
	}
	u.Path = strings.TrimSuffix(u.Path, "/") + "/" + relative
	u.RawPath = ""
	return u.String(), nil
}

// MediaDirectory lists one directory through the same credential, confinement
// and access gate as scanning. It does not probe or download the media body.
func (s *Service) MediaDirectory(ctx context.Context, sourceID, directory string) (map[string]*int64, bool, error) {
	src, e := s.findSource(ctx, s.db, sourceID)
	if e != nil {
		return nil, false, e
	}
	if src.Type != "WEBDAV" {
		return nil, false, bad("仅支持 WebDAV 媒体目录核验")
	}
	if directory != "" && !validRelative(directory) {
		return nil, false, bad("媒体目录无效")
	}
	root, e := url.Parse(src.Location)
	if e != nil {
		return nil, false, e
	}
	auth, e := s.authorization(src)
	if e != nil {
		return nil, false, e
	}
	target := root.Path + directory
	if !strings.HasSuffix(target, "/") {
		target += "/"
	}
	entries := map[string]*int64{}
	e = s.listDAVSource(ctx, sourceID, root, target, auth, func(entry davEntry) error {
		if !entry.directory {
			entries[strings.TrimPrefix(entry.path, root.Path)] = entry.size
		}
		return nil
	})
	missing := e != nil && e.Error() == "WebDAV 目录不存在，请检查 /dav/ 地址及挂载目录"
	return entries, missing, e
}

// RebindWith preserves the song identity, edits and shared evidence. Callers
// enqueue verification in this same transaction before exposing the new root.
func (s *Service) RebindWith(ctx context.Context, tx store.DBTX, id string, in SourceInput) (Source, error) {
	if _, e := tx.ExecContext(ctx, s.q["config-lock"]); e != nil {
		return Source{}, e
	}
	old, e := s.findSource(ctx, tx, id)
	if e != nil {
		return Source{}, e
	}
	if old.Type != "WEBDAV" {
		return Source{}, bad("仅 WebDAV 支持重新绑定媒体目录")
	}
	in.Type = old.Type
	in.Name = old.Name
	in.ParserProfileID = &old.ParserProfileID
	src, e := s.validateSource(ctx, tx, id, in, &old)
	if e != nil {
		return Source{}, e
	}
	if _, e = tx.ExecContext(ctx, s.q["source-update"], src.Name, src.Location, hash(src.Type+":"+src.Location), src.Username, src.encrypted, src.ParserProfileID, src.UpdatedAt, src.ScanEnabled, src.ScanCron, src.NextScanAt, id); e != nil {
		return Source{}, e
	}
	u, _ := url.Parse(src.Location)
	a := map[string]any{"sourceId": id, "binding": hash(src.Location), "group": hash(u.Scheme + "://" + u.Host + "\x00" + src.Username)}
	if _, e = s.named(ctx, tx, "dav-configInsert", a); e != nil {
		return Source{}, e
	}
	for _, q := range []string{"binding-config", "binding-resources", "binding-tasks", "binding-resetShared", "binding-touchFiles"} {
		if _, e = s.named(ctx, tx, q, a); e != nil {
			return Source{}, e
		}
	}
	return src, nil
}

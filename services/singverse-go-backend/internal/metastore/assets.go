package metastore

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/jmoiron/sqlx"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/metastandard"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

type Asset struct {
	ID             string  `db:"id" json:"id"`
	MIME           string  `db:"mime" json:"mime"`
	Size           int64   `db:"byte_size" json:"size"`
	Suffix         string  `db:"suffix" json:"-"`
	Width          *int    `db:"width" json:"width,omitempty"`
	Height         *int    `db:"height" json:"height,omitempty"`
	CreatedAt      string  `db:"created_at" json:"-"`
	UnreferencedAt *string `db:"unreferenced_at" json:"-"`
}

func (s *Store) AssetPath(id string) string {
	return filepath.Join(s.Root, "assets", id[:2], id+".bin")
}

// PutAsset preserves exact original bytes. fsync+rename precede registration;
// callers commit references only after this returns. A crash can leave an
// unreferenced file, never a committed reference to an unfinished file.
func (s *Store) PutAsset(ctx context.Context, db store.DBTX, data []byte, mime string) (Asset, error) {
	a := Asset{ID: metastandard.Digest(data), MIME: mime, Size: int64(len(data)), Suffix: ".bin", CreatedAt: timestamp()}
	if len(data) == 0 || len(data) > metastandard.ImageLimit {
		return a, &Problem{413, "资料附件大小必须为 1 字节至 32 MiB"}
	}
	width, height, e := metastandard.InspectAsset(data, mime)
	if e != nil {
		return a, &Problem{400, e.Error()}
	}
	if width > 0 {
		a.Width, a.Height = &width, &height
	}
	path := s.AssetPath(a.ID)
	if current, e := os.ReadFile(path); e == nil {
		if metastandard.Digest(current) != a.ID {
			return a, errors.New("persistent asset digest mismatch")
		}
	} else {
		if !errors.Is(e, os.ErrNotExist) {
			return a, e
		}
		if e = os.MkdirAll(filepath.Dir(path), 0700); e != nil {
			return a, e
		}
		f, e := os.CreateTemp(filepath.Dir(path), ".asset-*")
		if e != nil {
			return a, e
		}
		name := f.Name()
		defer os.Remove(name)
		if _, e = f.Write(data); e == nil {
			e = f.Sync()
		}
		closeErr := f.Close()
		if e == nil {
			e = closeErr
		}
		if e != nil {
			return a, e
		}
		if e = os.Rename(name, path); e != nil {
			return a, e
		}
		dir, e := os.Open(filepath.Dir(path))
		if e != nil {
			return a, e
		}
		e = dir.Sync()
		dir.Close()
		if e != nil {
			return a, e
		}
	}
	if _, e = db.ExecContext(ctx, s.q["asset-insert"], a.ID, a.MIME, a.Size, a.Suffix, a.Width, a.Height, a.CreatedAt, a.CreatedAt, a.ID); e != nil {
		return a, e
	}
	var saved Asset
	if e = sqlx.GetContext(ctx, db, &saved, s.q["asset-read"], a.ID); e != nil {
		return a, e
	}
	if saved.MIME != a.MIME || saved.Size != a.Size {
		return a, &Problem{409, "相同资源的类型或大小与本机登记不一致"}
	}
	return saved, nil
}
func (s *Store) ReadAsset(ctx context.Context, db store.DBTX, id string) (Asset, []byte, error) {
	var a Asset
	if !metastandard.SHA256.MatchString(id) {
		return a, nil, &Problem{400, "资料资源标识无效"}
	}
	if e := sqlx.GetContext(ctx, db, &a, s.q["asset-read"], id); e != nil {
		return a, nil, e
	}
	f, e := os.Open(s.AssetPath(id))
	if e != nil {
		return a, nil, e
	}
	defer f.Close()
	data, e := io.ReadAll(io.LimitReader(f, metastandard.ImageLimit+1))
	if e != nil {
		return a, nil, e
	}
	if int64(len(data)) != a.Size || metastandard.Digest(data) != id {
		return a, nil, errors.New("persistent asset integrity failure")
	}
	return a, data, nil
}
func (s *Store) Alias(ctx context.Context, db store.DBTX, legacy, id string) error {
	_, e := db.ExecContext(ctx, s.q["asset-alias-insert"], legacy, id, legacy)
	return e
}
func (s *Store) ResolveAsset(ctx context.Context, db store.DBTX, id string) (string, error) {
	var mapped string
	e := db.QueryRowContext(ctx, s.q["asset-alias-read"], id).Scan(&mapped)
	if errors.Is(e, sql.ErrNoRows) && metastandard.SHA256.MatchString(id) {
		return id, nil
	}
	return mapped, e
}

// References replaces one owner's complete reference set atomically. CURRENT
// has no expiry. IMPORT/EXPORT leases are renewed before each bounded batch.
func (s *Store) References(ctx context.Context, db store.DBTX, kind, owner string, ids []string, expiry *string) error {
	previous := []string{}
	if e := sqlx.SelectContext(ctx, db, &previous, s.q["asset-owner-refs"], kind, owner); e != nil {
		return e
	}
	if _, e := db.ExecContext(ctx, s.q["asset-ref-delete"], kind, owner); e != nil {
		return e
	}
	seen := map[string]bool{}
	for _, id := range ids {
		if seen[id] {
			continue
		}
		seen[id] = true
		var a Asset
		if e := sqlx.GetContext(ctx, db, &a, s.q["asset-read"], id); e != nil {
			return e
		}
		info, e := os.Stat(s.AssetPath(id))
		if e != nil || info.Size() != a.Size {
			return errors.New("asset is not durably available")
		}
		if _, e = db.ExecContext(ctx, s.q["asset-ref-insert"], kind, owner, id, expiry); e != nil {
			return e
		}
		if _, e = db.ExecContext(ctx, s.q["asset-mark-used"], id); e != nil {
			return e
		}
	}
	for _, id := range previous {
		if seen[id] {
			continue
		}
		if _, e := db.ExecContext(ctx, s.q["asset-mark-unused-one"], timestamp(), id); e != nil {
			return e
		}
	}
	return nil
}

// SweepOrphans reclaims bytes left before a transaction committed, or after a
// registry deletion. Eight rotating hash buckets keep each hourly pass bounded.
// The caller holds the same database write transaction used by PutAsset.
func (s *Store) SweepOrphans(ctx context.Context, db store.DBTX) error {
	cutoff := time.Now().Add(-7 * 24 * time.Hour)
	start := int(time.Now().Unix()/3600*8) % 256
	removed := 0
	for bucket := 0; bucket < 8; bucket++ {
		dir := filepath.Join(s.Root, "assets", fmt.Sprintf("%02x", (start+bucket)%256))
		entries, e := os.ReadDir(dir)
		if errors.Is(e, os.ErrNotExist) {
			continue
		}
		if e != nil {
			return e
		}
		for _, entry := range entries {
			if ctx.Err() != nil {
				return ctx.Err()
			}
			if entry.Type()&os.ModeSymlink != 0 || entry.IsDir() {
				continue
			}
			info, e := entry.Info()
			if e != nil || !info.Mode().IsRegular() || info.ModTime().After(cutoff) {
				continue
			}
			name := entry.Name()
			id := strings.TrimSuffix(name, ".bin")
			if metastandard.SHA256.MatchString(id) && name == id+".bin" {
				var asset Asset
				e = sqlx.GetContext(ctx, db, &asset, s.q["asset-read"], id)
				if e == nil {
					continue
				}
				if !errors.Is(e, sql.ErrNoRows) {
					return e
				}
			} else if !strings.HasPrefix(name, ".asset-") {
				continue
			}
			if e = os.Remove(filepath.Join(dir, name)); e != nil && !errors.Is(e, os.ErrNotExist) {
				return e
			}
			removed++
			if removed >= 250 {
				return nil
			}
		}
	}
	return nil
}
func LeaseExpiry() string { return time.Now().UTC().Add(48 * time.Hour).Format(time.RFC3339Nano) }
func (s *Store) Cleanup(ctx context.Context, db store.DBTX) error {
	if _, e := db.ExecContext(ctx, s.q["asset-expire"], timestamp()); e != nil {
		return e
	}
	if _, e := db.ExecContext(ctx, s.q["asset-mark-unused"], timestamp()); e != nil {
		return e
	}
	var rows []struct {
		ID     string `db:"id"`
		Suffix string `db:"suffix"`
	}
	if e := sqlx.SelectContext(ctx, db, &rows, s.q["asset-garbage"], time.Now().UTC().Add(-7*24*time.Hour).Format(time.RFC3339Nano)); e != nil {
		return e
	}
	for _, row := range rows {
		// Called under the same write transaction as reference changes. Delete
		// the row first; a failed unlink only leaves reclaimable orphan bytes.
		r, e := db.ExecContext(ctx, s.q["asset-delete"], row.ID)
		if e != nil {
			return e
		}
		n, e := r.RowsAffected()
		if e != nil {
			return e
		}
		if n == 1 {
			if e = os.Remove(s.AssetPath(row.ID)); e != nil && !errors.Is(e, os.ErrNotExist) {
				return e
			}
		}
	}
	return s.SweepOrphans(ctx, db)
}

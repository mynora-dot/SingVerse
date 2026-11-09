package execution

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

const originalBlockBytes = int64(2 << 20)
const originalWriteBudget = int64(32 << 20)

type prefetchContextKey struct{}

var errRangeUnsupported = errors.New("original range unsupported")

// ServeCachedOriginal is the library's WebDAV-only authorized read-through hook.
// A failed optional cache operation falls back before any response is committed.
func (s *Service) ServeCachedOriginal(ctx context.Context, w http.ResponseWriter, r *http.Request, songID string, fetch func(http.ResponseWriter, *http.Request) error, guard func() error) (bool, error) {
	if r.Method != "GET" && r.Method != "HEAD" {
		return false, nil
	}
	song, err := s.song(ctx, s.db, songID)
	if err != nil {
		return true, err
	}
	if str(song, "source_type") != "WEBDAV" {
		return false, nil
	}
	size := integer(song["file_size"])
	if song["file_size"] == nil || size <= 0 {
		return false, nil
	}
	start, end, status, ok := originalRange(r.Header.Get("Range"), size)
	if !ok {
		w.Header().Set("Content-Range", fmt.Sprintf("bytes */%d", size))
		w.Header().Set("Content-Length", "0")
		w.WriteHeader(416)
		return true, nil
	}
	if r.Header.Get("If-Range") != "" {
		return false, nil
	}
	fp := playbackFingerprint(song)
	check := func() error {
		if err := ctx.Err(); err != nil {
			return err
		}
		if guard != nil {
			if err := guard(); err != nil {
				return err
			}
		}
		current, err := s.song(ctx, s.db, songID)
		if err != nil {
			return err
		}
		if playbackFingerprint(current) != fp {
			return fail(409, "源媒体已变化，请重新扫描来源")
		}
		return nil
	}
	if err = check(); err != nil {
		return true, err
	}
	entry, err := s.originalEntry(ctx, song, fp, size)
	if err != nil {
		return false, nil
	}
	id := str(entry, "id")
	s.cacheMu.Lock()
	s.cacheReaders[id]++
	s.cacheMu.Unlock()
	defer func() { s.cacheMu.Lock(); s.cacheReaders[id]--; s.cacheMu.Unlock() }()
	prefetch, _ := ctx.Value(prefetchContextKey{}).(bool)
	if !prefetch {
		s.originalForeground.Add(1)
		defer s.originalForeground.Add(-1)
		s.mu.Lock()
		for _, cancel := range s.prefetchFetch {
			cancel()
		}
		s.mu.Unlock()
	}
	if r.Method == "HEAD" {
		originalHeaders(w, size, start, end, status)
		w.WriteHeader(status)
		return true, nil
	}
	budget := originalWriteBudget
	first, err := s.originalBlock(ctx, entry, start/originalBlockBytes, size, r, fetch, check, &budget)
	if errors.Is(err, errRangeUnsupported) {
		return false, nil
	}
	if err != nil {
		return true, err
	}
	originalHeaders(w, size, start, end, status)
	w.WriteHeader(status)
	for index := start / originalBlockBytes; index <= end/originalBlockBytes; index++ {
		data := first
		if index != start/originalBlockBytes {
			data, err = s.originalBlock(ctx, entry, index, size, r, fetch, check, &budget)
			if err != nil {
				return true, err
			}
		}
		from, to := max(start-index*originalBlockBytes, int64(0)), min(end-index*originalBlockBytes+1, int64(len(data)))
		if from < 0 || to < from || to > int64(len(data)) {
			return true, fail(502, "原片分块长度不一致")
		}
		for from < to {
			if err = check(); err != nil {
				return true, err
			}
			n, e := w.Write(data[from:min(from+65536, to)])
			from += int64(n)
			if e != nil {
				return true, e
			}
			if n == 0 {
				return true, io.ErrShortWrite
			}
		}
	}
	_, _ = s.exec(ctx, s.db, "cache-hit", map[string]any{"id": id, "now": now()})
	return true, nil
}
func originalHeaders(w http.ResponseWriter, size, start, end int64, status int) {
	w.Header().Set("Accept-Ranges", "bytes")
	w.Header().Set("Content-Length", strconv.FormatInt(end-start+1, 10))
	w.Header().Set("Cache-Control", "private, no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	if status == 206 {
		w.Header().Set("Content-Range", fmt.Sprintf("bytes %d-%d/%d", start, end, size))
	} else {
		w.Header().Del("Content-Range")
	}
}
func originalRange(value string, size int64) (int64, int64, int, bool) {
	if value == "" {
		return 0, size - 1, 200, size > 0
	}
	if !strings.HasPrefix(value, "bytes=") || strings.Contains(value, ",") {
		return 0, 0, 0, false
	}
	parts := strings.Split(strings.TrimPrefix(value, "bytes="), "-")
	if len(parts) != 2 {
		return 0, 0, 0, false
	}
	if parts[0] == "" {
		suffix, err := strconv.ParseInt(parts[1], 10, 64)
		if err != nil || suffix <= 0 {
			return 0, 0, 0, false
		}
		return max(int64(0), size-suffix), size - 1, 206, true
	}
	start, err := strconv.ParseInt(parts[0], 10, 64)
	if err != nil || start < 0 || start >= size {
		return 0, 0, 0, false
	}
	end := size - 1
	if parts[1] != "" {
		end, err = strconv.ParseInt(parts[1], 10, 64)
		if err != nil || end < start {
			return 0, 0, 0, false
		}
		end = min(end, size-1)
	}
	return start, end, 206, true
}
func (s *Service) originalEntry(ctx context.Context, song map[string]any, fp string, size int64) (map[string]any, error) {
	s.cacheMu.Lock()
	defer s.cacheMu.Unlock()
	key := hash("original:v1:" + str(song, "id") + ":" + fp)
	prior, err := s.optional(ctx, s.db, "cache-by-key", map[string]any{"key": key})
	if err != nil {
		return nil, err
	}
	if prior != nil {
		folder, e := s.cacheFolder(str(prior, "id"))
		if e != nil || str(prior, "state") == "DELETING" || !ownedFolder(folder) {
			return nil, fail(409, "原片缓存不可用")
		}
		return prior, nil
	}
	id := uuid()
	folder, _ := s.cacheFolder(id)
	settings, err := s.cacheSettings(ctx)
	if err != nil {
		return nil, err
	}
	usage, err := s.usage(ctx)
	if err != nil {
		return nil, err
	}
	if usage+s.reserved()+int64(len(cacheOwner)) > integer(settings["maxBytes"]) {
		return nil, fail(507, "缓存容量不足")
	}
	if err = os.Mkdir(folder, 0700); err != nil {
		return nil, err
	}
	if err = os.WriteFile(filepath.Join(folder, cacheOwnerFile), []byte(cacheOwner), 0600); err != nil {
		_ = os.Remove(folder)
		return nil, err
	}
	row := map[string]any{"id": id, "cache_key": key, "song_id": str(song, "id"), "execution_json": "{}", "target_json": encoded(map[string]any{"mode": "ORIGINAL", "sourceBytes": size, "blockBytes": originalBlockBytes, "fingerprint": fp, "rangeSupported": true}), "artifacts_json": "{}", "media_type": "ORIGINAL", "bytes": int64(0), "created_at": now()}
	if _, err = s.exec(ctx, s.db, "cache-insert", row); err != nil {
		_ = os.RemoveAll(folder)
		return nil, err
	}
	return row, nil
}

type originalCapture struct {
	header http.Header
	status int
	body   bytes.Buffer
	limit  int64
}

func (c *originalCapture) Header() http.Header { return c.header }
func (c *originalCapture) WriteHeader(status int) {
	if c.status == 0 {
		c.status = status
	}
}
func (c *originalCapture) Write(p []byte) (int, error) {
	if c.status == 0 {
		c.status = 200
	}
	if int64(c.body.Len())+int64(len(p)) > c.limit {
		return 0, errRangeUnsupported
	}
	return c.body.Write(p)
}
func (s *Service) originalBlock(ctx context.Context, entry map[string]any, index, size int64, r *http.Request, fetch func(http.ResponseWriter, *http.Request) error, guard func() error, budget *int64) ([]byte, error) {
	id := str(entry, "id")
	name := "original_" + strconv.FormatInt(index, 10) + ".block"
	folder, _ := s.cacheFolder(id)
	expected := min(originalBlockBytes, size-index*originalBlockBytes)
	flight := id + ":" + name
	for {
		s.cacheMu.Lock()
		if waiting := s.originalFlights[flight]; waiting != nil {
			s.cacheMu.Unlock()
			select {
			case <-ctx.Done():
				return nil, ctx.Err()
			case <-waiting:
				continue
			}
		}
		if data, ok := readOriginalBlock(folder, name, expected); ok {
			s.cacheMu.Unlock()
			return data, nil
		}
		s.originalFlights[flight] = make(chan struct{})
		s.cacheMu.Unlock()
		break
	}
	defer func() {
		s.cacheMu.Lock()
		close(s.originalFlights[flight])
		delete(s.originalFlights, flight)
		s.cacheMu.Unlock()
	}()
	if err := guard(); err != nil {
		return nil, err
	}
	start := index * originalBlockBytes
	req := r.Clone(ctx)
	req.Method = "GET"
	req.Header = r.Header.Clone()
	req.Header.Set("Range", fmt.Sprintf("bytes=%d-%d", start, start+expected-1))
	req.Header.Del("If-Range")
	req.Header.Del("If-None-Match")
	req.Header.Del("If-Modified-Since")
	capture := &originalCapture{header: make(http.Header), limit: expected}
	err := fetch(capture, req)
	if err != nil {
		return nil, err
	}
	if capture.status != 206 && !(capture.status == 200 && size <= originalBlockBytes && start == 0) {
		return nil, errRangeUnsupported
	}
	if capture.status == 206 && capture.header.Get("Content-Range") != fmt.Sprintf("bytes %d-%d/%d", start, start+expected-1, size) {
		return nil, fail(502, "原片范围与扫描记录不一致")
	}
	if int64(capture.body.Len()) != expected {
		return nil, fail(502, "原片分块长度不一致")
	}
	data := capture.body.Bytes()
	if err = guard(); err != nil {
		return nil, err
	}
	if *budget >= expected {
		if s.persistOriginalBlock(ctx, entry, name, data, guard) == nil {
			*budget -= expected
		}
	}
	return data, nil
}
func readOriginalBlock(folder, name string, expected int64) ([]byte, bool) {
	if !ownedFolder(folder) {
		return nil, false
	}
	info, err := os.Lstat(filepath.Join(folder, name))
	if err != nil || !info.Mode().IsRegular() || info.Size() != expected {
		return nil, false
	}
	root, err := os.OpenRoot(folder)
	if err != nil {
		return nil, false
	}
	defer root.Close()
	f, err := root.Open(name)
	if err != nil {
		return nil, false
	}
	defer f.Close()
	data, err := io.ReadAll(io.LimitReader(f, expected+1))
	return data, err == nil && int64(len(data)) == expected
}
func (s *Service) persistOriginalBlock(ctx context.Context, entry map[string]any, name string, data []byte, guard func() error) error {
	s.cacheMu.Lock()
	defer s.cacheMu.Unlock()
	if err := guard(); err != nil {
		return err
	}
	id := str(entry, "id")
	current, err := s.optional(ctx, s.db, "cache-find", map[string]any{"id": id})
	if err != nil {
		return err
	}
	if current == nil || str(current, "state") == "DELETING" {
		return fail(409, "缓存已进入清理")
	}
	settings, err := s.cacheSettings(ctx)
	if err != nil {
		return err
	}
	usage, err := s.usage(ctx)
	if err != nil {
		return err
	}
	free, err := availableBytes(s.cfg.SongCachePath)
	if err != nil {
		return err
	}
	needed := int64(len(data))
	if usage+s.reserved()+needed > integer(settings["maxBytes"]) || free-needed < s.cfg.SongCacheMinimumFreeBytes {
		return fail(507, "缓存空间不足")
	}
	folder, _ := s.cacheFolder(id)
	if !ownedFolder(folder) {
		return fail(409, "缓存所有权无效")
	}
	root, err := os.OpenRoot(folder)
	if err != nil {
		return err
	}
	defer root.Close()
	if regular(filepath.Join(folder, name)) {
		return nil
	}
	partial := name + ".part"
	_ = root.Remove(partial)
	f, err := root.OpenFile(partial, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
	if err != nil {
		return err
	}
	defer root.Remove(partial)
	if _, err = f.Write(data); err != nil {
		f.Close()
		return err
	}
	if err = f.Sync(); err != nil {
		f.Close()
		return err
	}
	if err = f.Close(); err != nil {
		return err
	}
	if err = guard(); err != nil {
		return err
	}
	if err = root.Rename(partial, name); err != nil {
		return err
	}
	_, err = s.exec(ctx, s.db, "cache-add-bytes", map[string]any{"id": id, "bytes": needed, "now": now()})
	return err
}

// PrefetchOriginal resumes persistent blocks. Foreground readers have priority;
// cancellation is rechecked before fetching and publishing every block.
func (s *Service) PrefetchOriginal(song, owner string, active func() bool) error {
	if song == "" || owner == "" || len(owner) > 160 || active == nil {
		return fail(400, "预取参数无效")
	}
	s.mu.Lock()
	if s.ctx.Err() != nil {
		s.mu.Unlock()
		return s.ctx.Err()
	}
	if len(s.preloads) >= 16 && s.preloads[owner] == nil {
		s.mu.Unlock()
		return fail(429, "预取任务过多")
	}
	if prior := s.preloads[owner]; prior != nil {
		prior()
	}
	ctx, cancel := context.WithCancel(s.ctx)
	generation := uuid()
	s.prefetchIDs[owner] = generation
	s.preloads[owner] = cancel
	s.cacheMu.Lock()
	s.prefetchSongs[owner] = song
	s.cacheMu.Unlock()
	s.wg.Add(1)
	s.mu.Unlock()
	go func() {
		defer s.wg.Done()
		defer cancel()
		defer func() {
			s.mu.Lock()
			if s.prefetchIDs[owner] == generation {
				delete(s.preloads, owner)
				delete(s.prefetchIDs, owner)
				delete(s.prefetchFetch, owner)
				s.cacheMu.Lock()
				delete(s.prefetchSongs, owner)
				s.cacheMu.Unlock()
			}
			s.mu.Unlock()
		}()
		row, err := s.song(ctx, s.db, song)
		if err != nil || str(row, "source_type") != "WEBDAV" {
			return
		}
		size := integer(row["file_size"])
		if size <= 0 {
			return
		}
		fp := playbackFingerprint(row)
		guard := func() error {
			if ctx.Err() != nil {
				return ctx.Err()
			}
			if !active() {
				return context.Canceled
			}
			latest, e := s.song(ctx, s.db, song)
			if e != nil {
				return e
			}
			if playbackFingerprint(latest) != fp {
				return context.Canceled
			}
			return nil
		}
		last := (size - 1) / originalBlockBytes
		for ordinal := int64(0); ordinal <= last; ordinal++ {
			index := ordinal
			if ordinal == 1 {
				index = last
			} else if ordinal > 1 {
				index = ordinal - 1
			}
			for {
				for s.originalForeground.Load() > 0 {
					timer := time.NewTimer(200 * time.Millisecond)
					select {
					case <-ctx.Done():
						timer.Stop()
						return
					case <-timer.C:
					}
					if guard() != nil {
						return
					}
				}
				if guard() != nil {
					return
				}
				readCtx, stopRead := context.WithCancel(context.WithValue(ctx, prefetchContextKey{}, true))
				s.mu.Lock()
				if s.prefetchIDs[owner] != generation {
					s.mu.Unlock()
					stopRead()
					return
				}
				s.prefetchFetch[owner] = stopRead
				if s.originalForeground.Load() > 0 {
					stopRead()
				}
				s.mu.Unlock()
				request, _ := http.NewRequestWithContext(readCtx, "GET", "http://singverse.invalid/prefetch", nil)
				request.Header.Set("Range", fmt.Sprintf("bytes=%d-%d", index*originalBlockBytes, min(size-1, (index+1)*originalBlockBytes-1)))
				out := &originalCapture{header: make(http.Header), limit: originalBlockBytes}
				err = s.library.ServeOriginalGuard(readCtx, out, request, song, guard)
				interrupted := readCtx.Err() != nil
				stopRead()
				s.mu.Lock()
				if s.prefetchIDs[owner] == generation {
					delete(s.prefetchFetch, owner)
				}
				s.mu.Unlock()
				if guard() != nil {
					return
				}
				if interrupted {
					continue
				}
				if err != nil || out.status != 206 && out.status != 200 {
					return
				}
				break
			}
		}
	}()
	return nil
}
func (s *Service) CancelOriginalPrefetch(owner string) {
	s.mu.Lock()
	if cancel := s.preloads[owner]; cancel != nil {
		cancel()
		delete(s.preloads, owner)
		delete(s.prefetchIDs, owner)
		if stopRead := s.prefetchFetch[owner]; stopRead != nil {
			stopRead()
			delete(s.prefetchFetch, owner)
		}
	}
	s.mu.Unlock()
	s.cacheMu.Lock()
	delete(s.prefetchSongs, owner)
	s.cacheMu.Unlock()
}

// Restore accounting from owned, regular block files after interrupted writes.
func (s *Service) repairOriginal(ctx context.Context, row map[string]any) {
	target := decoded(row["target_json"])
	if str(target, "mode") != "ORIGINAL" {
		return
	}
	folder, _ := s.cacheFolder(str(row, "id"))
	if !ownedFolder(folder) {
		return
	}
	entries, err := os.ReadDir(folder)
	if err != nil {
		return
	}
	size := integer(target["sourceBytes"])
	var complete, residue int64
	for _, entry := range entries {
		if entry.Name() == cacheOwnerFile {
			continue
		}
		info, e := entry.Info()
		if e != nil || !info.Mode().IsRegular() {
			continue
		}
		name := entry.Name()
		valid := false
		if strings.HasPrefix(name, "original_") && strings.HasSuffix(name, ".block") {
			index, e := strconv.ParseInt(strings.TrimSuffix(strings.TrimPrefix(name, "original_"), ".block"), 10, 64)
			if e == nil && index >= 0 && index <= (size-1)/originalBlockBytes && info.Size() == min(originalBlockBytes, size-index*originalBlockBytes) {
				complete += info.Size()
				valid = true
			}
		}
		if !valid {
			residue += info.Size()
		}
	}
	_, _ = s.exec(ctx, s.db, "cache-repair-original-bytes", map[string]any{"id": str(row, "id"), "bytes": complete, "residue": residue, "now": now()})
}

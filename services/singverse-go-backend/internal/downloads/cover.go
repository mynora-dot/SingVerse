package downloads

import (
	"bytes"
	"context"
	"encoding/binary"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/downloads/lxruntime"
	"image"
	_ "image/jpeg"
	_ "image/png"
	"sync"
	"time"
)

type coverReference struct {
	url     string
	expires time.Time
}
type coverEntry struct {
	bytes []byte
	kind  string
}
type coverStore struct {
	network catalogNetwork
	mu      sync.Mutex
	refs    map[string]coverReference
	cache   map[string]coverEntry
	order   []string
	clock   func() time.Time
}

func newCovers(network *Network) CoverStore {
	return &coverStore{network: network, refs: map[string]coverReference{}, cache: map[string]coverEntry{}, clock: time.Now}
}
func (c *coverStore) Reference(address string) *string {
	if address == "" {
		return nil
	}
	if _, e := lxruntime.CheckPublicURL(address, false); e != nil {
		return nil
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	now := c.clock()
	if len(c.refs) >= 2000 {
		for id, ref := range c.refs {
			if now.After(ref.expires) {
				delete(c.refs, id)
			}
		}
		if len(c.refs) >= 2000 {
			c.refs = map[string]coverReference{}
		}
	}
	id := hash(address)
	c.refs[id] = coverReference{address, now.Add(time.Hour)}
	path := "/api/v1/lx-downloads/covers/" + id
	return &path
}
func (c *coverStore) Get(ctx context.Context, id string) ([]byte, string, error) {
	c.mu.Lock()
	ref, ok := c.refs[id]
	if !ok || c.clock().After(ref.expires) {
		c.mu.Unlock()
		return nil, "", missingCover()
	}
	if prior, ok := c.cache[id]; ok {
		c.mu.Unlock()
		return append([]byte{}, prior.bytes...), prior.kind, nil
	}
	c.mu.Unlock()
	reply, e := c.network.Request(ctx, ref.url, "GET", nil, nil, 2000000, false)
	if e != nil {
		return nil, "", e
	}
	if reply.Status != 200 {
		return nil, "", missingCover()
	}
	extension, e := validateCover(reply.Bytes)
	if e != nil {
		return nil, "", e
	}
	kind := coverKind(extension)
	c.mu.Lock()
	if _, exists := c.cache[id]; !exists {
		if len(c.cache) >= 16 {
			delete(c.cache, c.order[0])
			c.order = c.order[1:]
		}
		c.order = append(c.order, id)
	}
	c.cache[id] = coverEntry{append([]byte{}, reply.Bytes...), kind}
	c.mu.Unlock()
	return reply.Bytes, kind, nil
}
func coverKind(extension string) string {
	switch extension {
	case "jpg":
		return "image/jpeg"
	case "png":
		return "image/png"
	case "webp":
		return "image/webp"
	}
	return ""
}
func missingCover() error { return &problem{404, "没有可用的封面"} }
func validateCover(b []byte) (string, error) {
	if len(b) == 0 || len(b) > 2000000 {
		return "", missingCover()
	}
	if len(b) < 12 || string(b[:4]) != "RIFF" || string(b[8:12]) != "WEBP" {
		cfg, format, e := image.DecodeConfig(bytes.NewReader(b))
		if e != nil || !contains([]string{"jpeg", "png"}, format) || cfg.Width < 1 || cfg.Height < 1 || cfg.Width > 4096 || cfg.Height > 4096 {
			return "", missingCover()
		}
		if format == "jpeg" {
			return "jpg", nil
		}
		return "png", nil
	}
	if uint64(binary.LittleEndian.Uint32(b[4:8]))+8 != uint64(len(b)) {
		return "", missingCover()
	}
	found := false
	for offset := 12; offset < len(b); {
		if len(b)-offset < 8 {
			return "", missingCover()
		}
		chunk := string(b[offset : offset+4])
		size := uint64(binary.LittleEndian.Uint32(b[offset+4 : offset+8]))
		end := uint64(offset) + 8 + size + (size & 1)
		if end > uint64(len(b)) {
			return "", missingCover()
		}
		start := offset + 8
		var w, h uint32
		switch chunk {
		case "VP8 ":
			if size < 10 || b[start]&1 != 0 || b[start+3] != 157 || b[start+4] != 1 || b[start+5] != 42 {
				return "", missingCover()
			}
			w = uint32(binary.LittleEndian.Uint16(b[start+6:start+8])) & 0x3fff
			h = uint32(binary.LittleEndian.Uint16(b[start+8:start+10])) & 0x3fff
			found = true
		case "VP8L":
			if size < 5 || b[start] != 47 {
				return "", missingCover()
			}
			bits := binary.LittleEndian.Uint32(b[start+1 : start+5])
			if bits>>29 != 0 {
				return "", missingCover()
			}
			w = (bits & 0x3fff) + 1
			h = ((bits >> 14) & 0x3fff) + 1
			found = true
		case "VP8X":
			if size != 10 || b[start]&2 != 0 {
				return "", missingCover()
			}
			w = uint32(b[start+4]) | uint32(b[start+5])<<8 | uint32(b[start+6])<<16
			h = uint32(b[start+7]) | uint32(b[start+8])<<8 | uint32(b[start+9])<<16
			w++
			h++
		case "ANIM", "ANMF":
			return "", missingCover()
		}
		if contains([]string{"VP8 ", "VP8L", "VP8X"}, chunk) && (w < 1 || h < 1 || w > 4096 || h > 4096) {
			return "", missingCover()
		}
		offset = int(end)
	}
	if !found {
		return "", missingCover()
	}
	return "webp", nil
}

package metadata

import (
	"archive/zip"
	"bytes"
	"encoding/base64"
	"encoding/binary"
	"encoding/xml"
	"fmt"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/metastandard"
	"io"
	"math"
	"net/url"
	"os"
	"strings"
	"unicode"
)

const shareLimit = 32 * 1024 * 1024
const uploadLimit = int64(8) * 1024 * 1024 * 1024

func validPortablePath(v string) bool {
	if strings.TrimSpace(v) == "" || len([]rune(v)) > 2048 || strings.HasPrefix(v, "/") || strings.ContainsAny(v, "\\:") {
		return false
	}
	for _, r := range v {
		if unicode.IsControl(r) {
			return false
		}
	}
	for _, s := range strings.Split(v, "/") {
		if strings.TrimSpace(s) == "" || s == "." || s == ".." {
			return false
		}
	}
	return true
}
func xmlText(v any) string {
	var b bytes.Buffer
	_ = xml.EscapeText(&b, []byte(text(v)))
	return b.String()
}
func portableURL(path string) string {
	return (&url.URL{Scheme: "https", Host: "replace.invalid", Path: "/dav/" + path}).String()
}
func packManifest(document map[string]any) ([]byte, error) {
	var b bytes.Buffer
	z := zip.NewWriter(&b)
	expanded := 0
	names := map[string]bool{}
	write := func(name string, data []byte) error {
		if !validPortablePath(name) || names[name] {
			return bad("索引包路径无效或重复")
		}
		names[name] = true
		expanded += len(data)
		if expanded > shareLimit {
			return &problem{413, "索引包展开后超过 32 MiB"}
		}
		w, e := z.Create(name)
		if e != nil {
			return e
		}
		_, e = w.Write(data)
		return e
	}
	if e := write("manifest.json", []byte(encode(document))); e != nil {
		z.Close()
		return nil, e
	}
	if assets, ok := document["assets"].(map[string]any); ok {
		for key, v := range assets {
			if !assetHash.MatchString(key) {
				z.Close()
				return nil, bad("共享资源标识无效")
			}
			data, e := decodeBase64(text(v))
			if e != nil {
				z.Close()
				return nil, e
			}
			if e = write("assets/"+key+".png", data); e != nil {
				z.Close()
				return nil, e
			}
		}
	}
	for _, raw := range array(document["entries"]) {
		item, ok := raw.(map[string]any)
		if !ok {
			z.Close()
			return nil, bad("索引包条目无效")
		}
		path := text(item["path"])
		if !validPortablePath(path) {
			z.Close()
			return nil, bad("索引包路径无效")
		}
		snapshot, ok := item["metadata"].(map[string]any)
		if !ok {
			z.Close()
			return nil, bad("共享元数据无效")
		}
		if e := write(path+".strm", []byte(portableURL(path)+"\n")); e != nil {
			z.Close()
			return nil, e
		}
		tags, _ := snapshot["tags"].(map[string]any)
		nfo := `<?xml version="1.0" encoding="UTF-8"?><musicvideo><title>` + xmlText(tags["title"]) + "</title><album>" + xmlText(tags["album"]) + "</album>"
		for _, artist := range stringsOf(tags["artists"]) {
			nfo += "<artist>" + xmlText(artist) + "</artist>"
		}
		if tags["releaseDate"] != nil {
			nfo += "<premiered>" + xmlText(tags["releaseDate"]) + "</premiered>"
		}
		if duration := decimal(pathValue(snapshot, "technical", "duration")); duration > 0 && !math.IsNaN(duration) && !math.IsInf(duration, 0) {
			nfo += fmt.Sprintf("<runtime>%d</runtime>", int64(math.Ceil(duration/60)))
		}
		if snapshot["coverData"] != nil {
			nfo += "<thumb>" + xmlText(path[strings.LastIndex(path, "/")+1:]+".png") + "</thumb>"
		}
		if ref := text(snapshot["coverRef"]); assetHash.MatchString(ref) {
			nfo += "<thumb>" + strings.Repeat("../", strings.Count(path, "/")) + "assets/" + ref + ".png</thumb>"
		}
		if e := write(path+".nfo", []byte(nfo+"</musicvideo>")); e != nil {
			z.Close()
			return nil, e
		}
		if snapshot["coverData"] != nil {
			data, e := decodeBase64(text(snapshot["coverData"]))
			if e != nil {
				z.Close()
				return nil, e
			}
			if e = write(path+".png", data); e != nil {
				z.Close()
				return nil, e
			}
		}
		if lyrics, ok := snapshot["lyrics"].(map[string]any); ok {
			if e := write(path+".lyrics.json", []byte(encode(lyrics))); e != nil {
				z.Close()
				return nil, e
			}
			timing := text(lyrics["timing"])
			if timing == "milliseconds" {
				var lrc strings.Builder
				for _, raw := range array(lyrics["lines"]) {
					line, _ := raw.(map[string]any)
					t := max(int64(0), number(line["time"]))
					fmt.Fprintf(&lrc, "[%02d:%02d.%02d]%s\n", t/60000, t/1000%60, t/10%100, text(line["text"]))
				}
				if e := write(path+".lrc", []byte(lrc.String())); e != nil {
					z.Close()
					return nil, e
				}
			} else if timing == "none" || timing == "lrc" {
				ext := ".txt"
				if timing == "lrc" {
					ext = ".lrc"
				}
				if e := write(path+ext, []byte(text(lyrics["text"]))); e != nil {
					z.Close()
					return nil, e
				}
			}
		}
	}
	if e := z.Close(); e != nil {
		return nil, e
	}
	if b.Len() > shareLimit {
		return nil, &problem{413, "索引包最多 32 MiB"}
	}
	return b.Bytes(), nil
}
func pathValue(v any, keys ...string) any { return path(v, keys...) }
func readManifest(data []byte) (map[string]any, error) {
	if len(data) > shareLimit {
		return nil, &problem{413, "索引包最多 32 MiB"}
	}
	z, e := checkedZip(bytes.NewReader(data), int64(len(data)), 30001)
	if e != nil {
		return nil, e
	}
	var manifest []byte
	expanded := int64(0)
	seen := map[string]bool{}
	for _, f := range z.File {
		if !validPortablePath(f.Name) || seen[f.Name] || f.FileInfo().Mode()&os.ModeSymlink != 0 {
			return nil, bad("索引包路径无效或重复")
		}
		seen[f.Name] = true
		if f.UncompressedSize64 > shareLimit || expanded+int64(f.UncompressedSize64) > shareLimit {
			return nil, &problem{413, "索引包展开后超过 32 MiB"}
		}
		r, e := f.Open()
		if e != nil {
			return nil, bad("索引包格式无效")
		}
		b, e := io.ReadAll(io.LimitReader(r, shareLimit-expanded+1))
		r.Close()
		expanded += int64(len(b))
		if e != nil || expanded > shareLimit {
			return nil, &problem{413, "索引包展开后超过 32 MiB"}
		}
		if f.Name == "manifest.json" {
			manifest = b
		}
	}
	if manifest == nil {
		return nil, bad("缺少 manifest.json")
	}
	raw, e := metastandard.Parse(manifest)
	if e != nil {
		return nil, bad(e.Error())
	}
	document, ok := raw.(map[string]any)
	if !ok || document["format"] != "singverse-media-index" || (number(document["version"]) != 2 && number(document["version"]) != 3 && number(document["version"]) != 4) {
		return nil, bad("索引包版本无效")
	}
	if number(document["version"]) == 4 {
		if e = unpackV4(document, z); e != nil {
			return nil, e
		}
	}
	entries, ok := document["entries"].([]any)
	if !ok || len(entries) > 5000 {
		return nil, bad("索引包条目无效")
	}
	return document, nil
}

func decodeBase64(value string) ([]byte, error) {
	data, e := base64.StdEncoding.DecodeString(value)
	if e != nil || base64.StdEncoding.EncodeToString(data) != value {
		return nil, bad("共享资源编码无效")
	}
	return data, nil
}

// checkedZip bounds central-directory allocation before archive/zip reads it, including ZIP64 sets.
func checkedZip(reader io.ReaderAt, size int64, maxEntries int) (*zip.Reader, error) {
	if size < 22 {
		return nil, bad("分享包格式无效")
	}
	tail := make([]byte, min(size, int64(65557)))
	if _, e := reader.ReadAt(tail, size-int64(len(tail))); e != nil {
		return nil, bad("分享包格式无效")
	}
	at := bytes.LastIndex(tail, []byte{'P', 'K', 5, 6})
	if at < 0 || at+22 > len(tail) {
		return nil, bad("分享包格式无效")
	}
	record := tail[at:]
	if at+22+int(binary.LittleEndian.Uint16(record[20:22])) != len(tail) {
		return nil, bad("分享包尾部无效")
	}
	count := uint64(binary.LittleEndian.Uint16(record[10:12]))
	directory := uint64(binary.LittleEndian.Uint32(record[12:16]))
	directoryOffset := uint64(binary.LittleEndian.Uint32(record[16:20]))
	if binary.LittleEndian.Uint16(record[4:6]) != 0 || binary.LittleEndian.Uint16(record[6:8]) != 0 {
		return nil, bad("不支持跨卷分享包")
	}
	if count == 65535 || directory == 4294967295 || binary.LittleEndian.Uint32(record[16:20]) == 4294967295 {
		eocdOffset := size - int64(len(tail)) + int64(at)
		if eocdOffset < 20 {
			return nil, bad("ZIP64 索引无效")
		}
		locator := make([]byte, 20)
		if _, e := reader.ReadAt(locator, eocdOffset-20); e != nil || !bytes.Equal(locator[:4], []byte{'P', 'K', 6, 7}) {
			return nil, bad("ZIP64 索引无效")
		}
		offset := binary.LittleEndian.Uint64(locator[8:16])
		if offset > uint64(size-56) {
			return nil, bad("ZIP64 索引无效")
		}
		extended := make([]byte, 56)
		if _, e := reader.ReadAt(extended, int64(offset)); e != nil || !bytes.Equal(extended[:4], []byte{'P', 'K', 6, 6}) {
			return nil, bad("ZIP64 索引无效")
		}
		count = binary.LittleEndian.Uint64(extended[32:40])
		directory = binary.LittleEndian.Uint64(extended[40:48])
		directoryOffset = binary.LittleEndian.Uint64(extended[48:56])
	}
	if count > uint64(maxEntries) || directory > 128*1024*1024 {
		return nil, bad("分享包条目过多")
	}
	if directoryOffset > uint64(size) || directory > uint64(size)-directoryOffset {
		return nil, bad("分享包目录无效")
	}
	// Inspect every fixed-size central header before archive/zip allocates file objects.
	position, end := directoryOffset, directoryOffset+directory
	actual := uint64(0)
	header := make([]byte, 46)
	for position < end {
		if end-position < 46 {
			return nil, bad("分享包目录无效")
		}
		if _, e := reader.ReadAt(header, int64(position)); e != nil || !bytes.Equal(header[:4], []byte{'P', 'K', 1, 2}) {
			return nil, bad("分享包目录无效")
		}
		actual++
		if actual > uint64(maxEntries) {
			return nil, bad("分享包条目过多")
		}
		length := uint64(46) + uint64(binary.LittleEndian.Uint16(header[28:30])) + uint64(binary.LittleEndian.Uint16(header[30:32])) + uint64(binary.LittleEndian.Uint16(header[32:34]))
		if length > end-position {
			return nil, bad("分享包目录无效")
		}
		position += length
	}
	if actual != count {
		return nil, bad("分享包目录条数不匹配")
	}
	z, e := zip.NewReader(reader, size)
	if e != nil {
		return nil, bad("分享包格式无效")
	}
	if len(z.File) > maxEntries {
		return nil, bad("分享包条目过多")
	}
	return z, nil
}

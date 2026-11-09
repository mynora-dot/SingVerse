package party

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"
	"unicode/utf16"
	"unicode/utf8"

	"github.com/gin-gonic/gin"
)

const textLimit = 131072

var stamp = regexp.MustCompile(`\[(\d{1,3}):(\d{2})(?:[.,]\d{1,3})?\]`)
var htmlTags = regexp.MustCompile(`<[^>]*>`)

func hasTimedLyrics(text string) bool {
	for _, line := range strings.Split(strings.ReplaceAll(text, "\r", "\n"), "\n") {
		matches := stamp.FindAllStringSubmatchIndex(line, -1)
		valid := false
		end := 0
		for _, m := range matches {
			seconds, _ := strconv.Atoi(line[m[4]:m[5]])
			valid = valid || seconds < 60
			end = m[1]
		}
		if valid && strings.TrimSpace(htmlTags.ReplaceAllString(line[end:], "")) != "" {
			return true
		}
	}
	return false
}
func decodeText(data []byte) (string, error) {
	if len(data) > textLimit {
		return "", errors.New("text limit")
	}
	if len(data) >= 2 && ((data[0] == 255 && data[1] == 254) || (data[0] == 254 && data[1] == 255)) {
		little := data[0] == 255
		data = data[2:]
		if len(data)%2 != 0 {
			return "", errors.New("invalid UTF16")
		}
		words := make([]uint16, len(data)/2)
		for i := range words {
			if little {
				words[i] = binary.LittleEndian.Uint16(data[i*2:])
			} else {
				words[i] = binary.BigEndian.Uint16(data[i*2:])
			}
		}
		for i := 0; i < len(words); i++ {
			if words[i] >= 0xd800 && words[i] <= 0xdbff {
				if i+1 >= len(words) || words[i+1] < 0xdc00 || words[i+1] > 0xdfff {
					return "", errors.New("invalid UTF16 surrogate")
				}
				i++
			} else if words[i] >= 0xdc00 && words[i] <= 0xdfff {
				return "", errors.New("invalid UTF16 surrogate")
			}
		}
		return string(utf16.Decode(words)), nil
	}
	data = bytes.TrimPrefix(data, []byte{239, 187, 191})
	if !utf8.Valid(data) {
		return "", errors.New("invalid UTF8")
	}
	return string(data), nil
}

func (s *Service) textTracks(c *gin.Context) {
	v, err := s.tracks(c.Request.Context(), c.Param("id"))
	if err != nil {
		s.report(c, err)
		return
	}
	c.JSON(200, v)
}
func (s *Service) tracks(ctx context.Context, key string) (document, error) {
	song, err := s.library.Song(ctx, key)
	if err != nil {
		return nil, err
	}
	relative := str(song, "relativePath")
	if strings.HasPrefix(relative, "/") || strings.Contains(relative, "\\") {
		return nil, fail(400, "媒体路径无效")
	}
	for _, part := range strings.Split(relative, "/") {
		if strings.TrimSpace(part) == "" || part == "." || part == ".." {
			return nil, fail(400, "媒体路径无效")
		}
	}
	stem := strings.TrimSuffix(relative, path.Ext(relative))
	var sourceType, location, username string
	var encrypted sql.NullString
	if err = s.db.QueryRowContext(ctx, s.q["source"], str(song, "sourceId")).Scan(&sourceType, &location, &username, &encrypted); err != nil {
		return nil, err
	}
	items := []document{}
	warnings := []string{}
	for _, kind := range []string{"lrc", "srt", "vtt"} {
		var data []byte
		if sourceType == "LOCAL" {
			var root *os.Root
			root, err = os.OpenRoot(location)
			if err == nil {
				var f *os.File
				f, err = root.Open(filepath.FromSlash(stem + "." + kind))
				if err == nil {
					var info os.FileInfo
					info, err = f.Stat()
					if err == nil && !info.Mode().IsRegular() {
						err = errors.New("not a regular sidecar")
					}
					if err == nil {
						data, err = io.ReadAll(io.LimitReader(f, textLimit+1))
					}
					f.Close()
				}
				root.Close()
			}
		}
		if sourceType != "LOCAL" {
			var base *url.URL
			base, err = url.Parse(location)
			if err == nil {
				base.Path = strings.TrimRight(base.Path, "/") + "/" + stem + "." + kind
				base.RawPath = ""
				base.RawQuery = ""
				base.Fragment = ""
				var req *http.Request
				req, err = http.NewRequestWithContext(ctx, "GET", base.String(), nil)
				if err == nil {
					if username != "" || encrypted.Valid {
						password := ""
						if encrypted.Valid {
							if s.cipher == nil {
								err = errors.New("credential cipher unavailable")
							} else {
								password, err = s.cipher.Decrypt(encrypted.String, "singverse:webdav:v1")
							}
						}
						if err == nil {
							req.SetBasicAuth(username, password)
						}
					}
					if err == nil {
						client := &http.Client{Timeout: 3500 * time.Millisecond, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
						var response *http.Response
						response, err = client.Do(req)
						if err == nil {
							if response.StatusCode == 404 {
								err = os.ErrNotExist
							} else if response.StatusCode != 200 {
								err = errors.New("sidecar response rejected")
							} else {
								data, err = io.ReadAll(io.LimitReader(response.Body, textLimit+1))
							}
							response.Body.Close()
						}
					}
				}
			}
		}
		if errors.Is(err, os.ErrNotExist) {
			continue
		}
		var text string
		if err == nil {
			text, err = decodeText(data)
		}
		if err != nil {
			warnings = append(warnings, kind+" 文本无法读取或格式无效")
			continue
		}
		items = append(items, document{"id": kind, "format": kind, "text": text})
	}
	original := -1
	for i, item := range items {
		if str(item, "format") == "lrc" {
			original = i
			if hasTimedLyrics(str(item, "text")) {
				return document{"songId": key, "items": items, "warnings": warnings}, nil
			}
			break
		}
	}
	metadata, stale, err := s.cachedLyrics(ctx, key)
	if stale {
		warnings = append(warnings, "媒体已变化，等待重新读取元数据歌词")
	} else if err != nil {
		warnings = append(warnings, "元数据歌词无法读取或超过限制")
	} else if metadata != nil {
		text := str(metadata, "text")
		format := "text"
		if str(metadata, "timing") == "milliseconds" {
			if lines, ok := metadata["lines"].([]any); ok {
				var lrc strings.Builder
				for _, raw := range lines {
					line, _ := raw.(map[string]any)
					at, valid := integral(line["time"])
					if !valid || at < 0 {
						err = errors.New("invalid lyric timestamp")
						break
					}
					content, valid := line["text"].(string)
					if !valid {
						err = errors.New("invalid lyric text")
						break
					}
					fmt.Fprintf(&lrc, "[%02d:%02d.%03d]%s\n", at/60000, at/1000%60, at%1000, content)
					if lrc.Len() > textLimit {
						err = errors.New("lyric limit")
						break
					}
				}
				if lrc.Len() > 0 && err == nil {
					text = lrc.String()
					format = "lrc"
				}
			}
		} else if hasTimedLyrics(text) {
			format = "lrc"
		}
		if err != nil || len(text) > textLimit {
			warnings = append(warnings, "元数据歌词无法读取或超过限制")
		} else if strings.TrimSpace(text) != "" {
			if original >= 0 {
				items = append(items[:original], items[original+1:]...)
				warnings = append(warnings, "原始LRC没有有效时间与正文，已使用元数据歌词")
			}
			items = append(items, document{"id": "metadata-lyrics", "format": format, "text": text})
		}
	}
	return document{"songId": key, "items": items, "warnings": warnings}, nil
}
func (s *Service) cachedLyrics(ctx context.Context, key string) (document, bool, error) {
	var remote, archived sql.NullString
	var changed int
	err := s.db.QueryRowContext(ctx, s.q["remote_lyrics"], key).Scan(&remote, &archived, &changed)
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return nil, false, err
	}
	if err == nil && (archived.Valid || changed != 0) {
		return nil, true, nil
	}
	var v document
	if remote.Valid {
		d := json.NewDecoder(strings.NewReader(remote.String))
		d.UseNumber()
		if err = d.Decode(&v); err != nil {
			return nil, false, err
		}
		if lyrics, ok := v["lyrics"].(map[string]any); ok {
			return lyrics, false, nil
		}
	}
	var local sql.NullString
	if err = s.db.QueryRowContext(ctx, s.q["local_lyrics"], key).Scan(&local); err != nil {
		return nil, false, err
	}
	if !local.Valid {
		return nil, false, nil
	}
	d := json.NewDecoder(strings.NewReader(local.String))
	d.UseNumber()
	if err = d.Decode(&v); err != nil {
		return nil, false, err
	}
	lyrics, _ := v["lyrics"].(map[string]any)
	return lyrics, false, nil
}

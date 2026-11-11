package metastandard

import (
	"encoding/json"
	"errors"
	"regexp"
	"strconv"
	"strings"
	"unicode/utf8"
)

// DisplayField excludes unresolved evidence from display overrides. An explicit
// clear or not-applicable value still blocks inheritance from a parent.
func DisplayField(doc Document, name string) (any, FieldState, bool) {
	value, exists := doc.Fields[name]
	state := doc.States[name]
	return value, state, exists && state.Status != "UNREAD" && state.Status != "MISSING"
}

// Performers preserves the ordered, credited names. Legacy unresolved names
// remain usable; an explicit later local decision on that field wins as well.
func Performers(doc Document) (any, FieldState, bool) {
	credits, cs, hasCredits := DisplayField(doc, "credits")
	artists, as, hasArtists := DisplayField(doc, "artists")
	local := func(s FieldState) bool { return s.Decision == "SET" || s.Decision == "CLEAR" }
	cr, _ := strconv.ParseInt(cs.Revision, 10, 64)
	ar, _ := strconv.ParseInt(as.Revision, 10, 64)
	if hasArtists && (!hasCredits || local(as) && (!local(cs) || ar > cr)) {
		return artists, as, true
	}
	if hasCredits {
		if credits == nil {
			return nil, cs, true
		}
		b, _ := json.Marshal(credits)
		var list []Credit
		_ = json.Unmarshal(b, &list)
		names := make([]string, 0, len(list))
		for _, credit := range list {
			names = append(names, credit.Name)
		}
		return names, cs, true
	}
	return nil, FieldState{}, false
}

var lyricTimestamp = regexp.MustCompile(`\[[0-9]{1,3}:[0-9]{2}(?:[.:][0-9]{1,3})?\]`)
var subtitleTime = regexp.MustCompile(`^(?:(\d{1,2}):)?(\d{2}):(\d{2})[.,](\d{3})\s+-->`)

// LyricsAsset adapts persisted originals without reopening the media. The raw
// resource remains unchanged; subtitles become a bounded millisecond timeline.
func LyricsAsset(mime string, data []byte) (map[string]any, error) {
	if len(data) > 262144 || !utf8.Valid(data) {
		return nil, errors.New("歌词超过 256 KiB 或不是 UTF-8 文本")
	}
	if mime == "application/json" {
		v, e := Parse(data)
		if e != nil {
			return nil, e
		}
		if e = ValidateField("VARIANT", "lyrics", v); e != nil {
			return nil, e
		}
		lyrics, ok := v.(map[string]any)
		if !ok {
			return nil, errors.New("歌词 JSON 必须为对象")
		}
		return lyrics, nil
	}
	if mime != "text/plain" && mime != "text/vtt" && mime != "application/x-subrip" {
		return nil, errors.New("资源不是歌词文本")
	}
	text := strings.TrimPrefix(strings.ReplaceAll(string(data), "\r\n", "\n"), "\ufeff")
	if mime == "text/vtt" || mime == "application/x-subrip" {
		lines := []any{}
		cue := []string{}
		var at int64 = -1
		flush := func() {
			if at >= 0 && len(cue) > 0 {
				lines = append(lines, map[string]any{"time": at, "text": strings.Join(cue, "\n")})
			}
			cue = nil
			at = -1
		}
		for _, line := range strings.Split(text, "\n") {
			if match := subtitleTime.FindStringSubmatch(strings.TrimSpace(line)); match != nil {
				flush()
				h, _ := strconv.ParseInt(match[1], 10, 64)
				m, _ := strconv.ParseInt(match[2], 10, 64)
				s, _ := strconv.ParseInt(match[3], 10, 64)
				ms, _ := strconv.ParseInt(match[4], 10, 64)
				if m > 59 || s > 59 {
					return nil, errors.New("无效的字幕时间")
				}
				at = ((h*60+m)*60+s)*1000 + ms
			} else if strings.TrimSpace(line) == "" {
				flush()
			} else if at >= 0 {
				cue = append(cue, line)
			}
		}
		flush()
		lyrics := map[string]any{"timing": "milliseconds", "lines": lines}
		b, _ := json.Marshal(lyrics)
		v, e := Parse(b)
		if e != nil {
			return nil, e
		}
		if e = ValidateField("VARIANT", "lyrics", v); e != nil {
			return nil, e
		}
		return v.(map[string]any), nil
	}
	timing := "none"
	if lyricTimestamp.MatchString(text) {
		timing = "lrc"
	}
	return map[string]any{"timing": timing, "text": text}, nil
}

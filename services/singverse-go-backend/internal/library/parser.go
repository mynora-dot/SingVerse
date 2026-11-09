package library

import (
	"fmt"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"unicode"
	"unicode/utf8"
)

var mediaTypes = map[string]string{"mkv": "video/x-matroska", "mp4": "video/mp4", "mpg": "video/mpeg", "mpeg": "video/mpeg", "avi": "video/x-msvideo", "ts": "video/mp2t", "vob": "video/mpeg", "mov": "video/quicktime", "wmv": "video/x-ms-wmv", "mp3": "audio/mpeg", "flac": "audio/flac", "wav": "audio/wav", "m4a": "audio/mp4", "aac": "audio/aac", "ogg": "audio/ogg", "oga": "audio/ogg", "opus": "audio/ogg", "wma": "audio/x-ms-wma", "aif": "audio/aiff", "aiff": "audio/aiff", "ape": "audio/x-ape"}
var validID = regexp.MustCompile(`^[A-Za-z0-9-]{1,100}$`)
var digits = regexp.MustCompile(`^[0-9]+$`)
var extensionRE = regexp.MustCompile(`^[A-Za-z0-9]{1,10}$`)
var languages = map[string]string{"国语": "MANDARIN", "普通话": "MANDARIN", "MANDARIN": "MANDARIN", "粤语": "CANTONESE", "广东话": "CANTONESE", "CANTONESE": "CANTONESE", "英语": "ENGLISH", "英文": "ENGLISH", "ENGLISH": "ENGLISH", "日语": "JAPANESE", "JAPANESE": "JAPANESE", "韩语": "KOREAN", "韩文": "KOREAN", "KOREAN": "KOREAN", "其他": "OTHER", "其它": "OTHER", "未知": "UNKNOWN"}
var versions = map[string]string{"原唱": "ORIGINAL", "原版": "ORIGINAL", "伴奏": "ACCOMPANIMENT", "纯伴奏": "ACCOMPANIMENT", "纯音乐": "INSTRUMENTAL", "合唱": "DUET", "对唱": "DUET", "卡拉 OK": "KARAOKE", "卡拉OK": "KARAOKE", "KTV": "KARAOKE"}
var qualities = map[string]bool{"480P": true, "720P": true, "1080P": true, "2K": true, "4K": true, "UHD": true, "HD": true}
var hints = map[string]bool{"LIVE": true, "REMIX": true, "MV": true, "MTV": true, "演唱会": true, "消伴": true, "风景": true, "人物": true, "流行": true, "民歌": true, "怀旧歌": true, "军歌": true, "儿歌": true, "舞曲": true, "戏曲": true, "影视": true, "流行歌曲": true, "军旅": true, "怀旧经典": true, "怀旧": true, "影视插曲": true, "情歌对唱": true, "双音轨KTV": true}

func presets() []Preset {
	p := []Preset{}
	template := func(id, name, expr, example string, enabled bool) {
		p = append(p, Preset{id, name, "TEMPLATE", expr, enabled, example, "按模板指定字段顺序，匹配并不核实歌手身份"})
	}
	template("book", "歌手《歌名》", "{artist}《{title}》", "周杰伦《晴天》.mkv", true)
	template("parentheses", "歌名（歌手）", "{title}({artist})", "晴天（周杰伦）.mkv", true)
	template("coded-spaces", "编号 歌手 歌名", "{code} {artist} {title}", "00128 周杰伦 晴天.mpg", true)
	library := `(?P<artist>[^-]+)-(?P<title>[^()\[\]]+?)(?:\([^()]*\)|\[[^\[\]]*\])*-(?P<language>国语|普通话|粤语|广东话|英语|英文|日语|韩语|其他|其它|Mandarin|Cantonese|English|Japanese|Korean)-(?P<extra>.+)`
	p = append(p, Preset{"coded-library", "编号＋歌手-歌名-语种-曲库标签", "REGEX", `(?P<code>[0-9]+) ` + library, true, "75 薛之谦-绅士[1080P]-国语-流行.mkv", "明确按编号、歌手在前的曲库约定匹配"}, Preset{"library", "歌手-歌名-语种-曲库标签", "REGEX", library, true, "刀郎-孤独的牧羊人(消伴)-国语-流行.mkv", "画面及尾部标签保留为附加信息"})
	template("all-tags", "歌手-歌名-语种-版本-清晰度", "{artist}-{title}-{language}-{version}-{quality}", "周杰伦-晴天-国语-原唱-1080P.mkv", true)
	template("version-tags", "歌手-歌名-语种-版本", "{artist}-{title}-{language}-{version}", "周杰伦-晴天-国语-原唱.mkv", true)
	template("language", "歌手-歌名-语种", "{artist}-{title}-{language}", "周杰伦-晴天-国语.mkv", true)
	template("underscore-version", "歌名_歌手_版本", "{title}_{artist}_{version}", "晴天_周杰伦_伴奏.mp4", true)
	template("underscore", "歌名_歌手", "{title}_{artist}", "晴天_周杰伦.mkv", true)
	template("hyphen", "歌手-歌名", "{artist}-{title}", "周杰伦-晴天.mkv", true)
	template("reverse-hyphen", "歌名-歌手（反向）", "{title}-{artist}", "晴天-周杰伦.mkv", false)
	return p
}
func defaultDocument() Document {
	d := Document{SchemaVersion: 2, Definitions: []Definition{}, Profiles: []Profile{{ID: "default", Name: "默认解析方案", Entries: []Entry{}}}}
	for _, p := range presets() {
		id := "builtin-" + p.ID
		d.Definitions = append(d.Definitions, Definition{id, p.Name, "BUILTIN", nil, ptr(p.ID)})
		d.Profiles[0].Entries = append(d.Profiles[0].Entries, Entry{id, ptr(p.EnabledByDefault)})
	}
	return d
}
func normalize(input string) string {
	var b strings.Builder
	space := false
	for _, r := range input {
		if r >= 0xff01 && r <= 0xff5e {
			r -= 0xfee0
		}
		if unicode.IsSpace(r) {
			space = b.Len() > 0
			continue
		}
		if space {
			b.WriteByte(' ')
			space = false
		}
		switch r {
		case '‐', '‑', '‒', '–', '—', '―', '−':
			r = '-'
		case '【', '〔':
			r = '['
		case '】', '〕':
			r = ']'
		}
		b.WriteRune(r)
	}
	return b.String()
}
func meaningful(s string) bool {
	for _, r := range s {
		if unicode.IsLetter(r) || unicode.IsDigit(r) {
			return true
		}
	}
	return false
}
func balanced(s string) bool {
	stack := []rune{}
	for _, r := range s {
		switch r {
		case '(', '[', '《':
			stack = append(stack, r)
		case ')', ']', '》':
			if len(stack) == 0 {
				return false
			}
			last := stack[len(stack)-1]
			stack = stack[:len(stack)-1]
			if (r == ')' && last != '(') || (r == ']' && last != '[') || (r == '》' && last != '《') {
				return false
			}
		}
	}
	return len(stack) == 0
}
func supports(filename string) bool {
	_, ok := mediaTypes[strings.TrimPrefix(strings.ToLower(filepath.Ext(filename)), ".")]
	return ok
}

type token struct{ field, literal string }
type span struct{ start, end int }
type capture struct {
	fields  map[string]string
	residue []string
}
type compiledRule struct {
	definition Rule
	regex      *regexp.Regexp
	tokens     []token
	builtin    string
}

var allowedFields = map[string]bool{"artist": true, "title": true, "code": true, "language": true, "version": true, "quality": true, "extra": true}

func compileRules(rules []Rule) ([]compiledRule, error) {
	if rules == nil || len(rules) > 100 {
		return nil, invalid("", -1, "rules", "规则列表必填，最多 100 条")
	}
	seen := map[string]bool{}
	out := make([]compiledRule, 0, len(rules))
	for i, r := range rules {
		if !validID.MatchString(r.ID) || seen[r.ID] {
			return nil, invalid(r.ID, i, "id", "规则 ID 无效或重复")
		}
		seen[r.ID] = true
		c, e := compileRule(r, i)
		if e != nil {
			return nil, e
		}
		out = append(out, c)
	}
	return out, nil
}
func compileRule(r Rule, i int) (compiledRule, error) {
	c := compiledRule{definition: r}
	fail := func(field, detail string) (compiledRule, error) { return c, invalid(r.ID, i, field, detail) }
	if !text(r.Name, 100) {
		return fail("name", "规则名称需要 1～100 个字符")
	}
	if r.Enabled == nil {
		return fail("enabled", "启用状态必填")
	}
	expr, kind := "", r.Type
	if r.Expression != nil {
		expr = *r.Expression
	}
	if kind == "BUILTIN" {
		if r.BuiltinID == nil || r.Expression != nil {
			return fail("builtinId", "内置规则引用不存在或试图改写内置表达式")
		}
		found := false
		for _, p := range presets() {
			if p.ID == *r.BuiltinID {
				expr = p.Expression
				kind = p.Type
				c.builtin = p.ID
				found = true
				break
			}
		}
		if !found {
			return fail("builtinId", "内置规则不存在")
		}
	} else if r.BuiltinID != nil {
		return fail("builtinId", "自定义规则不能引用内置 ID")
	}
	if !text(expr, 2048) {
		return fail("expression", "表达式需要 1～2048 个字符")
	}
	if kind == "REGEX" {
		re, e := regexp.Compile(`\A(?:` + expr + `)\z`)
		if e != nil {
			return fail("expression", "正则语法不受支持")
		}
		names := map[string]bool{}
		for _, name := range re.SubexpNames() {
			if name == "" {
				continue
			}
			if !allowedFields[name] || names[name] {
				return fail("expression", "正则只能使用支持的字段命名组")
			}
			names[name] = true
		}
		if !names["artist"] || !names["title"] {
			return fail("expression", "正则必须包含 artist/title 命名组")
		}
		c.regex = re
		return c, nil
	}
	if kind != "TEMPLATE" {
		return fail("type", "规则类型无效")
	}
	expr = normalize(expr)
	names := map[string]bool{}
	for len(expr) > 0 {
		open := strings.IndexByte(expr, '{')
		if open < 0 {
			if strings.Contains(expr, "}") {
				return fail("expression", "模板括号不匹配")
			}
			c.tokens = append(c.tokens, token{literal: expr})
			break
		}
		if open > 0 {
			lit := expr[:open]
			if strings.Contains(lit, "}") {
				return fail("expression", "模板括号不匹配")
			}
			c.tokens = append(c.tokens, token{literal: lit})
		}
		end := strings.IndexByte(expr[open:], '}')
		if end < 0 {
			return fail("expression", "模板括号不匹配")
		}
		end += open
		name := expr[open+1 : end]
		if !allowedFields[name] || names[name] {
			return fail("expression", "模板字段不受支持或重复")
		}
		if len(c.tokens) > 0 && c.tokens[len(c.tokens)-1].field != "" {
			return fail("expression", "相邻字段之间必须有字面分隔符")
		}
		names[name] = true
		c.tokens = append(c.tokens, token{field: name})
		expr = expr[end+1:]
	}
	if !names["artist"] || !names["title"] {
		return fail("expression", "模板必须包含 artist 和 title")
	}
	return c, nil
}
func (c compiledRule) accepts(f map[string]string) bool {
	if v, ok := f["code"]; ok && !digits.MatchString(v) {
		return false
	}
	if c.builtin == "" {
		return true
	}
	if v, ok := f["language"]; ok && languages[strings.ToUpper(v)] == "" {
		return false
	}
	if v, ok := f["version"]; ok && versions[strings.ToUpper(v)] == "" {
		return false
	}
	if v, ok := f["quality"]; ok && !qualities[strings.ToUpper(v)] {
		return false
	}
	if c.builtin == "coded-spaces" && (strings.ContainsAny(f["artist"], "-_") || strings.ContainsAny(f["title"], "-_")) {
		return false
	}
	if c.builtin == "hyphen" && regexp.MustCompile(`^[0-9]+ +`).MatchString(f["artist"]) {
		return false
	}
	if c.builtin == "parentheses" && (knownTag(f["artist"]) || hints[strings.ToUpper(f["artist"])] || digits.MatchString(f["artist"])) {
		return false
	}
	return true
}
func residue(s string, spans []span) []string {
	sort.Slice(spans, func(i, j int) bool { return spans[i].start < spans[j].start })
	out := []string{}
	offset := 0
	for _, p := range spans {
		if p.start > offset && meaningful(s[offset:p.start]) {
			out = append(out, strings.TrimSpace(s[offset:p.start]))
		}
		if p.end > offset {
			offset = p.end
		}
	}
	if offset < len(s) && meaningful(s[offset:]) {
		out = append(out, strings.TrimSpace(s[offset:]))
	}
	return out
}
func (c compiledRule) match(input string) ([]capture, bool) {
	out := []capture{}
	if c.regex != nil {
		indices := c.regex.FindStringSubmatchIndex(input)
		if indices == nil {
			return out, false
		}
		f := map[string]string{}
		spans := []span{}
		for i, name := range c.regex.SubexpNames() {
			if name != "" && indices[2*i] >= 0 {
				f[name] = strings.TrimSpace(input[indices[2*i]:indices[2*i+1]])
				spans = append(spans, span{indices[2*i], indices[2*i+1]})
			}
		}
		if c.accepts(f) {
			out = append(out, capture{f, residue(input, spans)})
		}
		return out, false
	}
	steps, limited := 0, false
	var visit func(int, int, map[string]string, []span)
	visit = func(index, offset int, f map[string]string, spans []span) {
		if len(out) >= 2 || limited {
			return
		}
		steps++
		if steps > 20000 {
			limited = true
			return
		}
		if index == len(c.tokens) {
			if offset == len(input) && c.accepts(f) {
				copy := map[string]string{}
				for k, v := range f {
					copy[k] = v
				}
				out = append(out, capture{copy, residue(input, append([]span{}, spans...))})
			}
			return
		}
		t := c.tokens[index]
		if t.field == "" {
			if strings.HasPrefix(input[offset:], t.literal) {
				visit(index+1, offset+len(t.literal), f, spans)
			}
			return
		}
		take := func(end int) {
			value := strings.TrimSpace(input[offset:end])
			if t.field == "code" && !digits.MatchString(value) {
				return
			}
			f[t.field] = value
			visit(index+1, end, f, append(spans, span{offset, end}))
			delete(f, t.field)
		}
		if index == len(c.tokens)-1 {
			take(len(input))
			return
		}
		boundary := c.tokens[index+1].literal
		for start := offset; start <= len(input); {
			next := strings.Index(input[start:], boundary)
			if next < 0 {
				break
			}
			end := start + next
			take(end)
			if len(out) >= 2 || limited || end == len(input) {
				break
			}
			_, width := utf8.DecodeRuneInString(input[end:])
			start = end + width
		}
	}
	visit(0, 0, map[string]string{}, nil)
	return out, limited
}
func knownTag(v string) bool {
	k := strings.ToUpper(v)
	return languages[k] != "" || versions[k] != "" || qualities[k]
}

var evidenceSplit = regexp.MustCompile(`[\s\[\]()_,，;；|\-]+`)
var artistSplit = regexp.MustCompile(`\s*[&、]\s*|\s+(?i:feat\.|ft\.)\s+`)

func capturedResult(original, normalized string, extension *string, c compiledRule, cap capture) ParseResult {
	r := ParseResult{OriginalFilename: original, NormalizedFilename: normalized, Artists: []string{}, Language: "UNKNOWN", VersionType: "UNKNOWN", Extension: extension, QualityTags: []string{}, UnparsedTokens: []string{}, Diagnostics: []Diagnostic{}, NeedsReview: true, ParseStatus: "PARTIAL", Confidence: .45, ParserRule: "CUSTOM_TEMPLATE"}
	switch c.definition.Type {
	case "BUILTIN":
		r.ParserRule = "CONFIGURED_BUILTIN"
	case "REGEX":
		r.ParserRule = "CUSTOM_REGEX"
	}
	evidence := append([]string{}, cap.residue...)
	for _, k := range []string{"language", "version", "quality", "extra"} {
		if v := cap.fields[k]; v != "" {
			if (k == "language" && languages[strings.ToUpper(v)] == "") || (k == "version" && versions[strings.ToUpper(v)] == "") {
				r.UnparsedTokens = append(r.UnparsedTokens, v)
			} else {
				evidence = append(evidence, v)
			}
		}
	}
	langs, vers := []string{}, []string{}
	for _, raw := range evidence {
		parts := []string{raw}
		if !knownTag(raw) && !hints[strings.ToUpper(raw)] {
			parts = evidenceSplit.Split(raw, -1)
		}
		for _, v := range parts {
			if !meaningful(v) {
				continue
			}
			k := strings.ToUpper(v)
			switch {
			case languages[k] != "":
				langs = unique(langs, languages[k])
			case versions[k] != "":
				vers = unique(vers, versions[k])
			case qualities[k]:
				r.QualityTags = unique(r.QualityTags, k)
			default:
				r.UnparsedTokens = unique(r.UnparsedTokens, v)
			}
		}
	}
	if len(langs) == 1 {
		r.Language = langs[0]
	}
	if len(vers) == 1 {
		r.VersionType = vers[0]
	}
	if len(langs) > 1 || len(vers) > 1 {
		r.Diagnostics = append(r.Diagnostics, Diagnostic{"CONFLICTING_TAGS", "存在冲突标签", 0})
	}
	if meaningful(cap.fields["title"]) {
		r.Title = ptr(cap.fields["title"])
	}
	artist := cap.fields["artist"]
	parts := artistSplit.Split(artist, -1)
	valid := true
	for _, p := range parts {
		if !meaningful(p) {
			valid = false
		}
	}
	if valid {
		r.Artists = uniqueAll(parts)
	}
	if r.VersionType == "DUET" && len(r.Artists) == 1 && strings.Contains(artist, " ") {
		names := strings.Fields(artist)
		split := len(names) > 1
		for _, name := range names {
			n := len([]rune(name))
			if n < 2 || n > 4 {
				split = false
			}
			for _, ch := range name {
				if !unicode.Is(unicode.Han, ch) {
					split = false
				}
			}
			for _, suffix := range []string{"乐队", "乐团", "合唱团", "组合", "战队", "乐坊"} {
				if strings.HasSuffix(name, suffix) {
					split = false
				}
			}
		}
		if split {
			r.Artists = uniqueAll(names)
		}
	}
	code, hasCode := cap.fields["code"]
	validCode := !hasCode || digits.MatchString(code)
	if hasCode && validCode {
		r.SongCode = ptr(code)
	}
	if hasCode && !validCode {
		r.UnparsedTokens = append(r.UnparsedTokens, code)
	}
	complete := r.Title != nil && len(r.Artists) > 0 && validCode
	if complete {
		r.ParseStatus = "SUCCESS"
		r.NeedsReview = false
		r.Confidence = .90
		if c.definition.Type == "BUILTIN" {
			r.Confidence = .85
		}
	}
	r.Diagnostics = append([]Diagnostic{{"MATCHED_RULE", c.definition.ID + "：" + c.definition.Name, r.Confidence}}, r.Diagnostics...)
	if len(r.UnparsedTokens) > 0 {
		r.Diagnostics = append(r.Diagnostics, Diagnostic{"UNKNOWN_TOKEN", fmt.Sprint(r.UnparsedTokens), 0})
	}
	if !complete {
		r.Diagnostics = append(r.Diagnostics, Diagnostic{"INCOMPLETE_FIELDS", "核心字段不完整或编号无效", 0})
	}
	if extension == nil {
		r.Diagnostics = append(r.Diagnostics, Diagnostic{"MISSING_EXTENSION", "未提供扩展名", 0})
	}
	return r
}
func unique(a []string, v string) []string {
	for _, x := range a {
		if x == v {
			return a
		}
	}
	return append(a, v)
}
func uniqueAll(a []string) []string {
	out := []string{}
	for _, v := range a {
		out = unique(out, strings.TrimSpace(v))
	}
	return out
}
func Parse(filename string, rules []Rule) (Preview, error) {
	if len([]rune(filename)) > 1024 {
		return Preview{}, invalid("", -1, "filename", "文件名最多 1024 个字符")
	}
	compiled, err := compileRules(rules)
	if err != nil {
		return Preview{}, err
	}
	normalized := normalize(filename)
	stem := normalized
	var ext *string
	if dot := strings.LastIndexByte(normalized, '.'); dot >= 0 && extensionRE.MatchString(normalized[dot+1:]) {
		s := strings.ToLower(normalized[dot+1:])
		ext = &s
		stem = strings.TrimSpace(normalized[:dot])
		normalized = normalized[:dot+1] + s
	}
	result := ParseResult{OriginalFilename: filename, NormalizedFilename: normalized, Artists: []string{}, Language: "UNKNOWN", VersionType: "UNKNOWN", Extension: ext, QualityTags: []string{}, UnparsedTokens: []string{stem}, Diagnostics: []Diagnostic{}, NeedsReview: true, ParseStatus: "FAILED", ParserRule: "NONE"}
	out := Preview{Result: result, MatchTarget: ptr(stem), Attempts: []Attempt{}}
	reason := ""
	if controls(filename) || !utf8.ValidString(filename) || !meaningful(stem) || !balanced(stem) {
		reason = "INVALID_INPUT"
	} else if ext != nil && !supports("x."+*ext) {
		reason = "UNSUPPORTED_EXTENSION"
	}
	if reason != "" {
		out.MatchTarget = nil
		out.Result.Diagnostics = []Diagnostic{{reason, "无效文件名或不支持的扩展名", 0}}
		return out, nil
	}
	var chosen, partial *ParseResult
	ambiguous := false
	for _, c := range compiled {
		a := Attempt{ID: c.definition.ID, Name: c.definition.Name, Type: c.definition.Type, Captures: map[string]string{}, Candidates: []map[string]string{}}
		if !*c.definition.Enabled {
			a.Status = "DISABLED"
			a.Detail = "规则已停用"
			out.Attempts = append(out.Attempts, a)
			continue
		}
		captures, limited := c.match(stem)
		switch {
		case limited || len(captures) > 1:
			a.Status = "AMBIGUOUS"
			a.Detail = "模板存在多个有效拆分，未任意选择"
			ambiguous = true
			for _, p := range captures {
				a.Candidates = append(a.Candidates, p.fields)
			}
		case len(captures) == 0:
			a.Status = "NO_MATCH"
			a.Detail = "未完整匹配名称或字段不符合约束"
		default:
			r := capturedResult(filename, normalized, ext, c, captures[0])
			a.Captures = captures[0].fields
			if r.ParseStatus == "SUCCESS" {
				a.Status = "MATCHED"
				a.Detail = "完整捕获歌手和歌名"
				if chosen == nil {
					chosen = &r
					out.SelectedRule = &Selection{c.definition.ID, c.definition.Name, c.definition.Type}
				}
			} else {
				a.Status = "PARTIAL"
				a.Detail = "已匹配结构，但核心字段不完整"
				if partial == nil {
					partial = &r
				}
			}
		}
		out.Attempts = append(out.Attempts, a)
	}
	if chosen != nil {
		out.Result = *chosen
	} else if partial != nil && !ambiguous {
		out.Result = *partial
	} else if ambiguous {
		out.Result.Confidence = .30
		out.Result.ParseStatus = "NEEDS_REVIEW"
		out.Result.ParserRule = "AMBIGUOUS"
		out.Result.Diagnostics = []Diagnostic{{"AMBIGUOUS_STRUCTURE", "没有规则完整解析出歌手和歌名；未调用其他解析器兜底", 0}}
	} else {
		out.Result.Diagnostics = []Diagnostic{{"NO_RULE_MATCH", "没有规则完整解析出歌手和歌名；未调用其他解析器兜底", 0}}
	}
	return out, nil
}

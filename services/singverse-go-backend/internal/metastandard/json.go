// Package metastandard implements the public Metadata Schema 1.0 wire rules.
// It has no database, HTTP or compute dependency and is shared by the validator
// and the server. Digests cover canonical JSON or the original binary bytes.
package metastandard

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
	"sort"
	"strconv"
	"strings"
	"unicode/utf16"
	"unicode/utf8"
)

const MaxJSON = 16 << 20

// Parse rejects duplicate member names (including escaped aliases), malformed
// UTF-8, unpaired UTF-16 escapes, trailing documents and excessive nesting.
func Parse(data []byte) (any, error) {
	if len(data) > MaxJSON || !utf8.Valid(data) {
		return nil, errors.New("invalid UTF-8 or JSON size")
	}
	for i := 0; i < len(data); i++ {
		if data[i] != '"' {
			continue
		}
		i++
		for ; i < len(data) && data[i] != '"'; i++ {
			if data[i] != '\\' {
				continue
			}
			i++
			if i >= len(data) {
				return nil, errors.New("incomplete string")
			}
			if data[i] != 'u' {
				continue
			}
			if i+4 >= len(data) {
				return nil, errors.New("incomplete unicode escape")
			}
			n, err := strconv.ParseUint(string(data[i+1:i+5]), 16, 16)
			if err != nil {
				return nil, err
			}
			i += 4
			if n >= 0xdc00 && n <= 0xdfff {
				return nil, errors.New("unpaired low surrogate")
			}
			if n >= 0xd800 && n <= 0xdbff {
				if i+6 >= len(data) || data[i+1] != '\\' || data[i+2] != 'u' {
					return nil, errors.New("unpaired high surrogate")
				}
				low, err := strconv.ParseUint(string(data[i+3:i+7]), 16, 16)
				if err != nil || low < 0xdc00 || low > 0xdfff {
					return nil, errors.New("unpaired high surrogate")
				}
				i += 6
			}
		}
	}
	d := json.NewDecoder(bytes.NewReader(data))
	d.UseNumber()
	var read func(int) (any, error)
	read = func(depth int) (any, error) {
		if depth > 64 {
			return nil, errors.New("JSON depth exceeds 64")
		}
		t, err := d.Token()
		if err != nil {
			return nil, err
		}
		switch t {
		case json.Delim('{'):
			m := map[string]any{}
			for d.More() {
				k, e := d.Token()
				if e != nil {
					return nil, e
				}
				key, ok := k.(string)
				if !ok {
					return nil, errors.New("object key")
				}
				if _, exists := m[key]; exists {
					return nil, fmt.Errorf("duplicate JSON key %q", key)
				}
				m[key], e = read(depth + 1)
				if e != nil {
					return nil, e
				}
			}
			_, err = d.Token()
			return m, err
		case json.Delim('['):
			a := []any{}
			for d.More() {
				v, e := read(depth + 1)
				if e != nil {
					return nil, e
				}
				a = append(a, v)
			}
			_, err = d.Token()
			return a, err
		default:
			switch t.(type) {
			case nil, bool, string, json.Number:
				return t, nil
			}
			return nil, errors.New("invalid JSON token")
		}
	}
	v, err := read(0)
	if err != nil {
		return nil, err
	}
	if _, err = d.Token(); err != io.EOF {
		return nil, errors.New("trailing JSON data")
	}
	return v, nil
}

// Canonical implements RFC 8785: UTF-16 member ordering, no normalization of
// strings, ECMAScript JSON number spelling, and no insignificant whitespace.
func Canonical(data []byte) ([]byte, error) {
	v, err := Parse(data)
	if err != nil {
		return nil, err
	}
	var out bytes.Buffer
	var write func(any) error
	write = func(v any) error {
		switch x := v.(type) {
		case nil:
			out.WriteString("null")
		case bool:
			out.WriteString(strconv.FormatBool(x))
		case string:
			writeString(&out, x)
		case json.Number:
			f, e := strconv.ParseFloat(string(x), 64)
			if e != nil || math.IsInf(f, 0) || math.IsNaN(f) {
				return errors.New("non-finite JSON number")
			}
			if f == 0 {
				out.WriteByte('0')
				break
			}
			// encoding/json uses the ECMAScript fixed/exponential thresholds and
			// shortest round-trip binary64 representation, unlike fmt's %g.
			b, e := json.Marshal(f)
			if e != nil {
				return e
			}
			out.Write(b)
		case []any:
			out.WriteByte('[')
			for i, item := range x {
				if i > 0 {
					out.WriteByte(',')
				}
				if e := write(item); e != nil {
					return e
				}
			}
			out.WriteByte(']')
		case map[string]any:
			keys := make([]string, 0, len(x))
			for key := range x {
				keys = append(keys, key)
			}
			sort.Slice(keys, func(i, j int) bool {
				a, b := utf16.Encode([]rune(keys[i])), utf16.Encode([]rune(keys[j]))
				for k := 0; k < len(a) && k < len(b); k++ {
					if a[k] != b[k] {
						return a[k] < b[k]
					}
				}
				return len(a) < len(b)
			})
			out.WriteByte('{')
			for i, key := range keys {
				if i > 0 {
					out.WriteByte(',')
				}
				writeString(&out, key)
				out.WriteByte(':')
				if e := write(x[key]); e != nil {
					return e
				}
			}
			out.WriteByte('}')
		default:
			return errors.New("unsupported JSON value")
		}
		return nil
	}
	if err = write(v); err != nil {
		return nil, err
	}
	return out.Bytes(), nil
}

func writeString(out *bytes.Buffer, s string) {
	out.WriteByte('"')
	for _, r := range s {
		switch r {
		case '"', '\\':
			out.WriteByte('\\')
			out.WriteRune(r)
		case '\b':
			out.WriteString(`\b`)
		case '\t':
			out.WriteString(`\t`)
		case '\n':
			out.WriteString(`\n`)
		case '\f':
			out.WriteString(`\f`)
		case '\r':
			out.WriteString(`\r`)
		default:
			if r < 32 {
				fmt.Fprintf(out, `\u%04x`, r)
			} else {
				out.WriteRune(r)
			}
		}
	}
	out.WriteByte('"')
}

func Digest(data []byte) string { h := sha256.Sum256(data); return hex.EncodeToString(h[:]) }
func DocumentDigest(data []byte) (string, error) {
	b, e := Canonical(data)
	if e != nil {
		return "", e
	}
	return Digest(b), nil
}
func ValidPath(path string) bool {
	if path == "" || !utf8.ValidString(path) || len([]rune(path)) > 2048 || strings.ContainsAny(path, "\\:") || strings.HasPrefix(path, "/") {
		return false
	}
	for _, r := range path {
		if r < 32 || r == 127 {
			return false
		}
	}
	for _, segment := range strings.Split(path, "/") {
		if segment == "" || segment == "." || segment == ".." {
			return false
		}
	}
	return true
}

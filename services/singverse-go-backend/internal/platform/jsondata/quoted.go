// Package jsondata parses external JSON-like data without executing scripts.
package jsondata

import (
	"encoding/json"
	"errors"
	"strings"
)

// NormalizeSingleQuoted accepts single-quoted strings; json.Decoder validates the result.
func NormalizeSingleQuoted(s string) (string, error) {
	var out strings.Builder
	for i := 0; i < len(s); {
		if s[i] == '"' {
			start := i
			i++
			for i < len(s) {
				if s[i] == '\\' {
					i += 2
					continue
				}
				if s[i] == '"' {
					i++
					break
				}
				i++
			}
			if i > len(s) {
				return "", errors.New("invalid string")
			}
			out.WriteString(s[start:i])
			continue
		}
		if s[i] != '\'' {
			out.WriteByte(s[i])
			i++
			continue
		}
		i++
		var v strings.Builder
		closed := false
		for i < len(s) {
			ch := s[i]
			i++
			if ch == '\'' {
				closed = true
				break
			}
			if ch != '\\' {
				v.WriteByte(ch)
				continue
			}
			if i >= len(s) {
				return "", errors.New("invalid escape")
			}
			esc := s[i]
			i++
			switch esc {
			case '\'', '\\', '/':
				v.WriteByte(esc)
			case '"':
				v.WriteByte('"')
			case 'n':
				v.WriteByte('\n')
			case 'r':
				v.WriteByte('\r')
			case 't':
				v.WriteByte('\t')
			case 'b':
				v.WriteByte('\b')
			case 'f':
				v.WriteByte('\f')
			case 'u':
				if i+4 > len(s) {
					return "", errors.New("invalid unicode")
				}
				end := i + 4
				for end+6 <= len(s) && s[end:end+2] == "\\u" {
					end += 6
				}
				quoted := "\"\\u" + s[i:end] + "\""
				var decoded string
				if json.Unmarshal([]byte(quoted), &decoded) != nil {
					return "", errors.New("invalid unicode")
				}
				v.WriteString(decoded)
				i = end
			default:
				return "", errors.New("invalid escape")
			}
		}
		if !closed {
			return "", errors.New("unclosed string")
		}
		b, _ := json.Marshal(v.String())
		out.Write(b)
	}
	return out.String(), nil
}

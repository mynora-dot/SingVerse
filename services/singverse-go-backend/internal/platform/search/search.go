// Package search provides the shared normalized Chinese library search keys.
package search

import (
	"github.com/mozillazg/go-pinyin"
	"golang.org/x/text/unicode/norm"
	"strings"
	"unicode"
	"unicode/utf16"
)

func Normalize(value string) string {
	return strings.Map(func(r rune) rune {
		if unicode.IsSpace(r) {
			return -1
		}
		return unicode.ToLower(r)
	}, norm.NFKC.String(value))
}
func UTF16Length(value string) int { return len(utf16.Encode([]rune(value))) }
func Searchable(value string) string {
	n := Normalize(value)
	var full, initials strings.Builder
	args := pinyin.NewArgs()
	args.Heteronym = true
	args.Fallback = func(r rune, _ pinyin.Args) []string { return []string{string(r)} }
	for _, r := range n {
		parts := pinyin.SinglePinyin(r, args)
		part := string(r)
		if len(parts) > 0 {
			part = strings.ReplaceAll(parts[0], "ü", "v")
		}
		full.WriteString(part)
		for _, initial := range part {
			initials.WriteRune(initial)
			break
		}
	}
	return n + " " + full.String() + " " + initials.String()
}

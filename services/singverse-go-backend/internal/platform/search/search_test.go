package search

import "testing"

func TestUnicodeSearch(t *testing.T) {
	if Normalize(" Ａ B\tＣ ") != "abc" {
		t.Fatal("normalization")
	}
	if Searchable("中国") != "中国 zhongguo zg" {
		t.Fatal(Searchable("中国"))
	}
	if UTF16Length("🎤") != 2 || Normalize(" 🎤 ") != "🎤" {
		t.Fatal("UTF-16 or supplementary character")
	}
}

package jsondata

import (
	"encoding/json"
	"testing"
)

func TestSingleQuotedDataIsDecodedWithoutScriptExecution(t *testing.T) {
	for _, tc := range []struct {
		input string
		valid bool
	}{
		{`{'name':'O\'Connor','id':'6807264292040083458','quote':'"hello"'}`, true},
		{`{"name":"Artist's portrait","items":[]}`, true},
		{`{'name':'\u6b4c\u624b','newline':'a\nb'}`, true},
		{`{'name':'unterminated}`, false},
		{`{'name':'bad\x41'}`, false},
		{`try{var jsondata={'id':'1'};alert(jsondata);}catch(e){}`, false},
		{`{'id':'1'};fetch('https://evil.example')`, false},
		{`{'id':function(){return '1'}}`, false},
	} {
		t.Run(tc.input, func(t *testing.T) {
			value, err := NormalizeSingleQuoted(tc.input)
			var object map[string]any
			valid := err == nil && json.Unmarshal([]byte(value), &object) == nil
			if valid != tc.valid {
				t.Fatal("unexpected data parser result", value, err)
			}
		})
	}
}

func TestSingleQuotedUnicodeKeepsSurrogatePairs(t *testing.T) {
	value, err := NormalizeSingleQuoted(`{'name':'\u6b4c\u624b\ud83c\udfb5'}`)
	if err != nil {
		t.Fatal(err)
	}
	var object map[string]string
	if err = json.Unmarshal([]byte(value), &object); err != nil || object["name"] != "歌手🎵" {
		t.Fatal(object, err)
	}
}

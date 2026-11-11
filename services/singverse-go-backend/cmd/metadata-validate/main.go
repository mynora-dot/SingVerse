// metadata-validate is a standalone format/integrity validator. It opens no
// database, starts no server and requires no compute node or media access.
package main

import (
	"encoding/json"
	"fmt"
	"io"
	"os"
	"strings"

	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/metadata"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/metastandard"
)

func main() {
	if len(os.Args) == 2 && os.Args[1] == "--schema" {
		b, e := json.MarshalIndent(metastandard.JSONSchema(), "", "  ")
		if e != nil {
			fail(e)
		}
		fmt.Println(string(b))
		return
	}
	if len(os.Args) != 2 {
		fmt.Fprintln(os.Stderr, "usage: metadata-validate <document.json|share.zip> | --schema")
		os.Exit(2)
	}
	path := os.Args[1]
	if strings.HasSuffix(strings.ToLower(path), ".zip") {
		if e := metadata.ValidateShareFile(path); e != nil {
			fail(e)
		}
	} else {
		f, e := os.Open(path)
		if e != nil {
			fail(e)
		}
		defer f.Close()
		b, e := io.ReadAll(io.LimitReader(f, metastandard.MaxJSON+1))
		if e != nil {
			fail(e)
		}
		if _, e = metastandard.Decode(b); e != nil {
			fail(e)
		}
	}
	fmt.Println("valid")
}
func fail(e error) { fmt.Fprintln(os.Stderr, e); os.Exit(1) }

package metastandard

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestPublishedSchemaAndExamples(t *testing.T) {
	root := "../../../../contracts/metadata"
	files, e := filepath.Glob(filepath.Join(root, "examples", "*.json"))
	if e != nil || len(files) < 5 {
		t.Fatal("published examples missing", e)
	}
	for _, file := range files {
		t.Run(filepath.Base(file), func(t *testing.T) {
			data, e := os.ReadFile(file)
			if e != nil {
				t.Fatal(e)
			}
			_, e = Decode(data)
			valid := strings.HasSuffix(file, ".valid.json")
			if (e == nil) != valid {
				t.Fatal("unexpected validation result", e)
			}
		})
	}
	published, e := os.ReadFile(filepath.Join(root, "metadata.schema.json"))
	if e != nil {
		t.Fatal(e)
	}
	current, e := json.Marshal(JSONSchema())
	if e != nil {
		t.Fatal(e)
	}
	a, e := Canonical(published)
	if e != nil {
		t.Fatal(e)
	}
	b, e := Canonical(current)
	if e != nil || string(a) != string(b) {
		t.Fatal("published JSON Schema drifted; regenerate with metadata-validate --schema", e)
	}
}

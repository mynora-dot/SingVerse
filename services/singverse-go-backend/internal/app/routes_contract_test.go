package app

import (
	"encoding/json"
	"os"
	"regexp"
	"sort"
	"strings"
	"testing"
)

// The committed migration baseline is the inventory, not a hand-maintained copy
// of the Go registrations. Every public operation must have a real handler.
func TestPublicRouteContractCoverage(t *testing.T) {
	a := application(t)
	b, e := os.ReadFile("../../../../contracts/go-migration-baseline.json")
	if e != nil {
		t.Fatal(e)
	}
	var baseline struct {
		PublicOperations []struct{ Method, Path string } `json:"publicOperations"`
	}
	if e = json.Unmarshal(b, &baseline); e != nil {
		t.Fatal(e)
	}
	if len(baseline.PublicOperations) < 250 {
		t.Fatal("baseline unexpectedly empty or truncated")
	}
	normalize := func(p string) string {
		p = regexp.MustCompile(`\{[^}]+\}|:[^/]+|\*[^/]+`).ReplaceAllString(p, "{}")
		return strings.TrimSuffix(p, "/")
	}
	actual := map[string]bool{}
	for _, r := range a.Router.Routes() {
		actual[r.Method+" "+normalize(r.Path)] = true
	}
	missing := []string{}
	for _, op := range baseline.PublicOperations {
		if !actual[op.Method+" "+normalize(op.Path)] {
			missing = append(missing, op.Method+" "+op.Path)
		}
	}
	sort.Strings(missing)
	if len(missing) > 0 {
		t.Fatalf("%d public operations missing from actual Gin route table:\n%s", len(missing), strings.Join(missing, "\n"))
	}
	t.Logf("matched %d baseline public operations against %d actual registrations", len(baseline.PublicOperations), len(actual))
}

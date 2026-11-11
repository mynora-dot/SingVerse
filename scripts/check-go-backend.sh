#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
export GOTOOLCHAIN=go1.27.4
export GOPATH="${GOPATH:-$PWD/.tools/go}"
export GOMODCACHE="${GOMODCACHE:-$PWD/.tools/go-mod-cache}"
export GOCACHE="${GOCACHE:-$PWD/.tools/go-build-cache}"
export STATICCHECK_CACHE="${STATICCHECK_CACHE:-$PWD/.tools/staticcheck-cache}"
repo=$PWD
gofmt_bin="$(go env GOROOT)/bin/gofmt"
formatting=$(find services/singverse-go-backend -type f -name '*.go' -exec "$gofmt_bin" -l {} +)
if [ -n "$formatting" ]; then
  printf '%s\n' "$formatting" >&2
  echo 'Run gofmt on the listed files.' >&2
  exit 1
fi
for executable in staticcheck govulncheck; do
  if [ ! -x "$repo/.tools/go/bin/$executable" ]; then
    echo 'Run make go-tools-setup first.' >&2
    exit 1
  fi
done
cd services/singverse-go-backend
go vet ./...
"$repo/.tools/go/bin/staticcheck" ./...
"$repo/.tools/go/bin/govulncheck" ./...
go test -race ./...

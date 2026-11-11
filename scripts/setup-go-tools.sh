#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
export GOTOOLCHAIN=go1.27.4
export GOPATH="${GOPATH:-$PWD/.tools/go}"
export GOMODCACHE="${GOMODCACHE:-$PWD/.tools/go-mod-cache}"
export GOCACHE="${GOCACHE:-$PWD/.tools/go-build-cache}"
export GOBIN="$PWD/.tools/go/bin"
mkdir -p "$GOBIN"
go install honnef.co/go/tools/cmd/staticcheck@v0.8.2
go install golang.org/x/vuln/cmd/govulncheck@v1.9.0

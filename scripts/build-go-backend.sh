#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
export GOTOOLCHAIN=go1.27.4
export GOPATH="${GOPATH:-$PWD/.tools/go}"
export GOMODCACHE="${GOMODCACHE:-$PWD/.tools/go-mod-cache}"
export GOCACHE="${GOCACHE:-$PWD/.tools/go-build-cache}"
python3 scripts/prepare-go-assets.py
version=$(cat VERSION)
case "$version" in ''|*[!a-zA-Z0-9._+-]*) echo 'Invalid VERSION' >&2; exit 1;; esac
mkdir -p services/singverse-go-backend/bin
CGO_ENABLED=0 go -C services/singverse-go-backend build -trimpath -ldflags "-s -w -X main.version=$version" -o bin/singverse ./cmd/singverse

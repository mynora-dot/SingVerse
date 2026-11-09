#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
export GOTOOLCHAIN=go1.27.3
export GOPATH="${GOPATH:-$PWD/.tools/go}"
export GOMODCACHE="${GOMODCACHE:-$PWD/.tools/go-mod-cache}"
export GOCACHE="${GOCACHE:-$PWD/.tools/go-build-cache}"
cd services/singverse-go-backend
exec go "$@"

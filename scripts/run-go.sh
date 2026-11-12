#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
. ./scripts/go-env.sh
cd services/singverse-go-backend
exec go "$@"

# Shared by Go build, check, setup and command wrappers after entering the repository root.
export GOTOOLCHAIN=go1.27.4
export GOPATH="${GOPATH:-$PWD/.tools/go}"
export GOMODCACHE="${GOMODCACHE:-$PWD/.tools/go-mod-cache}"
export GOCACHE="${GOCACHE:-$PWD/.tools/go-build-cache}"

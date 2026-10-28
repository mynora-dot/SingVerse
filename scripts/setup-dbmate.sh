#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
mkdir -p .tools
if [ -x .tools/dbmate ]; then
  test "$(.tools/dbmate --version)" = "dbmate version 2.37.0"
  exit
fi
case "$(uname -s)-$(uname -m)" in
  Darwin-arm64) asset=macos-arm64; digest=902d80f060cf9ffa89c15e14517413afd398fa787740cf67862d10b8821734d5 ;;
  Darwin-x86_64) asset=macos-amd64; digest=a906a27037393738ef56793749d915c4b64e9243c0a2b54d388825c1f9ddd2f7 ;;
  Linux-aarch64) asset=linux-arm64; digest=42de2bf27989192b9ca8db4cfaa0a9d6b4aee154a03abef30239b7186b1baf82 ;;
  Linux-x86_64) asset=linux-amd64; digest=f855e99a0f0a81322014bf686b1cb820d2b4d8074e4eadafd64b851b80ecb15a ;;
  *) echo 'Unsupported dbmate platform' >&2; exit 1 ;;
esac
tmp=$(mktemp .tools/dbmate.XXXXXX)
trap 'rm -f "$tmp"' EXIT
curl --fail --location --silent --show-error "https://github.com/amacneil/dbmate/releases/download/v2.37.0/dbmate-$asset" -o "$tmp"
if command -v sha256sum >/dev/null; then
  echo "$digest  $tmp" | sha256sum -c -
else
  echo "$digest  $tmp" | shasum -a 256 -c -
fi
chmod 755 "$tmp"
mv "$tmp" .tools/dbmate

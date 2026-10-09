#!/usr/bin/env bash
#
# Cross-compile the GEX -> ATAS poll bridge.
#
# Produces native macOS binaries AND a Windows drop-in replacement for
# GexPoller.exe from a single Go source file. No Windows machine is needed —
# Go's toolchain cross-compiles to Windows natively from macOS.
#
# Usage:
#   ./build.sh
#   OUT=dist VERSION=1.2.3 ./build.sh
#
# Install Go if it is missing:  brew install go   (or https://go.dev/dl/)

set -euo pipefail
cd "$(dirname "$0")"

if ! command -v go >/dev/null 2>&1; then
	echo "error: 'go' not found on PATH." >&2
	echo "       install it with:  brew install go" >&2
	echo "       or download from: https://go.dev/dl/" >&2
	exit 1
fi

OUT="${OUT:-dist}"
VERSION="${VERSION:-1.0.0}"
LDFLAGS="-s -w -X main.version=${VERSION}"

mkdir -p "$OUT"

build() {
	local goos="$1" goarch="$2" name="$3"
	printf '  %-26s %s/%s\n' "$name" "$goos" "$goarch"
	GOOS="$goos" GOARCH="$goarch" CGO_ENABLED=0 \
		go build -trimpath -ldflags "$LDFLAGS" -o "$OUT/$name" .
}

echo "building gexpoller $VERSION -> $OUT/"
build darwin  arm64 "GexPoller"            # native, Apple Silicon
build darwin  amd64 "GexPoller-x64"        # native, Intel Mac
build windows amd64 "GexPoller.exe"        # drop-in replacement for the original
build windows arm64 "GexPoller-arm64.exe"  # Windows on ARM
build linux   amd64 "GexPoller-linux-x64"  # optional

echo
echo "artifacts:"
ls -lh "$OUT"

# Publish hashes so a distributed binary can be tied back to this source.
echo
if command -v shasum >/dev/null 2>&1; then
	( cd "$OUT" && shasum -a 256 ./* | tee SHA256SUMS )
elif command -v sha256sum >/dev/null 2>&1; then
	( cd "$OUT" && sha256sum ./* | tee SHA256SUMS )
fi

#!/usr/bin/env bash
#
# Build GexLevels.dll on macOS against the locally installed ATAS X.
#
# No Windows machine is needed: the four reference assemblies are taken straight
# out of the ATAS X application bundle, so the DLL is compiled against exactly
# the ATAS X version that will load it (no MissingMethodException risk).
#
# Usage:
#   ./build-mac.sh                     # full 0.2 source  -> 3 indicators
#   ./build-mac.sh --two-indicators    # omit GexLevelsPlusIndicator -> 2
#   OUT=/tmp/x ./build-mac.sh
#   ATAS_DIR=/path/to/dlls ./build-mac.sh
#
# Requires the .NET 10 SDK (`brew install --cask dotnet-sdk`, or dotnet-install.sh).

set -euo pipefail
cd "$(dirname "$0")"

# ---------------------------------------------------------------- toolchain
DOTNET="${DOTNET:-}"
if [ -z "$DOTNET" ]; then
	for candidate in dotnet "$HOME/.workbuddy/binaries/dotnet/dotnet" /usr/local/share/dotnet/dotnet; do
		if command -v "$candidate" >/dev/null 2>&1; then
			DOTNET="$candidate"
			break
		fi
	done
fi
if [ -z "$DOTNET" ] || ! command -v "$DOTNET" >/dev/null 2>&1; then
	echo "error: the .NET SDK was not found." >&2
	echo "       install it with:  brew install --cask dotnet-sdk" >&2
	echo "       or:               curl -sSL https://dot.net/v1/dotnet-install.sh | bash -s -- --channel 10.0" >&2
	exit 1
fi

# ------------------------------------------------------------ reference DLLs
ATAS_DIR="${ATAS_DIR:-/Applications/ATAS X.app/Contents/MonoBundle}"
if [ ! -f "$ATAS_DIR/ATAS.Indicators.dll" ]; then
	echo "error: ATAS X reference assemblies not found in $ATAS_DIR" >&2
	echo "       set ATAS_DIR=... to point at your copy" >&2
	exit 1
fi

OUT="${OUT:-../dist-mac}"
TWO_INDICATORS=0
if [ "${1:-}" = "--two-indicators" ]; then TWO_INDICATORS=1; fi

# ------------------------------------------------------------------ staging
# Build in a scratch tree so that no obj/bin directories are created in the
# source folder. The path is fixed (not random) so repeated builds are
# byte-for-byte reproducible.
STAGE="/tmp/gexdll-build"
rm -rf "$STAGE"
mkdir -p "$STAGE/src"
cp ./*.cs ./*.csproj "$STAGE/src/"
if [ "$TWO_INDICATORS" = "1" ]; then
	rm -f "$STAGE/src/GexLevelsPlusIndicator.cs"
fi

export DOTNET_CLI_HOME="$STAGE/home"
export NUGET_PACKAGES="$STAGE/nuget"
export DOTNET_CLI_TELEMETRY_OPTOUT=1
export DOTNET_NOLOGO=1

echo "ATAS refs : $ATAS_DIR"
echo "variant   : $([ "$TWO_INDICATORS" = "1" ] && echo '2 indicators' || echo '3 indicators (full source)')"
echo "building ..."

"$DOTNET" build "$STAGE/src/GexLevels.csproj" \
	-c Release \
	-p:AtasDir="$ATAS_DIR" \
	-o "$STAGE/out" \
	--nologo -v quiet

NAME="GexLevels.dll"
if [ "$TWO_INDICATORS" = "1" ]; then NAME="GexLevels-2indicators.dll"; fi

mkdir -p "$OUT"
cp "$STAGE/out/GexLevels.dll" "$OUT/$NAME"

echo
echo "built  : $OUT/$NAME"
echo "size   : $(stat -f%z "$OUT/$NAME") bytes"
printf "target : "; strings -a "$OUT/$NAME" | grep -E '^\.NETCoreApp' | sort -u | tr '\n' ' '; echo
printf "classes: "; strings -a "$OUT/$NAME" | grep -E '^Gex[A-Za-z]+Indicator$' | sort -u | tr '\n' ' '; echo
echo
if command -v shasum >/dev/null 2>&1; then
	shasum -a 256 "$OUT/$NAME"
fi

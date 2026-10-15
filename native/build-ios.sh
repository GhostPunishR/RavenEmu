#!/bin/sh
# Build RavenCore for the Apple SDK selected by Xcode.
set -eu
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
PLATFORM="${PLATFORM_NAME:-iphonesimulator}"
case "$PLATFORM" in
    iphoneos|iphonesimulator) ;;
    *) echo "Unsupported Apple SDK: $PLATFORM" >&2; exit 1 ;;
esac
BUILD="$ROOT/build-ios/$PLATFORM"
cmake -S "$ROOT" -B "$BUILD" \
    -DCMAKE_SYSTEM_NAME=iOS \
    -DCMAKE_OSX_SYSROOT="$PLATFORM" \
    -DCMAKE_OSX_ARCHITECTURES=arm64 \
    -DCMAKE_OSX_DEPLOYMENT_TARGET=15.0 \
    -DCMAKE_BUILD_TYPE=Release
cmake --build "$BUILD" --target ravenemu_c_api --parallel 2

#!/bin/sh
# macOS/Apple Silicon: build and exercise the real simulator application without signing.
set -eu
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
if [ "$(uname -s)" != Darwin ] || [ "$(uname -m)" != arm64 ]; then
    echo "iOS simulator tests require an Apple Silicon Mac with Xcode." >&2
    exit 1
fi
cd "$ROOT/iosApp"
xcodegen generate
SIMULATOR_ID="$(xcrun simctl list devices available -j | python3 -c '
import json,sys
devices=json.load(sys.stdin)["devices"]
phones=[d["udid"] for group in devices.values() for d in group if "iPhone" in d["name"] and d.get("isAvailable",False)]
if not phones: raise SystemExit("No iPhone simulator available")
print(phones[0])
')"
mkdir -p "$ROOT/iosApp/build"
RESULT_DIR="$(mktemp -d "$ROOT/iosApp/build/test-run.XXXXXX")"
xcodebuild -project RavenEmu.xcodeproj -scheme RavenEmu -configuration Debug \
    -destination "platform=iOS Simulator,id=$SIMULATOR_ID" \
    -derivedDataPath "$ROOT/iosApp/DerivedData" \
    -resultBundlePath "$RESULT_DIR/results.xcresult" \
    CODE_SIGNING_ALLOWED=NO test
ditto -c -k --sequesterRsrc --keepParent \
    "$ROOT/iosApp/DerivedData/Build/Products/Debug-iphonesimulator/RavenEmu.app" \
    "$ROOT/iosApp/build/RavenEmu-simulator.zip"

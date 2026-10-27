#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
repository="$(cd "$project_dir/../.." && pwd)"
out="$project_dir/build/pairing-tests"
mkdir -p "$out"
if [[ "$(uname -s)" != Darwin ]]; then
    echo "This integration probe launches the macOS native host." >&2
    exit 1
fi
if lsof -nP -iUDP:24820 >/dev/null 2>&1; then
    echo "SKIP pairing integration: UDP 24820 is already in use; stop the discovery host to run this check."
    exit 0
fi
swift build --package-path "$repository/server/macos" --product reflection
host_bin="$(swift build --package-path "$repository/server/macos" --show-bin-path)/reflection"
javac --release 8 -encoding UTF-8 -d "$out" \
    "$project_dir/app/src/main/java/io/github/linloir/reflection/core/PenSample.java" \
    "$project_dir/app/src/main/java/io/github/linloir/reflection/core/TouchpadEvent.java" \
    "$project_dir/app/src/main/java/io/github/linloir/reflection/core/RawTouchpadFrame.java" \
    "$project_dir/app/src/main/java/io/github/linloir/reflection/core/Wire.java" \
    "$project_dir/tests/PairingIntegrationProbe.java"
java -cp "$out" PairingIntegrationProbe "$host_bin"

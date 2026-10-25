#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
out="$project_dir/build/tests"
mkdir -p "$out"
javac --release 8 -Xlint:-options -d "$out" \
    "$project_dir/app/src/main/java/io/github/linloir/reflection/core/PenSample.java" \
    "$project_dir/app/src/main/java/io/github/linloir/reflection/core/PenStabilizer.java" \
    "$project_dir/app/src/main/java/io/github/linloir/reflection/core/CalibrationProfile.java" \
    "$project_dir/app/src/main/java/io/github/linloir/reflection/core/Wire.java" \
    "$project_dir/app/src/main/java/io/github/linloir/reflection/transport/DuplexLink.java" \
    "$project_dir/app/src/main/java/io/github/linloir/reflection/transport/TcpLink.java" \
    "$project_dir/app/src/main/java/io/github/linloir/reflection/transport/PenTransport.java" \
    "$project_dir/tests/stubs/android/view/InputDevice.java" \
    "$project_dir/tests/stubs/android/view/MotionEvent.java" \
    "$project_dir/app/src/main/java/io/github/linloir/reflection/input/PenInput.java" \
    "$project_dir/tests/InputAdapterTest.java" \
    "$project_dir/tests/PenStabilizerTest.java" \
    "$project_dir/tests/CalibrationProfileTest.java" \
    "$project_dir/tests/TransportProbe.java" \
    "$project_dir/tests/CoreTest.java"
java -cp "$out" CoreTest
java -cp "$out" InputAdapterTest
java -cp "$out" PenStabilizerTest
java -cp "$out" CalibrationProfileTest
python3 "$project_dir/tests/transport_test.py"

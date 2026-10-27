#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
out="$project_dir/build/tests"
mkdir -p "$out"
javac --release 8 -Xlint:-options -d "$out" \
    "$project_dir/app/src/main/java/io/github/linloir/reflection/core/PenSample.java" \
    "$project_dir/app/src/main/java/io/github/linloir/reflection/core/InkTrail.java" \
    "$project_dir/app/src/main/java/io/github/linloir/reflection/core/NoiseCalibration.java" \
    "$project_dir/app/src/main/java/io/github/linloir/reflection/core/TouchpadEvent.java" \
    "$project_dir/app/src/main/java/io/github/linloir/reflection/core/RawTouchpadFrame.java" \
    "$project_dir/app/src/main/java/io/github/linloir/reflection/core/PenStabilizer.java" \
    "$project_dir/app/src/main/java/io/github/linloir/reflection/core/CalibrationProfile.java" \
    "$project_dir/app/src/main/java/io/github/linloir/reflection/core/SurfaceCalibration.java" \
    "$project_dir/app/src/main/java/io/github/linloir/reflection/core/TiltCalibration.java" \
    "$project_dir/app/src/main/java/io/github/linloir/reflection/core/TiltMatcher.java" \
    "$project_dir/app/src/main/java/io/github/linloir/reflection/core/PairingExchange.java" \
    "$project_dir/app/src/main/java/io/github/linloir/reflection/core/EdgeNavigator.java" \
    "$project_dir/app/src/main/java/io/github/linloir/reflection/core/TiltDecoder.java" \
    "$project_dir/app/src/main/java/io/github/linloir/reflection/core/Wire.java" \
    "$project_dir/app/src/main/java/io/github/linloir/reflection/core/MappingState.java" \
    "$project_dir/app/src/main/java/io/github/linloir/reflection/transport/DuplexLink.java" \
    "$project_dir/app/src/main/java/io/github/linloir/reflection/transport/TcpLink.java" \
    "$project_dir/app/src/main/java/io/github/linloir/reflection/transport/PenTransport.java" \
    "$project_dir/tests/stubs/android/view/InputDevice.java" \
    "$project_dir/tests/stubs/android/view/MotionEvent.java" \
    "$project_dir/app/src/main/java/io/github/linloir/reflection/input/PenInput.java" \
    "$project_dir/app/src/main/java/io/github/linloir/reflection/input/TouchpadInput.java" \
    "$project_dir/app/src/main/java/io/github/linloir/reflection/input/WorkspaceInputGate.java" \
    "$project_dir/tests/TouchpadTest.java" \
    "$project_dir/tests/RawTouchpadTest.java" \
    "$project_dir/tests/RawTouchpadTransportProbe.java" \
    "$project_dir/tests/InkTrailTest.java" \
    "$project_dir/tests/NoiseCalibrationTest.java" \
    "$project_dir/tests/NoiseTransportProbe.java" \
    "$project_dir/tests/InputAdapterTest.java" \
    "$project_dir/tests/WorkspaceInputGateTest.java" \
    "$project_dir/tests/PenStabilizerTest.java" \
    "$project_dir/tests/CalibrationProfileTest.java" \
    "$project_dir/tests/WorkspaceCoreTest.java" \
    "$project_dir/tests/TiltDecoderTest.java" \
    "$project_dir/tests/TiltMatcherTest.java" \
    "$project_dir/tests/PairingExchangeTest.java" \
    "$project_dir/tests/TransportProbe.java" \
    "$project_dir/tests/MappingStateTest.java" \
    "$project_dir/tests/MappingTransportProbe.java" \
    "$project_dir/tests/CoreTest.java"
java -cp "$out" CoreTest
java -cp "$out" InputAdapterTest
java -cp "$out" WorkspaceInputGateTest
java -cp "$out" PenStabilizerTest
java -cp "$out" CalibrationProfileTest
java -cp "$out" WorkspaceCoreTest
java -cp "$out" TiltDecoderTest
java -cp "$out" TiltMatcherTest
java -cp "$out" PairingExchangeTest
java -cp "$out" MappingStateTest
java -cp "$out" TouchpadTest
java -cp "$out" RawTouchpadTest
java -cp "$out" InkTrailTest
java -cp "$out" NoiseCalibrationTest
python3 "$project_dir/tests/transport_test.py"
python3 "$project_dir/tests/mapping_transport_test.py"
python3 "$project_dir/tests/noise_transport_test.py"
python3 "$project_dir/tests/raw_touchpad_transport_test.py"
pairing_out="$out/pairing-identity"
mkdir -p "$pairing_out"
javac --release 8 -Xlint:-options -encoding UTF-8 -d "$pairing_out" \
    "$project_dir/tests/pairing-stubs/android/content/Context.java" \
    "$project_dir/tests/pairing-stubs/android/content/SharedPreferences.java" \
    "$project_dir/tests/pairing-stubs/android/os/Build.java" \
    "$project_dir/tests/pairing-stubs/android/util/Base64.java" \
    "$project_dir/tests/pairing-stubs/io/github/linloir/reflection/control/Controller.java" \
    "$project_dir/app/src/main/java/io/github/linloir/reflection/core/PenSample.java" \
    "$project_dir/app/src/main/java/io/github/linloir/reflection/core/TouchpadEvent.java" \
    "$project_dir/app/src/main/java/io/github/linloir/reflection/core/RawTouchpadFrame.java" \
    "$project_dir/app/src/main/java/io/github/linloir/reflection/core/Wire.java" \
    "$project_dir/app/src/main/java/io/github/linloir/reflection/control/PairingIdentity.java" \
    "$project_dir/tests/PairingIdentityTest.java"
java -cp "$pairing_out" io.github.linloir.reflection.control.PairingIdentityTest

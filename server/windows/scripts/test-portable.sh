#!/bin/sh
set -eu

module_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
build_dir=$(mktemp -d "${TMPDIR:-/tmp}/reflection-core.XXXXXX")
trap 'rm -rf "$build_dir"' EXIT HUP INT TERM

compiler=${CXX:-c++}
"$compiler" -std=c++17 -Wall -Wextra -Wpedantic -Werror \
    -I"$module_dir/include" \
    "$module_dir/src/core.cpp" \
    "$module_dir/src/config.cpp" \
    "$module_dir/src/protocol.cpp" \
    "$module_dir/src/raw_touchpad.cpp" \
    "$module_dir/src/discovery_protocol.cpp" \
    "$module_dir/src/display_mapping.cpp" \
    "$module_dir/src/stabilization.cpp" \
    "$module_dir/tests/core_tests.cpp" \
    -o "$build_dir/reflection-core-tests"
"$build_dir/reflection-core-tests"
"$compiler" -std=c++17 -Wall -Wextra -Wpedantic -Werror \
    -I"$module_dir/include" \
    "$module_dir/src/core.cpp" "$module_dir/src/config.cpp" \
    "$module_dir/src/protocol.cpp" "$module_dir/src/raw_touchpad.cpp" "$module_dir/src/discovery_protocol.cpp" "$module_dir/src/display_mapping.cpp" \
    "$module_dir/src/stabilization.cpp" "$module_dir/tests/stabilization_tests.cpp" \
    -o "$build_dir/reflection-stabilization-tests"
"$build_dir/reflection-stabilization-tests"

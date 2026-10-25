# Contributing

Reflection is one repository with independently buildable native modules.
Do not create nested Git repositories under `client/` or `server/`.

Start with the [architecture](docs/architecture.md) and
[wire specification](protocol/README.md), then the platform README.

## Build and check

```bash
python3 reflection build all
python3 reflection test
```

Build each native host on its supported operating system. The Windows
portable core tests can also run with a C++17 compiler on macOS or Linux:

```bash
bash server/windows/scripts/test-portable.sh
```

The Android SDK-only script uses installed official SDK tools and a JDK.
The Gradle project is also suitable for Android Studio. See the Android
README for specific toolchain versions.

Run `tools/check_host.py` against a built native host to test the actual
network/parser/session implementation in dry-run mode.

## Changes

- Keep system capture, transport, configuration, mapping, and UI separate.
- Expose user-facing controls through the control core and CLI.
- Preserve bounded queues and ordered down/up/cancel transitions.
- Add regression tests for protocol, mapping, state, or security changes.
- Use `protocol/vectors.json` to prevent cross-language wire drift.
- Do not change protocol v1 silently; incompatible changes need a version.
- Distinguish compiled, simulated, device-tested, and application-tested.
- Do not claim latency or compatibility without identifying the measurement.

## Repository hygiene

Build outputs, local SDK configuration, credentials, and captures are ignored.
Use `.local/` for private experiments and machine-specific notes. Public
documentation should describe reproducible workflows, not personal paths,
device serial numbers, pairing keys, or temporary debugging transcripts.

Contributions are provided under the repository's MIT license.

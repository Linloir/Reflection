#!/usr/bin/env python3
"""Exercise a real native host in dry-run mode with temporary credentials."""

import argparse
import json
import os
from pathlib import Path
import signal
import socket
import struct
import subprocess
import tempfile
import time

from reflection_protocol import Client, PenSample, ProtocolError, encode_frame, receive_frame


def command(binary, config, *arguments):
    result = subprocess.run([binary, "--config", str(config), *arguments],
                            capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError(result.stderr.strip())
    return json.loads(result.stdout)


def wait_closed(stream, timeout=3.0):
    stream.settimeout(timeout)
    try:
        if stream.recv(4096):
            raise AssertionError("expected connection close, received a frame")
    except (ConnectionResetError, ConnectionAbortedError):
        pass


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server", required=True, help="Path to the native executable")
    parser.add_argument("--platform", choices=("macos", "windows"),
                        default="windows" if os.name == "nt" else "macos")
    args = parser.parse_args()
    binary = str(Path(args.server).resolve())
    checks = []
    with tempfile.TemporaryDirectory(prefix="reflection-check-") as directory:
        root = Path(directory)
        config = root / ("server.conf" if args.platform == "windows" else "server.json")
        with socket.socket() as reservation:
            reservation.bind(("127.0.0.1", 0))
            port = reservation.getsockname()[1]
        command(binary, config, *(["init"] if args.platform == "windows" else ["config", "init"]))
        command(binary, config, "config", "set", "port", str(port))
        pairing = command(binary, config, *(["config", "show", "--secrets"]
                                           if args.platform == "windows" else ["pair"]))
        key = bytes.fromhex(pairing["token"])
        with (root / "server.log").open("w+") as log:
            server = subprocess.Popen([binary, "--config", str(config), "start", "--dry-run"],
                                      stdout=log, stderr=log)
            try:
                deadline = time.monotonic() + 10
                while True:
                    try:
                        with Client("127.0.0.1", port, key) as client:
                            assert client.ping() >= 0
                        break
                    except (OSError, EOFError):
                        if time.monotonic() > deadline or server.poll() is not None:
                            log.seek(0)
                            raise RuntimeError("Host did not become ready: " + log.read())
                        time.sleep(0.05)
                checks.append("mutual authentication and signed heartbeat")

                def connect():
                    # Allow the preceding session's EOF to reach the single-owner host.
                    for _ in range(30):
                        try:
                            return Client("127.0.0.1", port, key)
                        except (OSError, EOFError):
                            time.sleep(0.05)
                    raise RuntimeError("host did not accept the next session")

                def require_contact(down):
                    if args.platform != "macos":
                        return
                    status_path = config.with_suffix(".status.json")
                    deadline = time.monotonic() + 2
                    while time.monotonic() < deadline:
                        if status_path.exists():
                            status = json.loads(status_path.read_text())
                            if status.get("contactDown") is down:
                                return
                        time.sleep(0.025)
                    raise AssertionError("recording contact state did not become " + str(down))

                with connect() as client:
                    for seq, phase, pressure in [(1, 0, 0), (2, 1, 0.25),
                                                 (3, 2, 0.75), (4, 3, 0),
                                                 (5, 5, 0)]:
                        client.send_sample(PenSample(seq, seq * 1000000, 0.5, 0.6,
                                                     pressure, -30, 45, phase=phase))
                    client.ping()
                checks.append("hover/down/move/up/leave through real host parser")

                with connect() as client:
                    sample = PenSample(1, 1, 0.3, 0.4, 0.5, phase=1)
                    client.send_sample(sample)
                    client.send_sample(sample)
                    wait_closed(client.stream)
                checks.append("duplicate sample rejected")

                with connect() as client:
                    client.send_sample(PenSample(2, 1, 0.3, 0.4, 0, phase=0))
                    wait_closed(client.stream)
                checks.append("first sample sequence must be one")

                with connect() as client:
                    sample = PenSample(1, 1, 0.3, 0.4, 0.5, phase=1)
                    frame = bytearray(encode_frame(0x10, sample.encode(), client.session_key))
                    frame[-1] ^= 1
                    client.stream.sendall(frame)
                    wait_closed(client.stream)
                checks.append("tampered sample rejected")

                with connect() as client:
                    ping = encode_frame(0x11, struct.pack(">Q", 123), client.session_key)
                    client.stream.sendall(ping)
                    assert receive_frame(client.stream, client.session_key)[0] == 0x12
                    client.stream.sendall(ping)
                    wait_closed(client.stream)
                checks.append("heartbeat replay rejected")

                with connect() as client:
                    client.stream.sendall(encode_frame(0x12, struct.pack(">Q", 123), client.session_key))
                    wait_closed(client.stream)
                checks.append("reflected server pong rejected")

                with connect() as client:
                    caps = encode_frame(0x20, b'{"platform":"test"}', client.session_key)
                    client.stream.sendall(caps)
                    client.ping()
                    client.stream.sendall(caps)
                    wait_closed(client.stream)
                checks.append("capabilities replay rejected")

                with connect() as client:
                    invalid = bytearray(PenSample(1, 1, 0.3, 0.4, 0.5).encode())
                    invalid[24:28] = struct.pack(">f", float("nan"))
                    client.stream.sendall(encode_frame(0x10, bytes(invalid), client.session_key))
                    wait_closed(client.stream)
                checks.append("authenticated NaN rejected")

                with connect() as client:
                    client.stream.sendall(struct.pack(">I", 4097))
                    wait_closed(client.stream)
                checks.append("oversized frame rejected before body allocation")

                with connect() as client:
                    sample = PenSample(1, 1, 0.3, 0.4, 0.5, phase=1)
                    client.send_sample(sample)
                    client.ping()
                    require_contact(True)
                    wait_closed(client.stream, timeout=3.5)
                    require_contact(False)
                checks.append("idle contact disconnected by watchdog")
                if args.platform == "macos":
                    checks.append("recording contact released before server shutdown")

                with connect() as client:
                    client.ping()
                checks.append("new client accepted after failures and timeout")
            finally:
                if server.poll() is None:
                    server.send_signal(signal.SIGTERM)
                    try:
                        server.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        server.kill()
                        server.wait()
            if args.platform == "macos":
                status_path = config.with_suffix(".status.json")
                status = json.loads(status_path.read_text())
                assert status["contactDown"] is False
                checks.append("recording backend released on shutdown")
    print(json.dumps({"passed": len(checks), "checks": checks,
                      "systemInputInjected": False}, indent=2))


if __name__ == "__main__":
    main()

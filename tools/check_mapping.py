#!/usr/bin/env python3
"""Exercise negotiated screen controls against a real host without injecting input."""

import argparse
import json
import os
from pathlib import Path
import socket
import struct
import subprocess
import tempfile
import time

from check_host import command, wait_closed
from reflection_protocol import Client, PenSample, encode_frame, receive_frame


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server", required=True)
    parser.add_argument("--platform", choices=("windows", "macos"),
                        default="windows" if os.name == "nt" else "macos")
    args = parser.parse_args()
    binary = str(Path(args.server).resolve())
    checks = []
    with tempfile.TemporaryDirectory(prefix="reflection-mapping-") as directory:
        root = Path(directory)
        config = root / ("server.conf" if args.platform == "windows" else "server.json")
        call = lambda *values: command(binary, config, *values)
        call(*(["init"] if args.platform == "windows" else ["config", "init"]))
        with socket.socket() as reservation:
            reservation.bind(("127.0.0.1", 0))
            port = reservation.getsockname()[1]
        call("config", "set", "port", str(port))
        pairing = call(*(["config", "show", "--secrets"] if args.platform == "windows" else ["pair"]))
        key = bytes.fromhex(pairing["token"])
        before = config.read_bytes()
        with (root / "host.log").open("w+") as log:
            process = subprocess.Popen([binary, "--config", str(config), "start", "--dry-run"], stdout=log, stderr=log)
            try:
                def connect():
                    deadline = time.monotonic() + 10
                    while True:
                        try:
                            return Client("127.0.0.1", port, key)
                        except (OSError, EOFError):
                            if process.poll() is not None or time.monotonic() >= deadline:
                                log.seek(0)
                                raise RuntimeError("Host did not accept a session: " + log.read())
                            time.sleep(0.05)

                def mapping_status(client, timeout=2.0):
                    kind, payload = receive_frame(client.stream, client.session_key, timeout=timeout)
                    assert kind == 0x22, f"expected mapping status, got {kind}"
                    value = json.loads(payload)
                    assert value["revision"] > getattr(client, "mapping_revision", 0)
                    client.mapping_revision = value["revision"]
                    assert value["mode"] in ("all", "display", "custom")
                    assert isinstance(value["pending"], bool)
                    assert 1 <= len(value["displays"]) <= 16
                    assert len({d["id"] for d in value["displays"]}) == len(value["displays"])
                    for rectangle in [value["target"], *value["displays"]]:
                        assert rectangle["width"] > 0 and rectangle["height"] > 0
                    return value

                def negotiate(client):
                    client.stream.sendall(encode_frame(0x20, b'{"platform":"test","mappingControl":1}', client.session_key))
                    return mapping_status(client)

                def request(client, sequence, action, display=""):
                    client.stream.sendall(encode_frame(0x21, struct.pack(">QB", sequence, action) + display.encode(), client.session_key))
                    return mapping_status(client)

                with connect() as client:
                    assert client.ping() >= 0
                checks.append("legacy clients still receive only pongs")
                call("mapping", "all")
                with connect() as client:
                    initial = negotiate(client)
                    assert initial["mode"] == "all"
                    display = initial["displays"][0]["id"]
                    state = request(client, 1, 4, display)
                    assert state["mode"] == "display" and state["selected"] == display and not state["pending"]
                    assert request(client, 2, 3)["mode"] == "all"
                    client.send_sample(PenSample(1, 1, 0.4, 0.4, 0.5, phase=1))
                    pending = request(client, 3, 4, display)
                    assert pending["pending"] and pending["mode"] == "all"
                    client.send_sample(PenSample(2, 2, 0.5, 0.5, 0.5, phase=2))
                    assert request(client, 4, 0)["pending"]
                    client.send_sample(PenSample(3, 3, 0.5, 0.5, 0, phase=3))
                    state = request(client, 5, 0)
                    # A host may publish the committed change immediately at UP.
                    while state["pending"]:
                        state = mapping_status(client)
                    assert state["mode"] == "display" and state["selected"] == display
                    checks.append("authenticated tablet switches wait for stroke release")
                    # Drain any asynchronous snapshot before a new CLI-originated change.
                    client.stream.settimeout(0.05)
                    try:
                        while True:
                            mapping_status(client, timeout=0.05)
                    except TimeoutError:
                        pass
                    finally:
                        client.stream.settimeout(5)
                    call("mapping", "all")
                    ping = struct.pack(">Q", time.monotonic_ns())
                    client.stream.sendall(encode_frame(0x11, ping, client.session_key))
                    seen_pong = seen_mapping = False
                    for _ in range(4):
                        kind, payload = receive_frame(client.stream, client.session_key)
                        if kind == 0x12:
                            assert payload == ping
                            seen_pong = True
                        elif kind == 0x22:
                            value = json.loads(payload)
                            assert value["revision"] > client.mapping_revision
                            client.mapping_revision = value["revision"]
                            seen_mapping |= value["mode"] == "all"
                        else:
                            raise AssertionError("unexpected response")
                        if seen_pong and seen_mapping:
                            break
                    assert seen_pong and seen_mapping
                    checks.append("CLI changes reach the tablet while heartbeats continue")
                    assert config.read_bytes() == before
                    checks.append("live switching preserves saved startup configuration")

                for payload, negotiated, label in [
                    (struct.pack(">QB", 1, 0), False, "unnegotiated controls rejected"),
                    (struct.pack(">QB", 0, 0), True, "zero sequence rejected"),
                    (struct.pack(">QB", 1, 5), True, "unknown action rejected"),
                    (struct.pack(">QB", 1, 4), True, "empty selected display rejected"),
                    (struct.pack(">QB", 1, 0) + b"extra", True, "extra control payload rejected"),
                ]:
                    with connect() as client:
                        if negotiated:
                            negotiate(client)
                        client.stream.sendall(encode_frame(0x21, payload, client.session_key))
                        wait_closed(client.stream)
                    checks.append(label)
                with connect() as client:
                    negotiate(client)
                    request(client, 1, 0)
                    client.stream.sendall(encode_frame(0x21, struct.pack(">QB", 1, 0), client.session_key))
                    wait_closed(client.stream)
                checks.append("mapping control replay rejected")
                with connect() as client:
                    negotiate(client)
                    tampered = bytearray(encode_frame(0x21, struct.pack(">QB", 1, 3), client.session_key))
                    tampered[-1] ^= 1
                    client.stream.sendall(tampered)
                    wait_closed(client.stream)
                checks.append("tampered mapping command rejected")
            finally:
                if process.poll() is None:
                    try:
                        call("stop")
                        process.wait(timeout=5)
                    except (RuntimeError, subprocess.TimeoutExpired):
                        process.kill()
                        process.wait(timeout=5)
    print(json.dumps({"result": "passed", "checks": checks}, indent=2))


if __name__ == "__main__":
    main()

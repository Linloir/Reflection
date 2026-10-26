#!/usr/bin/env python3
"""Negotiated mapping controls preserve pen order and cannot replace heartbeats."""
import hashlib
import hmac
import json
import pathlib
import socket
import struct
import subprocess
import threading
import time

root = pathlib.Path(__file__).resolve().parents[1]
key = bytes.fromhex("aa" * 32)
nonce = bytes(range(32))
mac = lambda k, data: hmac.new(k, data, hashlib.sha256).digest()


def exact(stream, size):
    result = b""
    while len(result) < size:
        part = stream.recv(size - len(result))
        if not part:
            raise EOFError("Peer disconnected")
        result += part
    return result


def read(stream):
    size, = struct.unpack(">I", exact(stream, 4))
    assert 2 <= size <= 4096
    return exact(stream, size)


def send(stream, message):
    stream.sendall(struct.pack(">I", len(message)) + message)


def status(revision, selected="", pending=False):
    return json.dumps({
        "revision": revision, "mode": "display" if selected else "all",
        "selected": selected, "pending": pending,
        "target": {"x": 0, "y": 0, "width": 1920 if selected else 3840, "height": 1080},
        "displays": [
            {"id": "left", "name": "Left", "x": -1920, "y": 0, "width": 1920, "height": 1080, "primary": True},
            {"id": "right", "name": "Right", "x": 0, "y": 0, "width": 1920, "height": 1080, "primary": False},
        ],
    }, separators=(",", ":")).encode()


def exercise(scenario):
    errors = []

    def host(listener):
        try:
            stream, _ = listener.accept()
            with stream:
                stream.settimeout(5)
                send(stream, b"\x01\x01" + nonce)
                assert read(stream) == b"\x01\x02" + mac(key, b"Reflection/client/v1" + nonce)
                send(stream, b"\x01\x03" + mac(key, b"Reflection/server/v1" + nonce))
                session = mac(key, b"Reflection/session/v1" + nonce)

                def signed(kind, payload):
                    body = bytes((1, kind)) + payload
                    send(stream, body + mac(session, body))

                def verified():
                    frame = read(stream)
                    assert hmac.compare_digest(frame[-32:], mac(session, frame[:-32]))
                    return frame[:-32]

                capabilities = verified()
                assert capabilities[1] == 0x20
                assert json.loads(capabilities[2:])["mappingControl"] == 1
                assert json.loads(capabilities[2:])["hostStabilization"] == 1
                signed(0x22, status(1))
                if scenario == "no-pong":
                    # Regular authenticated status updates must not extend the pong deadline.
                    for revision in range(2, 40):
                        time.sleep(.1)
                        try:
                            signed(0x22, status(revision))
                        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
                            break
                    return
                observed = []
                while len(observed) < 4:
                    body = verified()
                    if body[1] == 0x11:
                        signed(0x12, body[2:])
                    elif body[1] == 0x10:
                        phase = body[2 + 44]
                        observed.append(("pen", phase))
                        if phase == 3:
                            signed(0x22, status(3, "right"))
                    elif body[1] == 0x21:
                        assert len(body) == 11
                        seq, action = struct.unpack(">QB", body[2:])
                        observed.append(("mapping", seq, action))
                        signed(0x22, status(2, pending=True) if seq == 1 else status(4))
                    else:
                        raise AssertionError("Unexpected frame")
                assert observed == [("pen", 1), ("mapping", 1, 1), ("pen", 3), ("mapping", 2, 3)]
                time.sleep(.1)
                signed(0x22, status(4))
                # Replayed status must close the session even though its MAC is valid.
                while stream.recv(4096):
                    pass
        except BaseException as error:
            errors.append(error)

    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        listener.listen(1)
        listener.settimeout(8)
        thread = threading.Thread(target=host, args=(listener,), daemon=True)
        thread.start()
        result = subprocess.run(["java", "-cp", str(root / "build/tests"), "MappingTransportProbe",
                                 str(listener.getsockname()[1]), scenario], timeout=12)
        thread.join(6)
        assert not thread.is_alive(), "Mapping host test did not finish"
        if errors:
            raise errors[0]
        assert result.returncode == 0


exercise("replay")
exercise("no-pong")

#!/usr/bin/env python3
"""Actual TCP negotiation, contact ordering, reset, and signed acknowledgement replay."""
import hashlib
import hmac
import json
from pathlib import Path
import socket
import struct
import subprocess
import threading
import time

root = Path(__file__).resolve().parents[1]
key = bytes.fromhex("aa" * 32)
nonce = bytes(range(32))
errors = []


def mac(secret, data):
    return hmac.new(secret, data, hashlib.sha256).digest()


def exact(stream, size):
    result = b""
    while len(result) < size:
        block = stream.recv(size - len(result))
        if not block:
            raise EOFError()
        result += block
    return result


def read(stream):
    size, = struct.unpack(">I", exact(stream, 4))
    assert 2 <= size <= 4096
    return exact(stream, size)


def send(stream, body):
    stream.sendall(struct.pack(">I", len(body)) + body)


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

            caps = verified()
            assert caps[1] == 0x20 and json.loads(caps[2:])["tabletCalibration"] == 1
            signed(0x26, struct.pack(">BQf", 1, 0, 0))
            observed = []
            while len(observed) < 6:
                frame = verified()
                if frame[1] == 0x11:
                    signed(0x12, frame[2:])
                elif frame[1] == 0x25:
                    sequence, noise = struct.unpack(">Qf", frame[2:])
                    observed.append(("noise", sequence, round(noise, 6)))
                    signed(0x26, struct.pack(">BQf", 1, sequence, noise or .0006))
                elif frame[1] == 0x10:
                    observed.append(("pen", frame[46]))
                else:
                    raise AssertionError(f"Unexpected frame {frame[1]}")
            assert observed == [("noise", 1, .003), ("pen", 1), ("pen", 2), ("pen", 3),
                                ("noise", 2, .004), ("noise", 3, 0)], observed
            time.sleep(.1)
            signed(0x26, struct.pack(">BQf", 1, 3, .0006))
            while stream.recv(4096):
                pass
    except BaseException as error:
        errors.append(error)


with socket.socket() as listener:
    listener.bind(("127.0.0.1", 0)); listener.listen(1); listener.settimeout(8)
    worker = threading.Thread(target=host, args=(listener,), daemon=True); worker.start()
    subprocess.run(["java", "-cp", str(root / "build/tests"), "NoiseTransportProbe",
                    str(listener.getsockname()[1])], check=True, timeout=12)
    worker.join(timeout=6)
    assert not worker.is_alive()
if errors:
    raise errors[0]
print("Calibration TCP negotiation, contact ordering, reset and replay checks passed")

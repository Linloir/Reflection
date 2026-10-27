#!/usr/bin/env python3
"""Authenticate the production Java transport and exercise native contact streams."""
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


def mac(secret, body):
    return hmac.new(secret, body, hashlib.sha256).digest()


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


def host(listener, mode):
    fallback = mode == "fallback"
    geometry = mode == "geometry"
    for cycle in range(1 if fallback else 2):
        stream, _ = listener.accept()
        with stream:
            stream.settimeout(5)
            send(stream, b"\x01\x01" + nonce)
            assert read(stream) == b"\x01\x02" + mac(key, b"Reflection/client/v1" + nonce)
            send(stream, b"\x01\x03" + mac(key, b"Reflection/server/v1" + nonce))
            session = mac(key, b"Reflection/session/v1" + nonce)

            def signed(kind, body):
                packet = bytes((1, kind)) + body
                send(stream, packet + mac(session, packet))

            def verified():
                packet = read(stream)
                assert hmac.compare_digest(packet[-32:], mac(session, packet[:-32]))
                return packet[:-32]

            capabilities = verified()
            assert capabilities[1] == 0x20
            values = json.loads(capabilities[2:])
            dimensions = (9000, 6000) if geometry and cycle == 1 else (12000, 8000)
            assert (values["rawTouchpad"], values["rawTouchpadWidth"], values["rawTouchpadHeight"]) == (1, *dimensions)
            signed(0x24, b"\x01")
            if fallback:
                while True:
                    packet = verified()
                    if packet[1] == 0x11:
                        signed(0x12, packet[2:])
                    else:
                        assert packet[1] == 0x23 and packet[26] == 1, "unsupported raw frames must not be sent"
                        time.sleep(.6)
                        break
                continue
            acknowledgement = struct.pack(">BII", 1, *dimensions)
            signed(0x28, acknowledgement)
            observed = []
            pen = []
            while len(observed) < (2 if geometry else 3):
                packet = verified()
                if packet[1] == 0x11:
                    signed(0x12, packet[2:])
                    continue
                if packet[1] == 0x10:
                    pen.append(packet[46])
                    continue
                assert packet[1] == 0x27
                sequence, timestamp, count, flags, reserved = struct.unpack(">QQBBH", packet[2:22])
                assert sequence == len(observed) + 1 and reserved == 0
                assert len(packet) == 22 + count * 16
                if flags == 1:
                    assert count == 0
                    if geometry:
                        assert pen == [1, 2, 3], "geometry reconnection overtook pen release"
                    observed.append(("cancel", timestamp))
                else:
                    assert flags == 0 and count == 1
                    identity, phase, confidence, reserved, x, y = struct.unpack(">IBBHff", packet[22:])
                    assert confidence == 1 and reserved == 0 and (x, y) == (.25, .75)
                    observed.append((identity, phase, timestamp))
            if geometry:
                expected = [(7, 1, 20), ("cancel", 20)] if cycle == 0 else [(2, 1, 40), (2, 3, 50)]
            else:
                expected = [(7, 1, 20), (7, 2, 30), ("cancel", 40)] if cycle == 0 else [
                    (3, 1, 80), (3, 3, 90), ("cancel", 100)]
            assert observed == expected, observed
            if cycle == 1:
                signed(0x28, acknowledgement)
                assert stream.recv(1) == b"", "replayed capability must close the session"


for mode in ("native", "fallback", "geometry"):
    errors = []
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        listener.listen(2)
        listener.settimeout(8)

        def run_host():
            try:
                host(listener, mode)
            except BaseException as error:
                errors.append(error)

        worker = threading.Thread(target=run_host, daemon=True)
        worker.start()
        subprocess.run(["java", "-cp", str(root / "build/tests"), "RawTouchpadTransportProbe",
                        str(listener.getsockname()[1]), mode],
                       check=True, timeout=15)
        worker.join(timeout=5)
        assert not worker.is_alive()
    if errors:
        raise errors[0]
print("Native touchpad TCP and compatibility fallback checks passed")

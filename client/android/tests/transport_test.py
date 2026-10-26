#!/usr/bin/env python3
"""Independent wire peer for Java transport tests; no Android device required."""
import hashlib
import hmac
import json
import pathlib
import socket
import struct
import subprocess
import threading

root = pathlib.Path(__file__).resolve().parents[1]
key = bytes.fromhex('aa' * 32)
nonce = bytes(range(32))
mac = lambda k, data: hmac.new(k, data, hashlib.sha256).digest()
errors = []

def receive_exact(stream, count):
    result = b''
    while len(result) < count:
        chunk = stream.recv(count - len(result))
        if not chunk:
            raise AssertionError('Unexpected EOF')
        result += chunk
    return result

def read(stream):
    size, = struct.unpack('>I', receive_exact(stream, 4))
    assert 2 <= size <= 4096
    return receive_exact(stream, size)

def send(stream, body):
    stream.sendall(struct.pack('>I', len(body)) + body)

def host(listener, eraser):
    try:
        stream, _ = listener.accept()
        with stream:
            stream.settimeout(5)
            send(stream, b'\x01\x01' + nonce)
            assert read(stream) == b'\x01\x02' + mac(key, b'Reflection/client/v1' + nonce)
            send(stream, b'\x01\x03' + mac(key, b'Reflection/server/v1' + nonce))
            session = mac(key, b'Reflection/session/v1' + nonce)
            pens, pong = [], None
            expected = 17 if eraser else 6
            while len(pens) < expected or pong is None:
                frame = read(stream)
                body, tag = frame[:-32], frame[-32:]
                assert hmac.compare_digest(tag, mac(session, body))
                if body[1] == 0x10:
                    assert len(body) == 50
                    pens.append(struct.unpack('>QQffffffIBBH', body[2:]))
                elif body[1] == 0x11:
                    pong_body = b'\x01\x12' + body[2:]
                    pong = pong_body + mac(session, pong_body)
                    send(stream, pong)
                else:
                    assert body[1] == 0x20
                    assert json.loads(body[2:])['hostStabilization'] == 1
            assert [p[0] for p in pens] == list(range(1, expected + 1))
            assert all(p[11] == 0 for p in pens)
            assert all(a[1] <= b[1] for a, b in zip(pens, pens[1:]))
            if eraser:
                assert [p[9] for p in pens] == [0, 0, 0, 0, 0, 1, 2, 2, 2, 2, 3, 0, 5, 0, 0, 0, 5]
                assert [p[10] for p in pens] == [2] * 13 + [1] * 4
                assert [p[8] for p in pens] == [0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 2, 0, 0]
                pressures = [0, 0, 0, 0, 0, .2, .4, .6, .7, .7, 0, 0, 0, 0, 0, 0, 0]
                assert all(abs(p[4] - pressure) < 1e-6 for p, pressure in zip(pens, pressures))
                assert all(p[4] == 0 for p in pens if p[9] in (0, 3, 5))
                assert [p[1] for p in pens] == [(1000 + i * 4) * 1000000 for i in range(expected)]
                positions = {1: (.11, .21), 2: (.12, .22), 6: (.13, .23), 7: (.14, .24)}
                assert all(abs(p[2] - positions.get(i, (.1, .2))[0]) < 1e-6
                           and abs(p[3] - positions.get(i, (.1, .2))[1]) < 1e-6 for i, p in enumerate(pens))
            else:
                assert [p[9] for p in pens] == [1, 2, 2, 2, 2, 3]
                assert all(p[10] == 1 for p in pens)
                assert [p[1] for p in pens] == [1000000000, 1000000000, 1000000000,
                                              1001000000, 1002000000, 1002000000]
                assert all(abs(p[2] - i * .1) < 1e-6 and abs(p[3] - .4) < 1e-6
                           and abs(p[4] - (0 if i == 5 else i * .1)) < 1e-6 for i, p in enumerate(pens))
            send(stream, pong)
            # A replayed authenticated heartbeat must close the current session.
            while stream.recv(4096):
                pass
    except BaseException as error:
        errors.append(error)

for eraser in (False, True):
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0))
        listener.listen(1)
        listener.settimeout(8)
        thread = threading.Thread(target=host, args=(listener, eraser), daemon=True)
        thread.start()
        args = ['java', '-cp', str(root / 'build/tests'), 'TransportProbe', str(listener.getsockname()[1])]
        if eraser:
            args.append('eraser')
        result = subprocess.run(args, timeout=10)
        thread.join(6)
        assert not thread.is_alive(), 'Host test thread did not finish'
        if errors:
            raise errors[0]
        assert result.returncode == 0

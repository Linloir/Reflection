"""Reflection v1 reference protocol, diagnostics, and deterministic fixtures."""

import dataclasses
import hashlib
import hmac
import math
import socket
import struct
import time
from typing import Optional

VERSION = 1
MAX_BODY = 4096
DEFAULT_PORT = 24819
BLUETOOTH_UUID = "7b011e5a-8b31-4c40-8d7a-3e6c6dcbb758"
SAMPLE = struct.Struct(">QQffffffIBBH")


class ProtocolError(ValueError):
    pass


def decode_key(value: str) -> bytes:
    value = value.strip()
    if len(value) != 64:
        raise ProtocolError("pairing key must contain exactly 64 hex characters")
    try:
        key = bytes.fromhex(value)
    except ValueError as exc:
        raise ProtocolError("pairing key is not hexadecimal") from exc
    if len(key) != 32:
        raise ProtocolError("pairing key must contain exactly 32 bytes")
    return key


def digest(key: bytes, value: bytes) -> bytes:
    return hmac.new(key, value, hashlib.sha256).digest()


def proof(key: bytes, role: str, nonce: bytes) -> bytes:
    if role not in ("client", "server", "session") or len(nonce) != 32:
        raise ProtocolError("invalid handshake parameters")
    return digest(key, ("Reflection/" + role + "/v1").encode("ascii") + nonce)


def encode_frame(kind: int, payload: bytes, session_key: Optional[bytes] = None) -> bytes:
    body = bytes((VERSION, kind)) + payload
    if session_key is not None:
        body += digest(session_key, body)
    if not 2 <= len(body) <= MAX_BODY:
        raise ProtocolError("invalid frame length")
    return struct.pack(">I", len(body)) + body


def decode_body(body: bytes, session_key: Optional[bytes] = None):
    if not 2 <= len(body) <= MAX_BODY or body[0] != VERSION:
        raise ProtocolError("invalid frame header")
    if session_key is not None:
        if len(body) < 34:
            raise ProtocolError("missing authentication tag")
        if not hmac.compare_digest(digest(session_key, body[:-32]), body[-32:]):
            raise ProtocolError("invalid authentication tag")
        body = body[:-32]
    return body[1], body[2:]


def receive_exact(stream: socket.socket, size: int, deadline: float) -> bytes:
    chunks = bytearray()
    previous_timeout = stream.gettimeout()
    try:
        while len(chunks) < size:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError("frame deadline exceeded")
            stream.settimeout(remaining)
            try:
                chunk = stream.recv(size - len(chunks))
            except socket.timeout as exc:
                raise TimeoutError("frame deadline exceeded") from exc
            if not chunk:
                raise EOFError("peer closed the connection")
            chunks.extend(chunk)
        return bytes(chunks)
    finally:
        stream.settimeout(previous_timeout)


def receive_frame(stream: socket.socket, session_key: Optional[bytes] = None,
                  timeout: float = 2.0):
    deadline = time.monotonic() + timeout
    size = struct.unpack(">I", receive_exact(stream, 4, deadline))[0]
    if not 2 <= size <= MAX_BODY:
        raise ProtocolError("invalid frame length")
    return decode_body(receive_exact(stream, size, deadline), session_key)


@dataclasses.dataclass(frozen=True)
class PenSample:
    sequence: int
    timestamp_ns: int
    x: float
    y: float
    pressure: float
    tilt_x: float = 0.0
    tilt_y: float = 0.0
    distance: float = 0.0
    buttons: int = 0
    phase: int = 0
    tool: int = 1

    def validate(self):
        if not 1 <= self.sequence < 2 ** 64:
            raise ProtocolError("sequence must be a positive u64")
        if not 0 <= self.timestamp_ns < 2 ** 64:
            raise ProtocolError("timestamp must be a u64")
        for value in (self.x, self.y, self.pressure, self.distance):
            if not math.isfinite(value) or not 0 <= value <= 1:
                raise ProtocolError("normalized sample field out of range")
        for value in (self.tilt_x, self.tilt_y):
            if not math.isfinite(value) or not -90 <= value <= 90:
                raise ProtocolError("tilt out of range")
        if self.buttons not in (0, 1, 2, 3):
            raise ProtocolError("unknown buttons")
        if self.phase not in range(6) or self.tool not in (1, 2):
            raise ProtocolError("unknown pen phase or tool")

    def encode(self) -> bytes:
        self.validate()
        return SAMPLE.pack(
            self.sequence, self.timestamp_ns, self.x, self.y, self.pressure,
            self.tilt_x, self.tilt_y, self.distance, self.buttons,
            self.phase, self.tool, 0,
        )

    @classmethod
    def decode(cls, payload: bytes):
        if len(payload) != SAMPLE.size:
            raise ProtocolError("pen payload must contain exactly 48 bytes")
        *fields, reserved = SAMPLE.unpack(payload)
        if reserved != 0:
            raise ProtocolError("reserved field must be zero")
        sample = cls(*fields)
        sample.validate()
        return sample


class Client:
    def __init__(self, host: str, port: int, key: bytes, timeout: float = 5.0):
        self._last_ping = 0
        self.stream = socket.create_connection((host, port), timeout=timeout)
        self.stream.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        try:
            kind, nonce = receive_frame(self.stream, timeout=timeout)
            if kind != 1 or len(nonce) != 32:
                raise ProtocolError("expected server challenge")
            self.stream.sendall(encode_frame(2, proof(key, "client", nonce)))
            kind, server_proof = receive_frame(self.stream, timeout=timeout)
            if kind != 3 or not hmac.compare_digest(
                    server_proof, proof(key, "server", nonce)):
                raise ProtocolError("server authentication failed")
            self.session_key = proof(key, "session", nonce)
        except BaseException:
            self.stream.close()
            raise

    def send_sample(self, sample: PenSample):
        self.stream.sendall(encode_frame(0x10, sample.encode(), self.session_key))

    def ping(self) -> float:
        started = time.monotonic_ns()
        # A monotonic clock may return the same tick for two rapid calls.
        # Heartbeat values are replay-protected, so keep their sequence strict.
        self._last_ping = max(started, self._last_ping + 1)
        payload = struct.pack(">Q", self._last_ping)
        self.stream.sendall(encode_frame(0x11, payload, self.session_key))
        kind, response = receive_frame(self.stream, self.session_key)
        if kind != 0x12 or response != payload:
            raise ProtocolError("unexpected pong")
        return (time.monotonic_ns() - started) / 1_000_000

    def close(self):
        self.stream.close()

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()


def golden_vectors():
    key, nonce = bytes(range(32)), bytes(range(32, 64))
    session_key = proof(key, "session", nonce)
    sample = PenSample(1, 1234567890, 0.25, 0.75, 0.5, -30, 45, 0, 1, 1, 1)
    return {
        "description": "Public deterministic fixtures; never use this pairing key.",
        "key": key.hex(),
        "nonce": nonce.hex(),
        "clientProof": proof(key, "client", nonce).hex(),
        "serverProof": proof(key, "server", nonce).hex(),
        "sessionKey": session_key.hex(),
        "challengeFrame": encode_frame(1, nonce).hex(),
        "clientAuthFrame": encode_frame(2, proof(key, "client", nonce)).hex(),
        "serverAuthFrame": encode_frame(3, proof(key, "server", nonce)).hex(),
        "sample": dataclasses.asdict(sample),
        "samplePayload": sample.encode().hex(),
        "sampleFrame": encode_frame(0x10, sample.encode(), session_key).hex(),
        "pingFrame": encode_frame(0x11, struct.pack(">Q", 1234567890), session_key).hex(),
        "pongFrame": encode_frame(0x12, struct.pack(">Q", 1234567890), session_key).hex(),
    }

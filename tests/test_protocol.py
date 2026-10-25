import dataclasses
import json
import math
import pathlib
import socket
import struct
import sys
import threading
import time
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / "tools"))
import reflection_protocol as wire


class ProtocolTests(unittest.TestCase):
    def setUp(self):
        self.key = bytes(range(32))
        self.sample = wire.PenSample(1, 100, 0.2, 0.3, 0.5, phase=1)

    def test_golden_vectors_match_reference(self):
        path = pathlib.Path(__file__).resolve().parents[1] / "protocol" / "vectors.json"
        self.assertEqual(json.loads(path.read_text()), wire.golden_vectors())

    def test_sample_roundtrip_and_exact_wire_size(self):
        decoded = wire.PenSample.decode(self.sample.encode())
        self.assertAlmostEqual(decoded.x, self.sample.x)
        self.assertEqual(decoded.phase, self.sample.phase)
        self.assertEqual(len(self.sample.encode()), 48)
        self.assertEqual(len(wire.encode_frame(0x10, self.sample.encode(), self.key)), 86)

    def test_signed_frame_detects_payload_type_and_tag_tampering(self):
        original = wire.encode_frame(0x10, self.sample.encode(), self.key)[4:]
        for offset in (1, 10, len(original) - 1):
            changed = bytearray(original)
            changed[offset] ^= 1
            with self.assertRaises(wire.ProtocolError):
                wire.decode_body(bytes(changed), self.key)
        self.assertEqual(wire.decode_body(original, self.key)[0], 0x10)

    def test_rejects_invalid_fields_and_reserved_bits(self):
        for field, value in (
            ("x", math.nan), ("y", math.inf), ("pressure", -0.1),
            ("tilt_x", 91), ("distance", 2), ("buttons", 4),
            ("phase", 6), ("tool", 0), ("sequence", 0),
        ):
            with self.subTest(field=field), self.assertRaises(wire.ProtocolError):
                dataclasses.replace(self.sample, **{field: value}).encode()
        payload = self.sample.encode()[:-2] + b"\x00\x01"
        with self.assertRaises(wire.ProtocolError):
            wire.PenSample.decode(payload)

    def test_rejects_oversized_frame_before_reading_payload(self):
        writer, reader = socket.socketpair()
        try:
            writer.sendall(struct.pack(">I", 4097))
            with self.assertRaises(wire.ProtocolError):
                wire.receive_frame(reader)
        finally:
            writer.close()
            reader.close()

    def test_fragmented_and_coalesced_frames(self):
        writer, reader = socket.socketpair()
        frame = wire.encode_frame(1, bytes(range(32)))
        failure = []

        def send():
            try:
                for byte in frame:
                    writer.sendall(bytes((byte,)))
                writer.sendall(frame + frame)
            except Exception as exc:
                failure.append(exc)
            finally:
                writer.close()

        worker = threading.Thread(target=send)
        worker.start()
        try:
            for _ in range(3):
                self.assertEqual(wire.receive_frame(reader), (1, bytes(range(32))))
        finally:
            reader.close()
            worker.join()
        self.assertFalse(failure)

    def test_timeout_is_absolute_not_extended_by_partial_reads(self):
        writer, reader = socket.socketpair()
        try:
            writer.sendall(struct.pack(">I", 34) + b"\x01")
            started = time.monotonic()
            with self.assertRaises(TimeoutError):
                wire.receive_frame(reader, timeout=0.05)
            self.assertLess(time.monotonic() - started, 0.5)
        finally:
            writer.close()
            reader.close()

    def test_key_validation_rejects_embedded_whitespace(self):
        self.assertEqual(wire.decode_key("00" * 32 + "\n"), bytes(32))
        for key in ("", "z" * 64, "0 " * 32, "00" * 31):
            with self.subTest(key=key), self.assertRaises(wire.ProtocolError):
                wire.decode_key(key)


if __name__ == "__main__":
    unittest.main()

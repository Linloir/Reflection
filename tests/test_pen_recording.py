import pathlib
import struct
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / "tools"))
from record_pen import PenObserver, SampleBudget
from reflection_protocol import PenSample, encode_frame, proof


class RecordingTests(unittest.TestCase):
    def test_shared_budget_limits_all_sessions(self):
        key, nonce = bytes(32), bytes(range(32))
        budget = SampleBudget(2)
        observers = [PenObserver(key, 2, budget) for _ in range(3)]
        packet = encode_frame(0x10, PenSample(1, 100, .25, .75, .5, phase=1).encode(), proof(key, "session", nonce))
        for observer in observers:
            observer.feed("host", encode_frame(1, nonce), 1)
            observer.feed("tablet", packet, 2)
        self.assertEqual(sum(len(o.samples) for o in observers), 2)
        self.assertTrue(observers[-1].truncated)

    def test_fragmented_capture_preserves_original_time_and_position(self):
        key, nonce = bytes(range(32)), bytes(range(32, 64))
        observer = PenObserver(key, 1)
        hello = encode_frame(1, nonce)
        for part in (hello[:2], hello[2:8], hello[8:]):
            observer.feed("host", part, 10)
        session = proof(key, "session", nonce)
        sample = PenSample(1, 100, .25, .75, .5, phase=1)
        packet = encode_frame(0x10, sample.encode(), session)
        observer.feed("tablet", packet[:9], 20)
        observer.feed("tablet", packet[9:], 30)
        self.assertEqual(observer.samples[0]["timestamp_ns"], 100)
        self.assertEqual(observer.samples[0]["x"], .25)
        self.assertEqual(observer.samples[0]["arrival_ns"], 30)
        observer.feed("tablet", packet, 40)
        self.assertTrue(observer.truncated)
        self.assertEqual(len(observer.samples), 1)
        self.assertNotIn("key", observer.samples[0])

    def test_rejects_unbounded_or_unauthenticated_capture(self):
        observer = PenObserver(bytes(32))
        with self.assertRaises(ValueError):
            observer.feed("tablet", struct.pack(">I", 1000000), 1)
        observer = PenObserver(bytes(32))
        observer.feed("host", encode_frame(1, bytes(32)), 1)
        with self.assertRaises(ValueError):
            observer.feed("tablet", encode_frame(0x10, PenSample(1, 1, .1, .2, 0).encode(), bytes(32)), 2)

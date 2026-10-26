#!/usr/bin/env python3
"""Bounded local diagnostic relay. Records pen samples, never pairing/session keys.

Forward a tablet's TCP connection through this loopback listener to a running host.
Pen frames are forwarded unchanged before observation. This adds a relay hop: arrival
timing is diagnostic, not a measurement of normal end-to-end input latency.
"""

import argparse
import dataclasses
import json
from pathlib import Path
import socket
import struct
import threading
import time

from reflection_cli import native_pairing
from reflection_protocol import MAX_BODY, PenSample, decode_body, decode_key, proof


class SampleBudget:
    def __init__(self, limit):
        self.remaining = limit
        self.lock = threading.Lock()

    def take(self):
        with self.lock:
            if self.remaining <= 0:
                return False
            self.remaining -= 1
            return True


class PenObserver:
    def __init__(self, key, max_samples=60000, budget=None):
        self.key = key
        self.session_key = None
        self.buffers = {"tablet": bytearray(), "host": bytearray()}
        self.samples = []
        self.max_samples = max_samples
        self.budget = budget or SampleBudget(max_samples)
        self.errors = []
        self.transport_events = []
        self.truncated = False
        self.lock = threading.Lock()

    def feed(self, direction, chunk, arrival_ns):
        with self.lock:
            buffer = self.buffers[direction]
            buffer.extend(chunk)
            while len(buffer) >= 4:
                length = struct.unpack(">I", buffer[:4])[0]
                if not 2 <= length <= MAX_BODY:
                    raise ValueError("invalid observed frame size")
                if len(buffer) < length + 4:
                    return
                body = bytes(buffer[4:length + 4])
                del buffer[:length + 4]
                if direction == "host" and body[1] == 1:
                    kind, nonce = decode_body(body)
                    self.session_key = proof(self.key, "session", nonce)
                elif body[1] in (2, 3):
                    # Authentication proofs are forwarded but never recorded.
                    continue
                elif direction == "tablet" and body[1] == 0x10:
                    if self.session_key is None:
                        raise ValueError("sample before observed handshake")
                    _, payload = decode_body(body, self.session_key)
                    sample = PenSample.decode(payload)
                    if len(self.samples) < self.max_samples and self.budget.take():
                        self.samples.append(dict(dataclasses.asdict(sample), arrival_ns=arrival_ns))
                    else:
                        self.truncated = True


def run(args):
    pairing = native_pairing(args)
    key = decode_key(pairing["token"])
    port = args.host_port or int(pairing["port"])
    if port == args.listen_port:
        raise ValueError("listener and host ports must differ")
    output = Path(args.output).resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    progress = output.with_suffix(".status.json")
    started = time.monotonic()
    stop = threading.Event()
    observers = []
    budget = SampleBudget(args.max_samples)
    active_sockets = []
    threads = []
    listener = socket.socket()
    listener.bind(("127.0.0.1", args.listen_port))
    listener.listen(1)
    listener.settimeout(.2)

    def relay(source, destination, direction, observer):
        observing = True
        try:
            while not stop.is_set():
                try:
                    chunk = source.recv(65536)
                except socket.timeout:
                    continue
                if not chunk:
                    observer.transport_events.append({"direction": direction, "event": "eof", "at_ns": time.monotonic_ns()})
                    break
                arrival = time.monotonic_ns()
                if direction == "host" and observer.session_key is None:
                    # Observe the challenge before delivery so a fast client cannot
                    # race key derivation. No secret handshake data is persisted.
                    if observing:
                        try:
                            observer.feed(direction, chunk, arrival)
                        except Exception as error:
                            observer.errors.append(str(error))
                            observing = False
                    destination.sendall(chunk)
                    continue
                destination.sendall(chunk)
                if observing:
                    try:
                        observer.feed(direction, chunk, arrival)
                    except Exception as error:
                        # Recording must not modify the host's protocol decisions.
                        observer.errors.append(str(error))
                        observing = False
        except OSError as error:
            observer.transport_events.append({"direction": direction, "event": type(error).__name__,
                "errno": error.errno, "at_ns": time.monotonic_ns(), "stopping": stop.is_set()})
        finally:
            try:
                destination.shutdown(socket.SHUT_WR)
            except OSError:
                pass

    def report(recording):
        progress.write_text(json.dumps({"recording": recording,
            "elapsed_seconds": round(time.monotonic() - started, 2),
            "sessions": len(observers), "samples": sum(len(o.samples) for o in observers)}), encoding="utf-8")

    report(True)
    print(json.dumps({"listening": args.listen_port, "seconds": args.seconds, "output": str(output)}), flush=True)
    try:
        while time.monotonic() - started < args.seconds:
            report(True)
            try:
                tablet, _ = listener.accept()
            except socket.timeout:
                continue
            if len(observers) >= 16:
                tablet.close()
                continue
            try:
                host = socket.create_connection(("127.0.0.1", port), timeout=3)
            except OSError:
                tablet.close()
                continue
            tablet.settimeout(.2)
            host.settimeout(.2)
            tablet.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            host.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            active_sockets.extend((tablet, host))
            observer = PenObserver(key, args.max_samples, budget)
            observers.append(observer)
            for source, destination, direction in ((host, tablet, "host"), (tablet, host, "tablet")):
                thread = threading.Thread(target=relay, args=(source, destination, direction, observer), daemon=True)
                threads.append(thread)
                thread.start()
    finally:
        stop.set()
        listener.close()
        for stream in active_sockets:
            stream.close()
        for thread in threads:
            thread.join(timeout=1)
        # Only normalized pen data, timing and bounded diagnostic errors leave memory.
        output.write_text(json.dumps({"format": "reflection-raw-pen-v1", "relay_added": True,
            "elapsed_seconds": time.monotonic() - started,
            "sessions": [{"samples": observer.samples, "truncated": observer.truncated,
                          "errors": observer.errors, "transport_events": observer.transport_events}
                         for observer in observers]}, separators=(",", ":")), encoding="utf-8")
        report(False)
        print(json.dumps({"saved": str(output), "samples": sum(len(o.samples) for o in observers)}), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config")
    parser.add_argument("--listen-port", type=int, default=24820)
    parser.add_argument("--host-port", type=int)
    parser.add_argument("--seconds", type=float, default=90)
    parser.add_argument("--max-samples", type=int, default=60000)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    if not 1 <= args.seconds <= 300 or not 1 <= args.max_samples <= 100000 or not 1 <= args.listen_port <= 65535:
        parser.error("duration must be 1-300 seconds; sample limit 1-100000; port 1-65535")
    if args.host_port == args.listen_port:
        parser.error("listener and host ports must differ")
    run(args)

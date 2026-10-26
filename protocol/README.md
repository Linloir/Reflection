# Reflection Wire Protocol v1

This specification is independent of Android, Apple, Windows, and any UI
framework. It describes the ordered byte stream carried by TCP or Bluetooth
RFCOMM. Implementations must preserve pen transitions and must release a
pressed pointer when a session ends.

The default TCP port is `24819`. The Bluetooth Classic service UUID is
`7b011e5a-8b31-4c40-8d7a-3e6c6dcbb758`. USB uses the same TCP transport through
ADB reverse forwarding; USB HID gadget mode is not required.

## Framing

All integers and IEEE 754 binary32 values use big endian byte order.

| Field | Size | Meaning |
| --- | ---: | --- |
| length | 4 | Unsigned size of the following body, excluding this field |
| version | 1 | Exactly `1` |
| type | 1 | Message type |
| payload | variable | Type-specific content |

The body length must be between `2` and `4096` inclusive. Read implementations
must handle split frames and multiple frames in a single read. Reject an
oversized frame before allocating its payload. An invalid version, type,
length, authentication tag, or pen sample terminates the connection.

## Authentication and integrity

Each host configuration has a cryptographically random 32-byte pairing key,
represented to users as 64 hexadecimal characters. It is never sent over the
transport and must not be included in ordinary logs or status output.

1. Server sends type `0x01` with a fresh 32-byte random nonce.
2. Client sends type `0x02` containing
   `HMAC-SHA256(key, UTF8("Reflection/client/v1") || nonce)`.
3. Server verifies this proof using constant-time comparison and sends type
   `0x03` containing
   `HMAC-SHA256(key, UTF8("Reflection/server/v1") || nonce)`.
4. Client verifies the server proof before sending samples.
5. Both derive
   `sessionKey = HMAC-SHA256(key, UTF8("Reflection/session/v1") || nonce)`.

The three handshake payloads are exactly 32 bytes. The handshake deadline is
five seconds. Close the connection on any failure; do not continue in an
unauthenticated mode.

Every subsequent message appends a 32-byte tag to its payload:

```text
tag = HMAC-SHA256(sessionKey, version || type || unsignedPayload)
```

The length field includes the tag, version, and type. The tag does not include
the length field. The receiver checks the tag before interpreting any sample,
changing state, or accepting a heartbeat.

This protocol provides authentication and integrity, **not encryption**.
Coordinates and timing remain visible to an observer. Prefer USB, a trusted
local network, or an encrypted VPN. Do not expose the listener to the public
Internet. Changing the pairing key revokes previous clients.

## Pen sample (`0x10`)

The unsigned payload is exactly 48 bytes. With its tag and framing, each
sample occupies 86 bytes. No JSON encoding, compression, or batching timer is
used on the pen path.

| Offset | Type | Field | Meaning |
| ---: | --- | --- | --- |
| 0 | u64 | sequence | Starts at 1; strictly increasing within this connection |
| 8 | u64 | timestampNs | Client monotonic timestamp in nanoseconds |
| 16 | f32 | x | Normalized tablet coordinate, 0..1 |
| 20 | f32 | y | Normalized tablet coordinate, 0..1 |
| 24 | f32 | pressure | Normalized pressure, 0..1 |
| 28 | f32 | tiltX | Signed degrees, -90..90 |
| 32 | f32 | tiltY | Signed degrees, -90..90 |
| 36 | f32 | distance | Normalized hover distance, 0..1; 0 if unavailable |
| 40 | u32 | buttons | Bit 0 barrel, bit 1 secondary barrel; other bits zero |
| 44 | u8 | phase | See table |
| 45 | u8 | tool | 1 pen, 2 eraser |
| 46 | u16 | reserved | Must be zero |

| Phase | Meaning |
| ---: | --- |
| 0 | Hover / enter proximity |
| 1 | Tip down |
| 2 | Contact move |
| 3 | Tip up |
| 4 | Cancel: release contact and all buttons |
| 5 | Leave proximity: release contact and all buttons |

Reject NaN/infinity and any out-of-range field. Timestamp values must be
nondecreasing (multiple Android samples may share a millisecond timestamp).
Sequence numbers must be strictly increasing. Sequence gaps are permitted;
duplicate and backwards samples are not. Unknown tools, phases, buttons, and
reserved bits are invalid.

The source coordinate space has its origin at the top left of the active
client drawing surface. Positive X points right, positive Y points down.
Client orientation is already applied when coordinates are captured. Server
rotation is an additional user mapping. A zero pressure up/cancel/leave event
must still reach the system backend. Tilt is independent of pressure.

Hosts must handle or reject invalid contact transitions without leaving a
pressed pointer behind. An unexpected tool change while in contact releases
the preceding tool before admitting another down.

## Heartbeats

Type `0x11` is a client ping; type `0x12` is the server pong. Both unsigned
payloads contain exactly one u64, the client's monotonic send timestamp. The
server echoes the bytes without modification. Client ping timestamps must be
strictly increasing. Pongs allow RTT measurement without synchronized clocks.
Servers reject client-originated pongs; a reflected server message must not
keep the client session alive.

The client sends a ping every 500 ms. After two seconds without a valid
authenticated client frame, the host releases all input and closes the
session. Replaying a ping must not extend the session. A heartbeat permits a
stationary tip to remain down while the connection is healthy.

One-way sample latency cannot be calculated by subtracting clocks on two
machines. RTT, local queue age, sample rate, and rendering latency must be
reported separately.

## Optional capabilities (`0x20`)

The unsigned payload is a UTF-8 JSON object, limited by the frame size above.
Receivers may ignore unknown fields and may ignore the entire authenticated
message's contents. At most one capabilities frame is allowed per session,
before the first sample; reject duplicates to prevent heartbeat replay. A
sender can report fields such as:

```json
{
  "platform": "android",
  "pressure": true,
  "tilt": false,
  "distance": true,
  "eraser": true
}
```

Capability declarations are informational. A declaration does not prove that
the physical pen, firmware, and target application expose or consume that
axis correctly.

### Host-side stabilization

The optional top-level numeric field `"hostStabilization":1` declares that the
client sends raw normalized coordinates, with no client-side position smoothing.
Supporting hosts may apply their configured coordinate filter and collect noise
calibration locally. Absent this declaration, the host disables its position
smoothing to avoid stacking it with older Android filtering. Input continuity
protection remains enabled for both kinds of client. This capability adds no
wire message types or sample fields, and older hosts can ignore it.

Calibration is controlled by the computer UI/CLI, not by unauthenticated network
commands. During calibration the host receives the original samples but releases
system contact and suppresses injection. After completion or cancellation a new
DOWN is required before contact input resumes. Changing the input device or
surface orientation during a stroke must cancel the old stroke; it must not
reinterpret its remaining coordinates using new geometry.

### Negotiated screen mapping control

The optional top-level numeric field `"mappingControl":1` opts a client into
the screen-control extension. A supporting host replies with an authenticated
`0x22` mapping snapshot. A client **must wait for that response** before sending
`0x21` controls. Hosts send no extension frames to clients that did not opt in;
older hosts ignore the capability and continue accepting ordinary pen input.
The existing v1 handshake, pen sample layout, and heartbeat layout are unchanged.

`0x21` unsigned payloads contain a positive, strictly increasing u64 request
sequence followed by a one-byte action: `0` query, `1` next display, `2` previous
display, `3` all displays, or `4` select display. Only action `4` appends a UTF-8
display ID of 1..128 bytes. Other actions have exactly nine payload bytes.
Invalid, unauthenticated, replayed, or unnegotiated requests terminate the session.

`0x22` unsigned payloads are bounded UTF-8 JSON objects:

```json
{
  "revision": 1,
  "mode": "display",
  "selected": "platform-display-id",
  "target": {"x": 0, "y": 0, "width": 1920, "height": 1080},
  "pending": false,
  "displays": [
    {"id": "platform-display-id", "name": "Display 1", "primary": true,
     "x": 0, "y": 0, "width": 1920, "height": 1080}
  ]
}
```

Modes are `all`, `display`, and `custom`. Each response revision strictly
increases within its connection. Display lists contain at most 16 entries;
the complete authenticated frame must still fit the 4096-byte body limit.
Rectangles use the host's desktop coordinate system and may have negative
origins. Windows reports physical pixels; macOS reports CoreGraphics desktop
coordinates. IDs are opaque and must be copied from the host, never inferred
from screen numbering. Unknown snapshot fields may be ignored.

Hosts send an initial snapshot, answer controls, and deliver changed state by
the next heartbeat. The target in a snapshot is the **active** target; `pending`
means a switch is waiting for the current stroke to end. The UP sample maps
to the old screen, and the next stroke uses the new screen. Disconnect/cancel
also releases the stroke. Live switching does not rewrite startup settings.
Mapping snapshots do not replace pongs or extend the client's pong deadline.

## Backpressure and reconnection

Use `TCP_NODELAY` for TCP. Keep input buffers bounded. An overwhelmed sender
must close the session and clear queued samples rather than silently discard
down/up/cancel events. The Android reference queue holds at most 256 samples.
On reconnection the sequence restarts, the host starts with released input,
and the client waits for a new down before forwarding contact moves.

Only one active tablet may own a host pointer. A disconnected session must
not leave contact or buttons pressed. Bluetooth disconnection, app
backgrounding, transport timeout, authentication failure, and host shutdown
all use this same release path.

## Reference and test vectors

`tools/reflection_protocol.py` is an executable standard-library reference
implementation. `vectors.json` contains deterministic frames for checking
the native implementations. Test keys in that file are public fixtures and
must never be used for a real installation.

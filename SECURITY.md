# Security

Reflection accepts input that can control a host pointer. Pair only devices
you trust and keep the host listener on a private network.

## Pairing and transport

- Each installation generates a random 256-bit pairing key.
- Client and server authenticate each other using a fresh challenge.
- Every pen frame and heartbeat has an HMAC-SHA256 integrity tag.
- Sample sequences and heartbeat timestamps reject in-session replay.
- New host configurations listen on all local IPv4 interfaces for discovery.
  Existing explicit loopback configurations stay local until changed.
- Protocol v1 does **not encrypt** coordinates or timing. Use USB, a trusted
  local network, or an encrypted VPN; do not publish the port to the Internet.
- Only one active client controls the host at a time.
- Disconnect, timeout, cancellation, and malformed input release contact.

The Bluetooth transport still uses application-level authentication. Link
pairing alone is not a replacement for the Reflection pairing key.
First contact can use the separate Bluetooth pairing service after OS bonding;
the bootstrap service requires an encrypted Bluetooth link and the same
two-screen confirmation as LAN. Discovery requests cannot evict the active
input session.

## Automatic discovery and first contact

Discovery advertises public device identities, never reusable credentials.
The host verifies a tablet's six-digit identity code before approval, encrypts
its device-specific credential with RSA-OAEP-SHA256, and displays a separate
confirmation code derived from that credential and the tablet's nonce. The
tablet independently computes the code and requires first-time approval.
Compare the codes on both screens; a device name or IP address is not proof of
identity. Confirmation codes are not sent in plaintext discovery packets.
Approved encrypted offers are cached for the announced device identity and
nonce, allowing dropped UDP replies or Bluetooth polls to retry. They cannot
be decrypted by another tablet identity.

Subsequent replies are authenticated with the saved credential and bound to
fresh query nonces. Each approved tablet receives a separately derived
credential. Selecting a new tablet closes the old host session and changes the
credential admitted for the selected session. USB bootstrap is restricted to
devices already authorized by Android's debugging trust prompt.

Tablet identities and credentials reside in application-private storage.
Host trust registries contain device identities, not copies of the installation
key. Removing an app's data removes its saved trust and identity. Host key
rotation invalidates all derived credentials; per-device revocation UI is not
currently provided.

## Local configuration

Host configuration contains the pairing key. macOS stores it with private
file permissions; Windows configuration is stored under the current user's
profile. Normal CLI status output redacts secrets. Commands explicitly
revealing a key should not be included in shared logs.
When using a custom Windows `--config` path, choose a directory readable and
writable only by your account; the file inherits the directory's ACL.

Android stores configuration in application-private preferences and disables
application backup. Its ADB control receiver requires the system `DUMP`
permission. This assumes that the computer has already been authorized for
USB debugging; it does not create an unauthenticated network control API.

Root CLI pairing avoids displaying the key or asking users to paste it into
shell history. As with other local administration tools, privileged local
processes can inspect process arguments or private configuration.

## Reporting

Report security vulnerabilities privately to `3145078758@qq.com`. Include the
affected platform/version and a minimal reproduction with test credentials.
Do not publish real pairing keys, handwritten content, or private network
captures in issues.

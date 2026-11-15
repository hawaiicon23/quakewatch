# Message relays

Use the latest android-app/dist/bt-probe.apk on every participating phone.
On the sender, scroll to Message relay and enable relay mode. Enter the
recipient's 8-digit device ID, or leave Destination blank to broadcast to all.
Send a message of 1–8 UTF-8 bytes using Broadcast test. Enable relay mode on
intermediate phones too, and start Bluetooth/keep the app open on all devices.
The recipient can receive and acknowledge a relayed message with its own relay
checkbox off; it must run the updated app and have automatic replies enabled.

The sender and relay nodes preserve the original sender and message ID. Relay
packets have a maximum of three intermediate forwards. The destination's ACK
uses the same bounded forwarding mechanism to return to the origin. An
intermediate node does not acknowledge a targeted message as if delivered.
Duplicate display is suppressed; forwarding retries are limited to three per
packet and no more than one per five seconds. Lower-hop copies cannot refresh
that retry budget. Relay caches and transmit queues are bounded. Small random
forwarding delays reduce simultaneous rebroadcast collisions. Queue saturation
can drop traffic; this is best-effort public BLE test messaging, without
cryptographic authentication or guaranteed delivery. It does not execute text.

Continuous test beacons are not relayed. Signal measurements from forwarded
messages describe the immediate radio hop, not the original sender's range.
Normal messages and continuous mode remain usable. Relayed messages use 500 ms
radio slots, so intermediate nodes can pass messages/ACKs without waiting for
three-second slots.

Linux GUI has matching relay and destination controls. Terminal example:
  bash linux-app/run.sh --relay --to 37D2B5AC --send HELLO --duration 25
For a relay-only node, omit --send. Restart the companion from updated source.

Wire extension: low nibble of byte 3 is kind 4 (relay text) or 5 (relay ACK).
High nibble is remaining forwards (0..3), decremented at each intermediary.
Relay text body is a 4-byte big-endian destination (zero=all), then up to 8 UTF-8
bytes. Relay ACK body is the 4-byte original sender ID; the header sender is the
acknowledging destination. Hop count is excluded from duplicate keys. Existing
TEST/ACK/BEACON packets are unchanged; previous app versions ignore relays.

Validation: 22 tests pass. Tests use the production Linux receive/forward path
to simulate A -> B -> C delivery and C -> B -> A acknowledgment, plus Java/Python
wire interoperability, hop exhaustion, duplicate delivery/forward suppression,
retry bounds and disabled relays. The signed Android APK was installed on the
Motorola; relay controls were visually checked and live ordinary RX/ACK traffic
still worked. A physical three-device relay test has not been performed: only
the Motorola was connected for installation.

APK SHA256: 33134bba64c48c3c12ae6184a6e80f78ab7df0c02a97f5851f23dd7714de2768
Backup before changes: /tmp/btprobe-before-relay.tar.gz on this build host.

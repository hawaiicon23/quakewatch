BT Probe: continuous reception and delivery reliability

Changes deployed to /srv/projects/bluetooth-test on the SSH build host:
- Android creates one legacy AdvertisingSet before starting its scan, then changes the data and enables/disables that same set per transmission. Its scan remains registered for the session. This replaces the previous error-12 workaround that restarted scanning per transmission and triggered Android scan throttling.
- Replies are tracked per message AND sender. The first peer's acknowledgment no longer removes the pending message. Other peers can reply for 20 seconds; duplicate replies from the same peer count once.
- Both implementations display each incoming message once but can send another acknowledgment after two seconds if the sender retransmits because an earlier acknowledgment was lost.
- Up to three broadcasts, five seconds apart, when no peer has replied. Retries expire and are cancelled on stop/restart or acknowledgment.
- Android requests high TX power; this phone reports controller TX power of 0 dBm. Advertising lasts three seconds. Linux requests a 50 ms interval and high TX power only when the adapter publishes its supported TX-power capability.
- All packets retain the v1 wire format and remain compatible with the previous peer.

Validation:
- 15 tests pass, including multi-peer ACKs, duplicate/lost ACKs, retry cancellation, message expiration and Java/Python packet interoperability.
- Android APK v2/v3 signature verified.
- Initial on-phone test using the new AdvertisingSet path completed a handshake at -75 dBm RSSI.
- Final installed build repeat test: 7 logical broadcasts and 9 distinct per-message peer replies; still Listening nearby. Android retained the same scan starting 11:02:08 throughout the test (31 starts / 30 stops cumulative). At 54.9 seconds it had delivered 15 results. Recent Bluetooth logs showed no new random-address or scan-throttling failures.

Range has NOT been physically measured. Greater reception probability and requested power do not establish a guaranteed distance; both radios, antenna orientation, obstructions and interference matter. This remains a foreground app and stops when it is backgrounded.

The nearby Linux companion must be restarted from the updated linux-app source to use the Linux-side multi-peer/retry changes. Updating the build host does not restart a Linux app running on another computer.

Final APK SHA256: 2ce9cd617b367873ec49ee300afb0dcd394f4df80996ca5aa9dbeede1d216c35.
Backup before changes: /tmp/btprobe-before-range-fix.tar.gz on the build host.

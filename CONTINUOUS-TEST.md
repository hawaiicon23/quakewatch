# Continuous Bluetooth test

Install android-app/dist/bt-probe.apk on each phone. Open BT Probe, tap
Continuous test…, choose a short name, and tap Start continuous test. Keep the
app open. Repeat on the other phones. The menu also stops continuous mode.

Every app installation stores a persistent random 32-bit device ID. Names use
1–12 UTF-8 bytes and are saved. Peers display the name and ID, latest RSSI in
dBm, unique beacon sample count and seconds since last reception. RSSI is a
received signal measurement, not a distance measurement.

Android updates a retained legacy advertising set roughly every 500 ms, while
one scan remains active. The controller repeats each payload at its advertising
interval. Manual messages and ACKs can occupy a 500 ms slot during continuous
mode; test beacons continue afterward. Stop Bluetooth/backgrounding cancels the
test. The selected high transmit-power profile remains hardware-limited.

New wire kind 3 is an unacknowledged beacon: the header sender is the persistent
device ID, message ID is a changing sample sequence, and body is the device name.
Normal message/ACK formats are unchanged. Previous app versions ignore beacons;
update each device to see continuous-test samples without ACK storms.

Linux desktop: start linux-app/run.sh and use the Continuous test checkbox.
Linux terminal: bash linux-app/run.sh --continuous --name LAPTOP --json
The ID and name are stored in ~/.config/bt-probe/identity.json. Linux schedules
500 ms ticks; actual timing depends on BlueZ/controller scheduling. It drops
busy ticks instead of accumulating an unlimited queue.

Validation: all 18 tests pass, including Java/Python beacon compatibility,
per-peer RSSI/sample tracking, duplicate suppression without beacon ACKs, bounded
peer state and bounded continuous scheduling. The signed APK was installed on
the Motorola. Its visible count advanced 7 to 29 in roughly 11 seconds with one
continuous scan and no new Bluetooth errors. No second updated device was
attached for a two-phone beacon/RSSI test.
APK SHA256: e973c5d51b67bfb22a107ef26cf7bdbaa1075a147f1bd2dd902def1245f55260.

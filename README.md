# BT Probe

A small Android app and Linux companion for testing two-way Bluetooth Low Energy broadcasts. No internet, Bluetooth pairing, GATT connection, root, or cloud service is used for communication. The two devices must be physically nearby. The Arch machine is only the build host.

## Project layout

```text
android-app/           Android source, resources and build script
  build/               Intermediate Android build files
  dist/bt-probe.apk    Installable Android APK
  .keys/test.jks       Reused local test signing key
pixel-5/               Pixel-specific install script and test instructions
pixel-8-pro/           Pixel 8 Pro modem probe and recorded results
linux-app/             Linux source, desktop/terminal launcher and logs
  logs/                Laptop receiver output and PID files
tests/                 Shared packet and Java/Python interoperability checks
```

See [Android app](android-app/README.md), [Pixel 5](pixel-5/README.md), and [Linux app](linux-app/README.md) for their commands. The root `build-android.sh` and `run-linux.sh` remain compatible launchers.

Separate modem-interface checks are recorded under [Pixel 5](pixel-5/modem-probe/README.md) and [Pixel 8 Pro](pixel-8-pro/README.md). These are distinct from the Bluetooth app and do not demonstrate direct cellular communication.

## Mesh merge (0.5-mesh)

Background radio, fast live chat (BC/2), earthquake alerts with signed official alerts, auto-start and the laptop tools are described in [MESH.md](MESH.md). Laptop chat peer: `python3 linux-app/chat_peer.py listen`. Signed alerts: `python3 authority/quake_authority.py send EQW`.

## Pixel 5 test

1. Install `android-app/dist/bt-probe.apk` on the Pixel, or run `bash pixel-5/install.sh` from the laptop with the Pixel connected. Android may ask you to allow APK installation from the app opening the file.
2. Turn on Bluetooth and open **BT Probe**. Tap **Start Bluetooth** and allow **Nearby devices**. Keep the app in the foreground.
3. On the nearby laptop, run `bash run-linux.sh` for desktop controls, or `bash run-linux.sh --json` for a receiver in the terminal.
4. Start Bluetooth in the Linux window, then tap **Broadcast test** on the phone.
5. The Linux log should show **RX 'TEST'** and **TX acknowledgment**. The phone should show **Reply from …** and the round-trip time.
6. Send `LAPTOP` from the Linux window to test the reverse direction. The phone should show an RX event and automatically reply.

For a single terminal transmission with a receiver already running: `bash linux-app/run.sh --send LAPTOP --no-auto-ack --duration 20`.

The laptop receiver log is now `linux-app/logs/laptop-peer.jsonl`.

The app keeps the screen awake while listening. Switching away stops the Android test, including scanning and advertising. Return and tap Start Bluetooth to resume. Android 8–11 also require Location permission and enabled Location services for scanning.

## Linux dependencies

Python 3, BlueZ running, a powered Bluetooth adapter supporting LE scanning and advertising, dbus-python, PyGObject/GLib and (for the GUI) tkinter.

On Arch, packages are `bluez bluez-utils python-dbus python-gobject tk`. On Fedora, use `bluez python3-dbus python3-gobject python3-tkinter`. The tested laptop and Arch server already have the dependencies. Normal local-session BlueZ permissions are sufficient; no changes to adapter pairing or discoverability settings are needed.

`--adapter hci0` selects a radio. `--no-auto-ack` disables replies. `--duration N` stops and cleans up after N seconds. `--json` emits structured events.

## Build on Arch

Source on the server: `/srv/projects/bluetooth-test`, shared by `blx` and `guest` through the `btproject` group. The previous `/home/blx/Projects/bluetooth-test` location links to this shared directory.

Run `bash android-app/build.sh` (or the compatible `bash build-android.sh` launcher). It uses AOSP SDK 35, JDK 21, aapt2, D8, zipalign and apksigner, and writes `android-app/dist/bt-probe.apk`. It prefers the shared toolchain at `/srv/projects/.toolchains/android`, falling back to `/home/blx/Projects/Android/source` when the shared toolchain is absent. Set `AOSP_ROOT` to override this selection. There are no Gradle or third-party Android runtime dependencies.

The test signing key is kept in `android-app/.keys/test.jks` and reused for updates. This is a locally signed test APK, not a Play Store release. Android 8.0/API 26 and newer are supported; the app targets API 35. There is no INTERNET permission.

Run checks with the build-host JDK on PATH:

```bash
export PATH=/srv/projects/.toolchains/android/prebuilts/jdk/jdk21/linux-x86/bin:$PATH
python -m unittest discover -s tests -v
```

Tests exercise packet validation, UTF-8 and legacy advertisement size limits, duplicates, acknowledgment targeting, bounded memory, and Java/Python interoperability over 240 vectors. Physical reception on the Pixel is a separate hardware test.

## Wire format

Manufacturer-specific data using company ID `0xFFFF` (reserved for internal testing), with a maximum 24-byte application packet:

| Bytes | Meaning |
| --- | --- |
| 0–1 | ASCII `BT` |
| 2 | Protocol version `1` |
| 3 | Kind: `1` test text, `2` acknowledgment |
| 4–7 | Random sender ID, unsigned 32-bit big endian |
| 8–11 | Random message ID, unsigned 32-bit big endian |
| 12–23 | Test: up to 12 UTF-8 bytes. ACK: original sender ID, exactly 4 bytes. |

Including manufacturer AD framing and Bluetooth flags, the maximum packet fits a 31-byte legacy advertisement. Nonconnectable broadcasts run for about 1.8 seconds; Linux requests a 100 ms advertising interval. Each test is displayed once per sender/message ID in a 60-second cache (maximum 256 entries). Replies match the recipient and a pending message ID; the reply timeout is 10 seconds. Replies have priority over queued tests.

These broadcasts contain public, unauthenticated test data. A reply demonstrates reception by a peer but does not authenticate its identity. This demo only displays text and never executes received text as shell or system commands. Production commands need authentication and replay protection. Delivery is best effort, and displayed timing includes OS scan/advertising scheduling.

This implementation includes Android and Linux only; it does not claim universal browser, iOS or desktop support.


## Message relays

See [RELAY.md](RELAY.md) for optional three-relay forwarding, destination IDs, return acknowledgments, packet limits and validation. Update every participating phone and restart Linux companions from the updated source.


## Radio power settings

See [POWER-SETTINGS.md](POWER-SETTINGS.md) for the new settings page, requested-versus-reported power, root-gated +21 experiment and the Motorola controller results.


## Nearby chat and images

Open **Open chat** on each phone for named messages up to 4096 UTF-8 bytes and 70-pixel JPEG images at quality 70. Transfers use numbered BLE fragments, checksum validation, acknowledgments and retries. History is stored privately on the phone. See [CHAT.md](CHAT.md) for the wire format, limits and usage.

Settings now includes the shared device name, exclusive power modes, an **Apply & test on this phone** action, and a controller result. **Apply & return** also starts a previously stopped radio. Runtime capability checks choose the native or validated compatibility path for that phone. See [POWER-SETTINGS.md](POWER-SETTINGS.md).

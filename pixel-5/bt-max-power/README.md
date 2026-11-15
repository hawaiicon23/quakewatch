# BT Probe Max — Pixel 5 experimental +20 dBm

Version 0.3 requests +20 dBm on the connected Pixel 5 / Android 14. This is an experimental compatibility path beyond the older public builder's +1 dBm cap. It is not a supported Android 14 builder setting.

`PowerRequest.java` compares serialized baseline parameters at powers 1 and 0, requires exactly one validated integer field to differ, changes that field to 20, and verifies the full serialization round trip. Unknown layouts fail closed. Requests are bounded to [-127,20]. The Bluetooth service and controller remain responsible for accepting the request and selecting output. No firmware, calibration table, SAR setting, or system permission was changed.

Root was verified (`uid=0`) and used to inspect vendor configuration and read the existing Bluetooth HCI log. The compatibility parameter path itself does not require root; granting root alone does not change the public builder cap.

## Hardware result, 2026-11-14

- Baseline: request +1 dBm, controller callback -1 dBm.
- Experiment: request +20 dBm, successful startup, controller callback +20 dBm.
- Independent HCI check using root: LE Set Extended Advertising Parameters (opcode 0x2036), request 20; Command Complete status 0, Selected_TX_Power 20.
- Scanning continued and displayed peer beacons.
- This verifies the controller-selected setting; antenna output and range have not been measured.

Package `dev.blx.btprobe.maxpower`, label **BT Probe Max**. Existing Nearby devices permissions, app data, and any root grant remain associated with this package.

## Build and rollback

On Arch: `cd /srv/projects/bluetooth-test/pixel-5/bt-max-power && bash build.sh`.
APK: `dist/bt-probe-max.apk`. Install from the laptop with `adb -s 0A261FDD4001V6 install -r dist/bt-probe-max.apk`.

Server baseline source: `baseline-api34-source.tar.gz`. Baseline APK: `dist/bt-probe-max-api34.apk` (version 2). Rebuild the baseline with a higher version code if a rollback is needed without uninstalling and losing app data.

Use **Broadcast test** for text/ack exchange and **Continuous test…** for repeated beacons. Keep the app visible.

Source of the Android 14 builder cap and Parcel layout:
https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/tags/android-14.0.0_r1/framework/java/android/bluetooth/le/AdvertisingSetParameters.java

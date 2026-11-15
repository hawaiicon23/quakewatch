# BT Probe Max — Pixel 8 Pro experiment

Standalone copy of the shared Android app taken on 2026-11-14. Original source hashes are in SOURCE-SNAPSHOT.sha256. This build uses package `dev.blx.btprobe.maxpower` and launcher label **BT Probe Max**, so it installs alongside BT Probe.

## Power request

Requests Android 17's `AdvertisingSetParameters.TX_POWER_MAX_AVAILABLE` (numeric value 20) instead of `TX_POWER_HIGH` (1). Requires Android 17 / API 37. The shared SDK is API 35, so the documented API 37 constant is inlined with an explanatory comment. This is the largest documented request, not a promise of 20 dBm antenna output. The controller selects the supported power; the app displays its callback report separately from the request. Vendor power tables and firmware are unchanged. Root is not required.

The BLE protocol, peer tracking, acknowledgments, and continuous test remain compatible with the main app. Open **Continuous test…** to start repeated beacons or use **Broadcast test** for a text/ack test. Keep the app visible.

## Build and install

On the Arch server:

```sh
cd /srv/projects/bluetooth-test/pixel-8-pro/bt-max-power
bash build.sh
```

After copying the APK from the server, run on the laptop where the phone is attached:

```sh
adb -s 3A101FDJG001VM install -r dist/bt-probe-max.apk
```

 Build outputs stay in this folder's `build/` and `dist/`; signing uses its own `.keys/test.jks`.

For a development smoke test (after granting Nearby devices permissions):

```sh
adb shell am start -W -n dev.blx.btprobe.maxpower/.MainActivity --ez start_test true
adb logcat -s BTProbeMax:I
```

## References

- https://developer.android.com/reference/android/bluetooth/le/AdvertisingSetParameters#TX_POWER_MAX_AVAILABLE
- https://developer.android.com/reference/android/bluetooth/le/AdvertisingSetCallback#onAdvertisingSetStarted(android.bluetooth.le.AdvertisingSet,int,int)

Hardware results are in `reports/2026-11-14-pixel-8-pro.md`.

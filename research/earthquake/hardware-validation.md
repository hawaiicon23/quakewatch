# Connected-phone validation — 2026-11-14

The feature was built on the shared build server and installed using `adb install -r` on the two available phones. Both report versionCode 6 / `0.6-sensing`; data and stable IDs were retained. The Pixel 8 Pro was not connected during this feature's hardware tests; no Android 17 result is claimed.

| Device | OS | Node ID | Sensor | Observed rate |
| --- | --- | --- | --- | --- |
| Motorola G6 Play / phh GSI | Android 11 | 37D2B5AC | BMI160 Accelerometer | 49.8436 Hz |
| Pixel 5 | Android 14 | 93DBBD1E | LSM6DSR Accelerometer | 53.6418 Hz |

Both phones were configured via the new UI with the same roster and TEST mode, yielding group fingerprint `CE5BDE5D`, quorum 2/2. Clocks initially reported the same Unix second. Both reached calibrated quiet state and received the other phone's quiet vote.

Real sensor motion generated local candidate windows in both phones. Both logged **TEST: correlated shaking detected** around device-local time 14:07:04; another candidate period also reached quorum. Raw sensor CSV captures contain overlapping candidate intervals beginning around 22:07:03.7 UTC. No sensor samples or votes were injected into the live apps by the test tools. The physical stimulus was not independently witnessed or measured with a reference instrument, so this verifies motion-trigger and radio behavior, not earthquake recognition or a calibrated shake-table experiment. The raw captures do not establish that the requested one-phone-then-two-phone physical test was followed in that exact order; isolated-node rejection is established by automated tests.

The CSV document-picker export succeeded on both phones. Files are retained in [hardware/](hardware/); [summary.json](hardware/summary.json) contains counts, rates and metadata. They cover approximately 68–70 seconds and include quiet, calibration and candidate samples, all explicitly marked TEST:

| Device | Samples | Largest timestamp gap | Median filtered RMS | Largest filtered RMS |
| --- | --- | --- | --- | --- |
| Motorola | 3485 | 20.10 ms | 0.012846 m/s² | 1.336335 m/s² |
| Pixel 5 | 3642 | 18.81 ms | 0.010100 m/s² | 1.765960 m/s² |

These are short test captures, not long-term noise-floor estimates or labeled earthquake data.

During the initial build test, repeated `Broadcast update timed out` messages exposed a pre-existing scheduler bug. Completed updates left timeout callbacks queued; those callbacks could interfere with a later update. The fix cancels completed/failed-update timers and restarts the advertising session after a real timeout, invalidating late callbacks. The final APK was rebuilt, signature-verified and installed on both phones. Final current-process evidence is in `hardware/final-*.log`; the corrected build's observed run had no advertising-update timeout messages. Initial motion/CSV evidence preceded that scheduler-only fix; the detector and voting algorithm were unchanged.

The corrected build still showed intermittent **INSUFFICIENT DATA** transitions on otherwise quiet phones. Reports recover, but no sustained-delivery guarantee or measured outdoor range is established. The narrow freshness requirement intentionally rejects stale observations instead of reducing quorum or declaring an earthquake. Packet-loss/congestion profiling remains needed. These transient views also demonstrate that local nodes can temporarily disagree about data availability.

Bluetooth on the Pixel 5 was disabled temporarily and then restored. Its service stopped the radio/sensor path, restarted when Bluetooth returned, and recalibrated. The Motorola could not treat the missing peer as an affirmative vote. Bluetooth was restored at the end. Both phones are left in the two-node TEST configuration for further table tests; use the toggle and Apply to enter normal monitoring.

All 26 repository tests passed again after the scheduler fix. The final APK is `/srv/projects/bluetooth-test/android-app/dist/bt-probe.apk`, SHA-256:

`791493139dfc7d83b8147a2a0be0f3b916652519f86861bf726a613269ea35b8`

Not verified: Pixel 8 Pro/Android 17 sensor/background behavior, normal-mode detection during a physical earthquake, calibrated amplitude/frequency response, false-alarm rate over extended handling/traffic activity, multi-station spatial verification, locked-screen endurance, notification behavior across every device setting, or battery consumption.

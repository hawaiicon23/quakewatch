# Device settings and Bluetooth power

Open Settings from the main page, or Device name & settings from Chat. The page uses the app's dark cards, mint selection, larger power readout and inset-aware layout. Your saved name and stable identifier are shown first.

Choose **Custom request** (slider 0–20 dBm), **Maximum request** (+20), or the existing **Experimental +21** option after a recent successful root check. These modes are mutually exclusive. **Apply & test on this phone** saves the selection, briefly allocates an advertiser, waits for the actual callback, displays the selected controller power, and releases the advertiser. **Apply & return** saves synchronously and restarts the parent radio after navigation, including when it was previously stopped. Saved settings alone are never labeled as a controller result.

Requests have a revision number. Callbacks from an earlier selection cannot replace the result of a newer one. Test/startup operations time out visibly; navigation cancels the settings probe. A settings probe is stopped before the parent starts its new session. Chat resumes after settings and uses the same power configuration as the main BLE test. The classic Bluetooth voice link still uses system-managed power.

## Per-phone method selection

The code tries the phone's native AdvertisingSetParameters builder first. If the local framework rejects the requested integer, it compares valid serialized 0/+1 objects, identifies their one differing power field, changes only that field, and checks the complete serialization round trip. Unknown layouts fail visibly. This runtime capability detection handles older Pixels, newer Android versions and other vendors without assuming equal hardware limits for equal Android versions.

The controller response is shown separately from the request, with device model, Android version and selected method. Results are tied to the device fingerprint. A capped result is displayed as capped output rather than quietly reporting the requested value as achieved. Rejections remain visible. The compatibility path is experimental on older frameworks; no vendor firmware, calibration, SAR or thermal table is modified. Root is used for the optional capability check, not as a promise of higher RF power.

The root check runs a bounded read-only `su -c id` command. A recent check is required to enable the +21 control in Settings. This does not grant Bluetooth firmware privileges or establish a safe/effective output level.

## Observed devices

Prior tests: Pixel 5 / Android 14 accepted a +20 request and returned +20; Pixel 8 Pro / Android 17 accepted +20 and returned +10; Motorola G6 Play / Android 11 returned 0 for +1, +20 and +21 requests. These are controller reports, not antenna measurements. Physical limits vary by hardware and current device state.

Current chat/power build validation is recorded in `android-app/validation/2026-11-14-chat.md`.

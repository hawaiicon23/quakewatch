# Accelerometer earthquake sensing: implementation and findings

Date: 2026-11-14. Project: `/srv/projects/bluetooth-test`. Build: `0.6-sensing`.

## Outcome and scientific status

The app now has an **Earthquake sensing…** page, a normal monitoring mode, a separate shake-test toggle, a live node-vote display, silent sensor advisories, and raw accelerometer CSV export. The existing foreground Bluetooth service carries observations without internet or a carrier. Each participating phone measures independently; receiving another phone's candidate does not make the receiver vote yes.

This is an experimental coincident-motion detector. It does not establish that an earthquake occurred, identify P/S phases, calculate magnitude/epicenter, predict an earthquake, or provide a validated early-warning service. The display deliberately says **possible earthquake**. No sensitivity, specificity, false-alarm rate or warning-time claim is established by the tests below.

## Review of the version attributed to Claude

The shared server's source was reviewed before modification and compared with the earlier local version. There is no Git repository/history in the shared project, so individual authorship cannot be independently verified. The reviewed snapshot is preserved locally as `/tmp/btprobe-review-current.tar.gz`, SHA-256 `19f27a0f8406f4af33649b2b313caba0a0c7fb0bf880e40d41846aa329f8b48c`. A server backup is `/tmp/btprobe-before-sensing.tar.gz`.

| Finding | Code / consequence | Resolution in this step |
| --- | --- | --- |
| Advertising-update timeout callbacks were not cancelled after successful completion. | `MeshService.transmit` and `onAdvertisingDataSet`. An older timer could see a newer update pending, clear its state, and confuse callbacks. Live logs showed repeated timeout messages and intermittent stale-node views. | Cancel the matching timeout on completion/failure; clear cached payload on failure; restart the advertising session on a genuine timeout so a late callback cannot complete a later request. Final APK includes the fix. |
| Radio ownership moved into a foreground service, with one retained advertising set, one scan, priority scheduling, boot recovery and a wake lock. | `MeshService.java`, `BootReceiver.java`. Activities no longer own the BLE radio. | Integrated sensors into this service; preserved the chat, voice, test, relay and alert paths. Stopping the radio unregisters sensors and removes sensor timers. |
| Earthquake alerts were manually supplied codes, not sensor detections. | `MainActivity.showAlertMenu`, `MeshService.sendAlert`. No accelerometer listener, detector or node vote existed. | Added acquisition, a local trigger and a separate observation protocol. |
| Signed alerts verify possession of a configured key; this does not prove a government/seismological authority supplied the alert. Unsigned official codes can be played via a testing setting. | `SignedAlerts`, `PhoneAuthority`, `MeshService.resolve`. | Sensor votes never call `sendAlert`, create signatures, or reuse the official alarm path. Sensor advisories use a separate silent channel. |
| Beacon IDs and chat labels are public and unauthenticated. CRC protects against accidental corruption, not impersonation. | `DeviceIdentity`, `ChatProtocol`, `ChatTransfers`. | A fixed roster prevents accidental duplicate-vote counting, but sensor IDs remain spoofable. This is explicitly shown in the new UI; authenticated deployment needs a further protocol design. |
| Signing-key fingerprint used for replay-state lookup is only 32 bits. | `SignedAlerts.fingerprint` and `MeshService.resolve`. Different keys can collide and share replay-counter state. | Recorded as a follow-up; unchanged in this sensor feature. Use a full key digest for persistent replay identity before wider deployment. |
| Chat data and earthquake codes share limited legacy-BLE airtime. Existing CHAT.md describes an older chat revision in several places. | Current code supports BC/2, 12-byte chunks and 24 fragment attempts; older text describes BC/1 and eight attempts. | Sensor packets share the existing scheduler, approximately twice per second when airtime permits. Signed alert bursts retain priority. Packet expiry handles starvation as missing data. Broader chat-document refresh is outside this feature. |

The review covered the mesh/alert/identity boundaries and relevant chat/voice integration, not a complete security audit or full phone firmware audit.

## Research basis and data sources

[MyShake, Kong et al., Science Advances (2016)](https://rallen.berkeley.edu/pub/2016kong/KongAllenEtAl-MyShake-SciAdv-2016.pdf) supports the feasibility of phone seismic sensing and the need to distinguish everyday motion from earthquake motion. Its work combines local classification with multi-phone network processing. It also investigates sensor noise, device coupling/sliding, and a useful phone response band around 0.5–10 Hz. Its trained classifier and regional network algorithms are **not** implemented here. The parameters below are our explicit engineering choices, not published MyShake defaults or reproduced performance metrics. The [supplement](https://rallen.berkeley.edu/pub/2016kong/KongAllenEtAl-MyShake-SciAdv-2016-SM.pdf) describes an earlier stationary/STA-LTA trigger and a much longer stationary qualification than this prototype.

[Android motion-sensor documentation](https://developer.android.com/develop/sensors-and-location/sensors/sensors_motion) specifies three accelerometer axes in m/s², including gravity. This implementation reads the hardware accelerometer and filters each axis before combining energy; it does not treat raw acceleration magnitude near 9.81 m/s² as earthquake shaking. Sensor timestamps drive filter coefficients and sampling checks.

[USGS NSMP data guidance](https://earthquake.usgs.gov/monitoring/nsmp/nsmpdata.php) points to CESMD for processed strong-motion records. [CESMD](https://www.strongmotioncenter.org/wserv/) provides event, station and waveform services; its [record-service documentation](https://www.strongmotioncenter.org/wserv/records/documentation/) requires a registered email for waveform-dataset downloads. Event catalogs and peak-acceleration tables alone are not sample-by-sample accelerometer waveforms. No account was created or borrowed for this work.

For immediately reproducible replay tests, three public historical accelerograms were downloaded from the maintained [OpenSees repository](https://github.com/OpenSees/OpenSees). These are real recorded ground-motion components distributed as engineering reference records, not recordings from our phones. Exact commit-pinned download URLs and SHA-256 digests are in [sources.json](research/earthquake/data/sources.json), alongside unmodified original files and headers:

| Record | Station / component from original header | Sampling | Samples |
| --- | --- | --- | --- |
| El Centro 1940 | North–South, repository label “Peknold Version” | 0.02 s / 50 Hz | 1559 |
| Borrego Mountain, 1968-04-09 | El Centro Array #9, 180°, USGS station 117 | 0.01 s / 100 Hz | 4000 |
| Northridge, 1994-01-17 | Alhambra–Fremont School, 360°, CDMG station 24461 | 0.02 s / 50 Hz | 3000 |

The OpenSees [ground-motion documentation](https://opensees.berkeley.edu/OpenSees/manuals/GettingStartedManual/1389.htm) illustrates the NPTS/DT record structure and acceleration in g. Our importer validates the declared sample count and multiplies g values by 9.80665 to obtain m/s². These three reference records are a starting regression corpus, not a representative earthquake training/evaluation dataset. Broader studies should obtain many events and stations, noise-only periods and real phone handling recordings, retaining processing, units, timing, instrument and site metadata.

## Local determination logic

`QuakeDetector.java` is pure Java and is used both by live Android sensing and the waveform tests.

| Parameter | Monitoring | Shake test |
| --- | --- | --- |
| Requested Android acquisition | 50 Hz, no batching | Same |
| Filter | Per-axis time-aware first-order gravity/high-pass at 0.5 Hz, followed by low-pass at 10 Hz | Same |
| Energy measurement | RMS of the three filtered axes over 0.5-second buckets | Same |
| Quiet qualification before arming | 30 s with bucket RMS ≤0.05 m/s² | 3 s with RMS ≤0.10 m/s² |
| Candidate threshold | RMS ≥0.08 m/s² and ≥3× max(baseline, 0.01 m/s²) | RMS ≥0.12 m/s²; ratio requirement bypassed |
| Persistence | Three consecutive qualifying buckets (~1.5 s) | Two buckets (~1 s) |
| Candidate hold after qualifying motion | 2.5 s, refreshed by sustained motion | Same |

The baseline is a smoothed quiet RMS (0.05 update weight per bucket), frozen during candidate motion. This is a transparent amplitude/noise/persistence heuristic, not a trained earthquake-versus-human-motion classifier or a formal reproduction of STA/LTA.

Nonfinite samples, clipping near 95% of the declared sensor range, gaps over 200 ms and measured rates below 25 Hz reset qualification. Unreliable sensor-accuracy status blocks sample processing until it recovers. No recent valid sensor samples means no usable vote. Timestamps that move backwards are ignored. Initial calibration must happen while the phone is stationary; starting monitoring during an earthquake can therefore miss it.

## Node communication and collective decision

1. Enter the same explicit roster of up to 16 stable node IDs on every phone. The phone includes itself. Each phone must also use the same mode and compatible algorithm version.
2. A node broadcasts its local state: unavailable, calibrating, quiet or candidate. It does not copy or amplify a peer's vote.
3. The packet carries the roster fingerprint, observation time, filtered RMS and test/monitor flag. It fits the existing 31-byte legacy advertisement budget: 12-byte app header + 12-byte body + 7 bytes BLE/company/flags framing.
4. Each listed ID counts once. Two phones need two candidate votes. Larger groups require a strict majority, with a minimum of two. Examples: 2/2, 2/3, 3/4. Disconnects do not shrink the denominator.
5. Reports expire after 2.5 seconds without a newly accepted sequence, or when their wire observation time is older than four seconds. Reports over 1.5 seconds in the future are rejected. Keep clocks aligned to about a second. Duplicate and reordered packets cannot refresh an old vote.
6. Test and monitoring observations cannot combine. Mismatched rosters, unknown IDs, schemas and flags are rejected. The roster fingerprint is a consistency check, not authentication.
7. A quorum shows **CORRELATED SHAKING: possible earthquake**, or an explicit **TEST** result. A lone candidate remains unconfirmed. Quiet reports can yield **NO CORRELATED SHAKING observed**; missing/calibrating data yields insufficient data. Neither result proves the absence of an earthquake.

“Correlated” here means coincident candidate votes, not waveform cross-correlation. Each node calculates from the reports it currently receives; packet loss can temporarily give nodes different views. This is a best-effort corroboration protocol, not Byzantine consensus or a reliable distributed agreement guarantee. Fresh observations recover the view after transient loss.

Sensor reports are direct-neighbor broadcasts, not multi-hop reports. The existing manual-alert/message relay features remain separate. Geography, station separation and wave-propagation consistency are not assessed. Phones on the same table, in one vehicle, or vibrating together can therefore produce a false earthquake candidate. Distributed sensing on separate stable surfaces and a validated classifier are necessary next steps.

## UI, background behavior and recorded data

Open **Earthquake sensing…** on the main page. Enable monitoring, select the test toggle if desired, enter the shared roster, and press **Apply settings / start Bluetooth**. Applying settings resets calibration and prior votes. In test mode, wait still for at least three seconds, then shake the table for at least one second. First shake only one phone: there should be no two-node quorum. Then move both phones together: a TEST result should appear if both local detectors trigger.

Sensing runs with the existing foreground mesh service and notification. Stopping Bluetooth stops sensing. The service requests a wake lock; no battery-life measurement or Android 17 background-sensor validation is claimed. Bluetooth-off/restart resets qualification. Device-specific permission, sensor-delivery and power-management behavior still needs field testing.

A quorum produces a separate silent advisory, rate-limited to one per 30 seconds. It never plays the existing official earthquake voice clips or vibrates the phones, which also helps avoid sensor feedback. The live sensing page remains the current view of node state. Advisories are timestamped past observations, not an ongoing all-clear/alarm state.

A bounded in-memory ring retains the last 6000 raw samples (approximately 120 seconds at the requested rate) while monitoring. **Export last ~120 s of sensor CSV** uses Android's document picker; no storage permission or internet is added. It includes UTC receive time, monotonic sensor time, x/y/z in m/s² including gravity, mode, local state and filtered RMS, plus sensor name/range/resolution and node ID. Data is written only when exported; the in-memory buffer disappears when the service process dies. Mode is recorded per row so test motion can be distinguished from monitoring data.

## Reproducible validation and limits

Run `bash research/earthquake/run-validation.sh` on the build server. This runs the repository suite, the pure-Java detector/consensus checks, recorded waveform replays, Android compilation and APK signature verification. The replay importer originally rejected the El Centro file's explicit footer; it was corrected to recognize that footer while retaining strict sample-count validation. Original source data was not changed.

Results: **26 repository tests pass**, including **34 detection/consensus checks**. Tests cover isolated motion, two-node agreement, strict-majority behavior, quiet disagreement, missing peers, expiry, duplicates/reordering, clock skew, wrong roster/mode/version/flags, clipping/gaps/nonfinite data, low sample rate, short taps and calibration separation. Chat/relay/voice regression tests also pass.

[Recorded replay results](research/earthquake/replay-results.csv):

| Reference record | PGA (m/s²) | First local candidate after record start | Two-node simulation | One motion + quiet peer |
| --- | --- | --- | --- | --- |
| Borrego 1968 | 1.27554 | 8.000 s | Corroborated | Not corroborated |
| El Centro 1940 | 3.12656 | 2.000 s | Corroborated | Not corroborated |
| Northridge 1994 | 0.78280 | 7.500 s | Corroborated | Not corroborated |

Replay prepends 40 seconds of synthetic low-amplitude quiet data to arm monitoring, supplies each recorded component on one axis with static gravity on another, and uses the original sample interval. The second simulated node receives the **same record**, scaled to 70% and rotated to a different horizontal axis; it is not an independently recorded station. Every third report exchange is dropped, with duplicates on delivered exchanges. The negative case substitutes a quiet peer. These tests verify pipeline and quorum behavior; they do not validate spatial earthquake recognition. Trigger times are relative to the supplied file, not the earthquake origin or P-wave arrival. Native 100 Hz Borrego input also does not reproduce the phone's requested 50 Hz sampling.

Hardware verification is recorded separately in [hardware-validation.md](research/earthquake/hardware-validation.md). Android 17 testing requires the Pixel 8 Pro to be connected; the available Pixel 5 is Android 14.

## Next validation work

Before treating this as an earthquake-warning system, collect labeled per-device quiet, walking, handling, tapping, traffic and table-motion datasets; expand earthquake replay to many independent events and stations; separate development and held-out events; and report missed events, false alarms per device-hour, latency and sensor quality by model. Use a calibrated shake table/reference accelerometer to measure coupling, frequency response and sampling defects. Authenticate node identities and freshness, test congestion/partitions and choose a geographic/propagation model before extending votes over relays. No detector thresholds should be called scientifically calibrated until those evaluations have been completed.

# Persistent data links, sensing alerts, and image viewing

Current release: 0.13-media-alerts (13), November 14, 2026. Transport verification below began with version 0.11.

## Delivery

Compatible Android 10+ phones advertise both their image and persistent-data PSM in a new bounded capability beacon. After discovery, the lower unsigned node ID initiates the unpaired LE L2CAP connection. Up to three persistent peers can connect at once. One reader and one bounded-queue writer own each socket; this prevents chat, voice and earthquake frames from corrupting one another. A 5-second greeting deadline, 8-second connect deadline, 5-second heartbeats and 20-second idle timeout bound failed connections. Discovery retries failed peers after at least five seconds. No root or pairing is required by the app transport.

Text and image messages first use available persistent links, with a saved-message receipt per peer. Text falls back to the existing advertising protocol for recipients without a receipt. Images retain the Image broadcast fallback setting; when no persistent link is usable, the existing one-shot connected image transport remains available. When a persistent attempt serves some of a mixed group, remaining peers use the configured advertising fallback. This build does not guarantee maximum controller throughput or connectivity beyond three persistent peers.

Earthquake sensor observations go over persistent links every 500 ms and continue advertising for disconnected nodes. Scheduled alert packets, signature fragments, relay packets and acknowledgments also use open links immediately. Existing authority signature verification, hop limits and unique-node consensus remain in place; a fast transport does not turn an unverified alert into a verified one. Direct sensor frames must identify the same node as the socket handshake. Radio presence RSSI continues to come from actual advertisements; data sockets do not supply invented RSSI measurements.

Network → Devices & links lists persistent peers separately from advertising sightings and temporary image links. Persistent links remain open after an image transfer and can carry chat, images, observations and voice.

## Cross-path duplicates

The received chat envelope is validated and stored before a positive data-link receipt is sent. Committing it discards any partial advertising reassembly for that sender/message ID. Later advertising fragments are checked against stored envelope bytes and acknowledged without creating another inbox, decoding another image or notifying again. Conflicting content is rejected. Advertising cannot be physically prevented from reaching the radio while discovery is running; the application discards already-delivered payloads.

The same stored sender/message identity also handles duplicate data-link attempts. Earthquake observations continue using the existing per-node sequence/freshness checks; receiving one node over two transports never creates an extra vote. Alert notifications have bounded per-origin/text repeat suppression in addition to the existing receive/relay tracking.

## Walkie-talkie

Walkie-talkie now uses the service's persistent data links, replacing its separate paired RFCOMM session. Opening the page attaches voice playback to the existing links; Discover data links starts discovery. Hold Talk requests microphone permission when needed and captures 8 kHz mono PCM only while held. Audio is sent directly to connected peers and is not relayed or saved. The playback and send queues are bounded; congested audio is dropped instead of growing an unbounded delay. A test-tone action remains available.

Keep the voice page visible. Leaving it stops the microphone, detaches playback, clears queued audio and restores the previous audio route; shared chat/sensing links stay open. This is a public, unauthenticated, unencrypted nearby transport, matching the existing chat model. Two simultaneous speakers can overlap; take turns speaking.

## Always-on sensing and alert choices

Opening the app starts the foreground service and accelerometer monitor after Android's required permissions/enable flow. The monitor no longer depends on the old quakeEnabled toggle or radio readiness. It keeps sampling through radio reconnects and Pause Bluetooth. The foreground service remains active while Bluetooth is paused. Sticky service restarts and the existing boot/update receiver resume sensing after the app has been started once; a paused-radio preference is respected on automatic restarts.

Android force-stop, denied foreground-service permissions, revoked permissions, missing sensors and device power-off can still stop monitoring. This is a foreground-service implementation, not an ability to override Android's lifecycle.

Settings → Earthquake alerts has mutually exclusive radio buttons:

- Notification: silent earthquake notification, including corroborated sensor advisories.
- Audio: notification plus offline spoken audio. Use media volume is on by default; turning it off selects the alarm stream.
- Off: no earthquake notification/audio; sensing, voting and delivery continue.

Changing the choice immediately stops any currently playing alert and clears existing earthquake notifications. Android notification permission/channel settings still apply; selecting Notification or Audio requests notification permission if needed. Unverified official alerts retain their existing verification restrictions.

For a table test, enable Shake-test mode and apply the SAME shared node-ID roster on every participating phone. Keep them still for calibration, then shake them together. The fixed-roster strict majority is unchanged (a two-node group requires both). Test and normal observations remain separate. Coincident shaking is experimental evidence of motion, not verification that an earthquake occurred; this prototype does not replace official warnings.

## Image viewing and Downloads

Tap an image bubble to open the full-screen viewer. Pinch to zoom up to 6×, drag to pan, double-tap to toggle zoom, or use the accessible Zoom in / Zoom out / Reset buttons. Pan bounds use the fitted image size so the picture cannot be dragged completely away.

Long-press an image to choose Save to Downloads, or use the viewer's save button. Android 10+ saves a fresh PNG copy to Downloads/BTProbe through MediaStore, without broad storage permission. Older Android versions use the system document picker. The received JPEG XL bytes remain unchanged in conversation storage; export uses the existing CPU-decoded bitmap and re-encodes it as PNG without copying source EXIF/GPS metadata. Pending/failed downloads are cleaned up, and the UI reports success or failure.

## Verification

The debug/release builds and lint checks pass. All 29 engine/protocol tests pass, including the existing recorded-earthquake waveform replays. The normal phone suite passes 15 tests (one opt-in hardware case skipped); the image viewer/export and two settings checks also pass at 2× text size.

Pixel 8 Pro (Android 17) and Pixel 5 (Android 14) physical persistent-link tests passed in BOTH directions, with both sender and receiver instrumentation reporting success. Each run verified fresh remote sensor reports, saved chat and JPEG XL image receipts, a TEST alert arriving through the data packet handler, actual AudioTrack playback of a 25-frame quiet voice tone, and a follow-up chat message after 20 seconds on the same open link. The image fixture was a small 128×96 solid-color image; these are transaction timings, not sustained throughput benchmarks:

| Direction | First text saved receipt | Test image saved receipt | Text after 20 seconds |
| --- | ---: | ---: | ---: |
| Pixel 8 → Pixel 5 | 74 ms | 156 ms | 78 ms |
| Pixel 5 → Pixel 8 | 61 ms | 93 ms | 78 ms |

The first hardware attempt used a stale app APK on the Pixel 5 with newer test bytecode; installed hashes were then checked and matched before retrying. A subsequent test harness ended receiver instrumentation before the sender's liveness assertion; the final test coordinates completion with a final chat message. The successful run stdout files and timestamped logs are `design-verification/data-{8-to-5,5-to-8}-{sender,listener}*.txt`. Normal, large-text, build, engine and signature logs also use the `data-` prefix.

Only two physical phones were tested. Three simultaneous persistent peers, long-distance/range behavior, microphone capture over the new transport, and long-duration battery use remain unmeasured. Tone playback verifies the audio path; it is not a microphone or acoustic quality test. No physical earthquake was generated or claimed. Temporary test rosters/modes were restored afterward; use the same shared roster and test mode on both phones for your own table test. Prior one-shot image measurements in CONNECTED-TRANSFERS.md describe version 0.10.

New tests cover mixed frame streams, malformed/truncated frames, size bounds, wrong recipients, capability beacon validation/backward decoding, cross-path message deduplication, image viewer/PNG Downloads export, alert radio choices, and live sensor sampling while Bluetooth is paused. Existing waveform replays, consensus, image codecs, advertising transfer, relay and UI checks remain included.

## Primary references

- [Android BluetoothSocket stream APIs](https://developer.android.com/reference/android/bluetooth/BluetoothSocket).
- [Android sensor background restrictions and foreground services](https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview).
- [Android shared media / Downloads storage](https://developer.android.com/training/data-storage/shared/media).

## Release artifact

The server APK is `/srv/projects/bluetooth-test/android-app/dist/bt-probe.apk`; local copy: `bt-probe-data-links.apk`. Release SHA-256: `768f3c6b40fdcb56538d3dbf1dc2d115704bf2a04a45b4c50fc08cda0d53dd8e`. Signature verification retains certificate SHA-256 `673cd773bca75c55bf777c31dd80608bb5271e6bd45d67945feede451aa9c949`. No application data was cleared.

The final non-debuggable release was installed on both Pixels. On-device APK hashes match the server artifact; both release processes established persistent links and registered the accelerometer. Instrumentation packages were removed. Evidence: `design-verification/data-release-check.json`, `data-release-*.txt`, and the Devices & links release screenshot.

## Demo defaults (version 0.12)

Fresh installs default to Shake-test mode and roster `8EE9509F 93DBBD1E`, the current Pixel 8 Pro / Pixel 5 demonstration pair. Explicit saved rosters and modes take precedence. This does not add new phones automatically: when expanding the demo, apply the same expanded roster to every participating phone. The normal-mode detector and voting rules are unchanged.

Version 0.12 debug/release builds and lint pass. Latest release SHA-256: `03d9fd6f98e98c8b4fbb0809177a606810327742f460fadf381ce23d653cc646`; the canonical server path remains `android-app/dist/bt-probe.apk`. Local artifact: `bt-probe-demo.apk`. Signature certificate is unchanged.

## Media-volume alerts (version 0.13)

The Use media volume toggle is ON by default: earthquake speech uses `USAGE_MEDIA` and the attention tone uses `STREAM_MUSIC`, following media volume including mute. OFF uses `USAGE_ALARM` / `STREAM_ALARM`, bypassing media mute while following the system alarm volume. The app does not raise either system volume or bypass Android’s alarm/DND policies. The muted-volume message follows the selected stream, and Settings explains both options. Existing Audio / Notification / Off selections are retained. Earlier alarm-volume descriptions in this report describe the previous build.

Version 0.13 debug/release builds and lint pass, and all 29 engine tests pass. Latest signed release SHA-256: `6c08123035579c8131090fd1a4d3df54e6c95508a0ca90fc7bd14d06ebe31834`. Local APK: `bt-probe-media-alerts.apk`; canonical server APK remains `android-app/dist/bt-probe.apk`.

Physical release verification on the Pixel 5 observed active TEST speech with `USAGE_MEDIA` when Use media volume was ON and `USAGE_ALARM` when OFF. Both phones were left with the toggle ON and Audio alerts selected. Installed release hashes match the server build. See `design-verification/media-audio-route-check.json` and `media-installed.json`.

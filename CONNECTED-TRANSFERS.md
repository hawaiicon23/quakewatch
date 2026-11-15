# Connected image transfers and radio audit

> Historical version 0.10 report. See [DATA-LINKS-UPDATE.md](DATA-LINKS-UPDATE.md) for version 0.11 persistent links, chat/alert/voice routing, and image viewing.

## Behavior

Text chat, earthquake observations, alerts and relays keep their advertising transport. Images first attempt unpaired Bluetooth LE L2CAP connection-oriented channels to compatible, recently discovered peers. A separate connectable advertising set publishes the image listener's PSM and node ID; the primary mesh advertising set and its continuous scan are retained.

Settings → Image transfers → **Image broadcast fallback** controls whether an image may use advertising when connected delivery fails or a peer has no compatible image endpoint. With fallback off, new images require a compatible nearby data endpoint, and failed connected deliveries are reported without sending their image payload through advertising. Text and discovery advertising remain enabled. Changing this setting also applies to queued image fallbacks.

A shared sender pool permits up to three outbound image attempts concurrently across all queued images. Up to three incoming sockets are admitted. These are app resource limits, not guarantees of what every controller supports. Errors, unavailable advertising sets, unsupported Android versions, expired peer endpoints and dropped connections fall back according to the setting. Duplicate copies received through different paths do not create repeated chat notifications.

This build opens data sockets for image transactions and closes them after acknowledgement. It does not yet maintain an always-connected graph or carry text/sensor traffic over those sockets. That extension needs a persistent connection owner, multiplexed framing, reconnect/keepalive behavior and duplicate suppression across both transports. Earthquake consensus must continue counting unique node IDs, regardless of how many transports deliver a vote.

## Device visibility

Network → **Devices & links**, and the nearby-device header/menu in Chat, show separate groups:

- **Seen over advertising:** node name/ID, RSSI, last-seen age and availability of a connected image endpoint. This is presence discovery, not a socket connection.
- **Active data links:** connecting, connected/sending, connected/receiving, or disconnected state, plus the latest transfer result for each node ID.

Recent disconnected entries remain visible, explicitly labeled disconnected. An idle advertising beacon is never counted as an active data socket.

## Reliability and bounds

The image stream carries sender, recipient, message ID and a bounded existing chat envelope. The receiver checks the envelope CRC and image decoder bounds, persists it, then sends a receipt; the sender only counts that peer delivered after the receipt. A lost receipt can trigger a duplicate delivery attempt, which is matched against stored content before being acknowledged again. Text is excluded from the image-only stream protocol. The image header and payload are buffered into one socket write, and the receipt into one write, to avoid creating several tiny SDUs for scalar header fields.

Images retain their 16 KiB payload and 1024 px decoder limits. Each connected attempt has a 12-second deadline. Cancel closes outbound sockets, drops queued connected attempts and cancels the corresponding chat queue. Radio stop closes listeners and sockets. Peers are bounded and expire. Partial advertising delivery is checked even when higher-priority sensor/alert traffic occupies the scheduler.

Unpaired connections and node IDs do not provide authenticated identity or end-to-end encryption. This matches the app's existing public nearby-chat behavior. The transfer implementation uses ordinary Android APIs; it does not execute `su`.

## Physical tests

Devices: Pixel 5 (Android 14/API 34) and Pixel 8 Pro (Android 17/API 37). Both have working KernelSU root.

An initial Pixel 8 → Pixel 5 run delivered three generated JPEG XL fixtures through connected sockets and retained ordinary advertising for a text test. Payloads were 513, 1,920 and 7,131 bytes. The largest image was saved and acknowledged in about 0.82 seconds from queueing; the 120 ms / 12-byte advertising schedule alone would require roughly 72 seconds for that envelope before retries and ACKs. This is a comparison to the schedule's ideal first-pass lower bound, not a measured advertising benchmark or a claim of sustained link bandwidth.

The bond state was 10 (`BOND_NONE`) before and after the connections. No pairing prompt, root command, carrier connection or internet transfer was used for image delivery. An additional connected-only run passed the first two images, then the Pixel 5 dropped from USB and the third transfer ended; that interrupted run is kept separate from successful timings. The phone later returned without rebooting (uptime was over seven hours).

The Pixel 5 → Pixel 8 reverse-direction test also delivered all three fixtures, with the 7,131-byte payload saved and acknowledged in about 1.17 seconds. A forced outage of the receiving image listener exercised the 12-second connected deadline and then completed the advertising fallback. The fallback test took about 29.6 seconds including that deadline and retransmission/ACK traffic.

The sender pool's three-way concurrency test and the complete 28-test engine suite pass. The standard Android UI suite (10 functional checks plus one skipped opt-in hardware test) passes at normal and 2× text size. The tests scroll the device list to validate off-screen rows. The test launcher force-stops the previously running app before instrumentation to isolate its Compose window.

The final buffered-write, connected-only Pixel 8 → Pixel 5 run delivered all three fixtures; the largest payload was saved and acknowledged in 807 ms from queueing (786 ms socket attempt). A release-to-release image selected through the normal upload UI produced a 655-byte JPEG XL payload (673-byte envelope) and was saved and acknowledged over the data link in 729 ms. Both phones now have the signed release installed with their existing application data retained. The Devices & links release screenshot shows the completed link explicitly disconnected afterward.

Only two physical phones were available: three-recipient physical throughput is not established. The sender pool's actual three-way concurrency, cap, recipient de-duplication and per-peer failure handling are separately tested with controlled concurrent jobs.

## Root / raw radio findings

Both phones report UID 0 through `su`. The Pixel 5 was already SELinux permissive; the Pixel 8 Pro was enforcing. No SELinux mode was changed.

A small Java/Dex probe, run through root `app_process`, successfully opened and closed an `AF_BLUETOOTH / SOCK_RAW / BTPROTO_HCI` socket on each phone. It did not bind a controller or transmit HCI commands. `/sys/class/bluetooth` contained no controller device on either phone.

The running controller interfaces belong to Android vendor HALs:

- Pixel 5: `android.hardware.bluetooth@1.0-service-qti`; Bluetooth-owned UART/power nodes include `/dev/ttyHS0`, `/dev/smd7` and `/dev/btpower`.
- Pixel 8 Pro: `android.hardware.bluetooth-service.bcmbtlinux`; Bluetooth owns `/dev/ttySAC18` and CHRE Bluetooth offload device nodes.

Root therefore exposes low-level host access and diagnostics, but the opened raw socket is not evidence of an attached, usable Linux HCI controller. Taking over a vendor UART would require displacing the HAL and implementing the device-specific initialization/protocol; that was not attempted. It would not by itself provide arbitrary RF waveforms, unrestricted frequencies or higher hardware power limits. No vendor firmware, RF calibration, controller limits or kernel modules were modified. The connected-transfer speed improvement comes from the app transport change.

Evidence: `design-verification/radio-root-*.txt`, `tooling/RadioProbe.java`, and `tooling/bt-radio-audit.sh`.

## Primary references

- [Android LE L2CAP client API](https://developer.android.com/reference/android/bluetooth/BluetoothDevice#createInsecureL2capChannel(int)) and [server API](https://developer.android.com/reference/android/bluetooth/BluetoothAdapter#listenUsingInsecureL2capChannel()). Available from API 29; older phones keep advertising fallback.
- [Bluetooth radio modes and data rates](https://www.bluetooth.com/learn-about-bluetooth/tech-overview/). PHY rates do not equal application payload throughput.

## Chat scrolling and release

Chat uses a reversed lazy list with the newest item at index zero. Opening/resuming Chat and changes to the latest saved message or incoming preview scroll to that index. The reversed list anchors growing image/message bubbles to the bottom. Status-only updates do not reset scrolling. A release-to-release check sent a new message from the Pixel 5 while the Pixel 8 was viewing older history, confirmed the incoming message became visible, then scrolled away and reopened Chat to confirm it returned to the latest message. Evidence: `design-verification/chat-auto-scroll-check.json` and `chat-auto-scroll-release.png`.

The final debug/release builds and lint checks pass. Release APK SHA-256: `b14712c36c47aa4f600b560572eadd8719a741223b62da69c584261ba57e3779`. The server copy is `/srv/projects/bluetooth-test/android-app/dist/bt-probe.apk`; the local copy is `bt-probe-connected-images.apk`. The existing signing certificate is retained.

# Mesh merge: background radio, fast live chat, earthquake alerts

Version 0.5-mesh merges the separate Quake Mesh app (`quake-app/`, now superseded)
into BT Probe. Everything from 0.4-chat is kept: chat + images, walkie-talkie,
power settings, message relays, continuous test, peer list.

## What changed

- **One background radio for everything (`MeshService.java`).** A foreground service
  owns the only advertising set and the only scan. Chat, alerts, relays, tests and
  beacons keep working with the screen off, the app in the background or swiped away.
  A notification shows while it runs ("Stop" turns it off). `MainActivity`,
  `ChatActivity` only display its state. `ChatRadio.java` was removed (its logic is in
  `MeshService`). Leaving a screen no longer stops Bluetooth.
- **Scheduler.** The advertising set stays enabled for the whole session (enabling and
  disabling per packet triggered HCI 12 on some phones); every 120-150 ms slot only the
  data changes. Priority: earthquake alerts > ACKs (2 of 3 slots while chat data waits)
  > chat data > message relays/tests > presence beacons. When idle it sleeps until the
  next beacon; new work wakes it immediately (no polling).
- **Fast chat (`ChatProtocol` BC/2, `ChatEngine.java`).** New compact chat packets:
  12-byte header, 12 content bytes per broadcast (BC/1 carried 8), 16-bit transfer ID.
  Fragments go out in order every ~120 ms. Short messages (<= 16 fragments) send each
  fragment twice up front; repairs wait for ACKs (0.5-1.2 s, scaled to size); a
  receiver repeats its final ACK 3 times. Transfers end when ACK progress stalls for
  15 s instead of after a fixed 8 attempts. BC/1 is still decoded and answered, but
  0.4 builds cannot read BC/2: update every phone.
- **Live text.** `ChatTransfers.Inbox.preview()` exposes the text received so far
  (contiguous prefix, never a cut UTF-8 character). The chat screen shows a
  "receiving…" bubble that fills in packet by packet, then the CRC-checked message.
- **Chat in the background.** New messages raise a notification when the chat screen
  isn't open. Enter / the keyboard's Send key sends.
- **Earthquake alerts** (from Quake Mesh): 11 codes (EQW EQN EQA AFT TSU CLR CHK SOS
  CXL RLY TEST) with ElevenLabs voice clips in `res/raw`, alarm tone/vibration at alarm
  volume, coloured banner, lock-screen full-screen alert for urgent codes, "I'm OK"
  check-ins, mesh relaying with a hop limit (default 10) and duplicate suppression.
- **Signed official alerts.** EQW EQN EQA AFT TSU CLR CXL only sound when signed by a
  key in `res/raw/trusted_keys.txt` (ECDSA P-256, signature sent as 6 `SIG` packets,
  BT/1 kind **6**; kinds 4/5 stay BT Probe relays). Unsigned ones show a silent grey
  banner and are not relayed. Replays are rejected. Authorities: `authority/quake_authority.py`
  on a laptop (key in `~/.config/quake-mesh/`, never in this folder) or a phone's
  "Authority key…" (Android Keystore).
- **Auto-start** after reboot/app update if it was on; resumes when Bluetooth comes back.
  Android 10-11: scanning after a background start needs location "Allow all the time"
  (asked once); otherwise opening any app screen restores it. A phone in that state
  stops announcing chat capability so senders don't wait for it.
- **Tools.** `linux-app/chat_peer.py` (laptop chat peer: `listen` shows messages live,
  `send "text"`), `authority/quake_authority.py` (signed alerts, `listen --verbose`).

## Wire formats

- BC/2 chat: `'B','C', 0x20|kind, sender(4), id(2), index(12 bits)|count(12 bits) (3), data(<=12)`.
  ACK: same header + target(4) + 32-bit bitmap. Envelope/CRC unchanged (CHAT.md).
- BT/1 alert TEST packets may end with a hop byte `0x01..0x10` (= 0..15 hops left),
  outside the signature. `SIG` (kind 6) body: `index<<4|6`, then 11 signature bytes.
  Signed message: `"QM1" | sender | id | text`.

## Validation (Nov 14, 2026)

- `python -m unittest discover -s tests`: 25 tests pass, including the existing 24 and
  `MeshMergeTest.java` (71 checks: BC/2 round trip, 30% loss, live preview, fuzzing,
  hop byte, SIG fragments).
- Four phones (OnePlus 3 / LineageOS 11, Pixel 5 / Android 14, Moto G5 Plus / Android 11,
  Motorola phh / Android 11), all on 0.5-mesh:
  - Text OnePlus → Pixel 5 + Moto G5 Plus: 5/5 delivered to both. Arrival after
    pressing Enter: Pixel 1.1-2.0 s, Moto G5 Plus 1.9-7.6 s (clock-offset corrected).
    Two phones only: 0.8-1.2 s. The "receiving…" bubble appeared 0.35 s after Send.
  - Image (1333-byte JPEG, 113 packets) Pixel 5 → 2 phones: delivered in 78 s.
  - Signed CXL from the laptop: verified on 3 phones, relayed with the hop count
    decreasing (one phone verified a relayed copy).
  - Background, screen off, swipe-away, reboot, app-update restart and Bluetooth
    off/on were tested on the OnePlus (Quake Mesh build, same service code).
- Not tested: walkie-talkie while the BLE service runs (both use Bluetooth; BLE no
  longer stops when the walkie-talkie opens), urgent EQW/TSU full-screen alert end to
  end, message relays (kinds 4/5) through the new scheduler on hardware.

## Known limits

- Images are slow (about a minute for 1.3 KB on a busy channel).
- Battery: continuous scanning + advertising + wake lock while the service runs.
- Trusted keys are built in: adding/removing an authority needs a rebuild.
- Chat messages are not relayed across the mesh (alerts and BT Probe relay messages are).

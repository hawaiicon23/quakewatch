# Quake Mesh (alert-playback copy of BT Probe)

A separate copy of `../android-app` (BT Probe as of Nov 14, 11:08) with
earthquake alert playback added. The original `android-app/` is untouched.
It installs as its own app (`dev.blx.quakemesh`, label "Quake Mesh"), so it can
sit next to BT Probe on a phone. It uses the same Bluetooth protocol, so it can
exchange messages with BT Probe and the Linux app.

## What changed vs android-app/

- `src/dev/blx/btprobe/AlertPlayer.java` (new): maps received codes such as
  `EQW` or `EQA:4.8:120` to the voice clips in `res/raw/`. Urgent alerts play an
  alarm tone and vibration first. Clips play at alarm volume. The same alert is
  played at most once a minute (mesh relays re-send it), and a lower-priority
  alert cannot interrupt a higher one; `CXL` (cancel) can interrupt anything.
  Unknown text plays nothing.
- `MainActivity.java`: alert banner (red = urgent, amber = serious), a
  "Send alert..." menu, a warning if the alarm volume is muted, and an updated
  footer. Hooked in at the first delivery of each received message.
- Mesh relaying (`MainActivity.scheduleRelay`): every received alert code is
  re-broadcast once, unchanged (same origin sender ID and message ID), after a
  random 0.1-0.5 s delay. Each phone's existing 60 s first-seen cache drops
  copies, so loops die out. A phone skips its relay if it has already heard 3
  other devices relay the same alert. The relay is queued before the automatic
  reply (ACK) so the alert spreads first. Only alert codes are relayed, never
  free text. Toggle: "Relay alerts to other phones (mesh)" (on by default).
  BT Probe and the Linux app receive relayed alerts like normal messages.
- Safety check-in: a received `CHK` shows an **I'm OK** button. Tapping it
  broadcasts `OK`, which is relayed like an alert but plays no sound. The
  banner counts and lists the phones that answered. Sending `CHK` from
  "Send alert..." shows a "Safety check sent" banner that collects answers.
  Tested Nov 14: the PC received the phone's `OK`, and the phone counted an
  `OK` from a second Linux identity.
- Background operation (`MeshService.java`, new): all Bluetooth, relaying,
  alert playback and check-in logic moved out of the Activity into a foreground
  service with a permanent "Listening for earthquake alerts" notification
  (with a Stop action) and a partial wake lock. `MainActivity` is now only the
  screen: it renders the service's state, so leaving the app, locking the phone,
  rotating or swiping the app away does not stop listening. Alerts received in
  the background still play their voice clip; they also post a notification,
  and urgent alerts (EQW/EQN/TSU/SOS) use a full-screen notification that opens
  the alert over the lock screen. On first start the app asks once to be exempt
  from battery optimisation (Doze). Tap Stop Bluetooth (or Stop in the
  notification) to turn it off.
  Tested Nov 14 on the OnePlus 3: a `TEST` alert was received, played and relayed
  with the screen off, in forced deep Doze (`dumpsys deviceidle force-idle`) and
  after the app's task was removed (`am task remove`, same as swiping it away).
- `AndroidManifest.xml`: package `dev.blx.quakemesh`, VIBRATE, foreground-service, wake-lock, notification,
  full-screen-intent and battery-optimisation permissions; the `MeshService` declaration.
- `res/raw/`: the 44 clips from `../earthquake-voices/clips/`.
- `build.sh`: writes `dist/quake-mesh.apk`.

Alert codes and scripts: see `../earthquake-voices/README.md`.

## Signed official alerts (authority keys)

Anyone can broadcast a code, so official alerts are signed. **EQW EQN EQA AFT TSU
CLR CXL only sound when signed by a key in `res/raw/trusted_keys.txt`.**
Community messages (TEST, CHK, OK, SOS, RLY) stay open to everyone.

- A BLE broadcast holds 24 bytes, a signature is 64, so an authority sends the
  alert plus 6 `SIG` packets (new packet kind 4, ignored by BT Probe and the Linux
  app) that share the alert's sender + message ID. It cycles through all 7
  (200 ms each, 5 cycles). Phones verify ECDSA P-256 / SHA-256 over
  `"QM1" | sender | id | text` (`SignedAlerts.java`).
- **Valid signature**: "✓ VERIFIED · <authority>" banner, alarm, and the alert is
  relayed *with its signature* so the next phone can verify it too.
- **Missing/invalid signature** (8 s wait): silent grey "UNVERIFIED" banner, not
  sounded, not relayed. A verified banner is never covered by an unverified one.
  Testing switch: "Play unsigned official alerts (testing only)", off by default.
- **Replay protection**: the message ID is the authority's increasing counter
  (Unix time); phones reject an ID that is not newer than the last one they
  accepted from that key.
- **Authorities**:
  - Laptop: `authority/quake_authority.py` (needs only `openssl` and BlueZ):
    `keygen` (once), `send EQW`, `send EQA:4.8:120`, `send --replay` (test),
    `listen --verbose` (verify alerts from phones), `selftest`. The private key
    stays in `~/.config/quake-mesh/authority.pem` on that laptop. **Never put a
    private key in this shared folder.**
  - Phone: "Authority key..." creates a key in the Android Keystore (it never
    leaves the phone) and shows its trusted_keys line; that phone's
    "Send alert..." then signs official codes.
  - To trust a new authority: add its line to `res/raw/trusted_keys.txt`,
    rebuild, reinstall on every phone. To revoke: delete the line and rebuild.
- Trusted now: `laptop-authority` (the Arch laptop the phone was tested with) and
  `Phone-5C97-authority` (the OnePlus 3 test phone).

Tested Nov 14 (OnePlus 3 + laptop): signed CXL -> VERIFIED, played, relayed, and a
second listener verified the *relayed* copy; unsigned CXL -> UNVERIFIED (no
signature), silent, not relayed; CXL signed by an unknown key -> UNVERIFIED
(invalid signature); replay of an old signed CXL -> REJECTED; phone-signed CXL
-> verified by the laptop. Java and OpenSSL were cross-checked both ways.
Not tested: two Quake Mesh phones verifying each other (needs a second phone),
urgent EQW/TSU end to end (avoided near teammates).

## Hop limit and auto-start

- **Hop limit**: alert codes carry an optional last byte `0x01..0x10` = hops left
  (0-15, default 10). Each relay counts it down; at 0 the alert is still shown
  and played but not relayed. The byte is not covered by the signature, so it can
  be counted down without breaking verification (a relay could also raise it,
  which only widens how far a genuinely signed alert spreads). Alerts without it
  (BT Probe, Linux app) count as 10. Alert text is therefore at most 11 bytes.
  PC: `quake_authority.py send CXL --hops 3`.
- **Auto-start**: `BootReceiver` restarts listening after a reboot or app update
  if it was on (tapping Stop keeps it off). If Bluetooth is off, the service
  waits and starts the radio when Bluetooth comes on; switching Bluetooth off
  and on while running pauses and resumes listening by itself.
- **Android 10-11 caveat**: a foreground service started at boot gets no BLE scan
  results unless the app has location "Allow all the time" (Android counts BLE
  scanning as location; Quake Mesh never reads location). The app asks once when
  you tap Start. Without it, the notification says "Tap to resume receiving
  alerts"; opening the app restores scanning. Android 8-9 and 12+ are not affected.

Tested Nov 14 on the OnePlus 3 (Android 11): auto-start after app update and
after reboot; Bluetooth off/on resumes; hop limit 0 is not relayed; hop limit 3
is relayed with 2 left and its signature still verifies; after a reboot without
"Allow all the time" scanning is blocked until the app is opened; with it, the
phone answered a TEST with no interaction.

## Build and install

```bash
bash build.sh                       # on the Arch server
adb install -r dist/quake-mesh.apk  # on the machine the phone is plugged into
```

Tested Nov 14 on a OnePlus 3 (LineageOS 18.1 / Android 11): a `TEST` alert sent
with `linux-app/bt_probe.py --send TEST` was received, shown, played and
relayed. A second Linux listener with its own identity
(`HOME=/tmp/x bt_probe.py --json`, started after the sender stopped) received
the relayed copy. The Linux app keeps its ID in
`~/.config/bt-probe/identity.json`, so two copies sharing a home folder ignore
each other's messages.

## Not done yet

- Each relay hop adds roughly 0.1-0.5 s plus scan latency; ACKs from distant
  phones are not relayed back to the sender.
- A repeat of the same alert text within 60 s is shown in the log but not
  replayed.
- Signing adds ~1-2 s before an official alert sounds (time to collect the
  6 signature pieces); unsigned official alerts wait 8 s before showing grey.
- Phone clocks: a phone authority uses its clock for the counter; a phone with a
  wrong clock still works (IDs only need to increase per key).
- Battery: listening keeps BLE scanning and a wake lock on; expect noticeably
  higher battery use while it runs.
- The urgent full-screen alert path is untested on a device (only `TEST` was
  sent, to avoid alarming nearby teammates).

> **Superseded (Nov 14):** merged into android-app/ (BT Probe 0.5-mesh). See ../MESH.md.

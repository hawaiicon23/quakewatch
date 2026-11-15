# Nearby chat layout · 0.8-messenger

Chat now uses a full-height conversation rather than a scrollable dashboard with an embedded, height-limited thread. Incoming messages sit on the left in neutral bubbles with the sender above them; outgoing messages sit on the right in accent bubbles. Bubbles size to their content and retain selectable text, image previews, timestamps, delivery state, and retry actions.

The bottom composer stays above the keyboard and Android gesture area, with an attachment button, expanding message field, and send button. The header exposes nearby devices and listening status. Its menu preserves Bluetooth start/pause, settings, and cancellation of queued transfers. The nearby devices dialog includes the local device identity. Public conversation visibility remains explicit.

Incoming packet progress stays inside receiving bubbles; an outgoing packet progress bar and its existing acknowledgement text appear above the composer while a transfer is queued. Packet transmission progress does not imply confirmed delivery; the delivery text retains that distinction. Image selection still requires a preview before transmission.

The chat engine, packet formats, relay behavior, earthquake detection, audio, database, and signing identity are unchanged. Existing app data is retained through an in-place update.

## Verification

- Debug and release builds plus both Android lint tasks passed on the build server.
- Six UI integration tests passed on the Pixel 8 Pro running Android 17 at both 100% and 200% text size. They cover navigation, unsent drafts, chat, voice controls, sensing, and settings.
- After the final thumbnail and opening-position refinements, the chat test passed again at both font sizes. It verifies typing with the keyboard visible, accessible attachment/send actions, conversation visibility, peer details, and draft retention. Screenshots were reviewed in light and dark themes using existing message history.
- The signed release was installed in place on the Pixel. The Motorola remains offline and was left alone as requested. The test package was removed afterward.
- No new over-the-air delivery/range experiment was performed in this UI change. Existing history and transport code were preserved.

Release APK: `/srv/projects/bluetooth-test/android-app/dist/bt-probe.apk` (also `bt-probe-messenger.apk` locally).

SHA-256: `85da383ff32d4ead49d113df59eb21b86981d86def73af5202adde20edacbbeb`.

Evidence: `design-verification/messenger-*`, `btprobe-messenger-build.log`, `btprobe-messenger-signature.txt`, and `chat-{light,dark}-{1.0,2.0}.png`.

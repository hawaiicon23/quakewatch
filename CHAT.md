# Nearby chat

Open **Open chat** from the main BT Probe app on each phone, then **Start chat**. It uses BLE manufacturer broadcasts without internet, pairing, GATT connections or a SIM. Keep Chat visible on both phones. Selecting an image temporarily pauses this activity's radio; returning resumes it.

## Identity and content

**Device name & settings** changes the same saved name used by the main app's peer beacons and voice identification. Names allow 1–12 UTF-8 bytes. Each chat message and image includes the name at send time, the stable 32-bit device ID, and a per-transfer random message ID. Names are labels, not authenticated identities.

Text supports up to 4096 UTF-8 bytes. Images selected through Android's document picker are resized with aspect ratio preserved to a maximum 70 pixels on the longest side, converted to JPEG at quality 70, and previewed before sending. Alpha is flattened onto white. Incoming JPEG dimensions and compressed sizes are checked before rendering. Full resolution images are never broadcast.

The newest 100 messages are retained in the app's private SQLite database; the conversation renders the latest 50. Messages show sender name/ID, time and delivery status. Interrupted or failed outgoing transfers offer Retry using the same message ID, so a lost acknowledgment does not create a second message on the receiver. Cancel queued transfers stops local sending. The app keeps at most four outgoing transfers. Radio/application activity recreation preserves history, not partially transmitted queue state; interrupted sends are marked for retry.

## Delivery

The wire protocol is separate from the existing test/relay format, which is unchanged. Every fragment fits the 31-byte legacy advertising limit. A receiver accepts fragments in any order, rejects inconsistent counts/conflicting duplicates, and checks a CRC32 envelope before publishing a completed message. Duplicate packets and repeated completed transfers do not add duplicate history rows. Incomplete transfers expire after ten minutes and at most eight are held at once.

Receivers reply with per-window 32-bit acknowledgment bitmaps addressed to the original sender/message. Senders prioritize missing fragments, make at most eight attempts per fragment, and expire a transfer after ten minutes. A transfer snapshots up to 16 recently seen chat-capable peers; it completes when those peers acknowledge every fragment. If none have been discovered yet, a complete acknowledgment from at least one peer counts as delivered. Partial delivery is labeled separately. Radio reception remains best effort; image transfers can take a minute or more. The UI shows packet and acknowledgment progress.

Chat does not currently forward through the main page's legacy relay mode. Nearby peers need this chat-enabled version for text/image exchange. Existing main-page tests, relay and voice remain available. The existing Linux test GUI does not decode chat packets.

## Wire format

Company ID 0xffff, prefix `BC`, version 1, big-endian integers. Header is 16 bytes; data packets carry at most eight content bytes:

| Offset | Size | Field |
| --- | --- | --- |
| 0 | 2 | ASCII BC |
| 2 | 1 | Version 1 |
| 3 | 1 | Kind: 1 data, 2 ACK, 3 chat capability hello |
| 4 | 4 | Transmitting device ID |
| 8 | 4 | Transfer ID, or hello sequence |
| 12 | 2 | Fragment index / ACK window start |
| 14 | 2 | Fragment count |
| 16 | 1–8 | Data bytes, or ACK target ID + 32-bit bitmap |

A hello has index 0, count 1 and a one-byte capability value 1. Named presence also uses legacy BT/1 beacons so the main peer screen sees chat phones.

Reassembled envelope: content type (one byte: text 1 / JPEG 2), name-byte count (one), content length (four), CRC32 (four), UTF-8 name, then content. CRC covers type + name bytes + content. Images are limited to 16384 compressed bytes; text to 4096. Broadcasts are public and unauthenticated, as in the existing test app; CRC is an integrity check, not encryption or authentication.

## Validation

`python -m unittest discover -s tests -v` includes a Java test exercising long UTF-8 text, binary image payloads, reorder/duplicate handling, 16 two-peer loss simulations, ACK targeting, count conflicts, expiry, memory bounds, corrupt envelopes and 10000 malformed frames. Actual Android JPEG handling and radio transfer are checked separately on connected phones.

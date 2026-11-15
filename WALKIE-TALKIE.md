# Offline walkie-talkie

Open **Walkie-talkie…** on the main page. On one phone press **Host / wait for other phone** and accept visibility. On the second press **Find nearby phones**, select the host, and accept matching pairing prompts on both phones. Previously paired phones are also available with **Show paired phones**. Take turns holding **Hold to talk**; release to listen. **Send quiet test tone** checks the audio path without using the microphone.

This is a direct, secure Bluetooth Classic RFCOMM connection on 2.4 GHz. No SIM, carrier plan, internet, router or server participates. Keep the page open on both phones. It supports one peer and uses 8 kHz mono PCM in 20 ms frames. Microphone capture stops on release, cancellation, leaving the foreground, or disconnect. Audio is not stored. Device label and app identifier are exchanged. Frame counts and input peaks aid diagnosis.

The Bluetooth controller manages voice-link transmit power. The existing BLE advertising power slider does not control this connection. Bluetooth range still depends on hardware and surroundings; this feature does not provide cellular, VHF/UHF, or FRS operation, voice relaying, background/locked-screen service, or a measured range increase.

Validation: the Android project built and signature verification succeeded; all 23 repository tests passed, including voice frame validation, truncation handling, PCM peak handling, and test-tone framing. Hardware testing used the connected Pixel 8 Pro (Android 17 / SDK 37) and rooted Motorola (Android 11 / SDK 30). Both received the new APK. Microphone and tone transfers are checked with BTWalkie logs and TX/RX/played counters; these checks do not establish outdoor range or subjective voice quality.

Implementation follows [Android's RFCOMM connection API](https://developer.android.com/develop/connectivity/bluetooth/connect-bluetooth-devices). No INTERNET permission was added.

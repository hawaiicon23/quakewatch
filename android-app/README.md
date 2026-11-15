# BT Probe Android app

The UI uses Kotlin + Jetpack Compose. The mesh, earthquake, chat-transfer and voice engines remain Java. Android 8+ (API 26), target SDK 35.

See [Compose design and migration](../COMPOSE-DESIGN.md) for design choices, accessibility behavior and verification. The [earthquake findings](../EARTHQUAKE-FINDINGS.md) cover the sensor algorithm and its limits.

## Build

Provision JDK 17+ and Android SDK platform/build-tools 35, or use `python3 tooling/setup-sdk.py` with a compatible JDK. Preserve the existing signing key at `.keys/test.jks`; it is not distributed with source.

```sh
export ANDROID_HOME="$HOME/.local/share/btprobe-build/android-sdk"
bash build.sh
```

Gradle 8.11.1 is downloaded by the checksum-pinned wrapper. AGP, Kotlin, Compose and AndroidX dependencies are pinned in `build.gradle.kts`. The build runs compilation and Android lint, verifies the existing signing identity's APK, and writes `dist/bt-probe.apk`.

## Verify

From the project root, with a JDK on PATH:

```sh
python3 -m unittest discover -s tests -v
```

Build the instrumented UI checks with `./gradlew assembleDebugAndroidTest` from this directory. Install the application and test APKs on a connected device, then run:

```sh
adb shell am instrument -w dev.blx.btprobe.test/androidx.test.runner.AndroidJUnitRunner
```

UI checks do not send messages, create signing keys, request power boosts, or simulate sensor votes. The chat check may start the existing Bluetooth service, matching normal Chat behavior. Appearance preferences are restored after tests.

## Operation

Network starts/stops the existing background mesh. Chat supports public text and small JPEG transfers. Sensing configures experimental accelerometer voting and separate table-shake tests. Tools retains range tests, message relays, manual alerts, signing-key controls and event logs. Settings adds light/dark/system appearance and reduced-effect options alongside the existing device-name and radio requests. Walkie-talkie uses paired Bluetooth Classic audio framing.

No internet or carrier plan is needed for app communication. BLE and Classic Bluetooth transmit power remain controlled by hardware and firmware. A requested power value is not proof of measured RF output.

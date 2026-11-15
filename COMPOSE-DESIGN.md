# BT Probe · Compose redesign

The Android UI is now Kotlin + Jetpack Compose. Main, Chat, Sensing and Settings are Compose activities; the walkie-talkie activity retains its Java audio/socket engine and hosts a Compose screen. Existing activity class names, package, signing key, database and preference keys are retained. MeshService, earthquake algorithms, chat transfer protocol, signature verification and audio framing remain Java.

## Design choices

The installed [apple-design skill](https://github.com/emilkowalski/skills/tree/main/skills/apple-design) informed immediate press feedback, interruptible critically damped springs, typography hierarchy, restrained translucency and accessibility fallbacks. Installed source: `/home/cpu/.codex/skills/apple-design/SKILL.md`, SHA-256 `7fbacf928637e136da6d475358d0639667a3637ba772f03f8e815bb4c0b86946`. It is also available on future turns. Its web examples were translated into Android APIs; there is no SwiftUI, WebView, CSS runtime, SF font or iOS-only API.

The visual system uses a navy/teal network hero, generous rounded surfaces, Android system typography, measured emphasis, and a floating four-destination dock. Network and Tools remain in MainActivity; Chat and Sensing preserve their activity routes. Existing alerts still surface above the main content and use the established signing/audio behavior. Unsent tool drafts survive tab changes through a saveable state holder. Activity state and configuration changes preserve the relevant drafts and form values.

[Haze 1.5.3](https://github.com/chrisbanes/haze/releases/tag/1.5.3) samples the Compose backdrop for the dock's Android backdrop blur. Android 12+ uses the effect; older devices, higher-contrast mode and reduced-transparency mode use an opaque surface. This is an Android glass-inspired material, not Apple's refractive Liquid Glass renderer. Content cards stay opaque for predictable contrast.

Press feedback and local page transitions use Compose springs. There are no perpetual motion loops, artificial input delays or added haptics that could disturb accelerometer sensing. System-disabled animations and the app's Reduce motion option remove the added movement. Android activity/back gestures remain native; no custom edge-swipe recognizer replaces system navigation.

## Accessibility and layout

- System/light/dark appearance, with persistent overrides in a separate `appearance` preference file.
- Reduce transparency, Reduce motion and Higher contrast controls; system high-text-contrast and animation settings are respected.
- Material controls and semantic button, switch, tab, radio and talk actions; decorative radio/wave graphics have no redundant spoken labels.
- Touch targets at least 48 dp for main controls. The hold-to-talk target also exposes an accessibility click action and a visible Stop talking action while recording.
- System status/navigation insets and display cutouts are respected. The dock clears the gesture area, hides while the IME is visible, and the scroll viewport ends above the dock so focused/scrolled controls cannot land underneath it.
- Text uses sp and natural wrapping, without fixed card heights. Metric cards stack at larger font scales; content width is capped on wider displays. Forward/back arrows mirror with layout direction.
- Sensing labels continue to distinguish TEST, unconfirmed data and possible earthquake observations. No visual styling promotes an observation into a verified warning.

## Build migration

The old AOSP javac/d8 script could not compile Compose. `android-app` now contains a standard Gradle Android application using AGP 8.9.2, Kotlin/Compose compiler 2.1.20, Compose BOM 2025.04.01, Activity Compose 1.10.1, Lifecycle 2.8.7 and Haze 1.5.3. It keeps compile/target SDK 35 and minimum SDK 26. No application internet permission is required for offline operation.

Build on the existing server:

```sh
cd /srv/projects/bluetooth-test/android-app
export JAVA_HOME=/srv/projects/.toolchains/android/prebuilts/jdk/jdk21/linux-x86
export ANDROID_HOME="$HOME/.local/share/btprobe-build/android-sdk"
bash build.sh
```

`tooling/setup-sdk.py` can provision the build tools in the user's home directory. It verifies the Gradle ZIP with the official SHA-256 and Android command-line tools against the checksum in Google's repository manifest. The wrapper pins the Gradle ZIP checksum. Dependencies resolve from Google's Maven repository and Maven Central. Existing `.keys/test.jks` is required; the script refuses to replace it with a different update identity.

The default build produces the non-debuggable release variant, runs lint, verifies the APK signature, and only then copies it to `android-app/dist/bt-probe.apk`. The pre-redesign server APK is retained at `/tmp/btprobe-before-compose.apk`, with source snapshot `/tmp/btprobe-before-compose.tar.gz`.

## Verification

The existing 26 protocol, transfer, audio-framing and earthquake tests pass after migration. Android lint is enabled with abort-on-error. Scoped MissingPermission annotations document the existing explicit permission gates and protected cleanup paths, which lint cannot infer through the shared permission-array helpers; lint is not globally disabled. The unused legacy view factory was removed.

The Android 17 Pixel 8 Pro is the connected verification device. AndroidX Test/Espresso was updated to the stable 1.7.0/3.7.0 line because the earlier runner used a removed InputManager reflection method. [AndroidX release notes](https://developer.android.com/jetpack/androidx/releases/test) document the getSystemService fix. Compose UI tests exercise tab/draft retention, sensing controls, chat entry and attachments, appearance modes, and the accessible disconnected talk control. Verification artifacts and final results are recorded in `design-verification/`.

The Motorola is offline and was explicitly set aside by the user. No new two-phone radio/audio or older-device GPU test is claimed for this UI pass. The earthquake prototype's previously documented scientific and radio-loss limitations remain unchanged.

### Final results · 0.7-glass

- Build and Android lint pass (zero lint errors; existing warnings remain).
- All 26 engine/protocol tests pass.
- All six UI integration tests pass on the Android 17 Pixel 8 Pro at font scales 1.0 and 2.0. The checks cover navigation, unsent drafts, sensor controls, chat/attachment entry, accessible talk controls, appearance and the unsaved radio slider.
- Eleven text-palette contrast cases meet 4.5:1, including conservative translucent-dock composites; see `design-verification/contrast-results.json`. This is not a complete TalkBack or WCAG audit.
- Light/dark screenshots were captured at both font scales. Font scale and appearance preferences were restored after testing.
- The packaged application manifest has no INTERNET permission. Existing device IDs, chat storage and authority keys were retained by an in-place update using the existing signing key. The delivered release APK is non-debuggable, matching the earlier app’s security setting.
- APK signature verification passes (APK Signature Scheme v2); the installed and server-published APKs match.

Final APK SHA-256: `0ca10997025d9f3ca841324bcfa6a818cc23ba27910ab8e5bc61c0f71fdb9cba`.

Server artifact: `/srv/projects/bluetooth-test/android-app/dist/bt-probe.apk`.

# Image sharing · 0.9-images

## Format and compatibility

New uploads use lossy JPEG XL (JXL) with a bundled native CPU encoder and decoder (`io.github.awxkee:jxl-coder:2.6.1`, backed by libjxl). This is a modern format with compact codestream support. No format is the smallest for every image; this choice favors a small pixel-only stream and consistent software decoding. It does not depend on OS JPEG XL support, a hardware codec, a GPU decoder, or a network service.

The APK includes arm64-v8a, armeabi-v7a, x86 and x86_64 native binaries. The app still supports Android 8/API 26 and later. Older hardware is supported by packaging the software codec, but the connected Pixel is the physical device available for testing; the Motorola is offline. All receiving phones need version 0.9 or later for new JPEG XL images. Earlier JPEG history and incoming legacy 70 px JPEG images remain supported. A separate image type prevents old apps from treating JPEG XL data as a supported JPEG message; old clients do not fully accept new-image transfers.

## Upload controls

The attachment button opens the system image chooser, then a dedicated Share an image screen. The longest-edge slider ranges from 64 to 1024 pixels, defaulting to 256. Aspect ratio is preserved and small sources are not enlarged. Compression defaults to 80/100, mapped to quality 29; the full slider maps quality 85 down to 15. This is a compression preference, not a promised percentage reduction. Encoder effort stays at 9; decoder speed preference is 0 to favor compression.

A debounced, serialized background encode produces the exact bytes that will be sent. The screen shows their byte count, KiB size, actual dimensions, and upper-bound first-pass data-packet count; retries and ACKs cost additional airtime. The preview is decoded from those bytes using the software decoder. Sending stays disabled while settings are changing, while encoding or verification is unfinished, or when the file exceeds 16 KiB. The existing small Bluetooth advertising payload makes large files slow, so this transfer limit remains in place.

Canceling leaves chat history and the transfer queue unchanged. The Send image action returns the verified bytes to Chat, which uses the existing queue, fragmentation, CRC, acknowledgements, progress, cancellation and retry logic.

## Metadata, color and bounds

Sources are decoded with orientation applied, drawn onto a fresh opaque 8-bit sRGB bitmap, and re-encoded. Transparent pixels become white. No original EXIF, GPS, camera model, date, embedded thumbnail, source ICC blob, JPEG reconstruction data or animation is copied. The encoder must return a raw `FF 0A` JPEG XL codestream; optional file-container boxes are not transmitted. Dimensions, color encoding and other headers required by a valid decoder remain. Removing them would corrupt the image.

The preview, incoming decode and selected-file processing have dimension and byte limits. Incoming images are capped at 16 KiB and 1024 pixels per dimension before full decoding. Legacy JPEG dimensions retain their 70 px limit. JPEG XL source files can also be opened through the software codec, bounded to 8 MiB and 8192 px before sampled decoding. Native encoding and conversation thumbnail decoding run off the UI thread. Repeated slider changes cannot start overlapping native encoders.

## Build and provenance

The codec is fetched from Maven Central, pinned to 2.6.1, and both debug/release AAR SHA-256 values are checked by `verifyImageCodec` before builds. The optional AppCompat/Material transitive dependencies are excluded; the app supplies its Kotlin runtime. Native codec license notices ship in the APK assets.

The current stable codec uses Kotlin 2.3 metadata and compile SDK 36. The build uses Kotlin/Compose compiler 2.3.21, AGP 8.13.2, Gradle 8.13 and compile SDK 36; target SDK 35 and min SDK 26 remain. The Gradle distribution checksum is pinned and the SDK setup script downloads official tools.

## Sources

- [Android JPEG XL codec](https://github.com/awxkee/jxl-coder), with implementation reviewed at tag `2.6.1`.
- [Publisher metadata and artifact checksums](https://repo.maven.apache.org/maven2/io/github/awxkee/jxl-coder/2.6.1/jxl-coder-2.6.1.module).
- [libjxl reference implementation](https://github.com/libjxl/libjxl).
- [Android Kotlin build compatibility](https://developer.android.com/build/kotlin-support).
- [Android 16 SDK setup](https://developer.android.com/about/versions/16/setup-sdk).

## Verification

- Debug/release builds, both lint tasks and pinned codec checksums passed on the build server.
- The 26 engine tests pass with added JPEG XL transport coverage, including reordering, multiple receivers and simulated packet loss.
- Eight Android tests passed on the Pixel 8 Pro running Android 17 at both normal and 200% text size. New checks cover EXIF orientation, metadata removal, compressed-size reduction, software ARGB_8888 decoding, malformed data, dimension/byte limits, legacy JPEG decoding, compact packet reassembly, upload sliders and previews. Existing navigation/settings/voice/sensing checks also pass.
- Both themes were reviewed in screenshots. The signed, non-debuggable release was installed in place, preserving data and the existing signer. The test package was removed afterward.
- The release was additionally checked through the actual Chat attachment chooser using a generated test PNG. Its 256 × 192 px output was 655 bytes, previewed correctly, and enabled Send image. The upload was canceled and the temporary input file removed; no test message was broadcast.
- The bundled 64-bit native libraries have 16 KiB ELF load alignment. The physical Pixel uses 4 KiB pages; no physical 16 KiB-page or Android 8 device was available for runtime testing.

Evidence lives in `design-verification/images-ui-tests*.txt`, `image-upload-*.png`, `btprobe-images-*.log`, `btprobe-images-signature.txt` and `images-lint-results-release.xml`.

Published APK: `/srv/projects/bluetooth-test/android-app/dist/bt-probe.apk`.

SHA-256: `4a1460a0363a8cbf7a6e84e61a5cbeaf4739d059cf01cac1331b5a28941c323e`.

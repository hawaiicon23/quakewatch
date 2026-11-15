# Pixel 5 modem interface probe

This is a local modem-service test, not an over-the-air device-to-device transfer test.

## Observed on 2026-11-14

- Connected device: Pixel 5 (`redfin`), Android 14, platform `lito`.
- Baseband firmware: `g7250-00264-230619-B-10346159`.
- Root works through KernelSU (`uid=0`, `u:r:ksu:s0`).
- `/dev/diag` and Qualcomm modem libraries are present.
- `libril-qc-ltedirectdisc.so` contains Direct Discovery capability/status message definitions. `libqmiservices.so` exports `lte_get_service_object_internal_v01`, and the radio HAL library references LTE Direct Discovery OEM-hook events.
- The live QRTR name server returned a complete inventory of **110 service registrations**.
- The inventory contained **zero LTE service 70 / 0x46 registrations**. A second, targeted lookup for service 70 also completed with **zero registrations**.
- No WWAN device or `cdc-wdm` modem node is exposed on the laptop. ModemManager is not running. A second cellular peer is not available in the tested setup.

Result: Android-side LTE Direct Discovery components exist, but the corresponding LTE service endpoint was not exposed in these live checks. No usable direct-modem data path was established. This does not prove the chipset can never support sidelink or rule out other undocumented interfaces. Direct phone-to-phone RF communication remains untested because there is no available service path and no second compatible modem.

The JSON evidence is in `../reports/modem-probe-2026-11-14.json`. No radio settings, firmware, NV values, SIM configuration, carrier provisioning, or USB modes were changed. The only device files written were temporary probe executables under `/data/local/tmp`; they were removed afterward.

## Reproduce

Build the AArch64 helpers on Arch:

```bash
cd /srv/projects/bluetooth-test
bash pixel-5/modem-probe/build.sh
```

The build needs Clang plus the LLD linker. `CLANG=/path/to/clang` overrides the compiler. When the system linker is absent, the script uses the existing AOSP compiler under `blx`'s home if accessible. A user without access to that compiler must supply an accessible Clang/LLD installation. The generated binaries need no Android NDK, libc, or runtime libraries.

Copy `build/qrtr-probe` and `build/qrtr-probe-lte` into the matching local project folder on the laptop. With the Pixel connected, USB debugging authorized, and root available, run:

```bash
python pixel-5/modem-probe/run.py
```

Use `--serial USB_SERIAL` if necessary. The runner verifies `redfin`, copies the helpers to temporary files, runs them as root, saves the JSON report, and removes the temporary executables. It does not send arbitrary QMI or DIAG commands.

The helpers use `AF_QIPCRTR` and `QRTR_TYPE_NEW_LOOKUP` to enumerate the local name server. The targeted helper asks only for service 70. Both close their sockets on completion and use a three-second receive timeout.

References: [Linux QRTR UAPI](https://github.com/torvalds/linux/blob/master/include/uapi/linux/qrtr.h), [upstream QRTR service lookup and service-name table](https://github.com/linux-msm/qrtr/blob/master/src/lookup.c).

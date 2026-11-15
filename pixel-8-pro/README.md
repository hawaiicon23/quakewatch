# Pixel 8 Pro modem checks

Tested on 2026-11-14 with a USB-connected Pixel 8 Pro (`husky`), Android 17, platform `zuma`, KernelSU root, and modem firmware `g5300i-260908-260921-B-16399130` (reported for both modem slots).

## Result

Local modem driver access works. A native helper built on the Arch server queried `/dev/umts_boot0` and returned:

```json
{"cp_status_ioctl_result":4,"cpif_version_ioctl_result":0,"cpif_version":"CPIF-20220408R1"}
```

Status 4 corresponds to `STATE_ONLINE` in the published CPIF driver state enumeration. The version query succeeded. These results establish local driver access; they do not establish PC5/sidelink support.

The phone exposes Samsung CPIF/SIT interfaces, including `/dev/umts_ipc0`, `/dev/umts_dm0`, `/dev/umts_router`, radio AIDL services, Samsung OEM services and Google radio extension services. The Qualcomm QRTR service inventory used on the Pixel 5 is not a capability test for this modem.

No direct-discovery/PC5/V2X service or interface was identified in the inspected service list and vendor filenames. A string search in `libsitril.so`, `libsit_oem.so`, `liboemservice.so`, `vendor.radio.protocol.sit.json.so` and `vendor.radio.protocol.sit.base.so` found no matches for `sidelink`, `prose`, `pc5`, `cv2x`, `v2x`, `direct.discovery` or `lte.direct`. This limited inspection cannot prove that the hardware or firmware lacks all such functionality.

**No direct cellular transfer was attempted or demonstrated.** A working, understood modem interface for sidelink and a compatible second peer are still needed. No radio configuration, modem firmware, NV items, SIM settings, or carrier provisioning was changed. No reads were taken from the RIL IPC data stream, and no commands were written to the AT router or modem data channels.

Raw evidence: [reports/modem-probe-2026-11-14.json](reports/modem-probe-2026-11-14.json).

## Reproduce

On Arch:

```bash
cd /srv/projects/bluetooth-test
bash pixel-8-pro/modem-probe/build.sh
```

Build requirements: Clang and LLD. `CLANG` overrides the compiler path. The build script falls back to `blx`'s existing AOSP compiler if the system linker is absent and that compiler is accessible. Other users need an accessible Clang/LLD installation or a `CLANG` override. Output: `pixel-8-pro/modem-probe/build/cpif-probe`.

Copy the helper into the corresponding folder on the laptop. Connect and authorize the Pixel 8 Pro, then run from the local project root:

```bash
python pixel-8-pro/modem-probe/run.py
```

Pass `--serial USB_SERIAL` if needed. The runner verifies `husky` and root, records selected non-subscriber properties and interface metadata, pushes the native helper to `/data/local/tmp/blx-cpif-probe`, runs it with a five-second timeout, saves the JSON report, and removes the helper. The native helper opens the boot device read-only/nonblocking, performs only the documented status and version ioctls, and closes the descriptor.

Driver references: [CPIF ioctl definitions](https://android.googlesource.com/kernel/google-modules/radio/samsung/s5300/+/refs/tags/android-16.0.0_r0.10/modem_prj.h), [status/version handlers](https://android.googlesource.com/kernel/google-modules/radio/samsung/s5300/+/refs/tags/android-16.0.0_r0.10/bootdump_io_device.c).

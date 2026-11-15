# Pixel 5

Device-specific installation and test instructions. Device codename: `redfin`. Android package: `dev.blx.btprobe` (BT Probe).

The separate [modem interface probe](modem-probe/README.md) records root access and live modem service availability. Its results are in `reports/`. It does not establish a cellular device-to-device link.

The Pixel uses the APK built in `../android-app/dist/bt-probe.apk`; it does not need a separate copy of the Android source or signing key. Build on Arch, then copy the APK to the laptop with the phone attached.

## Install from the laptop

Authorize USB debugging on the Pixel, then run from the project root:

```bash
bash pixel-5/install.sh
```

If several Android devices are attached, pass the Pixel's serial number: `bash pixel-5/install.sh USB_SERIAL`. The script checks that the device is a Pixel 5 before installing, then opens BT Probe. Alternatively, manually open the APK on the phone to install it.

## Bluetooth test

1. Keep the Pixel within a few feet of the laptop. Turn Bluetooth on.
2. Open BT Probe, tap **Start Bluetooth**, and allow **Nearby devices**. Keep the app open.
3. Start the nearby Linux app with `bash linux-app/run.sh` from the project root and click **Start Bluetooth**, or run `bash linux-app/run.sh --json` for a terminal receiver.
4. Tap **Broadcast test** on the Pixel. Linux should show `RX 'TEST'` and send an acknowledgment. The Pixel should show **Reply from …**.
5. To test the reverse direction, run `bash linux-app/run.sh --send LAPTOP --no-auto-ack --duration 20` on the laptop. The Pixel should display the incoming message and reply.

The existing background laptop receiver writes to `../linux-app/logs/laptop-peer.jsonl`; watch it with `tail -f linux-app/logs/laptop-peer.jsonl`. Running processes keep their log open when the file is moved into this folder.

The Arch build machine is in another city, so Bluetooth testing uses the laptop and Pixel, not the Arch radio. Switching away from the Android app stops its Bluetooth test; tap Start Bluetooth again after returning.

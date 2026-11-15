# Linux app

- `bt_probe.py`: BlueZ sender/receiver and desktop controls.
- `protocol.py`: shared packet format implementation.
- `run.sh`: desktop or terminal launcher.
- `logs/`: runtime JSON output and PID files; the laptop's active receiver log is `logs/laptop-peer.jsonl`.
- `screenshots/`: desktop preview images, when captured locally.

Python source runs directly; there is no compiled Linux build directory.

From this directory:

```bash
bash run.sh                            # Desktop controls
bash run.sh --json                     # Terminal receiver, automatic replies
bash run.sh --send LAPTOP --duration 20 # Send once and receive replies
```

The existing `bash run-linux.sh` launcher at the project root still works. If another receiver is already running, add `--no-auto-ack` to a separate send process to avoid duplicate automatic replies.

Dependencies: Python 3, BlueZ, a powered adapter supporting LE scanning and advertising, dbus-python, PyGObject/GLib and (for the GUI) tkinter. On Arch use `bluez bluez-utils python-dbus python-gobject tk`; on Fedora use `bluez python3-dbus python3-gobject python3-tkinter`.

Both the laptop and Arch server already have these dependencies. The devices exchanging Bluetooth messages must be physically nearby.

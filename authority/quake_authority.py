#!/usr/bin/env python3
"""Quake Mesh authority tool: sign and broadcast official alerts over BLE.

Phones running Quake Mesh only sound official alerts (EQW EQN EQA AFT TSU CLR CXL)
that carry a valid signature from a key listed in quake-app/res/raw/trusted_keys.txt.

  python3 quake_authority.py keygen            # once: create key, print its trusted_keys line
  python3 quake_authority.py pubkey            # print the trusted_keys line again
  python3 quake_authority.py send CXL          # sign + broadcast for ~5 s
  python3 quake_authority.py send EQA:4.8:120  # details are part of the signed text
  python3 quake_authority.py send --replay     # re-broadcast the last packets unchanged (replay test)
  python3 quake_authority.py listen            # verify signed alerts from phones (trusted_keys.txt)
  python3 quake_authority.py selftest          # sign/verify round trip, no radio

The private key stays in ~/.config/quake-mesh/ (mode 600). Never copy it to the
shared project folder: anyone holding it can sound alarms on every phone.
Crypto uses the openssl command (ECDSA P-256 / SHA-256); radio uses BlueZ over D-Bus.
"""
import argparse
import base64
import json
import os
import secrets
import struct
import subprocess
import sys
import tempfile
import time
from pathlib import Path

COMPANY = 0xFFFF
TEST, SIG = 1, 6  # SIG is kind 6 in the BT/1 format (4 and 5 are BT Probe relay messages)
HEADER = struct.Struct(">2sBBII")
SIG_BYTES, CHUNK = 64, 11
MAX_TTL, DEFAULT_TTL = 15, 10  # optional last body byte 0x01..0x10 = hops left (not signed)
FRAGMENTS = (SIG_BYTES + CHUNK - 1) // CHUNK  # 6
AUTHORITY_CODES = {"EQW", "EQN", "EQA", "AFT", "TSU", "CLR", "CXL"}
CONFIG = Path.home() / ".config" / "quake-mesh"
KEY = CONFIG / "authority.pem"
STATE = CONFIG / "authority.json"
HERE = Path(__file__).resolve().parent
TRUSTED = HERE.parent / "android-app" / "res" / "raw" / "trusted_keys.txt"


# ---------------------------------------------------------------- packets
def encode(kind, sender, message_id, body):
    if len(body) > 12:
        raise ValueError("body longer than 12 bytes")
    return HEADER.pack(b"BT", 1, kind, sender, message_id) + body


def message(sender, message_id, text):
    return b"QM1" + struct.pack(">II", sender, message_id) + text.encode("utf-8")


def fragments(sender, message_id, raw_sig):
    out = []
    for i in range(FRAGMENTS):
        chunk = raw_sig[i * CHUNK:(i + 1) * CHUNK]
        out.append(encode(SIG, sender, message_id, bytes([(i << 4) | FRAGMENTS]) + chunk))
    return out


# ---------------------------------------------------------------- crypto via openssl
def openssl(*args, data=None):
    r = subprocess.run(["openssl", *args], input=data, capture_output=True)
    if r.returncode != 0:
        raise RuntimeError(f"openssl {' '.join(args)}: {r.stderr.decode(errors='replace').strip()}")
    return r.stdout


def der_to_raw(der):
    if der[0] != 0x30:
        raise ValueError("not DER")
    pos = 2 if der[1] < 0x80 else 2 + (der[1] & 0x7F)
    out = b""
    for _ in range(2):
        assert der[pos] == 0x02
        length = der[pos + 1]
        value = int.from_bytes(der[pos + 2:pos + 2 + length], "big")
        out += value.to_bytes(32, "big")
        pos += 2 + length
    return out


def raw_to_der(raw):
    def integer(b):
        v = int.from_bytes(b, "big").to_bytes(33, "big").lstrip(b"\x00") or b"\x00"
        if v[0] & 0x80:
            v = b"\x00" + v
        return b"\x02" + bytes([len(v)]) + v
    body = integer(raw[:32]) + integer(raw[32:])
    return b"\x30" + bytes([len(body)]) + body


def sign(text, sender, message_id, key=KEY):
    der = openssl("dgst", "-sha256", "-sign", str(key), "-binary", data=message(sender, message_id, text))
    return der_to_raw(der)


def public_der(key=KEY):
    return openssl("pkey", "-in", str(key), "-pubout", "-outform", "DER")


def verify(public_key_der, text, sender, message_id, raw_sig):
    with tempfile.TemporaryDirectory() as tmp:
        pub, sig = Path(tmp, "pub.der"), Path(tmp, "sig.der")
        pub.write_bytes(public_key_der)
        sig.write_bytes(raw_to_der(raw_sig))
        r = subprocess.run(["openssl", "dgst", "-sha256", "-verify", str(pub), "-keyform", "DER",
                            "-signature", str(sig)], input=message(sender, message_id, text), capture_output=True)
        return r.returncode == 0


def load_trusted(path=TRUSTED):
    keys = []
    if path.exists():
        for line in path.read_text().splitlines():
            line = line.strip()
            if line and not line.startswith("#"):
                parts = line.split()
                keys.append((parts[0], base64.b64decode(parts[-1])))
    return keys


# ---------------------------------------------------------------- state
def load_state():
    if STATE.exists():
        return json.loads(STATE.read_text())
    return {"sender": secrets.randbits(32), "last_id": 0}


def save_state(state):
    STATE.write_text(json.dumps(state))
    os.chmod(STATE, 0o600)


def next_id(state):
    # Increasing counter: phones reject any id <= the last one they accepted (replay protection).
    # Unix time keeps it increasing across reinstalls of this tool.
    return max(int(time.time()) & 0xFFFFFFFF, state["last_id"] + 1) & 0xFFFFFFFF


# ---------------------------------------------------------------- commands
def cmd_keygen(args):
    if KEY.exists() and not args.force:
        sys.exit(f"{KEY} already exists. Use --force to replace it (phones must then trust the new key).")
    CONFIG.mkdir(parents=True, exist_ok=True)
    os.chmod(CONFIG, 0o700)
    pem = openssl("ecparam", "-name", "prime256v1", "-genkey", "-noout")
    KEY.write_bytes(pem)
    os.chmod(KEY, 0o600)
    print(f"Private key: {KEY} (keep secret, never share)")
    cmd_pubkey(args)


def cmd_pubkey(args):
    if not KEY.exists():
        sys.exit("No key yet. Run: quake_authority.py keygen")
    name = args.name or "laptop-authority"
    print("Add this line to android-app/res/raw/trusted_keys.txt and rebuild the app:")
    print(f"{name} {base64.b64encode(public_der()).decode()}")


def cmd_selftest(_args):
    with tempfile.TemporaryDirectory() as tmp:
        key = Path(tmp, "k.pem")
        key.write_bytes(openssl("ecparam", "-name", "prime256v1", "-genkey", "-noout"))
        pub = openssl("pkey", "-in", str(key), "-pubout", "-outform", "DER")
        for i in range(50):
            sender, mid, text = secrets.randbits(32), secrets.randbits(32), "EQA:4.8:120"
            raw = sign(text, sender, mid, key)
            assert len(raw) == 64 and raw_to_der(raw) and der_to_raw(raw_to_der(raw)) == raw
            assert verify(pub, text, sender, mid, raw)
            assert not verify(pub, "EQW", sender, mid, raw), "tampered text must fail"
            assert not verify(pub, text, sender, mid + 1, raw), "changed id must fail"
            frags = fragments(sender, mid, raw)
            assert len(frags) == FRAGMENTS and all(len(f) <= 24 for f in frags)
            assert b"".join(f[13:] for f in frags) == raw
    print("selftest OK: 50 sign/verify round trips, tamper checks, 6 fragments of <= 24 bytes")


def bluez():
    import dbus
    import dbus.service
    from dbus.mainloop.glib import DBusGMainLoop
    from gi.repository import GLib
    DBusGMainLoop(set_as_default=True)
    return dbus, dbus.service, GLib


def adapter_path(bus, dbus):
    objects = dbus.Interface(bus.get_object("org.bluez", "/"), "org.freedesktop.DBus.ObjectManager").GetManagedObjects()
    for path, ifaces in objects.items():
        if "org.bluez.Adapter1" in ifaces and "org.bluez.LEAdvertisingManager1" in ifaces:
            if not ifaces["org.bluez.Adapter1"].get("Powered", False):
                sys.exit("Bluetooth is off: bluetoothctl power on")
            return path
    sys.exit("No BLE advertising adapter found (bluetoothctl show)")


def broadcast(packets, seconds):
    """Advertise all packets at the same time (one BlueZ instance each) for `seconds`."""
    dbus, service, GLib = bluez()

    class Advertisement(service.Object):
        def __init__(self, bus, path, data):
            super().__init__(bus, path)
            self.path, self.data = dbus.ObjectPath(path), data

        @service.method("org.freedesktop.DBus.Properties", in_signature="s", out_signature="a{sv}")
        def GetAll(self, interface):
            return {"Type": dbus.String("broadcast"), "MinInterval": dbus.UInt32(100), "MaxInterval": dbus.UInt32(100),
                    "ManufacturerData": dbus.Dictionary({dbus.UInt16(COMPANY): dbus.Array(self.data, signature="y")},
                                                        signature="qv")}

        @service.method("org.bluez.LEAdvertisement1", in_signature="", out_signature="")
        def Release(self):
            pass

    bus = dbus.SystemBus()
    manager = dbus.Interface(bus.get_object("org.bluez", adapter_path(bus, dbus)), "org.bluez.LEAdvertisingManager1")
    loop = GLib.MainLoop()
    ads, failures = [], []
    for i, raw in enumerate(packets):
        ad = Advertisement(bus, f"/dev/blx/quakemesh/authority{os.getpid()}_{i}", raw)
        ads.append(ad)
        manager.RegisterAdvertisement(ad.path, {}, reply_handler=lambda: None,
                                      error_handler=lambda e: (failures.append(str(e)), loop.quit()))
    GLib.timeout_add(int(seconds * 1000), loop.quit)
    loop.run()
    for ad in ads:
        try:
            manager.UnregisterAdvertisement(ad.path)
        except dbus.DBusException:
            pass
        ad.remove_from_connection()
    if failures:
        sys.exit(f"Broadcast failed: {failures[0]} (this adapter needs {len(packets)} advertising instances)")


def cmd_send(args):
    if not KEY.exists():
        sys.exit("No key yet. Run: quake_authority.py keygen")
    state = load_state()
    if args.replay:
        if "last_packets" not in state:
            sys.exit("Nothing sent yet.")
        packets = [bytes.fromhex(p) for p in state["last_packets"]]
        print(f"REPLAY of {state['last_text']!r} id {state['last_id']:08X} (phones should reject it)")
    else:
        text = args.code.strip()
        code = text.split(":")[0].upper()
        if len(text.encode()) > 11:
            sys.exit("Alert text must be at most 11 bytes (1 byte is the hop limit)")
        if code not in AUTHORITY_CODES:
            print(f"Note: {code} is a community code; phones accept it unsigned, but it is signed anyway.")
        mid = next_id(state)
        raw = sign(text, state["sender"], mid)
        hops = max(0, min(MAX_TTL, args.hops))
        packets = [encode(TEST, state["sender"], mid, text.encode() + bytes([hops + 1]))] + fragments(state["sender"], mid, raw)
        state.update(last_id=mid, last_text=text, last_packets=[p.hex() for p in packets])
        save_state(state)
        print(f"SIGNED {text!r} · sender {state['sender']:08X} · id {mid:08X} · {hops} hops · 1 alert + {FRAGMENTS} signature packets")
    broadcast(packets, args.seconds)
    print(f"Broadcast for {args.seconds:g} s.")


def cmd_listen(args):
    trusted = load_trusted(Path(args.trusted))
    if not trusted:
        sys.exit(f"No trusted keys in {args.trusted}")
    dbus, _service, GLib = bluez()
    bus = dbus.SystemBus()
    path = adapter_path(bus, dbus)
    adapter = dbus.Interface(bus.get_object("org.bluez", path), "org.bluez.Adapter1")
    alerts, sigs, done = {}, {}, set()

    def check(key):
        if key in done or key not in alerts or len(sigs.get(key, {})) < FRAGMENTS:
            return
        done.add(key)
        sender, mid = key
        raw = b"".join(sigs[key][i] for i in range(FRAGMENTS))
        who = next((name for name, pub in trusted if verify(pub, alerts[key], sender, mid, raw)), None)
        print(f"{'VERIFIED by ' + who if who else 'INVALID signature'}: {alerts[key]!r} sender {sender:08X} id {mid:08X}",
              flush=True)

    def data(props):
        raw = props.get("ManufacturerData", {}).get(dbus.UInt16(COMPANY))
        if raw is None:
            return
        raw = bytes(raw)
        if len(raw) < 12 or raw[:3] != b"BT\x01":
            return
        _, _, kind, sender, mid = HEADER.unpack(raw[:12])
        key, body = (sender, mid), raw[12:]
        if kind == TEST and key not in alerts:
            hops = body[-1] - 1 if body and 1 <= body[-1] <= MAX_TTL + 1 else None
            alerts[key] = (body[:-1] if hops is not None else body).decode("utf-8", "replace").strip()
            print(f"RX alert {alerts[key]!r} sender {sender:08X} id {mid:08X} hops left {hops}", flush=True)
        elif kind == SIG and body:
            index = body[0] >> 4
            if args.verbose and index not in sigs.get(key, {}):
                print(f"  signature piece {index + 1}/{FRAGMENTS} for {sender:08X}/{mid:08X}", flush=True)
            sigs.setdefault(key, {})[index] = body[1:]
        check(key)

    bus.add_signal_receiver(lambda p, i: "org.bluez.Device1" in i and data(i["org.bluez.Device1"]),
                            dbus_interface="org.freedesktop.DBus.ObjectManager", signal_name="InterfacesAdded")
    bus.add_signal_receiver(lambda iface, changed, inv, path=None: iface == "org.bluez.Device1" and data(changed),
                            dbus_interface="org.freedesktop.DBus.Properties", signal_name="PropertiesChanged",
                            path_keyword="path")
    adapter.SetDiscoveryFilter({"Transport": dbus.String("le"), "DuplicateData": dbus.Boolean(True)})
    adapter.StartDiscovery()
    print(f"Listening for signed alerts for {args.seconds:g} s ({len(trusted)} trusted keys)...", flush=True)
    loop = GLib.MainLoop()
    GLib.timeout_add(int(args.seconds * 1000), loop.quit)
    loop.run()
    try:
        adapter.StopDiscovery()
    except dbus.DBusException:
        pass


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    k = sub.add_parser("keygen"); k.add_argument("--force", action="store_true"); k.add_argument("--name")
    pk = sub.add_parser("pubkey"); pk.add_argument("--name")
    s = sub.add_parser("send"); s.add_argument("code", nargs="?"); s.add_argument("--seconds", type=float, default=5)
    s.add_argument("--replay", action="store_true")
    s.add_argument("--hops", type=int, default=DEFAULT_TTL, help="relay hop limit 0-15 (0 = phones do not relay)")
    li = sub.add_parser("listen"); li.add_argument("--seconds", type=float, default=30)
    li.add_argument("--trusted", default=str(TRUSTED))
    li.add_argument("--verbose", action="store_true", help="print each signature piece")
    sub.add_parser("selftest")
    args = p.parse_args()
    if args.cmd == "send" and not args.replay and not args.code:
        p.error("send needs an alert code, e.g. send CXL")
    {"keygen": cmd_keygen, "pubkey": cmd_pubkey, "send": cmd_send, "listen": cmd_listen,
     "selftest": cmd_selftest}[args.cmd](args)


if __name__ == "__main__":
    main()

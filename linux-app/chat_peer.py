#!/usr/bin/env python3
"""Laptop peer for BT Probe nearby chat (BC/2 fast format, also reads BC/1).

  python3 chat_peer.py listen [--seconds 60]           # show messages live as packets arrive, send ACKs
  python3 chat_peer.py send "Hello from the laptop"     # send a text message, wait for ACKs
  python3 chat_peer.py send "Hi" --name Laptop --seconds 10

Uses system BlueZ over D-Bus (no pairing, no GATT). Wire format: CHAT.md / ChatProtocol.java.
Timings printed are milliseconds since the command started.
"""
import argparse
import secrets
import struct
import sys
import time
import zlib

import dbus
import dbus.service
from dbus.mainloop.glib import DBusGMainLoop
from gi.repository import GLib

COMPANY = 0xFFFF
DATA, ACK, HELLO = 1, 2, 3
TEXT, JPEG = 1, 2
T0 = time.monotonic()


def ms():
    return int((time.monotonic() - T0) * 1000)


# ---------------------------------------------------------------- wire format
def envelope(name, text):
    label, content = name.encode(), text.encode()
    crc = zlib.crc32(bytes([TEXT]) + label + content) & 0xFFFFFFFF
    return struct.pack(">BBII", TEXT, len(label), len(content), crc) + label + content


def v2_packet(kind, sender, tid, index, count, body):
    packed = (index << 12) | count
    return b"BC" + bytes([0x20 | kind]) + struct.pack(">IH", sender, tid) + packed.to_bytes(3, "big") + body


def fragments_v2(sender, tid, env):
    count = (len(env) + 11) // 12
    return [v2_packet(DATA, sender, tid, i, count, env[i * 12:(i + 1) * 12]) for i in range(count)]


def ack(data, sender, window, bitmap):
    body = struct.pack(">Ii", data["sender"], bitmap if bitmap < 2**31 else bitmap - 2**32)
    if data["version"] == 2:
        return v2_packet(ACK, sender, data["id"], window, data["count"], body)
    return b"BC\x01" + bytes([ACK]) + struct.pack(">IIHH", sender, data["id"], window, data["count"]) + body


def decode(raw):
    if len(raw) < 13 or raw[:2] != b"BC":
        return None
    if raw[2] >> 4 == 2:
        kind = raw[2] & 15
        sender, tid = struct.unpack(">IH", raw[3:9])
        packed = int.from_bytes(raw[9:12], "big")
        return dict(version=2, kind=kind, sender=sender, id=tid, index=packed >> 12, count=packed & 4095, body=raw[12:])
    if raw[2] == 1 and len(raw) > 16:
        kind, sender, tid, index, count = struct.unpack(">BIIHH", raw[3:16])
        return dict(version=1, kind=kind, sender=sender, id=tid, index=index, count=count, body=raw[16:])
    return None


def utf8_prefix(b):
    for cut in range(len(b), max(-1, len(b) - 4), -1):
        try:
            return b[:cut].decode()
        except UnicodeDecodeError:
            continue
    return ""


# ---------------------------------------------------------------- BlueZ
def setup():
    DBusGMainLoop(set_as_default=True)
    bus = dbus.SystemBus()
    om = dbus.Interface(bus.get_object("org.bluez", "/"), "org.freedesktop.DBus.ObjectManager")
    for path, ifaces in om.GetManagedObjects().items():
        if "org.bluez.LEAdvertisingManager1" in ifaces:
            obj = bus.get_object("org.bluez", path)
            return bus, dbus.Interface(obj, "org.bluez.Adapter1"), dbus.Interface(obj, "org.bluez.LEAdvertisingManager1")
    sys.exit("No BLE advertising adapter (bluetoothctl show)")


class Advertiser:
    """Each payload is its own BlueZ advertisement, so several go out in parallel."""
    count = 0

    def __init__(self, bus, manager):
        self.bus, self.manager, self.active = bus, manager, {}

    def add(self, raw):
        Advertiser.count += 1
        path = f"/dev/blx/chatpeer/{Advertiser.count}"
        data = raw

        class Ad(dbus.service.Object):
            @dbus.service.method("org.freedesktop.DBus.Properties", in_signature="s", out_signature="a{sv}")
            def GetAll(self, interface):
                return {"Type": dbus.String("broadcast"), "MinInterval": dbus.UInt32(100), "MaxInterval": dbus.UInt32(100),
                        "ManufacturerData": dbus.Dictionary({dbus.UInt16(COMPANY): dbus.Array(data, signature="y")}, signature="qv")}

            @dbus.service.method("org.bluez.LEAdvertisement1", in_signature="", out_signature="")
            def Release(self):
                pass
        ad = Ad(self.bus, path)
        self.active[path] = ad
        self.manager.RegisterAdvertisement(dbus.ObjectPath(path), {}, reply_handler=lambda: None,
                                           error_handler=lambda e: print(f"{ms():6d}  advertise failed: {e}", flush=True))
        return path

    def remove(self, path):
        ad = self.active.pop(path, None)
        if ad:
            try:
                self.manager.UnregisterAdvertisement(dbus.ObjectPath(path))
            except dbus.DBusException:
                pass
            ad.remove_from_connection()

    def clear(self):
        for path in list(self.active):
            self.remove(path)


def listen_signals(bus, handler):
    def data(props):
        raw = props.get("ManufacturerData", {}).get(dbus.UInt16(COMPANY))
        if raw is not None:
            handler(bytes(raw))
    bus.add_signal_receiver(lambda p, i: "org.bluez.Device1" in i and data(i["org.bluez.Device1"]),
                            dbus_interface="org.freedesktop.DBus.ObjectManager", signal_name="InterfacesAdded")
    bus.add_signal_receiver(lambda iface, changed, inv, path=None: iface == "org.bluez.Device1" and data(changed),
                            dbus_interface="org.freedesktop.DBus.Properties", signal_name="PropertiesChanged", path_keyword="path")


# ---------------------------------------------------------------- commands
def cmd_listen(args):
    bus, adapter, manager = setup()
    adv = Advertiser(bus, manager)
    me = secrets.randbits(32)
    transfers, done = {}, set()

    def on_raw(raw):
        p = decode(raw)
        if not p or p["kind"] != DATA or p["sender"] == me:
            return
        key = (p["sender"], p["id"])
        t = transfers.setdefault(key, {"parts": {}, "first": ms(), "count": p["count"], "shown": None})
        new = p["index"] not in t["parts"]
        t["parts"][p["index"]] = p["body"]
        window = p["index"] // 32 * 32
        bitmap = 0
        for i in range(32):
            if window + i < p["count"] and (window + i) in t["parts"]:
                bitmap |= 1 << i
        if key not in done:
            prefix = b""
            for i in range(t["count"]):
                if i not in t["parts"]:
                    break
                prefix += t["parts"][i]
            text, name = "", ""
            if len(prefix) >= 10:
                n = prefix[1]
                if len(prefix) >= 10 + n:
                    name = prefix[10:10 + n].decode(errors="replace")
                    text = utf8_prefix(prefix[10 + n:])
            if new and text != t["shown"]:
                t["shown"] = text
                print(f"{ms():6d}  live  {name or '?'}: {text}▍  ({len(t['parts'])}/{t['count']} packets)", flush=True)
            if len(t["parts"]) == t["count"]:
                done.add(key)
                body = b"".join(t["parts"][i] for i in range(t["count"]))
                typ, n, length, crc = struct.unpack(">BBII", body[:10])
                content = body[10 + n:10 + n + length]
                ok = zlib.crc32(bytes([typ]) + body[10:10 + n] + content) & 0xFFFFFFFF == crc
                print(f"{ms():6d}  DONE  {body[10:10 + n].decode(errors='replace')} [{p['sender']:08X}] BC/{p['version']}: "
                      f"{content.decode(errors='replace') if typ == TEXT else f'<image {length} bytes>'}  "
                      f"· {t['count']} packets in {ms() - t['first']} ms · CRC {'ok' if ok else 'BAD'}", flush=True)
        # ACK (a short-lived advert); repeated packets refresh it.
        path = adv.add(ack(p, me, window, bitmap))
        GLib.timeout_add(600, lambda: adv.remove(path) or False)

    listen_signals(bus, on_raw)
    adapter.SetDiscoveryFilter({"Transport": dbus.String("le"), "DuplicateData": dbus.Boolean(True)})
    adapter.StartDiscovery()
    print(f"Listening for chat as {me:08X} for {args.seconds:g} s…", flush=True)
    loop = GLib.MainLoop()
    GLib.timeout_add(int(args.seconds * 1000), loop.quit)
    loop.run()
    adv.clear()


def cmd_send(args):
    bus, adapter, manager = setup()
    adv = Advertiser(bus, manager)
    me, tid = secrets.randbits(32), secrets.randbits(16)
    env = envelope(args.name, args.text)
    frags = fragments_v2(me, tid, env)
    acked = {}

    def on_raw(raw):
        p = decode(raw)
        if p and p["kind"] == ACK and p["id"] == tid and struct.unpack(">I", p["body"][:4])[0] == me:
            bitmap = struct.unpack(">I", p["body"][4:8])[0]
            got = acked.setdefault(p["sender"], set())
            before = len(got)
            got.update(p["index"] + i for i in range(32) if bitmap >> i & 1 and p["index"] + i < len(frags))
            if len(got) == len(frags) and before < len(frags):
                print(f"{ms():6d}  DELIVERED to {p['sender']:08X} · all {len(frags)} packets acknowledged", flush=True)
                if args.until_acked:
                    loop.quit()

    listen_signals(bus, on_raw)
    adapter.SetDiscoveryFilter({"Transport": dbus.String("le"), "DuplicateData": dbus.Boolean(True)})
    adapter.StartDiscovery()
    print(f"{ms():6d}  SEND  {args.name} [{me:08X}] {args.text!r} · {len(env)} bytes · {len(frags)} BC/2 packets", flush=True)
    # BlueZ can run several advertisements at once: up to 8 fragments go out in parallel.
    # Longer messages cycle through batches of 8 (700 ms each) until acknowledged or timed out.
    batch = 8
    batches = [frags[i:i + batch] for i in range(0, len(frags), batch)]
    state = {"k": 0, "paths": []}

    def next_batch():
        for x in state["paths"]:
            adv.remove(x)
        state["paths"] = [adv.add(f) for f in batches[state["k"] % len(batches)]]
        state["k"] += 1
        return len(batches) > 1  # a single batch simply stays on air
    next_batch()
    if len(batches) > 1:
        GLib.timeout_add(700, next_batch)
    loop = GLib.MainLoop()
    GLib.timeout_add(int(args.seconds * 1000), loop.quit)
    loop.run()
    adv.clear()
    if not any(len(v) == len(frags) for v in acked.values()):
        print(f"{ms():6d}  no complete acknowledgment", flush=True)


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    li = sub.add_parser("listen"); li.add_argument("--seconds", type=float, default=60)
    se = sub.add_parser("send"); se.add_argument("text"); se.add_argument("--name", default="Laptop")
    se.add_argument("--seconds", type=float, default=8); se.add_argument("--until-acked", action="store_true")
    args = p.parse_args()
    if args.cmd == "send" and len(args.text.encode()) > 4096:
        p.error("max 4096 bytes")
    {"listen": cmd_listen, "send": cmd_send}[args.cmd](args)


if __name__ == "__main__":
    main()

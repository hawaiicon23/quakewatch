#!/usr/bin/env python3
"""Linux BLE broadcast sender / receiver. System BlueZ; no pairing or GATT."""
import argparse
from collections import OrderedDict, deque
import datetime
import json
from pathlib import Path
import queue
import secrets
import signal
import sys
import threading
import time

import dbus
import dbus.service
from dbus.mainloop.glib import DBusGMainLoop
from gi.repository import GLib, GLibUnix

from protocol import COMPANY, TEST, ACK, BEACON, test_packet, ack_packet, beacon_packet, decode, RELAY_TEXT, RELAY_ACK, MAX_HOPS, RelayPolicy, relay_packet, relay_ack

BLUEZ = "org.bluez"
PROPS = "org.freedesktop.DBus.Properties"
ADAPTER = "org.bluez.Adapter1"
DEVICE = "org.bluez.Device1"
ADV_MANAGER = "org.bluez.LEAdvertisingManager1"
ADV = "org.bluez.LEAdvertisement1"
OM = "org.freedesktop.DBus.ObjectManager"


class Advertisement(dbus.service.Object):
    def __init__(self, bus, path, data, tx_power=None):
        super().__init__(bus, path)
        self.path = dbus.ObjectPath(path)
        self.data = data
        self.tx_power = tx_power

    @dbus.service.method(PROPS, in_signature="s", out_signature="a{sv}")
    def GetAll(self, interface):
        if interface != ADV:
            raise dbus.exceptions.DBusException("Invalid interface")
        properties = {"Type": dbus.String("broadcast"), "MinInterval": dbus.UInt32(50), "MaxInterval": dbus.UInt32(50), "ManufacturerData": dbus.Dictionary(
            {dbus.UInt16(COMPANY): dbus.Array(self.data, signature="y")}, signature="qv")}
        if self.tx_power is not None:
            properties["TxPower"] = dbus.Int16(self.tx_power)
        return properties

    @dbus.service.method(ADV, in_signature="", out_signature="")
    def Release(self):
        pass


class Probe:
    def __init__(self, emit, adapter=None, auto_ack=True):
        self.emit = emit
        self.adapter_name = adapter
        self.auto_ack = auto_ack
        self.sender = secrets.randbits(32)
        self.running = False
        self.discovery = False
        self.closing = False
        self.ad = None
        self.timer = None
        self.queue = deque()
        self.pending = {}
        self.responders = {}
        self.attempts = {}
        self.ack_times = {}
        self.tx_power = None
        self.generation = 0
        self.seen = OrderedDict()
        self.tx = self.rx = self.acks = 0
        self.counter = 0
        self.bus = None
        self.signal_matches = []
        self.continuous = False
        self.beacon_timer = None
        self.label = "Linux"
        self.peers = OrderedDict()
        self.beacon_count = 0
        self.relay_enabled = False
        self.target = 0
        self.relay_policy = RelayPolicy()
        self.queued_relays = 0

    def set_continuous(self, enabled):
        self.continuous = bool(enabled)
        if self.beacon_timer is not None:
            GLib.source_remove(self.beacon_timer)
            self.beacon_timer = None
        if self.continuous and self.running:
            self.beacon_timer = GLib.timeout_add(500, self._beacon_tick)
            self._beacon_tick()
        self.log("Continuous test ON · ~0.5 s" if enabled else "Continuous test OFF")
        return False

    def _beacon_tick(self):
        if not self.running or not self.continuous:
            self.beacon_timer = None
            return False
        if self.ad is None and not self.queue:
            self.queue.append(beacon_packet(self.sender, secrets.randbits(32), self.label))
            self._pump()
        return True

    def log(self, message, **extra):
        self.emit({"time": datetime.datetime.now().strftime("%H:%M:%S"), "message": message,
                   "sender": f"{self.sender:08X}", "tx": self.tx, "rx": self.rx, "acks": self.acks, **extra})

    def start(self):
        if self.running:
            return False
        try:
            self.bus = dbus.SystemBus(private=True)
            objects = dbus.Interface(self.bus.get_object(BLUEZ, "/"), OM).GetManagedObjects()
            candidates = [p for p, v in objects.items() if ADAPTER in v and ADV_MANAGER in v
                          and (not self.adapter_name or str(p).endswith("/" + self.adapter_name))]
            if not candidates:
                raise RuntimeError("No adapter supports BLE advertising. Check bluetoothctl show.")
            self.path = candidates[0]
            props = objects[self.path][ADAPTER]
            if not props.get("Powered", False):
                raise RuntimeError("Bluetooth is off. Turn it on with bluetoothctl power on.")
            obj = self.bus.get_object(BLUEZ, self.path)
            self.adapter = dbus.Interface(obj, ADAPTER)
            self.manager = dbus.Interface(obj, ADV_MANAGER)
            capabilities = objects[self.path][ADV_MANAGER].get("SupportedCapabilities", {})
            if "MaxTxPower" in capabilities:
                self.tx_power = min(20, int(capabilities["MaxTxPower"]))
            self.signal_matches = [
                self.bus.add_signal_receiver(self._added, dbus_interface=OM, signal_name="InterfacesAdded", bus_name=BLUEZ),
                self.bus.add_signal_receiver(self._changed, dbus_interface=PROPS, signal_name="PropertiesChanged",
                                             bus_name=BLUEZ, path_keyword="path")]
            # No UUID filter: our protocol is carried in manufacturer test data.
            self.adapter.SetDiscoveryFilter({"Transport": dbus.String("le"), "DuplicateData": dbus.Boolean(True)})
            self.adapter.StartDiscovery()
            self.discovery = True
            self.running = True
            self.generation += 1
            self.closing = False
            self.log(f"Listening on {self.path}. No pairing or connection.", state="running")
            if self.continuous: self.set_continuous(True)
        except Exception as exc:
            self.log(f"Cannot start: {exc}", state="error")
            self.stop()
        return False

    def _added(self, path, interfaces):
        if str(path).startswith(str(self.path) + "/") and DEVICE in interfaces:
            self._data(interfaces[DEVICE])

    def _changed(self, interface, changed, invalidated, path=None):
        if interface == DEVICE and path and str(path).startswith(str(self.path) + "/"):
            self._data(changed)

    def _data(self, props):
        if not self.running:
            return
        raw = props.get("ManufacturerData", {}).get(dbus.UInt16(COMPANY))
        if raw is None:
            return
        self.receive(bytes(raw), props.get("RSSI"))

    def receive(self, raw, rssi=None):
        packet = decode(raw)
        if packet is None or packet.sender == self.sender:
            return
        if packet.kind == ACK and (packet.recipient != self.sender or packet.message_id not in self.pending):
            return
        now = time.monotonic()
        if packet.kind in (RELAY_TEXT, RELAY_ACK):
            self._receive_relay(packet, rssi, now)
            return
        peer = self.peers.setdefault(packet.sender, {"id": f"{packet.sender:08X}", "name": "Peer", "samples": 0})
        peer.update(rssi=rssi, last_seen=now)
        if len(self.peers) > 64: self.peers.popitem(last=False)
        if packet.kind == BEACON:
            peer["name"] = packet.text
            if peer.get("sequence") != packet.message_id:
                peer["samples"] += 1
                peer["sequence"] = packet.message_id
                self.log(f"{packet.text} [{packet.sender:08X}] · {rssi} dBm", event="beacon", peers=list(self.peers.values()))
            return
        first = self._first(packet,now)
        if packet.kind == TEST:
            if first:
                self.rx += 1
                strength = f" · {int(rssi)} dBm" if rssi is not None else ""
                self.log(f"RX {packet.text!r} from {packet.sender:08X}{strength}", event="received", text=packet.text)
            if self.auto_ack and len(self.queue) < 16 and now-self.ack_times.get(packet.key, float("-inf")) >= 2:
                self.ack_times[packet.key] = now
                self.queue.appendleft(ack_packet(self.sender, packet.message_id, packet.sender))
                self._pump()
        elif packet.recipient == self.sender:
            started = self.pending.get(packet.message_id)
            peers = self.responders.setdefault(packet.message_id, set())
            if started is not None and now-started < 20 and packet.sender not in peers:
                peers.add(packet.sender)
                self.acks += 1
                self.log(f"Reply from {packet.sender:08X} · {int((now-started)*1000)} ms", event="ack", rssi=rssi)

    def _first(self, packet, now):
        while self.seen and next(iter(self.seen.values())) < now - 60:
            key, _ = self.seen.popitem(last=False)
            self.ack_times.pop(key, None)
        first = packet.key not in self.seen
        if first:
            self.seen[packet.key] = now
            if len(self.seen) > 256:
                key, _ = self.seen.popitem(last=False)
                self.ack_times.pop(key, None)
        return first

    def _receive_relay(self, packet, rssi, now):
        local = packet.recipient == self.sender
        broadcast = packet.recipient == 0
        if packet.kind == RELAY_TEXT and (local or broadcast):
            if self._first(packet,now):
                self.rx += 1
                self.log(f"DELIVERED {packet.text!r} · origin {packet.sender:08X} · {MAX_HOPS-packet.hops} relay(s) · last hop {rssi} dBm", event="delivered", origin=f"{packet.sender:08X}", text=packet.text)
            if self.auto_ack and len(self.queue)+self.queued_relays<16 and now-self.ack_times.get(packet.key,float("-inf"))>=2:
                self.ack_times[packet.key]=now
                self.queue.appendleft(relay_ack(self.sender,packet.message_id,packet.sender))
                self._pump()
        elif packet.kind == RELAY_ACK and local:
            started=self.pending.get(packet.message_id)
            peers=self.responders.setdefault(packet.message_id,set()) if started is not None else set()
            if started is not None and now-started<20 and packet.sender not in peers:
                peers.add(packet.sender); self.acks+=1
                self.log(f"Destination reply {packet.sender:08X} · {int((now-started)*1000)} ms · {MAX_HOPS-packet.hops} relay(s)",event="ack")
        if local or not self.relay_enabled or len(self.queue)+self.queued_relays>=16: return
        raw=self.relay_policy.forward(packet,now)
        if raw is None: return
        generation=self.generation
        self.queued_relays+=1
        def forward():
            if not self.running or generation!=self.generation: return False
            self.queued_relays-=1
            if self.relay_enabled and len(self.queue)<16:
                self.queue.append(raw)
                self.log(f"RELAY origin {packet.sender:08X} → {packet.recipient:08X} · {packet.hops-1} hops left",event="relayed")
                self._pump()
            return False
        GLib.timeout_add(100+secrets.randbelow(251),forward)

    def send(self, text):
        if not self.running:
            self.log("Start Bluetooth first.")
            return False
        try:
            if len(self.queue) >= 16:
                raise ValueError("Send queue full. Wait and retry.")
            self.queue.append(relay_packet(self.sender,secrets.randbits(32),self.target,text) if self.relay_enabled else test_packet(self.sender, secrets.randbits(32), text))
            self._pump()
        except Exception as exc:
            self.log(str(exc), event="send_error")
        return False

    def _pump(self):
        if not self.running or self.ad is not None or not self.queue:
            return
        raw = self.queue.popleft()
        packet = decode(raw)
        if packet.kind in (TEST,RELAY_TEXT) and packet.sender==self.sender and self.responders.get(packet.message_id):
            self._pump()  # A reply arrived while this retry was queued.
            return
        self.counter += 1
        ad = Advertisement(self.bus, f"/dev/blx/btprobe/advertisement{self.counter}", raw, self.tx_power)
        self.ad = ad

        def success():
            if self.ad is not ad or not self.running:
                return
            if packet.kind in (TEST,RELAY_TEXT) and packet.sender==self.sender:
                attempt = self.attempts.get(packet.message_id, 0) + 1
                self.attempts[packet.message_id] = attempt
                if attempt == 1:
                    self.tx += 1
                    self.pending[packet.message_id] = time.monotonic()
                    self.responders[packet.message_id] = set()
                    self.log(f"TX {packet.text!r} · collecting replies from all peers", event="sent", text=packet.text)
                    GLib.timeout_add(20000, self._timeout, packet.message_id, self.generation)
                else:
                    self.log(f"Retry {packet.text!r} · attempt {attempt}", event="retry")
                if attempt < 3:
                    GLib.timeout_add(5000, self._retry, raw, self.generation)
            elif packet.kind in (ACK,RELAY_ACK):
                self.log("TX acknowledgment", event="ack_sent")
            elif packet.kind == BEACON:
                self.beacon_count += 1
            else:
                self.log("TX forwarded message",event="relay_sent")
            # Leave time for BlueZ unregister/register work before the next 500 ms tick.
            self.timer = GLib.timeout_add(250 if self.continuous else (500 if packet.kind in (RELAY_TEXT,RELAY_ACK) else 3000), self._finish)

        def failure(error):
            if self.ad is ad:
                self.log(f"Broadcast failed: {error}", event="send_error")
                ad.remove_from_connection()
                self.ad = None
                self._pump()

        self.manager.RegisterAdvertisement(ad.path, {}, reply_handler=success, error_handler=failure)

    def _retry(self, raw, generation):
        packet = decode(raw)
        started = self.pending.get(packet.message_id)
        if (self.running and generation == self.generation and started is not None
                and time.monotonic()-started < 20 and not self.responders.get(packet.message_id)
                and self.attempts.get(packet.message_id, 0) < 3 and len(self.queue) < 16):
            self.queue.append(raw)
            self._pump()
        return False

    def _timeout(self, message_id, generation=None):
        if generation is not None and generation != self.generation:
            return False
        if self.pending.pop(message_id, None) is not None:
            peers = self.responders.pop(message_id, set())
            if not peers:
                self.log("No reply after 20 seconds and up to 3 broadcasts.", event="timeout")
        self.attempts.pop(message_id, None)
        # Do not revive an expired message if its retry was waiting behind other traffic.
        self.queue = deque(raw for raw in self.queue if not
                           (decode(raw).kind in (TEST,RELAY_TEXT) and decode(raw).sender==self.sender and decode(raw).message_id == message_id))
        return False

    def _finish(self):
        self.timer = None
        ad = self.ad
        if ad is None:
            return False

        def done(*args):
            ad.remove_from_connection()
            if self.ad is ad:
                self.ad = None
            self._pump()

        self.manager.UnregisterAdvertisement(ad.path, reply_handler=done, error_handler=lambda e: done())
        return False

    def stop(self):
        if self.beacon_timer is not None:
            GLib.source_remove(self.beacon_timer)
            self.beacon_timer = None
        self.continuous = False
        was_running = self.running
        self.running = False
        self.closing = True
        self.generation += 1
        self.relay_policy.sent.clear()
        self.queued_relays=0
        self.pending.clear()
        self.responders.clear()
        self.attempts.clear()
        self.ack_times.clear()
        self.queue.clear()
        self.seen.clear()
        if self.timer:
            GLib.source_remove(self.timer)
            self.timer = None
        if self.ad:
            try:
                self.manager.UnregisterAdvertisement(self.ad.path)
            except dbus.DBusException:
                pass
            self.ad.remove_from_connection()
            self.ad = None
        if self.discovery:
            try:
                self.adapter.StopDiscovery()
            except dbus.DBusException:
                pass
            self.discovery = False
        for match in self.signal_matches:
            match.remove()
        self.signal_matches.clear()
        if self.bus:
            self.bus.close()  # Releases this client's discovery filter and advertisements.
            self.bus = None
        if was_running:
            self.log("Bluetooth test stopped.", state="stopped")
        return False


def gui(probe, events, loop):
    import tkinter as tk
    from tkinter import ttk
    root = tk.Tk()
    root.title("BT Probe · Offline Bluetooth test")
    root.geometry("680x720")
    root.configure(bg="#101c22")
    style = ttk.Style(root)
    style.theme_use("clam")
    style.configure("TButton", font=("sans", 12), padding=12)
    page = tk.Frame(root, bg="#101c22", padx=28, pady=24)
    page.pack(fill="both", expand=True)
    def label(text, size, color="#edf5f5"):
        w = tk.Label(page, text=text, font=("sans", size), fg=color, bg="#101c22", anchor="w")
        w.pack(fill="x", pady=(0,8))
        return w
    label("NEARBY / OFFLINE", 11, "#79e3c0")
    label("BT Probe", 32)
    label("Send a signal. See it arrive.", 14, "#9aafb8")
    status = label(f"Device {probe.sender:08X} · Stopped", 14, "#79e3c0")
    controls = tk.Frame(page, bg="#101c22")
    controls.pack(fill="x", pady=12)
    ttk.Button(controls, text="Start Bluetooth", command=lambda: GLib.idle_add(probe.start)).pack(side="left", padx=(0,8))
    ttk.Button(controls, text="Stop", command=lambda: GLib.idle_add(probe.stop)).pack(side="left")
    name = tk.StringVar(value=probe.label)
    tk.Entry(page,textvariable=name).pack(fill="x")
    continuous = tk.BooleanVar(value=False)
    def change_continuous():
        desired=continuous.get(); value=name.get().strip()
        try: beacon_packet(probe.sender,0,value)
        except ValueError as exc:
            probe.log(str(exc)); continuous.set(False); return
        def update():
            probe.label=value
            if hasattr(probe,"identity_path"):
                probe.identity_path.write_text(json.dumps({"id":probe.sender,"name":value}))
            if desired and not probe.running: probe.start()
            return probe.set_continuous(desired)
        GLib.idle_add(update)
    tk.Checkbutton(page,text="Continuous test · ~0.5 s",variable=continuous,command=change_continuous,
                   bg="#101c22",fg="white",selectcolor="#1b2c34").pack(anchor="w")
    peer_label=label("No continuous-test peers yet.",12,"#79e3c0")
    text = tk.StringVar(value="TEST")
    tk.Entry(page, textvariable=text, font=("sans",22), bg="#1b2c34", fg="white", insertbackground="white").pack(fill="x", pady=10, ipady=10)
    ttk.Button(page, text="Broadcast test", command=lambda: send_from_ui()).pack(fill="x")
    label("Up to 12 UTF-8 bytes · public test data", 11, "#9aafb8")
    relay = tk.BooleanVar(value=probe.relay_enabled)
    target = tk.StringVar(value=f"{probe.target:08X}" if probe.target else "")
    tk.Entry(page,textvariable=target).pack(fill="x")
    label("Relay destination ID · blank = all · messages up to 8 UTF-8 bytes",10,"#9aafb8")
    def update_relay():
        value=target.get().strip()
        try:
            if value and (len(value)!=8 or any(c not in "0123456789abcdefABCDEF" for c in value)): raise ValueError("Use an 8-digit device ID")
            recipient=int(value,16) if value else 0
        except ValueError as exc:
            probe.log(str(exc)); relay.set(False)
            GLib.idle_add(lambda:setattr(probe,"relay_enabled",False) or False)
            return
        enabled=relay.get()
        def apply(): probe.relay_enabled=enabled; probe.target=recipient; return False
        GLib.idle_add(apply)
    tk.Checkbutton(page,text="Enable relay mode · up to 3 relays",variable=relay,command=update_relay,bg="#101c22",fg="white",selectcolor="#1b2c34").pack(anchor="w")
    target.trace_add("write",lambda *args:update_relay() if len(target.get()) in (0,8) else None)
    def send_from_ui():
        value=target.get().strip(); enabled=relay.get(); content=text.get()
        if enabled and value and (len(value)!=8 or any(c not in "0123456789abcdefABCDEF" for c in value)):
            probe.log("Use an 8-digit destination ID, or leave blank for all."); return
        recipient=int(value,16) if enabled and value else 0
        def transmit():
            probe.relay_enabled=enabled; probe.target=recipient
            return probe.send(content)
        GLib.idle_add(transmit)
    auto_ack = tk.BooleanVar(value=True)
    def set_ack():
        value = auto_ack.get()
        def update():
            probe.auto_ack = value
            return False
        GLib.idle_add(update)
    tk.Checkbutton(page,text="Reply to incoming tests automatically",variable=auto_ack,command=set_ack,
                   bg="#101c22",fg="white",selectcolor="#1b2c34",activebackground="#101c22",activeforeground="white").pack(anchor="w")
    counts = label("0 sent     0 received     0 replies", 13, "#79e3c0")
    log = tk.Text(page, bg="#1b2c34", fg="#edf5f5", font=("monospace",11), wrap="word", relief="flat", padx=14, pady=14, height=5)
    log.pack(fill="both", expand=True, pady=10)
    log.configure(state="disabled")
    label("No pairing or internet. Received text never executes commands.", 11, "#9aafb8")
    def poll():
        if not loop.is_running():
            root.destroy()
            return
        while True:
            try: event = events.get_nowait()
            except queue.Empty: break
            counts.configure(text=f"{event['tx']} sent     {event['rx']} received     {event['acks']} replies")
            if "state" in event:
                status.configure(text=f"Device {probe.sender:08X} · {event['state'].capitalize()}")
                if event["state"] == "stopped": continuous.set(False)
            if "peers" in event:
                peer_label.configure(text="\n".join(f"{p['name']} [{p['id']}] · {p['rssi']} dBm · {p['samples']} samples" for p in event["peers"]))
            if event.get("event") == "beacon": continue
            log.configure(state="normal")
            log.insert("1.0",f"{event['time']}  {event['message']}\n\n")
            if int(log.index("end-1c").split(".")[0]) > 250:
                log.delete("250.0","end")
            log.configure(state="disabled")
        root.after(100,poll)
    def close():
        def finish():
            probe.stop()
            loop.quit()
            return False
        GLib.idle_add(finish)
        root.destroy()
    root.protocol("WM_DELETE_WINDOW",close)
    poll()
    root.mainloop()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gui", action="store_true", help="Open desktop controls")
    parser.add_argument("--adapter", help="Adapter name, e.g. hci0")
    parser.add_argument("--send", help="Broadcast once after starting, then keep receiving")
    parser.add_argument("--no-auto-ack", action="store_true")
    parser.add_argument("--duration", type=int, help="Stop after N seconds")
    parser.add_argument("--json", action="store_true", help="Machine-readable events")
    parser.add_argument("--continuous", action="store_true", help="Continuously broadcast test beacons at approximately 0.5 s")
    parser.add_argument("--name", help="Device label (1–12 UTF-8 bytes)")
    parser.add_argument("--relay", action="store_true", help="Enable bounded message/ACK relaying")
    parser.add_argument("--to", default="00000000", help="Relay destination device ID; 00000000 broadcasts to all")
    args = parser.parse_args()
    DBusGMainLoop(set_as_default=True)
    loop = GLib.MainLoop()
    events = queue.Queue()
    def emit(event):
        if args.gui:
            events.put(event)
        else:
            print(json.dumps(event) if args.json else f"{event['time']}  {event['message']}", flush=True)
    probe = Probe(emit,args.adapter,not args.no_auto_ack)
    identity_path=Path.home()/".config"/"bt-probe"/"identity.json"
    probe.identity_path=identity_path
    try:
        identity=json.loads(identity_path.read_text())
        probe.sender=int(identity["id"]) & 0xffffffff
    except (OSError,ValueError,KeyError,TypeError):
        identity={"id":probe.sender}
    probe.label=args.name or identity.get("name",f"Linux-{probe.sender & 65535:04X}")
    beacon_packet(probe.sender,0,probe.label)
    identity.update(id=probe.sender,name=probe.label)
    identity_path.parent.mkdir(parents=True,exist_ok=True)
    identity_path.write_text(json.dumps(identity))
    identity_path.chmod(0o600)
    probe.continuous=args.continuous
    probe.relay_enabled=args.relay
    if len(args.to)!=8 or any(c not in "0123456789abcdefABCDEF" for c in args.to): parser.error("--to needs an 8-digit hexadecimal device ID")
    probe.target=int(args.to,16)
    def shutdown(*_):
        probe.stop()
        loop.quit()
        return False
    if args.duration:
        GLib.timeout_add_seconds(args.duration,shutdown)
    GLibUnix.signal_add(GLib.PRIORITY_DEFAULT,signal.SIGINT,shutdown)
    GLibUnix.signal_add(GLib.PRIORITY_DEFAULT,signal.SIGTERM,shutdown)
    if args.gui:
        thread = threading.Thread(target=loop.run,daemon=True)
        thread.start()
        gui(probe,events,loop)
        thread.join(timeout=5)
    else:
        def start():
            probe.start()
            if not probe.running:
                loop.quit()
            elif args.send is not None:
                GLib.timeout_add(800,probe.send,args.send)
            return False
        GLib.idle_add(start)
        loop.run()


if __name__ == "__main__":
    main()

"""BT Probe v1: compact, public, non-executable BLE test messages."""
import struct
from dataclasses import dataclass

COMPANY = 0xFFFF
PREFIX = b"BT\x01"
TEST, ACK, BEACON, RELAY_TEXT, RELAY_ACK = 1, 2, 3, 4, 5
MAX_HOPS = 3
MAX_TEXT = 12
HEADER = struct.Struct(">2sBBII")


@dataclass(frozen=True)
class Packet:
    kind: int
    sender: int
    message_id: int
    body: bytes
    hops: int = 0

    @property
    def key(self):
        return self.kind, self.sender, self.message_id

    @property
    def text(self):
        return self.body[4 if self.kind == RELAY_TEXT else 0:].decode("utf-8", errors="replace")

    @property
    def recipient(self):
        return struct.unpack(">I", self.body[:4])[0]


def encode(kind, sender, message_id, body, hops=0):
    relay = kind in (RELAY_TEXT, RELAY_ACK)
    if not 0 <= hops <= MAX_HOPS or (not relay and hops) or (kind == RELAY_TEXT and len(body)<5) or (kind == RELAY_ACK and len(body)!=4):
        raise ValueError("Invalid relay packet")
    if kind not in (TEST, ACK, BEACON, RELAY_TEXT, RELAY_ACK) or len(body) > MAX_TEXT or (kind == ACK and len(body) != 4):
        raise ValueError("Invalid packet")
    return HEADER.pack(b"BT", 1, kind | (hops << 4), sender, message_id) + body


def test_packet(sender, message_id, text):
    body = text.encode("utf-8")
    if not body or len(body) > MAX_TEXT:
        raise ValueError("Enter 1–12 UTF-8 bytes")
    return encode(TEST, sender, message_id, body)


def ack_packet(sender, message_id, recipient):
    return encode(ACK, sender, message_id, struct.pack(">I", recipient))


def beacon_packet(sender, sequence, label):
    body = label.encode("utf-8")
    if not body or len(body) > MAX_TEXT:
        raise ValueError("Device name must use 1–12 UTF-8 bytes")
    return encode(BEACON, sender, sequence, body)


def relay_packet(sender, message_id, recipient, text):
    body = text.encode("utf-8")
    if not 1 <= len(body) <= 8: raise ValueError("Relay messages use 1–8 UTF-8 bytes")
    return encode(RELAY_TEXT, sender, message_id, struct.pack(">I",recipient)+body, MAX_HOPS)


def relay_ack(sender, message_id, recipient):
    return encode(RELAY_ACK, sender, message_id, struct.pack(">I",recipient), MAX_HOPS)


def decode(data):
    data = bytes(data)
    if not HEADER.size <= len(data) <= HEADER.size + MAX_TEXT:
        return None
    magic, version, kind, sender, message_id = HEADER.unpack(data[:HEADER.size])
    hops, kind = kind >> 4, kind & 15
    relay = kind in (RELAY_TEXT, RELAY_ACK)
    if hops > MAX_HOPS or (not relay and hops): return None
    if magic != b"BT" or version != 1 or kind not in (TEST, ACK, BEACON, RELAY_TEXT, RELAY_ACK):
        return None
    body = data[HEADER.size:]
    if kind in (ACK, RELAY_ACK) and len(body) != 4:
        return None
    if kind == RELAY_TEXT and len(body)<5: return None
    return Packet(kind, sender, message_id, body, hops)


class RelayPolicy:
    def __init__(self):
        from collections import OrderedDict
        self.sent = OrderedDict()

    def forward(self, packet, now):
        if packet.kind not in (RELAY_TEXT, RELAY_ACK) or packet.hops == 0: return None
        for key in list(self.sent):
            if now-self.sent[key][0]>120: del self.sent[key]
        previous=self.sent.get(packet.key)
        if previous and (packet.hops<previous[2] or now-previous[1]<5 or previous[3]>=3): return None
        self.sent[packet.key]=(previous[0] if previous else now, now, packet.hops, previous[3]+1 if previous else 1)
        if len(self.sent)>512: self.sent.popitem(last=False)
        return encode(packet.kind,packet.sender,packet.message_id,packet.body,packet.hops-1)

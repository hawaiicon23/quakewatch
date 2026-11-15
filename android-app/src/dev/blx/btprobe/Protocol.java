package dev.blx.btprobe;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class Protocol {
    public static final int COMPANY = 0xffff; // Reserved test identifier; not a production company ID.
    // SIG (6): one fragment of an earthquake-alert authority signature, see SignedAlerts.
    public static final int TEST = 1, ACK = 2, BEACON = 3, RELAY_TEXT = 4, RELAY_ACK = 5, SIG = 6, SENSOR = 7, MAX_HOPS = 3, HEADER = 12, MAX_TEXT = 12;
    /**
     * Alert hop limit: alert codes (TEST packets) may end with one byte 0x01..0x10 = hops left 0..15.
     * It is outside the authority signature so relays can count it down. Alerts without it count as
     * ALERT_DEFAULT_HOPS. Separate from the 3-hop RELAY_TEXT mechanism, whose 8-byte body is too small for alerts.
     */
    public static final int ALERT_MAX_HOPS = 15, ALERT_DEFAULT_HOPS = 10;
    public static int alertHops(byte[] body) {
        if (body.length > 0) { int b = body[body.length - 1] & 255; if (b >= 1 && b <= ALERT_MAX_HOPS + 1) return b - 1; }
        return -1;
    }
    public static String alertText(byte[] body) {
        return new String(body, 0, alertHops(body) >= 0 ? body.length - 1 : body.length, StandardCharsets.UTF_8);
    }
    /** Same TEST packet with its alert hop byte set (added if missing). Throws if the text leaves no room. */
    public static byte[] withAlertHops(byte[] packet, int hops) {
        Packet p = decode(packet);
        byte[] text = alertText(p.body).getBytes(StandardCharsets.UTF_8);
        byte[] body = Arrays.copyOf(text, text.length + 1);
        body[text.length] = (byte)(Math.max(0, Math.min(ALERT_MAX_HOPS, hops)) + 1);
        return encode(p.kind, p.sender, p.id, body);
    }
    public static final byte[] PREFIX = {'B', 'T', 1};
    public static byte[] encode(int kind, int sender, int id, byte[] body) {
        return encode(kind,sender,id,body,0);
    }
    public static byte[] encode(int kind,int sender,int id,byte[] body,int hops) {
        boolean relay=kind==RELAY_TEXT || kind==RELAY_ACK;
        if(hops<0 || hops>MAX_HOPS || (!relay && hops!=0) || (kind==RELAY_TEXT && body.length<5) || (kind==RELAY_ACK && body.length!=4)) throw new IllegalArgumentException("Invalid relay packet");
        if ((kind != RELAY_TEXT && kind != RELAY_ACK && kind != TEST && kind != ACK && kind != BEACON && kind != SIG && kind != SENSOR) || body.length > MAX_TEXT || (kind == ACK && body.length != 4))
            throw new IllegalArgumentException("Invalid packet");
        if(kind==SENSOR && body.length!=12) throw new IllegalArgumentException("Invalid sensor packet");
        return ByteBuffer.allocate(HEADER + body.length).put(PREFIX).put((byte)(kind|(hops<<4)))
            .putInt(sender).putInt(id).put(body).array();
    }
    public static byte[] text(int sender, int id, String text) {
        return encode(TEST, sender, id, text.getBytes(StandardCharsets.UTF_8));
    }
    public static byte[] ack(int sender, int id, int recipient) {
        return encode(ACK, sender, id, ByteBuffer.allocate(4).putInt(recipient).array());
    }
    public static byte[] beacon(int sender,int sequence,String label) {
        return encode(BEACON,sender,sequence,label.getBytes(StandardCharsets.UTF_8));
    }
    public static byte[] relayText(int sender,int id,int recipient,String text) {
        byte[] bytes=text.getBytes(StandardCharsets.UTF_8);
        if(bytes.length<1 || bytes.length>8) throw new IllegalArgumentException("Relay messages use 1–8 UTF-8 bytes");
        return encode(RELAY_TEXT,sender,id,ByteBuffer.allocate(4+bytes.length).putInt(recipient).put(bytes).array(),MAX_HOPS);
    }
    public static byte[] relayAck(int sender,int id,int recipient) {
        return encode(RELAY_ACK,sender,id,ByteBuffer.allocate(4).putInt(recipient).array(),MAX_HOPS);
    }
    public static Packet decode(byte[] data) {
        if (data == null || data.length < HEADER || data.length > HEADER + MAX_TEXT ||
            data[0] != 'B' || data[1] != 'T' || data[2] != 1) return null;
        int kind = data[3] & 15; int hops=(data[3]&255)>>>4;
        boolean relay=kind==RELAY_TEXT || kind==RELAY_ACK;
        if(hops>MAX_HOPS || (!relay && hops!=0) || (kind==RELAY_TEXT && data.length<HEADER+5) || (kind==RELAY_ACK && data.length!=HEADER+4)) return null;
        if ((kind != TEST && kind != ACK && kind != BEACON && kind != RELAY_TEXT && kind != RELAY_ACK && kind != SIG && kind != SENSOR) || (kind == ACK && data.length != HEADER + 4)) return null;
        if(kind==SENSOR && data.length!=HEADER+12) return null;
        ByteBuffer b = ByteBuffer.wrap(data); b.position(4);
        return new Packet(kind, b.getInt(), b.getInt(), Arrays.copyOfRange(data, HEADER, data.length),hops);
    }
    public static final class Packet {
        public final int kind, sender, id, hops; public final byte[] body;
        Packet(int k, int s, int i, byte[] b,int h) { kind=k; sender=s; id=i; body=b; hops=h; }
        public String text() { return new String(body,kind==RELAY_TEXT?4:0,body.length-(kind==RELAY_TEXT?4:0),StandardCharsets.UTF_8); }
        public boolean relay() { return kind==RELAY_TEXT || kind==RELAY_ACK; }
        public boolean message() { return kind==TEST || kind==RELAY_TEXT; }
        public int recipient() { return ByteBuffer.wrap(body).getInt(); }
        public String key() { return kind + ":" + sender + ":" + id; }
        /** Text of a TEST packet without the optional alert hop byte. */
        public String alertText() { return Protocol.alertText(body).trim(); }
        public int alertHops() { return Protocol.alertHops(body); }
    }
}

package dev.blx.btprobe;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class Protocol {
    public static final int COMPANY = 0xffff; // Reserved test identifier; not a production company ID.
    // SIG (Quake Mesh): one fragment of an authority signature; see SignedAlerts. BT Probe ignores it.
    public static final int TEST = 1, ACK = 2, BEACON = 3, SIG = 4, HEADER = 12, MAX_TEXT = 12;
    public static final byte[] PREFIX = {'B', 'T', 1};
    /**
     * Hop limit for relayed alerts (Quake Mesh). Optional last body byte 0x01..0x10 = hops left 0..15.
     * It is NOT covered by authority signatures, so relays can count it down; alerts without it
     * (BT Probe, Linux app, older builds) are treated as DEFAULT_TTL. Alert text is therefore max 11 bytes.
     */
    public static final int MAX_TTL = 15, DEFAULT_TTL = 10;
    public static int ttl(byte[] body) {
        if (body.length > 0) { int b = body[body.length - 1] & 255; if (b >= 1 && b <= MAX_TTL + 1) return b - 1; }
        return -1;
    }
    public static String alertText(byte[] body) {
        return new String(body, 0, ttl(body) >= 0 ? body.length - 1 : body.length, StandardCharsets.UTF_8);
    }
    /** Same packet with its hop byte set (added if missing). Throws if the text leaves no room. */
    public static byte[] withTtl(byte[] packet, int ttl) {
        Packet p = decode(packet);
        byte[] text = alertText(p.body).getBytes(StandardCharsets.UTF_8);
        byte[] body = Arrays.copyOf(text, text.length + 1);
        body[text.length] = (byte)(Math.max(0, Math.min(MAX_TTL, ttl)) + 1);
        return encode(p.kind, p.sender, p.id, body);
    }
    public static byte[] encode(int kind, int sender, int id, byte[] body) {
        if ((kind != TEST && kind != ACK && kind != BEACON && kind != SIG) || body.length > MAX_TEXT || (kind == ACK && body.length != 4))
            throw new IllegalArgumentException("Invalid packet");
        return ByteBuffer.allocate(HEADER + body.length).put(PREFIX).put((byte)kind)
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
    public static Packet decode(byte[] data) {
        if (data == null || data.length < HEADER || data.length > HEADER + MAX_TEXT ||
            data[0] != 'B' || data[1] != 'T' || data[2] != 1) return null;
        int kind = data[3] & 255;
        if ((kind != TEST && kind != ACK && kind != BEACON && kind != SIG) || (kind == ACK && data.length != HEADER + 4)) return null;
        ByteBuffer b = ByteBuffer.wrap(data); b.position(4);
        return new Packet(kind, b.getInt(), b.getInt(), Arrays.copyOfRange(data, HEADER, data.length));
    }
    public static final class Packet {
        public final int kind, sender, id; public final byte[] body;
        Packet(int k, int s, int i, byte[] b) { kind=k; sender=s; id=i; body=b; }
        public String text() { return new String(body, StandardCharsets.UTF_8); }
        /** Text without the optional hop-limit byte. */
        public String alertText() { return Protocol.alertText(body).trim(); }
        /** Hops left, or -1 if the sender didn't set a limit. */
        public int ttl() { return Protocol.ttl(body); }
        public int recipient() { return ByteBuffer.wrap(body).getInt(); }
        public String key() { return kind + ":" + sender + ":" + id; }
    }
}

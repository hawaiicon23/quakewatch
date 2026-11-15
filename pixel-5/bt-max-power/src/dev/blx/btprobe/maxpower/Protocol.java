package dev.blx.btprobe.maxpower;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class Protocol {
    public static final int COMPANY = 0xffff; // Reserved test identifier; not a production company ID.
    public static final int TEST = 1, ACK = 2, BEACON = 3, HEADER = 12, MAX_TEXT = 12;
    public static final byte[] PREFIX = {'B', 'T', 1};
    public static byte[] encode(int kind, int sender, int id, byte[] body) {
        if ((kind != TEST && kind != ACK && kind != BEACON) || body.length > MAX_TEXT || (kind == ACK && body.length != 4))
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
        if ((kind != TEST && kind != ACK && kind != BEACON) || (kind == ACK && data.length != HEADER + 4)) return null;
        ByteBuffer b = ByteBuffer.wrap(data); b.position(4);
        return new Packet(kind, b.getInt(), b.getInt(), Arrays.copyOfRange(data, HEADER, data.length));
    }
    public static final class Packet {
        public final int kind, sender, id; public final byte[] body;
        Packet(int k, int s, int i, byte[] b) { kind=k; sender=s; id=i; body=b; }
        public String text() { return new String(body, StandardCharsets.UTF_8); }
        public int recipient() { return ByteBuffer.wrap(body).getInt(); }
        public String key() { return kind + ":" + sender + ":" + id; }
    }
}

package dev.blx.btprobe;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;

/**
 * Authority signatures for official alerts. Pure Java (no Android APIs) so it can be
 * tested on a desktop JVM against OpenSSL.
 *
 * Signed message = "QM1" | sender (4 bytes BE) | message id (4 bytes BE) | alert text (UTF-8).
 * Signature = ECDSA P-256 / SHA-256, raw r||s (64 bytes), split into 6 SIG packets
 * (Protocol.SIG) that share the alert's sender and message id:
 *   body[0] = index<<4 | total, body[1..] = up to 11 signature bytes.
 * The message id doubles as the authority's increasing counter (replay protection).
 */
public final class SignedAlerts {
    public static final int SIG_BYTES=64, CHUNK=11, FRAGMENTS=(SIG_BYTES+CHUNK-1)/CHUNK; // 6
    /** Codes that must carry a valid authority signature to sound an alarm. */
    public static final Set<String> AUTHORITY_CODES=new HashSet<>(Arrays.asList("EQW","EQN","EQA","AFT","TSU","CLR","CXL"));

    public static boolean needsAuthority(String text) {
        AlertPlayer.Parsed p=AlertPlayer.parse(text);
        return p!=null && AUTHORITY_CODES.contains(p.alert.code);
    }

    public static byte[] message(int sender,int id,String text) {
        byte[] t=text.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(3+4+4+t.length).put(new byte[]{'Q','M','1'}).putInt(sender).putInt(id).put(t).array();
    }

    // ---- Fragments ----
    public static byte[][] fragments(int sender,int id,byte[] rawSignature) {
        if(rawSignature.length!=SIG_BYTES) throw new IllegalArgumentException("signature must be 64 bytes");
        byte[][] out=new byte[FRAGMENTS][];
        for(int i=0;i<FRAGMENTS;i++) {
            int from=i*CHUNK, len=Math.min(CHUNK,SIG_BYTES-from);
            byte[] body=new byte[1+len]; body[0]=(byte)((i<<4)|FRAGMENTS);
            System.arraycopy(rawSignature,from,body,1,len);
            out[i]=Protocol.encode(Protocol.SIG,sender,id,body);
        }
        return out;
    }

    /** Collects the 6 signature fragments for one alert (sender + id). */
    public static final class Collector {
        private final byte[][] chunks=new byte[FRAGMENTS][];
        final byte[][] raw=new byte[FRAGMENTS][];
        private int have;
        /** Returns true if this fragment was new. */
        public boolean add(Protocol.Packet p,byte[] rawPacket) {
            if(p.body.length<2) return false;
            int index=(p.body[0]>>4)&15, total=p.body[0]&15;
            if(total!=FRAGMENTS || index>=FRAGMENTS || chunks[index]!=null) return false;
            int expected=Math.min(CHUNK,SIG_BYTES-index*CHUNK);
            if(p.body.length-1!=expected) return false;
            chunks[index]=Arrays.copyOfRange(p.body,1,p.body.length); raw[index]=rawPacket.clone(); have++;
            return true;
        }
        public boolean complete() { return have==FRAGMENTS; }
        public byte[] signature() {
            ByteArrayOutputStream o=new ByteArrayOutputStream();
            for(byte[] c:chunks) o.write(c,0,c.length);
            return o.toByteArray();
        }
    }

    // ---- ECDSA encoding: raw r||s <-> DER (what java.security and OpenSSL use) ----
    public static byte[] rawToDer(byte[] raw) {
        byte[] r=derInt(Arrays.copyOfRange(raw,0,32)), s=derInt(Arrays.copyOfRange(raw,32,64));
        ByteArrayOutputStream o=new ByteArrayOutputStream();
        o.write(0x30); o.write(r.length+s.length); o.write(r,0,r.length); o.write(s,0,s.length);
        return o.toByteArray();
    }
    private static byte[] derInt(byte[] v) {
        byte[] b=new BigInteger(1,v).toByteArray(); // minimal two's complement, positive
        byte[] out=new byte[2+b.length]; out[0]=0x02; out[1]=(byte)b.length; System.arraycopy(b,0,out,2,b.length); return out;
    }
    public static byte[] derToRaw(byte[] der) {
        if(der.length<8 || der[0]!=0x30) throw new IllegalArgumentException("not a DER ECDSA signature");
        int pos=2; if((der[1]&0x80)!=0) pos=2+(der[1]&0x7f);
        byte[] out=new byte[64];
        for(int k=0;k<2;k++) {
            if(der[pos]!=0x02) throw new IllegalArgumentException("bad DER integer");
            int len=der[pos+1]&0xff; byte[] v=Arrays.copyOfRange(der,pos+2,pos+2+len);
            byte[] n=new BigInteger(1,v).toByteArray();
            int start=n.length>32?n.length-32:0, copy=n.length-start;
            if(copy>32) throw new IllegalArgumentException("integer too long");
            System.arraycopy(n,start,out,k*32+32-copy,copy);
            pos+=2+len;
        }
        return out;
    }

    // ---- Trusted authority keys ----
    public static final class Authority {
        public final String name; public final PublicKey key;
        Authority(String n,PublicKey k) { name=n; key=k; }
    }
    /** Parses lines of "name base64(SubjectPublicKeyInfo DER)"; '#' starts a comment. */
    public static List<Authority> parseTrusted(String text) {
        List<Authority> out=new ArrayList<>();
        for(String line:text.split("\n")) {
            line=line.trim(); if(line.isEmpty() || line.startsWith("#")) continue;
            String[] parts=line.split("\\s+");
            if(parts.length<2) continue;
            try {
                byte[] der=Base64.getDecoder().decode(parts[parts.length-1]);
                out.add(new Authority(parts[0],KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(der))));
            } catch(Exception e) { /* skip malformed key */ }
        }
        return out;
    }
    /** Returns the authority whose key verifies this alert, or null. */
    public static Authority verify(List<Authority> trusted,int sender,int id,String text,byte[] rawSignature) {
        byte[] msg=message(sender,id,text), der;
        try { der=rawToDer(rawSignature); } catch(RuntimeException e) { return null; }
        for(Authority a:trusted) {
            try {
                Signature v=Signature.getInstance("SHA256withECDSA"); v.initVerify(a.key); v.update(msg);
                if(v.verify(der)) return a;
            } catch(GeneralSecurityException ignored) {}
        }
        return null;
    }
    public static String fingerprint(PublicKey key) {
        try {
            byte[] h=MessageDigest.getInstance("SHA-256").digest(key.getEncoded());
            return String.format(Locale.US,"%02X%02X%02X%02X",h[0],h[1],h[2],h[3]);
        } catch(NoSuchAlgorithmException e) { return "????"; }
    }
}

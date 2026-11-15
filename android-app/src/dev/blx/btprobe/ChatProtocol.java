package dev.blx.btprobe;

import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import java.util.zip.CRC32;

/**
 * A separate wire format: legacy BT/1 test and relay packets remain unchanged.
 * Version 1 (BC/1): 16-byte header, 8 content bytes per packet. Still decoded for older builds.
 * Version 2 (BC/2, sent by this build): 12-byte header, 12 content bytes per packet (50% more per
 * broadcast): 'B','C', version<<4|kind, sender (4), 16-bit transfer ID, 12-bit index + 12-bit count
 * (3 bytes). Same envelope, CRC, ACK bitmap and Inbox/Outgoing logic as version 1. BC/1 readers
 * reject it (byte 2 is not 1).
 */
public final class ChatProtocol {
    public static final byte[] PREFIX={'B','C',1};
    public static final int HEADER2=12, CHUNK2=12, MAX_FRAGMENTS2=4095;
    public static final int DATA=1,ACK=2,HELLO=3,IMAGE_HELLO=4,LINK_HELLO=5,TEXT=1,JPEG=2,JXL=3,HEADER=16,CHUNK=8,MAX_TEXT=4096,MAX_IMAGE=16384;
    public static final int MAX_ENVELOPE=MAX_IMAGE+22,MAX_FRAGMENTS=(MAX_ENVELOPE+CHUNK-1)/CHUNK;
    private static String utf8(byte[] bytes) {
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch(CharacterCodingException e) { throw new IllegalArgumentException("Invalid UTF-8",e); }
    }
    public static final class Message {
        public final int type; public final String name; public final byte[] content;
        Message(int type,String name,byte[] content) { this.type=type; this.name=name; this.content=content; }
        public String text() { return utf8(content); }
    }
    public static byte[] envelope(int type,String name,byte[] content) {
        byte[] label=name.getBytes(StandardCharsets.UTF_8);
        if(label.length<1 || label.length>12 || name.trim().isEmpty() || name.matches("(?s).*\\p{Cntrl}.*") || content.length==0 || content.length>(type==TEXT?MAX_TEXT:MAX_IMAGE) || (type!=TEXT && type!=JPEG && type!=JXL)) throw new IllegalArgumentException("Invalid chat content");
        if(type==TEXT) utf8(content);
        CRC32 crc=new CRC32(); crc.update(type); crc.update(label); crc.update(content);
        return ByteBuffer.allocate(10+label.length+content.length).put((byte)type).put((byte)label.length).putInt(content.length).putInt((int)crc.getValue()).put(label).put(content).array();
    }
    public static Message message(byte[] bytes) {
        if(bytes==null || bytes.length<11 || bytes.length>MAX_ENVELOPE) throw new IllegalArgumentException("Invalid envelope length");
        ByteBuffer b=ByteBuffer.wrap(bytes); int type=b.get()&255, n=b.get()&255, len=b.getInt(), checksum=b.getInt();
        if(n<1 || n>12 || len<1 || len>(type==TEXT?MAX_TEXT:MAX_IMAGE) || (type!=TEXT && type!=JPEG && type!=JXL) || bytes.length!=10+n+len) throw new IllegalArgumentException("Invalid envelope metadata");
        byte[] label=new byte[n],content=new byte[len]; b.get(label); b.get(content);
        String name=utf8(label);
        if(name.trim().isEmpty() || name.matches("(?s).*\\p{Cntrl}.*")) throw new IllegalArgumentException("Invalid name");
        CRC32 crc=new CRC32(); crc.update(type); crc.update(label); crc.update(content);
        if((int)crc.getValue()!=checksum) throw new IllegalArgumentException("Checksum mismatch");
        if(type==TEXT) utf8(content);
        return new Message(type,name,content);
    }
    public static final class Packet {
        public final int kind,sender,id,index,count,version; public final byte[] body;
        Packet(int k,int s,int id,int i,int n,byte[] body) { this(k,s,id,i,n,body,1); }
        Packet(int k,int s,int id,int i,int n,byte[] body,int v) { kind=k; sender=s; this.id=id; index=i; count=n; this.body=body; version=v; }
        public int target() { return ByteBuffer.wrap(body).getInt(); }
        public int bitmap() { return ByteBuffer.wrap(body).getInt(4); }
    }
    private static byte[] packet(int kind,int sender,int id,int index,int count,byte[] body) {
        return ByteBuffer.allocate(HEADER+body.length).put(PREFIX).put((byte)kind).putInt(sender).putInt(id).putShort((short)index).putShort((short)count).put(body).array();
    }
    public static List<byte[]> fragment(int sender,int id,byte[] envelope) {
        message(envelope); List<byte[]> result=new ArrayList<>(); int count=(envelope.length+CHUNK-1)/CHUNK;
        for(int i=0;i<count;i++) result.add(packet(DATA,sender,id,i,count,Arrays.copyOfRange(envelope,i*CHUNK,Math.min(envelope.length,(i+1)*CHUNK))));
        return result;
    }
    public static byte[] ack(int sender,int id,int target,int start,int count,int bitmap) {
        if(count<1 || count>MAX_FRAGMENTS || start<0 || start>=count || start%32!=0) throw new IllegalArgumentException("Invalid ACK window");
        return packet(ACK,sender,id,start,count,ByteBuffer.allocate(8).putInt(target).putInt(bitmap).array());
    }
    public static byte[] hello(int sender,int sequence) { return packet(HELLO,sender,sequence,0,1,new byte[]{1}); }
    /** Dedicated connectable image beacon; old clients safely ignore the new kind. */
    public static byte[] imageHello(int sender,int psm) {
        if(psm<1||psm>65535)throw new IllegalArgumentException("Invalid image PSM");
        return packet2(IMAGE_HELLO,sender,0,0,1,new byte[]{(byte)(psm>>>8),(byte)psm});
    }
    public static byte[] linkHello(int sender,int imagePsm,int dataPsm) {
        if(imagePsm<1||imagePsm>65535||dataPsm<1||dataPsm>65535)throw new IllegalArgumentException("Invalid PSM");
        return packet2(LINK_HELLO,sender,0,0,1,ByteBuffer.allocate(4).putShort((short)imagePsm).putShort((short)dataPsm).array());
    }
    // ---- Version 2 ----
    private static byte[] packet2(int kind,int sender,int id,int index,int count,byte[] body) {
        if(id<0 || id>65535 || index<0 || index>=count || count<1 || count>MAX_FRAGMENTS2) throw new IllegalArgumentException("Invalid v2 packet");
        int packed=(index<<12)|count;
        return ByteBuffer.allocate(HEADER2+body.length).put((byte)'B').put((byte)'C').put((byte)(0x20|kind)).putInt(sender).putShort((short)id)
            .put((byte)(packed>>>16)).put((byte)(packed>>>8)).put((byte)packed).put(body).array();
    }
    /** Version 2 fragments, sent in order so receivers can show text as it arrives. id must be 0..65535. */
    public static List<byte[]> fragment2(int sender,int id,byte[] envelope) {
        message(envelope); List<byte[]> result=new ArrayList<>(); int count=(envelope.length+CHUNK2-1)/CHUNK2;
        for(int i=0;i<count;i++) result.add(packet2(DATA,sender,id,i,count,Arrays.copyOfRange(envelope,i*CHUNK2,Math.min(envelope.length,(i+1)*CHUNK2))));
        return result;
    }
    public static byte[] ack2(int sender,int id,int target,int start,int count,int bitmap) {
        if(count<1 || count>MAX_FRAGMENTS2 || start<0 || start>=count || start%32!=0) throw new IllegalArgumentException("Invalid ACK window");
        return packet2(ACK,sender,id,start,count,ByteBuffer.allocate(8).putInt(target).putInt(bitmap).array());
    }
    /** ACK in the same version as the data packet being acknowledged. */
    public static byte[] ackFor(Packet data,int sender,int start,int bitmap) {
        return data.version==2?ack2(sender,data.id,data.sender,start,data.count,bitmap):ack(sender,data.id,data.sender,start,data.count,bitmap);
    }
    private static Packet decode2(byte[] bytes) {
        if(bytes.length<=HEADER2 || bytes.length>24) return null;
        ByteBuffer b=ByteBuffer.wrap(bytes); int kind=bytes[2]&15; b.position(3); int sender=b.getInt(),id=b.getShort()&65535;
        int packed=((b.get()&255)<<16)|((b.get()&255)<<8)|(b.get()&255), index=packed>>>12, count=packed&4095;
        if(count<1 || index>=count || (kind!=DATA && kind!=ACK && kind!=IMAGE_HELLO && kind!=LINK_HELLO)) return null;
        if(kind==IMAGE_HELLO && (index!=0||count!=1||bytes.length!=HEADER2+2||((bytes[HEADER2]&255)==0&&(bytes[HEADER2+1]&255)==0)))return null;
        if(kind==LINK_HELLO && (index!=0||count!=1||bytes.length!=HEADER2+4||ByteBuffer.wrap(bytes,HEADER2,4).getShort()==0||ByteBuffer.wrap(bytes,HEADER2+2,2).getShort()==0))return null;
        if(kind==ACK && (bytes.length!=HEADER2+8 || index%32!=0)) return null;
        if(kind==DATA && index<count-1 && bytes.length!=HEADER2+CHUNK2) return null;
        return new Packet(kind,sender,id,index,count,Arrays.copyOfRange(bytes,HEADER2,bytes.length),2);
    }
    public static Packet decode(byte[] bytes) {
        if(bytes!=null && bytes.length>3 && bytes[0]=='B' && bytes[1]=='C' && (bytes[2]&0xF0)==0x20) return decode2(bytes);
        if(bytes==null || bytes.length<=HEADER || bytes.length>24 || bytes[0]!='B' || bytes[1]!='C' || bytes[2]!=1) return null;
        ByteBuffer b=ByteBuffer.wrap(bytes); b.position(3); int kind=b.get()&255,sender=b.getInt(),id=b.getInt(),index=b.getShort()&65535,count=b.getShort()&65535;
        if(count<1 || count>MAX_FRAGMENTS || index>=count || (kind!=DATA && kind!=ACK && kind!=HELLO)) return null;
        if(kind==HELLO && (index!=0 || count!=1 || bytes.length!=HEADER+1 || bytes[HEADER]!=1)) return null;
        if(kind==ACK && (bytes.length!=24 || index%32!=0)) return null;
        if(kind==DATA && index<count-1 && bytes.length!=24) return null;
        return new Packet(kind,sender,id,index,count,Arrays.copyOfRange(bytes,HEADER,bytes.length));
    }
}

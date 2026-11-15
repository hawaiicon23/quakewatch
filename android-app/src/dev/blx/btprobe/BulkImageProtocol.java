package dev.blx.btprobe;

import java.io.*;

/** Bounded, image-only stream framing. The existing envelope supplies content CRC. */
public final class BulkImageProtocol {
    public static final int MAGIC=0x42494d31, ACK=0x42494131;
    public static final class Frame {
        public final int sender,target,id; public final byte[] envelope;
        Frame(int sender,int target,int id,byte[] bytes) {this.sender=sender;this.target=target;this.id=id;envelope=bytes;}
    }
    public static void write(DataOutputStream out,int sender,int target,int id,byte[] envelope) throws IOException {
        image(envelope);
        out.writeInt(MAGIC);out.writeInt(sender);out.writeInt(target);out.writeInt(id);out.writeInt(envelope.length);out.write(envelope);out.flush();
    }
    public static Frame read(DataInputStream in,int target) throws IOException {
        if(in.readInt()!=MAGIC)throw new IOException("Unknown image stream");
        int sender=in.readInt(),recipient=in.readInt(),id=in.readInt(),length=in.readInt();
        if(recipient!=target||sender==target||length<11||length>ChatProtocol.MAX_ENVELOPE)throw new IOException("Invalid image header");
        byte[] bytes=new byte[length];in.readFully(bytes);image(bytes);
        return new Frame(sender,recipient,id,bytes);
    }
    private static void image(byte[] bytes) throws IOException {
        try {if(ChatProtocol.message(bytes).type==ChatProtocol.TEXT)throw new IOException("Text stays on advertising");}
        catch(IllegalArgumentException invalid){throw new IOException("Invalid image envelope",invalid);}
    }
    public static void acknowledge(DataOutputStream out,int receiver,int id,boolean saved) throws IOException {
        out.writeInt(ACK);out.writeInt(receiver);out.writeInt(id);out.writeBoolean(saved);out.flush();
    }
    public static boolean accepted(DataInputStream in,int receiver,int id) throws IOException {
        if(in.readInt()!=ACK||in.readInt()!=receiver||in.readInt()!=id)throw new IOException("Image acknowledgement mismatch");
        return in.readBoolean();
    }
}

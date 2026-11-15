import dev.blx.btprobe.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;

public final class BulkImageProtocolTest {
    private static void check(boolean ok){if(!ok)throw new AssertionError();}
    interface Action{void run()throws Exception;}
    private static void reject(Action action)throws Exception{try{action.run();throw new AssertionError("Invalid stream accepted");}catch(IOException expected){}}
    public static void main(String[] args)throws Exception {
        byte[] image={-1,10,0,1,2,3,4,5};byte[] envelope=ChatProtocol.envelope(ChatProtocol.JXL,"Phone",image);
        ByteArrayOutputStream buffer=new ByteArrayOutputStream();
        BulkImageProtocol.write(new DataOutputStream(buffer),1,2,65535,envelope);
        byte[] wire=buffer.toByteArray();
        BulkImageProtocol.Frame frame=BulkImageProtocol.read(new DataInputStream(new ByteArrayInputStream(wire)),2);
        check(frame.sender==1&&frame.id==65535&&Arrays.equals(frame.envelope,envelope));
        reject(()->BulkImageProtocol.read(new DataInputStream(new ByteArrayInputStream(wire)),3));
        reject(()->BulkImageProtocol.read(new DataInputStream(new ByteArrayInputStream(Arrays.copyOf(wire,wire.length-1))),2));
        byte[] huge=wire.clone();ByteBuffer.wrap(huge).putInt(16,Integer.MAX_VALUE);
        reject(()->BulkImageProtocol.read(new DataInputStream(new ByteArrayInputStream(huge)),2));
        byte[] corrupt=wire.clone();corrupt[corrupt.length-1]^=1;
        reject(()->BulkImageProtocol.read(new DataInputStream(new ByteArrayInputStream(corrupt)),2));
        reject(()->BulkImageProtocol.write(new DataOutputStream(new ByteArrayOutputStream()),1,2,3,ChatProtocol.envelope(ChatProtocol.TEXT,"Phone",new byte[]{65})));
        buffer.reset();BulkImageProtocol.acknowledge(new DataOutputStream(buffer),2,65535,true);
        check(BulkImageProtocol.accepted(new DataInputStream(new ByteArrayInputStream(buffer.toByteArray())),2,65535));
        reject(()->BulkImageProtocol.accepted(new DataInputStream(new ByteArrayInputStream(buffer.toByteArray())),3,65535));
        ChatProtocol.Packet hello=ChatProtocol.decode(ChatProtocol.imageHello(2,255));
        check(hello.kind==ChatProtocol.IMAGE_HELLO&&hello.sender==2&&hello.body[1]==(byte)255);
        ChatTransfers.Outgoing mixed=new ChatTransfers.Outgoing(1,99,envelope,Arrays.asList(2,3),0,2);
        mixed.deliveredViaConnection(2,20000);check(!mixed.complete()&&!mixed.failed(20001));
        mixed.resumeAfterConnection(35000);check(!mixed.failed(35001));
        for(int start=0;start<mixed.frames.size();start+=32)mixed.acknowledge(ChatProtocol.decode(ChatProtocol.ack2(3,99,1,start,mixed.frames.size(),-1)));
        check(mixed.complete()&&mixed.delivered()==2);
        System.out.println("BulkImageProtocol OK: bounded streams, CRC, recipient, ACK, capability, mixed connected/broadcast delivery");
    }
}

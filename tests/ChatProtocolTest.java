import dev.blx.btprobe.ChatProtocol;
import dev.blx.btprobe.ChatTransfers;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class ChatProtocolTest {
    private static int checks;
    private static void check(boolean value,String why) { checks++; if(!value) throw new AssertionError(why); }
    private static void rejects(Runnable f,String why) { try { f.run(); } catch(IllegalArgumentException expected) { checks++; return; } throw new AssertionError(why); }
    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    private static void roundTrip(int type,byte[] content) {
        byte[] body=ChatProtocol.envelope(type,"Píxel-5",content);
        List<byte[]> frames=ChatProtocol.fragment(0xfedcba98,42,body);
        List<byte[]> shuffled=new ArrayList<>(frames); Collections.shuffle(shuffled,new Random(83));
        ChatTransfers.Inbox inbox=new ChatTransfers.Inbox(); ChatProtocol.Message found=null; int messages=0; long now=0;
        for(byte[] raw:shuffled) {
            check(raw.length+7<=31,"Fits legacy advertisement including flags/company framing");
            ChatProtocol.Packet p=ChatProtocol.decode(raw); check(p!=null,"Packet decodes");
            for(int repeat=0;repeat<3;repeat++) {
                ChatTransfers.Reception r=inbox.receive(p,now++);
                if(r.message!=null) { found=r.message; messages++; }
                check((r.bitmap&(1<<(p.index%32)))!=0,"Received fragment is acknowledged");
            }
        }
        check(messages==1,"Duplicates display exactly once");
        check(found!=null && found.name.equals("Píxel-5") && Arrays.equals(found.content,content),"Identity and content survive reordered fragments");
        check(inbox.activeCount()==0,"Completed allocation released");
    }
    private static void lossSimulation(int type,byte[] content,int seed) {
        Random random=new Random(seed); byte[] envelope=ChatProtocol.envelope(type,"Sender",content);
        ChatTransfers.Outgoing out=new ChatTransfers.Outgoing(1,87,envelope,Arrays.asList(2,3),0);
        ChatTransfers.Inbox[] receivers={new ChatTransfers.Inbox(),new ChatTransfers.Inbox()}; int[] received={0,0};
        ArrayList<ChatProtocol.Packet> acks=new ArrayList<>();
        long now=0;
        while(!out.complete() && !out.failed(now) && now<ChatTransfers.EXPIRE_MS) {
            byte[] frame=out.next(now); now+=280;
            if(frame!=null) for(int peer=0;peer<2;peer++) {
                if(random.nextDouble()<.12) continue;
                ChatProtocol.Packet p=ChatProtocol.decode(frame);
                ChatTransfers.Reception r=receivers[peer].receive(p,now);
                if(r.message!=null) { received[peer]++; check(Arrays.equals(content,r.message.content),"Lossy content exact"); }
                if(random.nextDouble()>.25) acks.add(ChatProtocol.decode(ChatProtocol.ack(peer+2,out.id,out.sender,r.window,r.count,r.bitmap)));
            }
            if(!acks.isEmpty() && random.nextBoolean()) {
                Collections.shuffle(acks,random);
                for(ChatProtocol.Packet ack:acks) out.acknowledge(ack); acks.clear();
            }
        }
        check(out.complete() && out.delivered()==2,"Two receivers complete despite packet/ACK loss and reordering (seed "+seed+")");
        check(received[0]==1 && received[1]==1,"One reconstructed message per peer");
    }
    public static void main(String[] args) {
        check(ChatProtocol.decode(ChatProtocol.hello(3,9)).kind==ChatProtocol.HELLO,"Chat capability discovery");
        roundTrip(ChatProtocol.TEXT,bytes("A much longer message across many packets. \ud83d\udcf1 é 中 "+String.join("",Collections.nCopies(80,"text "))));
        byte[] image=new byte[2048]; new Random(123).nextBytes(image); roundTrip(ChatProtocol.JPEG,image); roundTrip(ChatProtocol.JXL,image);
        roundTrip(ChatProtocol.TEXT,new byte[ChatProtocol.MAX_TEXT]);
        for(int seed=0;seed<12;seed++) lossSimulation(ChatProtocol.TEXT,bytes(String.join("",Collections.nCopies(60,"Hello é "))),seed);
        for(int seed=12;seed<16;seed++) lossSimulation(ChatProtocol.JPEG,image,seed);
        for(int seed=20;seed<24;seed++) lossSimulation(ChatProtocol.JXL,image,seed);
        rejects(()->ChatProtocol.envelope(ChatProtocol.JXL,"name",new byte[ChatProtocol.MAX_IMAGE+1]),"Oversized JXL");
        byte[] modern=ChatProtocol.envelope(ChatProtocol.JXL,"Phone",image);
        ChatTransfers.Inbox modernInbox=new ChatTransfers.Inbox();
        ChatProtocol.Message modernResult=null;
        for(byte[] packet:ChatProtocol.fragment2(1,123,modern)) {
            ChatTransfers.Reception received=modernInbox.receive(ChatProtocol.decode(packet),1);
            if(received.message!=null)modernResult=received.message;
            else check(modernInbox.preview(1,123).type==ChatProtocol.JXL,"JXL receiving preview is recognized");
        }
        check(modernResult!=null&&modernResult.type==ChatProtocol.JXL&&Arrays.equals(image,modernResult.content),"JXL survives compact BC/2 transport");
        rejects(()->ChatProtocol.envelope(ChatProtocol.TEXT,"name",new byte[4097]),"Oversized text");
        rejects(()->ChatProtocol.envelope(ChatProtocol.JPEG,"name",new byte[16385]),"Oversized image");
        rejects(()->ChatProtocol.envelope(9,"name",new byte[1]),"Unknown media type");
        rejects(()->ChatProtocol.envelope(ChatProtocol.TEXT,"too-long-device",bytes("hello")),"Oversized name");
        rejects(()->ChatProtocol.envelope(ChatProtocol.TEXT,"bad\nname",bytes("hello")),"Control character name");
        rejects(()->ChatProtocol.envelope(ChatProtocol.TEXT,"name",new byte[]{(byte)0xff}),"Malformed UTF-8");
        byte[] body=ChatProtocol.envelope(ChatProtocol.TEXT,"Phone",bytes("Checksum and bounded receiver test message"));
        byte[] corrupted=body.clone(); corrupted[corrupted.length-1]^=1;
        rejects(()->ChatProtocol.message(corrupted),"Checksum detects corruption");
        ChatTransfers.Inbox inbox=new ChatTransfers.Inbox();
        List<byte[]> frames=ChatProtocol.fragment(10,7,body);
        check(inbox.receive(ChatProtocol.decode(frames.get(0)),0).received==1,"Partial transfer");
        check(inbox.receive(ChatProtocol.decode(frames.get(0)),1).received==1,"Duplicate does not increase progress");
        byte[] conflict=frames.get(0).clone(); conflict[23]^=1;
        check(inbox.receive(ChatProtocol.decode(conflict),2)==null,"Conflicting duplicate rejected");
        byte[] changed=frames.get(1).clone(); ByteBuffer.wrap(changed).putShort(14,(short)(frames.size()+1));
        check(inbox.receive(ChatProtocol.decode(changed),3)==null,"Changing fragment count rejected");
        for(int id=100;id<200;id++) inbox.receive(ChatProtocol.decode(ChatProtocol.fragment(99,id,body).get(0)),4);
        check(inbox.activeCount()==8,"Incomplete transfer memory bounded");
        inbox.receive(ChatProtocol.decode(frames.get(0)),ChatTransfers.EXPIRE_MS+100);
        check(inbox.activeCount()==1,"Stale incomplete transfers expire");
        ChatTransfers.Outgoing out=new ChatTransfers.Outgoing(1,3,body,Arrays.asList(2),0);
        out.acknowledge(ChatProtocol.decode(ChatProtocol.ack(2,3,9,0,out.frames.size(),-1)));
        check(!out.complete() && out.acknowledged()==0,"ACK recipient enforced");
        out.acknowledge(ChatProtocol.decode(ChatProtocol.ack(2,4,1,0,out.frames.size(),-1)));
        check(!out.complete(),"ACK message ID enforced");
        out.acknowledge(ChatProtocol.decode(ChatProtocol.ack(3,3,1,0,out.frames.size(),-1)));
        check(!out.complete(),"Required peer must acknowledge");
        out.acknowledge(ChatProtocol.decode(ChatProtocol.ack(2,3,1,0,out.frames.size(),-1)));
        check(out.complete(),"Matching ACK completes transfer");
        ChatTransfers.Outgoing absent=new ChatTransfers.Outgoing(1,9,body,Collections.emptyList(),0);
        long now=0; while(absent.next(now)!=null) now+=280;
        check(absent.failed(now+4000),"No-peer retries terminate");
        byte[] invalid=frames.get(0).clone(); ByteBuffer.wrap(invalid).putShort(14,(short)65535);
        check(ChatProtocol.decode(invalid)==null,"Unbounded packet allocation rejected");
        Random fuzz=new Random(31);
        for(int i=0;i<10000;i++) { byte[] raw=new byte[fuzz.nextInt(40)]; fuzz.nextBytes(raw); check(ChatProtocol.decode(raw)==null,"Random malformed frame"); }
        System.out.println("ChatProtocol OK · "+checks+" checks");
    }
}

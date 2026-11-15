import dev.blx.btprobe.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Fast chat (BC/2), live previews, alert hop limits and signature fragments. */
public final class MeshMergeTest {
    private static int checks;
    private static void check(boolean value,String why) { checks++; if(!value) throw new AssertionError(why); }
    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    public static void main(String[] args) {
        // ---- BC/2 round trip, reordered + duplicated, all fit a legacy advertisement
        String text="Are you okay? We're at the north gate 🚪 — é 中";
        byte[] env=ChatProtocol.envelope(ChatProtocol.TEXT,"Píxel-5",bytes(text));
        List<byte[]> v2=ChatProtocol.fragment2(0xfedcba98,4242,env), v1=ChatProtocol.fragment(0xfedcba98,4242,env);
        check(v2.size()<v1.size(),"BC/2 needs fewer packets than BC/1 ("+v2.size()+" vs "+v1.size()+")");
        check(v2.size()==(env.length+11)/12,"12 content bytes per BC/2 packet");
        List<byte[]> shuffled=new ArrayList<>(v2); Collections.shuffle(shuffled,new Random(7));
        ChatTransfers.Inbox inbox=new ChatTransfers.Inbox(); ChatProtocol.Message got=null; int n=0;
        for(byte[] raw:shuffled) {
            check(raw.length+7<=31,"BC/2 fits a legacy advertisement");
            ChatProtocol.Packet p=ChatProtocol.decode(raw); check(p!=null && p.version==2,"BC/2 decodes as version 2");
            for(int r=0;r<2;r++) { ChatTransfers.Reception x=inbox.receive(p,0); if(x.message!=null) { got=x.message; n++; } }
        }
        check(n==1 && got!=null && got.text().equals(text) && got.name.equals("Píxel-5"),"BC/2 reassembles once, intact");

        // ---- ACK in the same version, and Outgoing completes with BC/2
        ChatTransfers.Outgoing out=new ChatTransfers.Outgoing(1,77,env,Arrays.asList(2),0,2);
        check(ChatProtocol.decode(out.frames.get(0)).version==2,"Outgoing v2 uses BC/2 frames");
        ChatProtocol.Packet first=ChatProtocol.decode(out.frames.get(0));
        byte[] ack=ChatProtocol.ackFor(first,2,0,-1);
        ChatProtocol.Packet ap=ChatProtocol.decode(ack);
        check(ap!=null && ap.kind==ChatProtocol.ACK && ap.version==2 && ap.target()==1 && ap.id==77,"BC/2 ACK targets the sender");
        out.acknowledge(ap); check(out.complete(),"Full BC/2 ACK completes the transfer");
        check(ChatProtocol.decode(ChatProtocol.ackFor(ChatProtocol.decode(v1.get(0)),2,0,1)).version==1,"BC/1 data gets a BC/1 ACK");

        // ---- Loss simulation with BC/2 (30% loss both ways)
        for(int seed=0;seed<10;seed++) {
            Random rnd=new Random(seed); long now=0;
            ChatTransfers.Outgoing o=new ChatTransfers.Outgoing(1,seed+100,env,Arrays.asList(2),0,2);
            ChatTransfers.Inbox in=new ChatTransfers.Inbox(); boolean delivered=false;
            while(!o.complete() && !o.failed(now) && now<60000) {
                byte[] f=o.next(now); now+=120; if(f==null) { now+=500; continue; }
                if(rnd.nextDouble()<.3) continue;
                ChatProtocol.Packet p=ChatProtocol.decode(f); ChatTransfers.Reception r=in.receive(p,now);
                if(r.message!=null) delivered=true;
                if(rnd.nextDouble()>.3) o.acknowledge(ChatProtocol.decode(ChatProtocol.ackFor(p,2,r.window,r.bitmap)));
            }
            check(delivered,"BC/2 delivers under 30% loss (seed "+seed+")");
        }

        // ---- Live preview grows packet by packet, never splits a UTF-8 character
        ChatTransfers.Inbox live=new ChatTransfers.Inbox(); String lastShown="";
        for(int i=0;i<v2.size()-1;i++) {
            live.receive(ChatProtocol.decode(v2.get(i)),0);
            ChatTransfers.Preview pv=live.preview(0xfedcba98,4242);
            check(pv!=null,"Preview exists while receiving");
            if(i>=1) check("Píxel-5".equals(pv.name),"Sender name shows once its bytes arrive");
            check(text.startsWith(pv.text),"Preview is always a clean prefix of the message: '"+pv.text+"'");
            check(pv.text.length()>=lastShown.length(),"Preview only grows"); lastShown=pv.text;
            check(pv.received==i+1 && pv.count==v2.size(),"Preview progress counts packets");
        }
        check(lastShown.length()>text.length()/2,"Most of the text is visible before the last packet");
        // Gap: missing packet 1 shows only packet 0's prefix
        ChatTransfers.Inbox gap=new ChatTransfers.Inbox();
        gap.receive(ChatProtocol.decode(v2.get(0)),0); gap.receive(ChatProtocol.decode(v2.get(2)),0);
        check(gap.preview(0xfedcba98,4242).received==2,"Out-of-order packet counted");
        check(gap.preview(0xfedcba98,4242).text.isEmpty(),"Text after a gap is not shown out of order");

        // ---- BC/2 rejects malformed input; BC/1 still decodes
        check(ChatProtocol.decode(new byte[]{'B','C',0x21,0,0,0,1,0,1,0,0,1})==null,"Header-only BC/2 rejected");
        Random junk=new Random(5); int accepted=0;
        for(int i=0;i<20000;i++) { byte[] b=new byte[3+junk.nextInt(22)]; junk.nextBytes(b); b[0]='B'; b[1]='C'; b[2]=(byte)(0x20|junk.nextInt(16));
            try { ChatProtocol.Packet p=ChatProtocol.decode(b); if(p!=null) { accepted++; new ChatTransfers.Inbox().receive(p,0); } }
            catch(RuntimeException e) { throw new AssertionError("Malformed BC/2 frame threw "+e); } }
        check(ChatProtocol.decode(v1.get(0)).version==1,"BC/1 still decodes");

        // ---- Alert hop byte
        byte[] alert=Protocol.withAlertHops(Protocol.text(5,6,"EQA:4.8:120"),10);
        Protocol.Packet pa=Protocol.decode(alert);
        check(pa.alertText().equals("EQA:4.8:120") && pa.alertHops()==10,"Alert hop byte round trip");
        check(Protocol.decode(Protocol.withAlertHops(alert,9)).alertHops()==9,"Hop count decrements");
        check(Protocol.decode(Protocol.text(5,6,"TEST")).alertHops()==-1,"Plain text has no hop byte");
        check(Protocol.decode(Protocol.text(5,6,"TEST")).alertText().equals("TEST"),"Plain text unchanged");

        // ---- Signature fragments use kind 6 and coexist with relay kinds 4/5
        byte[] sig=new byte[64]; new Random(3).nextBytes(sig);
        byte[][] frags=SignedAlerts.fragments(5,6,sig);
        check(frags.length==6,"Six signature fragments");
        SignedAlerts.Collector c=new SignedAlerts.Collector();
        for(byte[] f:frags) { Protocol.Packet p=Protocol.decode(f); check(p!=null && p.kind==Protocol.SIG && !p.relay(),"SIG is kind 6, not a relay"); c.add(p,f); }
        check(c.complete() && Arrays.equals(c.signature(),sig),"Signature reassembles");
        check(Protocol.decode(Protocol.relayText(1,2,0,"HELLO")).relay(),"Relay text still decodes as relay");

        System.out.println("MeshMergeTest OK · "+checks+" checks · BC/2 "+v2.size()+" packets vs BC/1 "+v1.size()+" for "+env.length+" envelope bytes");
    }
}

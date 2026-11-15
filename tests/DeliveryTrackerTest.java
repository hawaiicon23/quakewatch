import dev.blx.btprobe.*;

public class DeliveryTrackerTest {
    static void check(boolean condition) { if(!condition) throw new AssertionError(); }
    public static void main(String[] args) {
        DeliveryTracker d=new DeliveryTracker();
        check(d.canTransmit(7,0));
        check(d.transmitted(7,0).attempts==1);
        check(d.retry(7,5000));
        check(d.acknowledge(7,11,100)!=null);
        check(d.acknowledge(7,12,200)!=null);
        check(!d.canTransmit(7,300));
        check(d.acknowledge(7,11,300)==null);
        check(!d.retry(7,5000));
        check(d.acknowledge(999,12,300)==null);
        check(d.acknowledge(7,13,20000)==null);
        check(!d.expire(7));
        check(!d.canTransmit(7,21000));
        Protocol.Packet p=Protocol.decode(Protocol.text(22,7,"TEST"));
        check(d.first(p,0)); check(!d.first(p,100));
        check(d.replyDue(p,0)); d.replied(p,0);
        check(!d.replyDue(p,1000)); check(d.replyDue(p,2000));
        check(d.first(Protocol.decode(Protocol.text(23,7,"OTHER")),100));
        d.transmitted(8,0); d.transmitted(8,5000); d.transmitted(8,10000);
        check(!d.retry(8,15000)); check(d.expire(8));
        d.clear(); check(d.canTransmit(7,0));
        PeerStats peers=new PeerStats();
        Protocol.Packet beacon=Protocol.decode(Protocol.beacon(11,1,"Phone-A"));
        peers.observe(beacon,-50,0); peers.observe(beacon,-60,500);
        peers.observe(Protocol.decode(Protocol.beacon(11,2,"Phone-A")),-70,1000);
        PeerStats.Peer peer=peers.values().iterator().next();
        check(peer.samples==2 && peer.rssi==-70 && peer.label.equals("Phone-A"));
        peers.observe(Protocol.decode(Protocol.beacon(12,1,"Phone-B")),-55,1000);
        check(peers.values().size()==2);
        RelayPolicy relay=new RelayPolicy();
        Protocol.Packet routed=Protocol.decode(Protocol.relayText(11,7,33,"HELLO"));
        Protocol.Packet forwarded=Protocol.decode(relay.forward(routed,0));
        check(forwarded.sender==11 && forwarded.recipient()==33 && forwarded.hops==2 && forwarded.text().equals("HELLO"));
        check(relay.forward(routed,1000)==null);
        check(relay.forward(forwarded,6000)==null);
        check(relay.forward(routed,6000)!=null);
        check(relay.forward(routed,12000)!=null);
        check(relay.forward(routed,18000)==null);
        check(relay.forward(Protocol.decode(Protocol.encode(routed.kind,routed.sender,routed.id,routed.body,0)),20000)==null);
        check(PowerPolicy.requested(0,false,false,false)==0);
        check(PowerPolicy.requested(20,false,false,false)==20);
        check(PowerPolicy.requested(3,true,false,false)==20);
        check(PowerPolicy.requested(3,false,true,true)==21);
        boolean blocked=false;
        try { PowerPolicy.requested(3,false,true,false); } catch(IllegalStateException e) { blocked=true; }
        check(blocked);
        System.out.println("DeliveryTracker OK");
    }
}

package dev.blx.btprobe;
import java.util.*;

public final class PeerStats {
    public static final class Peer {
        public final int id;
        public String label="";
        public int rssi, samples, sequence;
        public long lastSeen;
        boolean hasSequence;
        Peer(int id) { this.id=id; }
    }
    private final LinkedHashMap<Integer,Peer> peers=new LinkedHashMap<>();
    public void observe(Protocol.Packet packet,int rssi,long now) {
        Peer p=peers.get(packet.sender);
        if(p==null) { p=new Peer(packet.sender); peers.put(p.id,p); }
        p.rssi=rssi; p.lastSeen=now;
        if(packet.kind==Protocol.BEACON) {
            p.label=packet.text();
            if(!p.hasSequence || p.sequence!=packet.id) { p.samples++; p.sequence=packet.id; p.hasSequence=true; }
        }
        if(peers.size()>64) peers.remove(peers.keySet().iterator().next());
    }
    public Collection<Peer> values() { return peers.values(); }
}

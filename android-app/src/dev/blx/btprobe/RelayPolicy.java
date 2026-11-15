package dev.blx.btprobe;
import java.util.*;

/** Bounded flood: preserve origin/message identity, decrement hops, suppress loops. */
public final class RelayPolicy {
    private static final class Entry { long time, first; int hops, count; }
    private final LinkedHashMap<String,Entry> sent=new LinkedHashMap<>();
    public byte[] forward(Protocol.Packet packet,long now) {
        if(!packet.relay() || packet.hops==0) return null;
        Iterator<Entry> it=sent.values().iterator();
        while(it.hasNext()) if(now-it.next().first>120000) it.remove();
        Entry e=sent.get(packet.key());
        if(e!=null && (packet.hops<e.hops || now-e.time<5000 || e.count>=3)) return null;
        if(e==null) { e=new Entry(); e.first=now; sent.put(packet.key(),e); }
        e.time=now; e.hops=packet.hops; e.count++;
        if(sent.size()>512) sent.remove(sent.keySet().iterator().next());
        return Protocol.encode(packet.kind,packet.sender,packet.id,packet.body,packet.hops-1);
    }
    public void clear() { sent.clear(); }
}

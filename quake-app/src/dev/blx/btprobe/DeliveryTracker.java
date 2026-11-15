package dev.blx.btprobe;

import java.util.*;

/** Delivery state is per message and per peer; an ACK never selects a single peer. */
public final class DeliveryTracker {
    public static final long WINDOW_MS=20000, ACK_INTERVAL_MS=2000;
    public static final class Pending {
        public final long started;
        public int attempts;
        public final Set<Integer> peers=new HashSet<>();
        Pending(long now) { started=now; }
    }
    private final Map<Integer,Pending> pending=new HashMap<>();
    private final LinkedHashSet<Integer> retired=new LinkedHashSet<>();
    private final LinkedHashMap<String,Long> seen=new LinkedHashMap<>();
    private final Map<String,Long> acked=new HashMap<>();

    public Pending transmitted(int id,long now) {
        Pending p=pending.get(id);
        if(p==null) { p=new Pending(now); pending.put(id,p); }
        p.attempts++;
        return p;
    }
    public boolean accepts(int id,long now) {
        Pending p=pending.get(id);
        return p!=null && now-p.started<WINDOW_MS;
    }
    public boolean canTransmit(int id,long now) {
        Pending p=pending.get(id);
        return !retired.contains(id) && (p==null || (accepts(id,now) && p.peers.isEmpty()));
    }
    public Long acknowledge(int id,int peer,long now) {
        Pending p=pending.get(id);
        if(!accepts(id,now) || !p.peers.add(peer)) return null;
        return now-p.started;
    }
    public boolean retry(int id,long now) {
        Pending p=pending.get(id);
        return accepts(id,now) && p.peers.isEmpty() && p.attempts<3;
    }
    public boolean expire(int id) {
        Pending p=pending.remove(id);
        retired.add(id);
        if(retired.size()>256) retired.remove(retired.iterator().next());
        return p!=null && p.peers.isEmpty();
    }
    public boolean first(Protocol.Packet p,long now) {
        Iterator<Map.Entry<String,Long>> it=seen.entrySet().iterator();
        while(it.hasNext()) {
            Map.Entry<String,Long> e=it.next();
            if(now-e.getValue()>60000) { acked.remove(e.getKey()); it.remove(); }
        }
        if(seen.containsKey(p.key())) return false;
        seen.put(p.key(),now);
        if(seen.size()>256) { String key=seen.keySet().iterator().next(); seen.remove(key); acked.remove(key); }
        return true;
    }
    public boolean replyDue(Protocol.Packet p,long now) {
        Long last=acked.get(p.key());
        return last==null || now-last>=ACK_INTERVAL_MS;
    }
    public void replied(Protocol.Packet p,long now) { acked.put(p.key(),now); }
    public void clear() { pending.clear(); retired.clear(); seen.clear(); acked.clear(); }
}

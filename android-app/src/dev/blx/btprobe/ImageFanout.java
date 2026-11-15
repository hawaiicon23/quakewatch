package dev.blx.btprobe;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Global three-link limit across all queued images, rather than one thread per recipient. */
public final class ImageFanout implements AutoCloseable {
    public static final int MAX_LINKS=3;
    public interface Attempt {boolean send(int peer)throws Exception;}
    public interface Completion {void done(Set<Integer> delivered);}
    private final ThreadPoolExecutor pool=new ThreadPoolExecutor(MAX_LINKS,MAX_LINKS,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(64));
    private final AtomicInteger epoch=new AtomicInteger();
    public void send(Collection<Integer> peers,Attempt attempt,Completion completion) {
        Set<Integer> targets=new LinkedHashSet<>(peers);
        if(targets.isEmpty()){completion.done(Collections.emptySet());return;}
        int current=epoch.get();AtomicInteger remaining=new AtomicInteger(targets.size());
        Set<Integer> delivered=ConcurrentHashMap.newKeySet();
        for(int peer:targets) {
            Runnable task=()->{
                try {if(current==epoch.get()&&attempt.send(peer))delivered.add(peer);}
                catch(Exception ignored){} // Per-peer failures are returned as undelivered for fallback.
                finally {if(remaining.decrementAndGet()==0&&current==epoch.get())completion.done(new LinkedHashSet<>(delivered));}
            };
            try{pool.execute(task);}catch(RejectedExecutionException full){if(remaining.decrementAndGet()==0&&current==epoch.get())completion.done(new LinkedHashSet<>(delivered));}
        }
    }
    public void cancel(){epoch.incrementAndGet();pool.getQueue().clear();}
    @Override public void close(){cancel();pool.shutdownNow();}
}

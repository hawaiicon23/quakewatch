import dev.blx.btprobe.ImageFanout;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
public final class ImageFanoutTest {
 public static void main(String[] args)throws Exception {
  try(ImageFanout fanout=new ImageFanout()) {
   CountDownLatch three=new CountDownLatch(3),release=new CountDownLatch(1),done=new CountDownLatch(1);
   AtomicInteger active=new AtomicInteger(),peak=new AtomicInteger(),calls=new AtomicInteger();
   AtomicReference<Set<Integer>> delivered=new AtomicReference<>();
   fanout.send(Arrays.asList(1,2,3,4,5,6,6),peer->{
    calls.incrementAndGet();int n=active.incrementAndGet();peak.accumulateAndGet(n,Math::max);three.countDown();
    if(!release.await(2,TimeUnit.SECONDS))throw new AssertionError("Parallel connections did not start");
    active.decrementAndGet();if(peer==5)throw new java.io.IOException("Simulated failed peer");return true;
   },peers->{delivered.set(peers);done.countDown();});
   if(!three.await(2,TimeUnit.SECONDS))throw new AssertionError("No three-way concurrency");
   if(peak.get()!=3)throw new AssertionError("Connection cap not enforced");release.countDown();
   if(!done.await(2,TimeUnit.SECONDS)||calls.get()!=6||!delivered.get().equals(new HashSet<>(Arrays.asList(1,2,3,4,6))))throw new AssertionError("Per-peer delivery/fallback broken");
  }
  System.out.println("ImageFanout OK: three concurrent links, global bound, de-duplication and per-peer failure");
 }
}

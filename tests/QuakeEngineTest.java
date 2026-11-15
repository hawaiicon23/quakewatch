import dev.blx.btprobe.*;
import java.util.*;
public final class QuakeEngineTest {
    static int checks;
    static void check(boolean b,String why) { checks++; if(!b) throw new AssertionError(why); }
    static Protocol.Packet packet(QuakeConsensus c,int seq,int state,long wall) { return Protocol.decode(c.packet(seq,state,.2,wall)); }
    static void feed(QuakeDetector d,double start,double duration,double amplitude) {
        for(int i=0;i<Math.round(duration*50);i++) {
            double t=start+i*.02;
            d.sample((long)(t*1e9),amplitude*Math.sin(2*Math.PI*3*t),0,9.80665,40);
        }
    }
    public static void main(String[] args) {
        long wall=1700000000000L, elapsed=10000;
        QuakeConsensus a=new QuakeConsensus(1,Arrays.asList(1,2),false), b=new QuakeConsensus(2,Arrays.asList(1,2),false);
        check(a.cohort==b.cohort&&a.required==2,"same roster same group, both required");
        a.local(1,3,.2,wall,elapsed);
        check(!a.decision(wall,elapsed).startsWith("CORRELATED"),"one node never confirms");
        check(a.accept(packet(b,1,2,wall),wall,elapsed),"quiet peer accepted");
        check(a.positives(wall,elapsed)==1,"quiet peer does not inherit another phone trigger");
        check(a.accept(packet(b,2,3,wall),wall,elapsed),"new positive accepted");
        check(a.decision(wall,elapsed).startsWith("CORRELATED"),"two matching candidates corroborate");
        check(!a.accept(packet(b,2,3,wall),wall+1000,elapsed+1000),"duplicate does not refresh");
        check(a.positives(wall+2600,elapsed+2600)==0,"stale nodes expire");
        check(!a.accept(packet(b,1,3,wall),wall,elapsed),"out of order rejected");
        check(!a.accept(packet(b,3,3,wall-5000),wall,elapsed),"old wire timestamp rejected");
        check(!a.accept(packet(b,3,3,wall+2000),wall,elapsed),"future clock rejected");
        QuakeConsensus bt=new QuakeConsensus(2,Arrays.asList(1,2),true);
        check(!a.accept(packet(bt,9,3,wall),wall,elapsed),"test cannot vote in monitoring");
        QuakeConsensus wrong=new QuakeConsensus(2,Arrays.asList(1,2,3),false);
        check(!a.accept(packet(wrong,9,3,wall),wall,elapsed),"different membership rejected");
        QuakeConsensus outsider=new QuakeConsensus(3,Arrays.asList(1,2),false);
        check(!a.accept(packet(outsider,9,3,wall),wall,elapsed),"unlisted node rejected");
        QuakeConsensus single=new QuakeConsensus(1,Arrays.asList(1),false);
        single.local(1,3,.2,wall,elapsed); check(single.decision(wall,elapsed).startsWith("NEEDS"),"single node insufficient");
        QuakeConsensus three=new QuakeConsensus(1,Arrays.asList(1,2,3),false);
        QuakeConsensus second=new QuakeConsensus(2,Arrays.asList(1,2,3),false);
        three.local(1,3,.2,wall,elapsed); three.accept(packet(second,1,3,wall),wall,elapsed);
        check(three.decision(wall,elapsed).startsWith("CORRELATED"),"strict majority two of three");
        check(three.required==2&&new QuakeConsensus(1,Arrays.asList(1,2,3,4),false).required==3,"majority fixed");
        byte[] raw=b.packet(4,3,.2,wall); check(raw.length+7==31,"fits BLE legacy 31 bytes");
        raw[raw.length-1]=2; check(!a.accept(Protocol.decode(raw),wall,elapsed),"wrong schema rejected");
        raw=b.packet(4,3,.2,wall); raw[raw.length-2]=(byte)0xff;
        check(!a.accept(Protocol.decode(raw),wall,elapsed),"unknown flags rejected");
        check(Protocol.decode(Arrays.copyOf(raw,raw.length-1))==null,"truncated sensor payload rejected");
        check(QuakeConsensus.parseRoster("00000002, 00000001 00000002",1).size()==2,"roster deduplicated");
        try { QuakeConsensus.parseRoster("not-an-id",1); throw new AssertionError("bad roster accepted"); } catch(IllegalArgumentException expected) { checks++; }
        QuakeDetector live=new QuakeDetector(false); feed(live,1,35,0);
        check(live.state==2,"normal mode arms after quiet period");
        feed(live,36,1,.005); check(live.state==2,"tiny noise does not trigger");
        feed(live,37,3,.4); check(live.state==3,"sustained motion triggers");
        check(live.stateAt(45000000000L)==0,"missing sensor cannot keep voting");
        QuakeDetector tap=new QuakeDetector(false); feed(tap,1,35,0); feed(tap,36,.1,2); feed(tap,36.1,2,0);
        check(tap.state!=3,"short tap is not sustained");
        QuakeDetector demo=new QuakeDetector(true); feed(demo,1,4,0); feed(demo,5,2,.4);
        check(demo.state==3,"table shake test triggers after short calibration");
        QuakeDetector unarmed=new QuakeDetector(false); feed(unarmed,1,4,0); feed(unarmed,5,2,.4);
        check(unarmed.state!=3,"normal mode cannot skip its calibration");
        live.sample(46000000000L,0,0,9.8,40); check(live.state!=3,"gap resets candidate");
        feed(live,47,35,0); live.sample(82000000000L,40,0,9.8,40); check(live.state!=3,"clipping cannot vote");
        live.sample(83000000000L,Double.NaN,0,9.8,40); check(live.state!=3,"NaN cannot vote");
        QuakeDetector slow=new QuakeDetector(false);
        for(int i=0;i<1000;i++) slow.sample(1000000000L+i*100000000L,0,0,9.8,40);
        check(slow.stateAt(100900000000L)!=3,"slow sensor cannot trigger");
        System.out.println("QuakeEngine OK: "+checks+" checks");
    }
}

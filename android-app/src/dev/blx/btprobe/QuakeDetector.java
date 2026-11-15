package dev.blx.btprobe;

/** Experimental, time-aware motion trigger. SI units throughout; no magnitude estimate. */
public final class QuakeDetector {
    public static final int UNAVAILABLE=0, CALIBRATING=1, QUIET=2, CANDIDATE=3;
    public final boolean test;
    public int state=CALIBRATING;
    public double rms, baseline, ratio, hz;
    public String reason="Place the phone on a stable surface";
    private final double[] gravity=new double[3], low=new double[3];
    private long last, bucketStart, quietSince, positiveUntil;
    private double energy; private int samples, sustained;
    private boolean initialized, armed;
    public QuakeDetector(boolean test) { this.test=test; }
    public void reset(String why) {
        initialized=armed=false; last=bucketStart=quietSince=positiveUntil=0;
        energy=0; samples=sustained=0; rms=baseline=ratio=hz=0;
        state=CALIBRATING; reason=why;
    }
    public void sample(long ns,double x,double y,double z,double maxRange) {
        if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)) { reset("Invalid sensor value"); return; }
        if(initialized && ns<=last) return;
        if(initialized && ns-last>200000000L) reset("Sensor gap; recalibrating");
        double[] v={x,y,z};
        if(!initialized) {
            System.arraycopy(v,0,gravity,0,3); java.util.Arrays.fill(low,0);
            initialized=true; last=bucketStart=ns; quietSince=ns; return;
        }
        double dt=(ns-last)*1e-9; last=ns;
        if(maxRange>0 && (Math.abs(x)>=maxRange*.95||Math.abs(y)>=maxRange*.95||Math.abs(z)>=maxRange*.95)) { reset("Sensor clipping; recalibrating"); return; }
        double ga=dt/(1/(2*Math.PI*.5)+dt), la=dt/(1/(2*Math.PI*10)+dt), e=0;
        for(int i=0;i<3;i++) { gravity[i]+=ga*(v[i]-gravity[i]); low[i]+=la*(v[i]-gravity[i]-low[i]); e+=low[i]*low[i]; }
        energy+=e; samples++;
        if(ns-bucketStart<500000000L) return;
        double elapsed=(ns-bucketStart)*1e-9; hz=samples/elapsed;
        rms=Math.sqrt(energy/samples); energy=0; samples=0; bucketStart=ns;
        if(hz<25) { reset("Sampling below 25 Hz; cannot vote"); state=UNAVAILABLE; return; }
        double quietLimit=test?.10:.05;
        if(!armed) {
            if(rms>quietLimit) quietSince=ns;
            baseline=baseline==0?rms:.95*baseline+.05*rms;
            long required=test?3000000000L:30000000000L;
            if(ns-quietSince<required) { state=CALIBRATING; reason="Calibrating: keep still "+(test?3:30)+" s"; return; }
            armed=true;
        }
        ratio=rms/Math.max(.01,baseline);
        boolean above=rms>=(test?.12:.08) && (test||ratio>=3);
        sustained=above?sustained+1:0;
        if(sustained>=(test?2:3)) positiveUntil=ns+2500000000L;
        state=ns<positiveUntil?CANDIDATE:QUIET;
        reason=state==CANDIDATE?"Sustained shaking candidate":"Monitoring; no local candidate";
        // Freeze noise baseline during a candidate, to avoid learning away the earthquake.
        if(!above && state==QUIET) baseline=.95*baseline+.05*rms;
    }
    public int stateAt(long ns) {
        if(!initialized||ns-last>1500000000L) return UNAVAILABLE;
        return state==CANDIDATE && ns>=positiveUntil?QUIET:state;
    }
}

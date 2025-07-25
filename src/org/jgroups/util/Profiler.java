package org.jgroups.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.jgroups.util.Util.printTime;

/**
 * Maintains averages for time values measured between {@link #start} and {@link #stop}.
 * @author Bela Ban
 * @since  5.2.13
 */
public class Profiler {
    protected final AverageMinMax    avg=new AverageMinMax(1024).unit(NANOSECONDS);
    protected final Map<Thread,Long> threads=new ConcurrentHashMap<>();
    protected boolean                print_details=true;
    protected boolean                enabled=true;


    public Profiler() {
    }

    public boolean  details()          {return print_details;}
    public Profiler details(boolean d) {print_details=d; return this;}
    public boolean  enabled()          {return enabled;}
    public Profiler enable(boolean b)  {enabled=b; return this;}

    public void reset() {
        threads.clear();
        avg.clear();
    }

    public void start() {
        if(!enabled)
            return;
        Thread curr=Thread.currentThread();
        threads.put(curr, System.nanoTime());
    }

    public void stop() {
        if(!enabled)
            return;
        Thread curr_thread=Thread.currentThread();
        Long start=threads.remove(curr_thread);
        if(start != null) {
            long time=System.nanoTime() - start;
            avg.add(time);
        }
    }

    @Override
    public String toString() {
        if(avg.count() == 0)
            return "n/a";
        return print_details? String.format("min/avg/max=%s/%s/%s", printTime(avg.min(), NANOSECONDS),
                                            printTime(avg.average(), NANOSECONDS), printTime(avg.max(), NANOSECONDS))
          : String.format("avg=%s", printTime(avg.average(), NANOSECONDS));
    }
}

package org.jgroups.tests;


import org.jgroups.*;
import org.jgroups.protocols.DISCARD_PAYLOAD;
import org.jgroups.protocols.MAKE_BATCH;
import org.jgroups.protocols.NAKACK4;
import org.jgroups.protocols.pbcast.NAKACK2;
import org.jgroups.util.Util;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.jgroups.stack.ProtocolStack.Position.BELOW;

/**
 * Tests the NAKACK{2,4} protocols for sending/reception of OOB and regular msgs.
 * Ref: https://issues.redhat.com/browse/JGRP-379
 * @author Bela Ban
 */
@Test(groups=Global.STACK_DEPENDENT,singleThreaded=true)
public class NAKACK_Test extends ChannelTestBase {
    protected JChannel a, b, c;

    @BeforeMethod
    void setUp() throws Exception {
        a=createChannel().name("A");
        b=createChannel().name("B");
        c=createChannel().name("C");
        makeUnique(a, b, c);
    }

    @AfterMethod
    void tearDown() throws Exception {
        Util.close(c, b, a);
    }


    /**
     * Tests https://issues.redhat.com/browse/JGRP-379: we send 1, 2, 3, 4(OOB) and 5 to the cluster.
     * Message with seqno 3 is discarded two times, so retransmission will make the receivers receive it *after* 4.
     * Note that OOB messages *destroy* FIFO ordering (or whatever ordering properties are set)!
     */
    public void testOutOfBandMessages() throws Exception {
        NAKACK_Test.MyReceiver receiver1=new NAKACK_Test.MyReceiver("A");
        NAKACK_Test.MyReceiver receiver2=new NAKACK_Test.MyReceiver("B");
        NAKACK_Test.MyReceiver receiver3=new NAKACK_Test.MyReceiver("C");
        a.setReceiver(receiver1);
        b.setReceiver(receiver2);
        c.setReceiver(receiver3);

        a.getProtocolStack().insertProtocol(new DISCARD_PAYLOAD(), BELOW, NAKACK2.class, NAKACK4.class);

        a.connect("NAKACK_Test");
        b.connect("NAKACK_Test");
        c.connect("NAKACK_Test");
        Util.waitUntilAllChannelsHaveSameView(5000, 500, a,b,c);

        for(int i=1; i <=5; i++) {
            Message msg=new BytesMessage(null, i);
            if(i == 4)
                msg.setFlag(Message.Flag.OOB);
            System.out.println("-- sending message #" + i);
            a.send(msg);
            Util.sleep(100);
        }

        Collection<Integer> seqnos1=receiver1.getSeqnos();
        Collection<Integer> seqnos2=receiver2.getSeqnos();
        Collection<Integer> seqnos3=receiver3.getSeqnos();

        // wait until retransmission of seqno #3 happens, so that 4 and 5 are received as well
        long target_time=System.currentTimeMillis() + 20000;
        do {
            if(seqnos1.size() >= 5 && seqnos2.size() >= 5 && seqnos3.size() >= 5)
                break;
            Util.sleep(500);
        }
        while(target_time > System.currentTimeMillis());

        System.out.println("sequence numbers:");
        System.out.println("c1: " + seqnos1);
        System.out.println("c2: " + seqnos2);
        System.out.println("c3: " + seqnos3);
        checkOrder(seqnos1, seqnos2, seqnos3);
    }

    public void testOobBatch() throws Exception {
        NAKACK_Test.MyReceiver receiver2=new NAKACK_Test.MyReceiver("B");
        b.setReceiver(receiver2);

        MAKE_BATCH m=new MAKE_BATCH().multicasts(true).unicasts(false).skipOOB(false);
        b.getProtocolStack().insertProtocol(m, BELOW, NAKACK2.class, NAKACK4.class);
        m.start();

        a.connect("NAKACK_Test");
        b.connect("NAKACK_Test");
        Util.waitUntilAllChannelsHaveSameView(5000, 500, a,b);

        for(int i=1; i <= 5; i++) {
            Message msg=new BytesMessage(null, i).setFlag(Message.Flag.OOB);
            a.send(msg);
        }
        Util.waitUntil(5000, 500, () -> receiver2.size() == 5);
    }

    /** Sends multicast messages on A, the disconnects and reconnects A and sends more messages. B and C should
     * receive all of A's messages. https://issues.redhat.com/browse/JGRP-2720
     */
    public void testReconnect() throws Exception {
        NAKACK_Test.MyReceiver r1=new NAKACK_Test.MyReceiver("A");
        NAKACK_Test.MyReceiver r2=new NAKACK_Test.MyReceiver("B");
        NAKACK_Test.MyReceiver r3=new NAKACK_Test.MyReceiver("C");
        a.connect("NAKACK_Test").setReceiver(r1);
        b.connect("NAKACK_Test").setReceiver(r2);
        c.connect("NAKACK_Test").setReceiver(r3);
        Util.waitUntilAllChannelsHaveSameView(5000, 200, a,b,c);

        List<Integer> msgs=msgs(1, 10);
        for(int i: msgs)
            a.send(null, i);
        waitUntilReceiversHaveMessages(10, r1,r2,r3);
        check(msgs, r1,r2,r3);

        System.out.println("-- disconnecting and reconnecting A:");
        a.disconnect();
        Util.waitUntilAllChannelsHaveSameView(5000, 200, b,c);
        a.connect("NAKACK_Test").setReceiver(r1);
        Util.waitUntilAllChannelsHaveSameView(5000, 200, a,b,c);

        Stream.of(r1,r2,r3).forEach(MyReceiver::clear);
        msgs=msgs(11,20);
        for(int i: msgs)
            a.send(null, i);
        waitUntilReceiversHaveMessages(10, r1,r2,r3);
        check(msgs, r1,r2,r3);

        a.disconnect();
        Util.waitUntilAllChannelsHaveSameView(5000, 200, b,c);
        Stream.of(r1,r2,r3).forEach(MyReceiver::clear);
        msgs=msgs(21,30);
        for(int i: msgs)
            b.send(null, i);
        waitUntilReceiversHaveMessages(10, r2,r3);
        check(msgs, r2,r3);

        // B has a higher digest; make sure A fetches it correctly
        a.connect("NAKACK_Test").setReceiver(r1);

        Stream.of(r1,r2,r3).forEach(MyReceiver::clear);
        msgs=msgs(31,40);
        for(int i: msgs)
            b.send(null, i);
        waitUntilReceiversHaveMessages(10, r1,r2,r3);
        check(msgs, r1,r2,r3);
    }

    protected static List<Integer> msgs(int from, int to) {
        return IntStream.rangeClosed(from,to).boxed().collect(Collectors.toList());
    }

    protected static void check(List<Integer> expected, MyReceiver ... receivers) {
        System.out.printf("-- receivers:\n%s\n", print(receivers));
        for(MyReceiver r: receivers) {
            Collection<Integer> actual=r.getSeqnos();
            assert expected.equals(actual) : String.format("%s: expected: %s, actual: %s", r.name(), expected, actual);
        }
    }

    protected static String print(MyReceiver ... receivers) {
        return Stream.of(receivers)
          .map(r -> String.format("%s: %s", r.name(), r.getSeqnos())).collect(Collectors.joining("\n"));
    }

    protected static final boolean match(List<Integer> expected, List<Integer> actual) {
        return expected.equals(actual);
    }

    protected static void waitUntilReceiversHaveMessages(int expected, MyReceiver... receivers) throws TimeoutException {
        Util.waitUntil(5000, 200,
                       () -> Stream.of(receivers).allMatch(r -> r.size() == expected), () -> print(receivers));
    }

    /**
     * Checks whether the numbers are in order *after* removing 4: the latter is OOB and can therefore appear anywhere
     * in the sequence
     * @param lists
     */
    @SafeVarargs
    private static void checkOrder(Collection<Integer> ... lists) throws Exception {
        for(Collection<Integer> list: lists) {
            list.remove(4);
            long prev_val=0;
            for(int val: list) {
                if(val <= prev_val)
                    throw new Exception("elements are not ordered in list: " + list);
                prev_val=val;
            }
        }
    }



    public static class MyReceiver implements Receiver {
        protected final List<Integer> seqnos=new ArrayList<>();
        protected final String        name;

        public MyReceiver(String name) {
            this.name=name;
        }

        public String              name()      {return name;}
        public Collection<Integer> getSeqnos() {return seqnos;}
        public MyReceiver          clear()     {seqnos.clear(); return this;}

        public void receive(Message msg) {
            if(msg != null) {
                Integer num=msg.getObject();
                synchronized(this) {
                    seqnos.add(num);
                }
            }
        }

        public int size() {return seqnos.size();}

        @Override
        public String toString() {
            return String.format("(%d) %s", size(), seqnos);
        }
    }


}

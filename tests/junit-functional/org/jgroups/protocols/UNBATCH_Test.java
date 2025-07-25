package org.jgroups.protocols;

import org.jgroups.*;
import org.jgroups.protocols.pbcast.GMS;
import org.jgroups.protocols.pbcast.NAKACK2;
import org.jgroups.protocols.pbcast.STABLE;
import org.jgroups.stack.Protocol;
import org.jgroups.stack.ProtocolStack;
import org.jgroups.util.MessageBatch;
import org.jgroups.util.Util;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Tests {@link UNBATCH}
 * @author Bela Ban
 * @since  5.2.18
 */
@Test(groups= Global.FUNCTIONAL,singleThreaded=true)
public class UNBATCH_Test {
    protected MyReceiver ra=new MyReceiver(), rb=new MyReceiver();
    protected JChannel   a, b;

    @BeforeMethod protected void setup() throws Exception {
        a=create("A").connect(UNBATCH_Test.class.getSimpleName());
        b=create("B").connect(UNBATCH_Test.class.getSimpleName());
        Util.waitUntilAllChannelsHaveSameView(5000, 100, a, b);
        a.setReceiver(ra); b.setReceiver(rb);
        ra.clear(); rb.clear();
    }

    @AfterMethod void destroy() {Util.close(b, a);}

    /** Tests that all unicasts sent by A to B are received as single messages by B */
    public void testUnicastSingleMessages() throws Exception {
        sendMessages(b.getAddress(), false);
        Util.waitUntil(5000, 100, () -> rb.numMsgs() == 100, () -> print(b));
        System.out.printf("msgs:\n%s\n", print(b));
        assert rb.numSingleMsgs() == 100;
        assert rb.numBatches() == 0;
    }

    /**
     * Tests that no unicast OOB message batches are received with message processing policy being
     * {@link org.jgroups.util.UnbatchOOBBatches}
     */
    public void testUnicastBatchesWithUnbatchPolicy() throws Exception {
        setUnbatchPolicy(a,b);
        sendMessages(b.getAddress(), true);
        Util.waitUntil(5000, 100, () -> rb.numMsgs() == 100, () -> print(b));
        System.out.printf("msgs:\n%s\n", print(b));
        assert rb.numSingleMsgs() == 100;
        assert rb.numBatches() == 0;
    }

    /** Tests that all multicasts sent by A to B are received as single messages by A and B */
    public void testMulticastSingleMessages() throws Exception {
        sendMessages(null, false);
        Util.waitUntil(5000, 100, () -> ra.numMsgs() == 100 && rb.numMsgs() == 100, () -> print(a,b));
        System.out.printf("msgs:\n%s\n", print(a,b));
        assert ra.numSingleMsgs() == 100 && rb.numSingleMsgs() == 100;
        assert ra.numBatches() == 0 && rb.numBatches() == 0;
    }


    public void testMulticastBatchesWithUnbatchPolicy() throws Exception {
        setUnbatchPolicy(a,b);
        sendMessages(null, true);
        Util.waitUntil(500000, 100, () -> ra.numMsgs() == 100 && rb.numMsgs() == 100, () -> print(a,b));
        System.out.printf("msgs:\n%s\n", print(a,b));
        assert rb.numBatches() == 0 && rb.numSingleMsgs() == 100;
        // we *cannot* assert that A doesn't get looped-back batches:
        // * NAKACK2.down(Message msg) adds msg to the table, then loops back if dest==sender
        // * NAKACK2.handleMessage() delivers an OOB message, then calls removeAndDeliver(): when a message was added
        //   to the table, but not yet marked as OOB_DELIVERED, an OOB batch may be created
    }

    protected void sendMessages(Address target, boolean oob) throws Exception {
        for(int i=1; i <= 100; i++) {
            Message msg=new ObjectMessage(target, i);
            if(oob)
                msg.setFlag(Message.Flag.OOB);
            a.send(msg);
        }
    }

    protected static String print(JChannel... channels) {
        return Stream.of(channels).map(ch -> String.format("%s: %s", ch.getAddress(), ch.getReceiver()))
          .collect(Collectors.joining("\n"));
    }

    protected static JChannel create(String name) throws Exception {
        Protocol[] prots={
          new SHARED_LOOPBACK(),
          new LOCAL_PING(),
          new NAKACK2(),
          new UNICAST3(),
          new UNBATCH().enable(true),
          new STABLE(),
          new GMS().setJoinTimeout(100),
        };
        return new JChannel(prots).name(name);
    }

    protected static void setUnbatchPolicy(JChannel ... channels) {
        for(JChannel ch: channels) {
            ProtocolStack stack=ch.stack();
            stack.removeProtocol(UNBATCH.class);
            stack.getTransport().setMessageProcessingPolicy("unbatch");
        }
    }

    protected static class MyReceiver implements Receiver {
        protected final LongAdder num_batches=new LongAdder();
        protected final LongAdder num_single_msgs=new LongAdder();
        protected final LongAdder num_msgs=new LongAdder();

        protected long numBatches()    {return num_batches.sum();}
        protected long numSingleMsgs() {return num_single_msgs.sum();}
        protected long numMsgs()       {return num_msgs.sum();}
        protected MyReceiver clear()   {num_msgs.reset(); num_batches.reset(); num_single_msgs.reset(); return this;}

        @Override
        public void receive(Message msg) {
            num_single_msgs.increment(); num_msgs.increment();
        }

        @Override
        public void receive(MessageBatch batch) {
            num_batches.increment(); num_msgs.add(batch.size());
        }

        @Override
        public String toString() {
            return String.format("%d msgs: %d batches, %d single msgs",
                                 num_msgs.sum(), num_batches.sum(), num_single_msgs.sum());
        }
    }
}

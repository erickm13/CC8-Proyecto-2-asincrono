package com.cc8.server.protocol;

import com.cc8.server.ws.WebSocketHandler;
import com.cc8.server.ws.WebSocketSession;

import java.util.ArrayList;
import java.util.List;

/** Focused checks for batched DATA, bounded pumping, and batch retransmission. */
public final class RapidSessionTest {

    public static void main(String[] args) {
        batchRoundTripAndRetransmission();
        batchPayloadLimit();
        defaultDataRemainsUnbatched();
        backpressureRetainsUnsentData();
        viewportDiscardKeepsOutstandingData();
        System.out.println("RapidSessionTest paso.");
    }

    private static void batchRoundTripAndRetransmission() {
        long[] now = {0};
        List<byte[]> sent = new ArrayList<>();
        List<byte[]> packets = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            packets.add(new byte[]{(byte) i, 7, 8, 9});
        }
        SegmentSource source = new SegmentSource() {
            int cursor;
            public boolean hasNext() { return cursor < packets.size(); }
            public byte[] next() { return packets.get(cursor++); }
        };
        ReliableSender sender = new ReliableSender(bytes -> sent.add(bytes.clone()),
                source, () -> now[0], false);
        sender.setBatchingEnabled(true);

        int bytes = sender.pumpOne(Wire.MAX_BATCH_PAYLOAD + 64);
        check(bytes > 0 && sent.size() == 1, "one bounded send");
        Wire.Data first = Wire.decodeData(sent.get(0));
        check(first.seq() == 0 && first.flags() == Wire.FLAG_BATCH, "batch DATA header");
        check(first.payload()[0] == 0 && first.payload()[1] == 4,
                "batch uses big-endian u16 packet lengths");
        checkPackets(Wire.decodeBatch(first.payload()), packets.subList(0, 16));

        sender.onAck(Wire.encodeAck(1, 1 << 20, 0, new int[0], new int[0]));
        check(sent.size() == 1, "handler-controlled sender does not pump on ACK");
        check(sender.pumpOne(Wire.MAX_BATCH_PAYLOAD + 64) > 0, "second explicit send");
        Wire.Data second = Wire.decodeData(sent.get(1));
        check(second.seq() == 1 && second.flags() == Wire.FLAG_BATCH, "one sequence per batch");
        checkPackets(Wire.decodeBatch(second.payload()), packets.subList(16, 32));

        sender.onAck(Wire.encodeAck(2, 1 << 20, 0, new int[0], new int[0]));
        check(sender.pumpOne(Wire.MAX_BATCH_PAYLOAD + 64) > 0, "last explicit send");
        Wire.Data third = Wire.decodeData(sent.get(2));
        check(third.seq() == 2, "third DATA sequence");
        checkPackets(Wire.decodeBatch(third.payload()), packets.subList(32, 40));

        now[0] = 1000;
        sender.tick();
        check(sent.size() == 4, "timeout retransmits one outstanding batch");
        Wire.Data retransmission = Wire.decodeData(sent.get(3));
        check(retransmission.seq() == third.seq(), "retransmission keeps DATA sequence");
        check(retransmission.flags() == (Wire.FLAG_BATCH | Wire.FLAG_RETX),
                "retransmission keeps the batch flag");
        check(java.util.Arrays.equals(retransmission.payload(), third.payload()),
                "retransmission keeps the exact batch payload");
    }

    private static void defaultDataRemainsUnbatched() {
        List<byte[]> sent = new ArrayList<>();
        byte[] packet = {1, 2, 3, 4};
        SegmentSource source = new SegmentSource() {
            int cursor;
            public boolean hasNext() { return cursor == 0; }
            public byte[] next() { cursor++; return packet; }
        };
        ReliableSender sender = new ReliableSender(bytes -> sent.add(bytes.clone()), source, () -> 0);
        sender.pump();
        Wire.Data data = Wire.decodeData(sent.get(0));
        check(data.flags() == 0, "legacy DATA flags");
        check(java.util.Arrays.equals(data.payload(), packet), "legacy payload stays raw");
    }

    private static void batchPayloadLimit() {
        List<byte[]> sent = new ArrayList<>();
        SegmentSource source = new SegmentSource() {
            int cursor;
            public boolean hasNext() { return cursor < 20; }
            public byte[] next() { cursor++; return new byte[3000]; }
        };
        ReliableSender sender = new ReliableSender(bytes -> sent.add(bytes.clone()),
                source, () -> 0, false);
        sender.setBatchingEnabled(true);
        sender.pumpOne(Integer.MAX_VALUE);
        Wire.Data data = Wire.decodeData(sent.get(0));
        check((data.flags() & Wire.FLAG_BATCH) != 0, "large batch flag");
        check(data.payload().length <= Wire.MAX_BATCH_PAYLOAD, "batch byte limit");
        check(Wire.decodeBatch(data.payload()).size() == 10, "byte limit stops batch before packet 11");
    }

    private static void backpressureRetainsUnsentData() {
        byte[] oldViewportPacket = {1, 8, 7};
        byte[] newViewportPacket = {2, 6, 5};
        int[] attempts = {0};
        List<byte[]> accepted = new ArrayList<>();
        Link link = new Link() {
            public void send(byte[] datagram) { accepted.add(datagram.clone()); }
            public boolean trySend(byte[] datagram) {
                if (attempts[0]++ == 0) return false;
                accepted.add(datagram.clone());
                return true;
            }
        };
        class ViewSource implements SegmentSource {
            final List<byte[]> queue = new ArrayList<>();
            final java.util.Set<Integer> sent = new java.util.HashSet<>();
            int requeued;
            ViewSource(byte[] packet) { queue.add(packet); }
            public boolean hasNext() { return !queue.isEmpty(); }
            public byte[] next() {
                byte[] packet = queue.remove(0);
                sent.add(packet[0] & 0xFF);
                return packet;
            }
            public void requeueUnsent(byte[] packet) {
                sent.remove(packet[0] & 0xFF);
                queue.add(packet);
                requeued++;
            }
            void setViewport(byte[] packet) {
                queue.clear();
                if (!sent.contains(packet[0] & 0xFF)) queue.add(packet);
            }
        }
        ViewSource source = new ViewSource(oldViewportPacket);
        ReliableSender sender = new ReliableSender(link, source, () -> 0, false);
        check(sender.pumpOne(1024) == 0, "full write queue declines send");
        check(sender.sndNxt() == 0 && sender.inFlight() == 0,
                "declined send does not consume a sequence");
        sender.discardStaged();
        source.setViewport(newViewportPacket);
        check(source.requeued == 1, "viewport change restores staged packet to scheduler");
        check(sender.pumpOne(1024) > 0, "new viewport packet sends after backpressure");
        Wire.Data data = Wire.decodeData(accepted.get(0));
        check(data.seq() == 0, "retry keeps next sequence");
        check(java.util.Arrays.equals(data.payload(), newViewportPacket),
                "discarded old viewport packet is not sent");

        WebSocketSession ws = new WebSocketSession(null, new WebSocketHandler() {
            public void onBinary(WebSocketSession session, byte[] data) { }
        });
        check(!ws.trySendBinary(new byte[1 << 20]), "WebSocket queue rejects a frame over its cap");
    }

    private static void viewportDiscardKeepsOutstandingData() {
        long[] now = {0};
        List<byte[]> sent = new ArrayList<>();
        List<byte[]> packets = List.of(new byte[]{1}, new byte[]{2}, new byte[]{3});
        SegmentSource source = new SegmentSource() {
            int cursor;
            public boolean hasNext() { return cursor < packets.size(); }
            public byte[] next() { return packets.get(cursor++); }
            public void requeueUnsent(byte[] payload) { }
        };
        ReliableSender sender = new ReliableSender(bytes -> sent.add(bytes.clone()),
                source, () -> now[0], false);
        sender.pumpOne(1024);
        sender.onAck(Wire.encodeAck(1, 1 << 20, 0, new int[0], new int[0]));
        sender.pumpOne(1024);
        byte[] outstanding = Wire.decodeData(sent.get(1)).payload();
        check(sender.nextWireBytes() > 0, "stage next viewport candidate");
        sender.discardStaged();
        check(sender.inFlight() == 1 && sender.sndUna() == 1,
                "viewport discard leaves transmitted data outstanding");

        now[0] = 1000;
        sender.tick();
        Wire.Data retransmission = Wire.decodeData(sent.get(2));
        check(retransmission.seq() == 1 && (retransmission.flags() & Wire.FLAG_RETX) != 0,
                "outstanding data still retransmits after viewport discard");
        check(java.util.Arrays.equals(retransmission.payload(), outstanding),
                "outstanding payload remains exact");
    }

    private static void checkPackets(List<byte[]> actual, List<byte[]> expected) {
        check(actual.size() == expected.size(), "batch item count");
        for (int i = 0; i < actual.size(); i++) {
            check(java.util.Arrays.equals(actual.get(i), expected.get(i)), "batch item " + i);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

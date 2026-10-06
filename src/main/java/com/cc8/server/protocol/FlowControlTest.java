package com.cc8.server.protocol;

import java.util.PriorityQueue;
import java.util.Random;

/**
 * Verifica el control de flujo: si el receptor anuncia un rwnd pequeno, el
 * emisor NUNCA pone en vuelo mas de rwnd segmentos (ventana = min(cwnd, rwnd)),
 * y aun asi entrega todo. Esto es lo que protege al navegador de saturarse.
 */
public final class FlowControlTest {

    private static long now = 0;

    private record Event(long time, long tie, boolean toReceiver, byte[] bytes) {
    }

    public static void main(String[] args) {
        int n = 1000;
        int rwnd = 4;

        Random rnd = new Random(1);
        PriorityQueue<Event> net = new PriorityQueue<>(
                (a, b) -> a.time != b.time ? Long.compare(a.time, b.time)
                        : Long.compare(a.tie, b.tie));
        long[] tie = {0};
        long[] dataFree = {0}, ackFree = {0};
        final long base = 20, serial = 1;

        boolean[] delivered = new boolean[n];
        DeliverySink sink = (seq, p) -> delivered[Wire.getInt(p, 0)] = true;

        SegmentSource source = new SegmentSource() {
            int i = 0;
            public boolean hasNext() { return i < n; }
            public byte[] next() { byte[] p = new byte[32]; Wire.putInt(p, 0, i); i++; return p; }
        };

        Link dataLink = bytes -> {
            long tx = Math.max(now, dataFree[0]) + serial; dataFree[0] = tx;
            net.add(new Event(tx + base, tie[0]++, true, bytes.clone()));
        };
        ReliableReceiver receiver = new ReliableReceiver(bytes -> {
            long tx = Math.max(now, ackFree[0]) + serial; ackFree[0] = tx;
            net.add(new Event(tx + base, tie[0]++, false, bytes.clone()));
        }, sink, rwnd);
        ReliableSender sender = new ReliableSender(dataLink, source, () -> now);

        int maxInFlight = 0;
        sender.pump();
        long guard = 0;
        while (!sender.isDone()) {
            if (++guard > 10_000_000L) throw new AssertionError("no converge");
            maxInFlight = Math.max(maxInFlight, sender.inFlight());
            Long evT = net.isEmpty() ? null : net.peek().time;
            long toT = sender.nextTimeoutAt();
            if (evT != null && (toT == Long.MAX_VALUE || evT <= toT)) {
                Event ev = net.poll();
                now = Math.max(now, ev.time);
                if (ev.toReceiver) receiver.onData(ev.bytes);
                else sender.onAck(ev.bytes);
            } else if (toT != Long.MAX_VALUE) {
                now = Math.max(now, toT);
                sender.tick();
            } else {
                sender.pump();
            }
            maxInFlight = Math.max(maxInFlight, sender.inFlight());
        }

        for (int i = 0; i < n; i++) {
            if (!delivered[i]) throw new AssertionError("falta segmento " + i);
        }
        System.out.printf("rwnd anunciado=%d · máximo en vuelo observado=%d%n", rwnd, maxInFlight);
        if (maxInFlight > rwnd) {
            System.out.println("FALLO: el emisor excedió la ventana de recepción");
            System.exit(1);
        }
        System.out.println("OK  el emisor nunca excede rwnd (flow control) y entrega todo");
        System.out.println("FlowControlTest paso.");
    }
}

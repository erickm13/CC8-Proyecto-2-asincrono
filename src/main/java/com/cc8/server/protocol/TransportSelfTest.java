package com.cc8.server.protocol;

import java.util.PriorityQueue;
import java.util.Random;

/**
 * Prueba del transporte confiable con un enlace SIMULADO (perdida, retardo y
 * reordenamiento) y un reloj virtual. Demuestra que:
 * <ul>
 *   <li>Todos los segmentos se entregan intactos pese a la perdida.</li>
 *   <li>Selective Repeat + SACK evitan retransmitir lo ya recibido.</li>
 *   <li>El control de congestion reacciona (ssthresh baja ante perdida).</li>
 * </ul>
 */
public final class TransportSelfTest {

    // Reloj virtual compartido.
    private static long now = 0;

    /** Evento de red: entrega de un datagrama en cierto instante. */
    private record Event(long time, long seqTie, boolean toReceiver, byte[] bytes) {
    }

    public static void main(String[] args) {
        run("Sin perdida, en orden", 2000, 0.0, 0.0, 0);
        run("Reordenamiento fuerte, sin perdida", 2000, 0.0, 0.0, 80);
        run("Perdida 10% (datos y ACKs)", 2000, 0.10, 0.10, 4);
        run("Perdida 30% severa", 1000, 0.30, 0.30, 4);
        System.out.println("Todas las pruebas de transporte pasaron.");
    }

    private static void run(String title, int n, double dataLoss, double ackLoss, long jitter) {
        now = 0;
        Random rnd = new Random(12345);
        PriorityQueue<Event> net = new PriorityQueue<>(
                (a, b) -> a.time != b.time ? Long.compare(a.time, b.time)
                        : Long.compare(a.seqTie, b.seqTie));
        long[] tie = {0};

        final long baseDelay = 25;
        final long serialTime = 1;      // ms por segmento (ancho de banda del enlace)
        long[] dataFree = {0};          // instante en que el enlace de datos queda libre
        long[] ackFree = {0};

        // Sumidero que verifica integridad y unicidad de cada payload.
        boolean[] delivered = new boolean[n];
        long[] deliveredCount = {0};
        DeliverySink sink = (seq, payload) -> {
            int idx = Wire.getInt(payload, 0);
            if (idx != seq) throw new AssertionError("payload no coincide con seq");
            if (idx < 0 || idx >= n) throw new AssertionError("seq fuera de rango");
            if (!delivered[idx]) {
                delivered[idx] = true;
                deliveredCount[0]++;
            }
        };

        // Fuente fija de n payloads (cada uno lleva su indice codificado).
        SegmentSource source = new SegmentSource() {
            int i = 0;
            public boolean hasNext() { return i < n; }
            public byte[] next() {
                byte[] p = new byte[64];
                Wire.putInt(p, 0, i);
                i++;
                return p;
            }
        };

        // Enlaces con perdida/retardo. El receptor se referencia luego.
        ReliableReceiver[] receiverRef = new ReliableReceiver[1];
        Link dataLink = bytes -> schedule(net, tie, rnd, dataLoss, baseDelay, jitter, serialTime, dataFree, true, bytes);
        Link ackLink = bytes -> schedule(net, tie, rnd, ackLoss, baseDelay, jitter, serialTime, ackFree, false, bytes);

        ReliableReceiver receiver = new ReliableReceiver(ackLink, sink, 1 << 20);
        receiverRef[0] = receiver;
        ReliableSender sender = new ReliableSender(dataLink, source, () -> now);

        // Bucle de eventos discreto.
        sender.pump();
        long guard = 0;
        while (!sender.isDone()) {
            if (++guard > 20_000_000L) throw new AssertionError("no converge: " + title);
            Long evT = net.isEmpty() ? null : net.peek().time;
            long toT = sender.nextTimeoutAt();

            if (evT != null && (toT == Long.MAX_VALUE || evT <= toT)) {
                Event ev = net.poll();
                now = Math.max(now, ev.time);
                if (ev.toReceiver) {
                    receiver.onData(ev.bytes);
                } else {
                    sender.onAck(ev.bytes);
                }
            } else if (toT != Long.MAX_VALUE) {
                now = Math.max(now, toT);
                sender.tick();
            } else {
                sender.pump(); // sin eventos ni timer: reintentar
                if (net.isEmpty() && sender.nextTimeoutAt() == Long.MAX_VALUE && !sender.isDone()) {
                    throw new AssertionError("estancado: " + title);
                }
            }
        }

        // Verificacion: cobertura total.
        for (int i = 0; i < n; i++) {
            if (!delivered[i]) throw new AssertionError("falta segmento " + i + " en " + title);
        }

        System.out.printf("%n== %s ==%n", title);
        System.out.printf("  entregados=%d/%d  duplicados_receptor=%d%n",
                deliveredCount[0], n, receiver.duplicates());
        System.out.printf("  enviados=%d  retransmisiones=%d  timeouts=%d  fastRetx=%d%n",
                sender.sent(), sender.retransmits(), sender.timeouts(), sender.fastRetx());
        System.out.printf("  cwnd_final=%.1f  ssthresh_final=%.1f  srtt=%.1fms  rto=%dms%n",
                sender.cwnd(), sender.ssthresh(), sender.srtt(), sender.rto());
        double overhead = 100.0 * sender.retransmits() / n;
        System.out.printf("  overhead_retransmision=%.1f%%%n", overhead);
    }

    private static void schedule(PriorityQueue<Event> net, long[] tie, Random rnd,
                                 double loss, long baseDelay, long jitter, long serialTime,
                                 long[] linkFree, boolean toReceiver, byte[] bytes) {
        // Serializacion: los paquetes se ponen "en el cable" en orden (el enlace
        // tiene ancho de banda finito), luego suman propagacion + jitter.
        long txStart = Math.max(now, linkFree[0]);
        long txEnd = txStart + serialTime;
        linkFree[0] = txEnd;
        if (rnd.nextDouble() < loss) {
            return; // datagrama perdido (tras ocupar el cable)
        }
        long arrival = txEnd + baseDelay + (long) (rnd.nextDouble() * jitter);
        net.add(new Event(arrival, tie[0]++, toReceiver, bytes.clone()));
    }
}

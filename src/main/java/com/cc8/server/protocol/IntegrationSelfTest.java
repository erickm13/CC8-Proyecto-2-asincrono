package com.cc8.server.protocol;

import com.cc8.server.image.ClientReconstructor;
import com.cc8.server.image.H2kFormat;
import com.cc8.server.image.H2kReader;
import com.cc8.server.image.H2kWriter;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;

/**
 * Prueba END-TO-END del lado servidor: Scheduler -> ReliableSender -> enlace con
 * perdida -> ReliableReceiver -> ensamblador -> ClientReconstructor. Verifica
 * que la imagen se entrega y se reconstruye SIN PERDIDA pese a la perdida de red,
 * validando la integracion de las Fases 3a (scheduler) y 3b (transporte).
 */
public final class IntegrationSelfTest {

    private static long now = 0;

    private record Event(long time, long tie, boolean toReceiver, byte[] bytes) {
    }

    /** Acumulador de un precinct recibido. */
    private static final class Acc {
        int level, py, px, numCoeffs, numPlanes;
        byte[][] layers;
    }

    public static void main(String[] args) throws Exception {
        int w = 700, h = 500;
        BufferedImage src = synthetic(w, h);

        Path tmp = Files.createTempFile("integration", ".h2k");
        new H2kWriter(256, 4, 64).write(src, tmp);

        try (H2kReader reader = new H2kReader(tmp)) {
            H2kFormat.Header hdr = reader.header();
            Scheduler scheduler = new Scheduler(reader);
            // Viewport = imagen completa, maxima resolucion y todas las capas.
            scheduler.setViewport(0, 0, w, h, hdr.levels(), 999);

            // tile -> comp -> precinctKey -> Acc
            Map<Integer, Map<Integer, Map<Long, Acc>>> store = new HashMap<>();

            DeliverySink sink = (seq, payload) -> {
                ImagePacket.Packet pk = ImagePacket.decode(payload);
                Acc acc = store
                        .computeIfAbsent(pk.tile(), k -> new HashMap<>())
                        .computeIfAbsent(pk.comp(), k -> new HashMap<>())
                        .computeIfAbsent(key(pk.level(), pk.py(), pk.px()), k -> new Acc());
                acc.level = pk.level();
                acc.py = pk.py();
                acc.px = pk.px();
                acc.numCoeffs = pk.numCoeffs();
                acc.numPlanes = pk.numPlanes();
                if (acc.layers == null) {
                    acc.layers = new byte[pk.numPlanes()][];
                }
                acc.layers[pk.layer()] = pk.data();
            };

            runNetwork(scheduler, sink, 0.10);

            // Reconstruir y comparar con el original (debe ser exacto).
            int ts = hdr.tileSize();
            long mismatches = 0;
            for (int ty = 0; ty < hdr.tilesY(); ty++) {
                for (int tx = 0; tx < hdr.tilesX(); tx++) {
                    int tile = ty * hdr.tilesX() + tx;
                    int[][] comp = new int[3][];
                    for (int c = 0; c < 3; c++) {
                        comp[c] = ClientReconstructor.component(hdr, precincts(store, tile, c));
                    }
                    int x0 = tx * ts, y0 = ty * ts;
                    int validW = Math.min(ts, w - x0), validH = Math.min(ts, h - y0);
                    for (int yy = 0; yy < validH; yy++) {
                        for (int xx = 0; xx < validW; xx++) {
                            int p = yy * ts + xx;
                            int rgb = (comp[0][p] << 16) | (comp[1][p] << 8) | comp[2][p];
                            if ((src.getRGB(x0 + xx, y0 + yy) & 0xFFFFFF) != rgb) {
                                mismatches++;
                            }
                        }
                    }
                }
            }
            if (mismatches != 0) {
                System.out.println("FALLO: " + mismatches + " pixeles distintos");
                System.exit(1);
            }
            System.out.println("OK  imagen reconstruida SIN PERDIDA tras transporte con 10% de perdida");
            System.out.println("IntegrationSelfTest paso.");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static List<ClientReconstructor.Precinct> precincts(
            Map<Integer, Map<Integer, Map<Long, Acc>>> store, int tile, int comp) {
        List<ClientReconstructor.Precinct> list = new ArrayList<>();
        Map<Integer, Map<Long, Acc>> byComp = store.get(tile);
        if (byComp == null) return list;
        Map<Long, Acc> byKey = byComp.get(comp);
        if (byKey == null) return list;
        for (Acc a : byKey.values()) {
            int received = 0;
            while (received < a.numPlanes && a.layers[received] != null) {
                received++;
            }
            list.add(new ClientReconstructor.Precinct(
                    a.level, a.py, a.px, a.numCoeffs, a.numPlanes, a.layers, received));
        }
        return list;
    }

    private static void runNetwork(Scheduler scheduler, DeliverySink sink, double loss) {
        now = 0;
        Random rnd = new Random(99);
        PriorityQueue<Event> net = new PriorityQueue<>(
                (a, b) -> a.time != b.time ? Long.compare(a.time, b.time)
                        : Long.compare(a.tie, b.tie));
        long[] tie = {0};
        long[] dataFree = {0}, ackFree = {0};
        final long base = 20, serial = 1, jitter = 4;

        Link dataLink = bytes -> {
            long tx = Math.max(now, dataFree[0]) + serial;
            dataFree[0] = tx;
            if (rnd.nextDouble() >= loss) {
                net.add(new Event(tx + base + (long) (rnd.nextDouble() * jitter), tie[0]++, true, bytes.clone()));
            }
        };
        ReliableReceiver receiver = new ReliableReceiver(
                bytes -> {
                    long tx = Math.max(now, ackFree[0]) + serial;
                    ackFree[0] = tx;
                    if (rnd.nextDouble() >= loss) {
                        net.add(new Event(tx + base + (long) (rnd.nextDouble() * jitter), tie[0]++, false, bytes.clone()));
                    }
                }, sink, 1 << 20);
        ReliableSender sender = new ReliableSender(dataLink, scheduler, () -> now);

        sender.pump();
        long guard = 0;
        while (!sender.isDone()) {
            if (++guard > 50_000_000L) throw new AssertionError("no converge");
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
                if (net.isEmpty() && sender.nextTimeoutAt() == Long.MAX_VALUE && !sender.isDone()) {
                    throw new AssertionError("estancado");
                }
            }
        }
        System.out.printf("Transporte: enviados=%d retransmisiones=%d timeouts=%d cwnd=%.1f%n",
                sender.sent(), sender.retransmits(), sender.timeouts(), sender.cwnd());
    }

    private static long key(int level, int py, int px) {
        return ((long) level << 40) | ((long) py << 20) | px;
    }

    private static BufferedImage synthetic(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int r = (x * 255) / w;
                int g = (y * 255) / h;
                int b = (x ^ y) & 0xFF;
                img.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        return img;
    }
}

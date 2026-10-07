package com.cc8.server.protocol;

import com.cc8.server.image.H2kFormat;
import com.cc8.server.image.H2kReader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Scheduler: decide QUE paquete de imagen enviar a continuacion (es la fuente
 * del emisor confiable). Dado el viewport y el zoom, enumera los paquetes
 * candidatos (tile, componente, nivel, precinct, capa) que faltan y los ordena
 * por prioridad:
 *
 * <pre>
 *   prioridad = (nivel de resolucion, capa, Hilbert(tile), Hilbert(precinct))
 * </pre>
 *
 * es decir, grueso-a-fino (aparece borroso y se refina), y dentro de cada paso
 * se llena el espacio en orden de curva de Hilbert (localidad). Al cambiar el
 * viewport se reconstruye la cola excluyendo lo ya enviado.
 */
public final class Scheduler implements RapidScheduler {

    private final H2kReader reader;
    private static final SharedPacketStore DEFAULT_STORE = new SharedPacketStore();
    private final SharedPacketStore store;
    private final H2kFormat.Header h;
    private final Map<Integer, H2kFormat.TileIndex> tileCache = new HashMap<>();
    private final Set<Long> sent = new HashSet<>();
    private final int hilbertTileN;
    private final int hilbertPrecN;

    private List<Cand> queue = new ArrayList<>();
    private int cursor = 0;
    private int mode;
    private int vx, vy, vw, vh, vlevel, vlayers;
    private double dx, dy, confidence, load;
    private long viewportNanos, deadlineMisses;
    private double prefetchTokens = 65536;
    private long tokenNanos = System.nanoTime();
    private boolean rapidExhausted;
    private static final int MAX_CANDIDATES = 65536;

    /** Margen de tiles alrededor del viewport que se precargan (prefetch). */
    private static final int PREFETCH_MARGIN = 1;

    /** Tope de tiles a enumerar por viewport (la vista alejada usa el overview). */
    private static final int MAX_VIEWPORT_TILES = 256;

    private record Cand(int tile, int comp, H2kFormat.PacketIndex p, int layer,
                        int deadline, double utility, long hilbert) {
    }

    public Scheduler(H2kReader reader) {
        this(reader, DEFAULT_STORE);
    }

    public Scheduler(H2kReader reader, SharedPacketStore store) {
        this.reader = reader;
        this.store = java.util.Objects.requireNonNull(store);
        this.h = reader.header();
        this.hilbertTileN = HilbertCurve.nextPow2(Math.max(1, Math.max(h.tilesX(), h.tilesY())));
        this.hilbertPrecN = HilbertCurve.nextPow2(Math.max(1, h.tileSize() / h.precinct()));
    }

    /** 0=legacy, 1=RAPID Cobertura EDF, 2=RAPID Predictivo DRR. */
    public synchronized void setMode(int mode) {
        if (mode < 0 || mode > 2) throw new IllegalArgumentException("mode must be 0, 1 or 2");
        if (this.mode != mode) {
            this.mode = mode;
            setViewport(vx, vy, vw, vh, vlevel, vlayers);
        }
    }

    /** Number of visible coarse (250 ms) and base (1000 ms) deadlines missed. */
    public synchronized long deadlineMisses() { return deadlineMisses; }

    /** Refresh current load without resetting viewport deadlines. */
    public synchronized void setLoad(double load) {
        double previous = this.load;
        this.load = fraction(load);
        if (mode != 0 && ((previous >= 0.7) != (this.load >= 0.7)
                || (previous >= 0.85) != (this.load >= 0.85))) {
            rapidExhausted = false;
            buildRapidQueue();
        }
    }

    /** Direction components are normalized to [-1,1]; confidence and load are in [0,1]. */
    public synchronized void setViewport(int x, int y, int w, int h0,
                                         int maxLevel, int maxLayers,
                                         double dx, double dy, double confidence, double load) {
        this.dx = unit(dx); this.dy = unit(dy);
        this.confidence = fraction(confidence); this.load = fraction(load);
        setViewport(x, y, w, h0, maxLevel, maxLayers);
    }

    /**
     * Fija la zona visible (coordenadas de la imagen completa), el maximo nivel
     * de resolucion y el maximo de capas, y reconstruye la cola de prioridad
     * con lo que aun falta. Orden de prioridad (de mas a menos urgente):
     * <ol>
     *   <li><b>deadline</b>: 0 = tile visible ahora; 1 = anillo de prefetch.</li>
     *   <li><b>resolucion</b>: nivel ascendente (grueso -> fino), para que la
     *       imagen aparezca completa y borrosa y se vaya afinando.</li>
     *   <li><b>capa de calidad</b>: plano de bits ascendente (MSB -> LSB) dentro
     *       del nivel. Garantiza que cada precinct reciba sus planos en orden
     *       contiguo (requisito del decodificador) y da una progresion por
     *       calidad (estilo JPEG2000).</li>
     *   <li><b>utilidad/byte</b> descendente ENTRE precincts del mismo plano:
     *       reduccion de distorsion por byte (rate-distortion). Para el plano p
     *       de un precinct con N coeficientes y B bytes: utilidad = N · 2^(2p)/B.
     *       Prioriza los precincts mas "informativos" (mas detalle por byte).</li>
     *   <li><b>Hilbert</b> (tile y precinct) como desempate espacial.</li>
     * </ol>
     */
    public synchronized void setViewport(int x, int y, int w, int h0,
                                         int maxLevel, int maxLayers) {
        vx = x; vy = y; vw = w; vh = h0; vlevel = maxLevel; vlayers = maxLayers;
        viewportNanos = System.nanoTime();
        if (mode != 0) {
            rapidExhausted = false;
            buildRapidQueue();
            return;
        }
        int ts = h.tileSize();
        int txMin = clamp(x / ts, 0, h.tilesX() - 1);
        int tyMin = clamp(y / ts, 0, h.tilesY() - 1);
        int txMax = clamp((x + w - 1) / ts, 0, h.tilesX() - 1);
        int tyMax = clamp((y + h0 - 1) / ts, 0, h.tilesY() - 1);

        // Rango ampliado con el margen de prefetch.
        int txMinP = clamp(txMin - PREFETCH_MARGIN, 0, h.tilesX() - 1);
        int tyMinP = clamp(tyMin - PREFETCH_MARGIN, 0, h.tilesY() - 1);
        int txMaxP = clamp(txMax + PREFETCH_MARGIN, 0, h.tilesX() - 1);
        int tyMaxP = clamp(tyMax + PREFETCH_MARGIN, 0, h.tilesY() - 1);

        // Proteccion: si el rango es enorme (vista muy alejada en una imagen
        // gigante), limitar la enumeracion a una ventana centrada; la vista
        // alejada se cubre con el overview, no pidiendo decenas de miles de tiles.
        if ((long) (txMaxP - txMinP + 1) * (tyMaxP - tyMinP + 1) > MAX_VIEWPORT_TILES) {
            int cx = (txMin + txMax) / 2, cy = (tyMin + tyMax) / 2;
            int side = (int) Math.sqrt(MAX_VIEWPORT_TILES) / 2;
            txMinP = clamp(cx - side, 0, h.tilesX() - 1);
            txMaxP = clamp(cx + side, 0, h.tilesX() - 1);
            tyMinP = clamp(cy - side, 0, h.tilesY() - 1);
            tyMaxP = clamp(cy + side, 0, h.tilesY() - 1);
        }

        List<Cand> cands = new ArrayList<>();
        for (int ty = tyMinP; ty <= tyMaxP; ty++) {
            for (int tx = txMinP; tx <= txMaxP; tx++) {
                boolean visible = tx >= txMin && tx <= txMax && ty >= tyMin && ty <= tyMax;
                int deadline = visible ? 0 : 1;
                int tile = ty * h.tilesX() + tx;
                long tileH = HilbertCurve.xy2d(hilbertTileN, tx, ty);
                H2kFormat.TileIndex idx = tileIndex(tile);
                for (int comp = 0; comp < h.components(); comp++) {
                    for (H2kFormat.PacketIndex p : idx.components().get(comp)) {
                        if (p.level() > maxLevel) {
                            continue;
                        }
                        int layers = Math.min(maxLayers, p.numPlanes());
                        long precH = HilbertCurve.xy2d(hilbertPrecN,
                                Math.min(p.px(), hilbertPrecN - 1),
                                Math.min(p.py(), hilbertPrecN - 1));
                        long hilbert = (tileH << 20) | (precH & 0xFFFFF);
                        for (int layer = 0; layer < layers; layer++) {
                            long id = packetId(tile, comp, p.level(), p.py(), p.px(), layer);
                            if (sent.contains(id)) {
                                continue;
                            }
                            double utility = utilityPerByte(p, layer);
                            cands.add(new Cand(tile, comp, p, layer, deadline, utility, hilbert));
                        }
                    }
                }
            }
        }
        cands.sort(Comparator
                .comparingInt(Cand::deadline)                    // 1) visible antes que prefetch
                .thenComparingInt(c -> c.p().level())            // 2) resolucion: grueso -> fino
                .thenComparingInt(Cand::layer)                   // 3) calidad: plano MSB -> LSB (contiguo)
                .thenComparing(Comparator.comparingDouble(Cand::utility).reversed()) // 4) utilidad/byte entre precincts
                .thenComparingLong(Cand::hilbert));              // 5) localidad espacial
        this.queue = cands;
        this.cursor = 0;
    }

    /**
     * Utilidad por byte (rate-distortion) del paquete de la capa {@code layer}.
     * La capa L corresponde al plano de bits p = numPlanes-1-L; anadir ese plano
     * reduce el error cuadratico de cada coeficiente en una cantidad proporcional
     * a 2^(2p). Dividido entre los bytes comprimidos da la ganancia por byte.
     */
    private static double utilityPerByte(H2kFormat.PacketIndex p, int layer) {
        int plane = p.numPlanes() - 1 - layer;
        double gain = (double) p.numCoeffs() * Math.scalb(1.0, 2 * plane); // N · 2^(2p)
        int bytes = Math.max(1, p.layerLen()[layer]);
        return gain / bytes;
    }

    /**
     * "Olvida" un tile: quita sus paquetes del conjunto de ya-enviados para que
     * se reenvien cuando el tile vuelva al viewport. Lo invoca el cliente al
     * desalojar de su cache (LRU) -> retransmision a nivel de aplicacion.
     */
    public synchronized void forget(int tile) {
        sent.removeIf(id -> ((id >>> 37) & 0xFFFFFF) == tile);
    }

    /** Restore one staged image packet that was never handed to the transport. */
    @Override
    public synchronized void requeueUnsent(byte[] imagePacket) {
        ImagePacket.Packet packet = ImagePacket.decode(imagePacket);
        sent.remove(packetId(packet.tile(), packet.comp(), packet.level(),
                packet.py(), packet.px(), packet.layer()));
    }

    @Override
    public synchronized boolean hasNext() {
        if (mode != 0 && cursor >= queue.size() && !rapidExhausted) buildRapidQueue();
        if (mode != 0 && cursor < queue.size() && queue.get(cursor).deadline == 1) {
            refillTokens();
            return prefetchTokens >= queue.get(cursor).p.layerLen()[queue.get(cursor).layer];
        }
        return cursor < queue.size();
    }

    @Override
    public synchronized byte[] next() {
        if (mode != 0 && cursor >= queue.size() && !rapidExhausted) buildRapidQueue();
        if (cursor >= queue.size()) {
            return null;
        }
        if (mode != 0 && queue.get(cursor).deadline == 1) {
            refillTokens();
            if (prefetchTokens < queue.get(cursor).p.layerLen()[queue.get(cursor).layer]) return null;
        }
        Cand c = queue.get(cursor++);
        if (mode != 0 && c.deadline == 1) {
            refillTokens();
            prefetchTokens = Math.max(0, prefetchTokens - c.p.layerLen()[c.layer]);
        }
        long id = packetId(c.tile, c.comp, c.p.level(), c.p.py(), c.p.px(), c.layer);
        sent.add(id);
        try {
            byte[] data = store.read(reader, c.p, c.layer);
            if (mode != 0 && c.deadline == 0 && c.layer == 0) {
                long elapsed = System.nanoTime() - viewportNanos;
                if (elapsed > (c.p.level() == 0 ? 250_000_000L : 1_000_000_000L)) deadlineMisses++;
            }
            if (mode == 2 && c.deadline == 0 && load < 0.85
                    && c.layer + 1 < Math.min(vlayers, c.p.numPlanes())) {
                int nextLayer = c.layer + 1;
                Cand successor = new Cand(c.tile, c.comp, c.p, nextLayer, 0,
                        utilityPerByte(c.p, nextLayer), c.hilbert);
                if (queue.size() - cursor < MAX_CANDIDATES) {
                    if (cursor > 1024) {
                        queue.subList(0, cursor).clear();
                        cursor = 0;
                    }
                    Comparator<Cand> order = rapidOrder();
                    int at = java.util.Collections.binarySearch(queue.subList(cursor, queue.size()), successor, order);
                    queue.add(cursor + (at < 0 ? -at - 1 : at), successor);
                }
            }
            return ImagePacket.encode(c.tile, c.comp, c.p.level(), c.p.py(), c.p.px(),
                    c.layer, c.p.numPlanes(), c.p.numCoeffs(), data);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private H2kFormat.TileIndex tileIndex(int tile) {
        return tileCache.computeIfAbsent(tile, t -> {
            try {
                return reader.readTileIndex(t);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    // Identidad del paquete: tile(24b) comp(3b) level(4b) py(11b) px(11b) layer(8b)
    private static long packetId(int tile, int comp, int level, int py, int px, int layer) {
        return ((long) (tile & 0xFFFFFF) << 37)
                | ((long) (comp & 0x7) << 34)
                | ((long) (level & 0xF) << 30)
                | ((long) (py & 0x7FF) << 19)
                | ((long) (px & 0x7FF) << 8)
                | (layer & 0xFF);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static double fraction(double v) {
        return Double.isFinite(v) ? Math.max(0, Math.min(1, v)) : 0;
    }

    private static double unit(double v) {
        return Double.isFinite(v) ? Math.max(-1, Math.min(1, v)) : 0;
    }

    private int phase(Cand c) {
        if (c.deadline == 1) return 3;
        if (c.p.level() == 0 && c.layer == 0) return 0;
        if (mode == 1 && c.layer == 0) return 1;
        return 2;
    }

    private Comparator<Cand> rapidOrder() {
        Comparator<Cand> order = Comparator.comparingInt(this::phase);
        if (mode == 1) order = order.thenComparingInt(c -> c.p.level())
                .thenComparingInt(Cand::layer);
        return order.thenComparing(Comparator.comparingDouble(Cand::utility).reversed())
                .thenComparingLong(Cand::hilbert).thenComparingInt(Cand::comp);
    }

    private void buildRapidQueue() {
        if (vw <= 0 || vh <= 0 || vlevel < 0 || vlayers <= 0) {
            queue = List.of(); cursor = 0; rapidExhausted = true; return;
        }
        int ts = h.tileSize();
        int x0 = clamp(Math.floorDiv(vx, ts), 0, h.tilesX() - 1);
        int y0 = clamp(Math.floorDiv(vy, ts), 0, h.tilesY() - 1);
        int x1 = clamp((int) Math.floorDiv((long) vx + vw - 1, ts), 0, h.tilesX() - 1);
        int y1 = clamp((int) Math.floorDiv((long) vy + vh - 1, ts), 0, h.tilesY() - 1);
        int px0 = Math.max(0, x0 - 1), py0 = Math.max(0, y0 - 1);
        int px1 = Math.min(h.tilesX() - 1, x1 + 1), py1 = Math.min(h.tilesY() - 1, y1 + 1);
        if ((long) (px1 - px0 + 1) * (py1 - py0 + 1) > MAX_VIEWPORT_TILES) {
            int cx = (x0 + x1) / 2, cy = (y0 + y1) / 2;
            px0 = clamp(cx - 8, 0, h.tilesX() - 1); px1 = clamp(cx + 7, 0, h.tilesX() - 1);
            py0 = clamp(cy - 8, 0, h.tilesY() - 1); py1 = clamp(cy + 7, 0, h.tilesY() - 1);
        }
        refillTokens();
        Comparator<Cand> order = rapidOrder();
        PriorityQueue<Cand> best = new PriorityQueue<>(MAX_CANDIDATES, order.reversed());
        for (int ty = py0; ty <= py1; ty++) for (int tx = px0; tx <= px1; tx++) {
            boolean visible = tx >= x0 && tx <= x1 && ty >= y0 && ty <= y1;
            if (!visible) {
                if (load >= 0.7 || prefetchTokens < 1) continue;
                if (mode == 2 && !predictiveStrip(tx, ty, x0, y0, x1, y1)) continue;
            }
            int tile = ty * h.tilesX() + tx;
            long tileH = HilbertCurve.xy2d(hilbertTileN, tx, ty);
            H2kFormat.TileIndex idx = tileIndex(tile);
            for (int comp = 0; comp < h.components(); comp++) {
                for (H2kFormat.PacketIndex p : idx.components().get(comp)) {
                    if (p.level() > vlevel) continue;
                    int layers = Math.min(vlayers, p.numPlanes());
                    long precH = HilbertCurve.xy2d(hilbertPrecN,
                            Math.min(p.px(), hilbertPrecN - 1), Math.min(p.py(), hilbertPrecN - 1));
                    long hilbert = (tileH << 20) | (precH & 0xFFFFF);
                    for (int layer = 0; layer < layers; layer++) {
                        if (!visible && (layer > 0 || p.level() > 0)) break;
                        if (visible && load >= 0.85 && layer > 0) break;
                        long id = packetId(tile, comp, p.level(), p.py(), p.px(), layer);
                        if (sent.contains(id)) continue;
                        Cand c = new Cand(tile, comp, p, layer, visible ? 0 : 1,
                                utilityPerByte(p, layer), hilbert);
                        if (best.size() < MAX_CANDIDATES) best.add(c);
                        else if (order.compare(c, best.peek()) < 0) {
                            best.poll(); best.add(c);
                        }
                        if (mode == 2) break; // only the first unsent eligible layer
                    }
                }
            }
        }
        ArrayList<Cand> selected = new ArrayList<>(best);
        selected.sort(order);
        queue = selected;
        cursor = 0;
        rapidExhausted = selected.isEmpty();
    }

    private boolean predictiveStrip(int tx, int ty, int x0, int y0, int x1, int y1) {
        if (confidence <= 0) return false;
        if (Math.abs(dx) >= Math.abs(dy) && dx != 0) {
            if (tx != (dx > 0 ? x1 + 1 : x0 - 1)) return false;
            int keep = Math.max(1, (int) Math.ceil((y1 - y0 + 1) * confidence));
            int start = (y0 + y1 - keep + 1) / 2;
            return ty >= start && ty < start + keep;
        }
        if (dy == 0) return false;
        if (ty != (dy > 0 ? y1 + 1 : y0 - 1)) return false;
        int keep = Math.max(1, (int) Math.ceil((x1 - x0 + 1) * confidence));
        int start = (x0 + x1 - keep + 1) / 2;
        return tx >= start && tx < start + keep;
    }

    private void refillTokens() {
        long now = System.nanoTime();
        prefetchTokens = Math.min(65536, prefetchTokens + Math.max(0, now - tokenNanos) * 65536.0 / 1_000_000_000.0);
        tokenNanos = now;
    }
}

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
public final class Scheduler implements SegmentSource {

    private final H2kReader reader;
    private final H2kFormat.Header h;
    private final Map<Integer, H2kFormat.TileIndex> tileCache = new HashMap<>();
    private final Set<Long> sent = new HashSet<>();
    private final int hilbertTileN;
    private final int hilbertPrecN;

    private List<Cand> queue = new ArrayList<>();
    private int cursor = 0;

    /** Margen de tiles alrededor del viewport que se precargan (prefetch). */
    private static final int PREFETCH_MARGIN = 1;

    /** Tope de tiles a enumerar por viewport (la vista alejada usa el overview). */
    private static final int MAX_VIEWPORT_TILES = 256;

    private record Cand(int tile, int comp, H2kFormat.PacketIndex p, int layer,
                        int deadline, double utility, long hilbert) {
    }

    public Scheduler(H2kReader reader) {
        this.reader = reader;
        this.h = reader.header();
        this.hilbertTileN = HilbertCurve.nextPow2(Math.max(1, Math.max(h.tilesX(), h.tilesY())));
        this.hilbertPrecN = HilbertCurve.nextPow2(Math.max(1, h.tileSize() / h.precinct()));
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

    @Override
    public synchronized boolean hasNext() {
        return cursor < queue.size();
    }

    @Override
    public synchronized byte[] next() {
        if (cursor >= queue.size()) {
            return null;
        }
        Cand c = queue.get(cursor++);
        long id = packetId(c.tile, c.comp, c.p.level(), c.p.py(), c.p.px(), c.layer);
        sent.add(id);
        try {
            byte[] data = reader.readPacket(c.p, c.layer);
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
}

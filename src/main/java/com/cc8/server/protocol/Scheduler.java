package com.cc8.server.protocol;

import com.cc8.server.image.H2kFormat;
import com.cc8.server.image.H2kReader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
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

    private record Cand(int tile, int comp, H2kFormat.PacketIndex p, int layer, long key) {
    }

    public Scheduler(H2kReader reader) {
        this.reader = reader;
        this.h = reader.header();
        this.hilbertTileN = HilbertCurve.nextPow2(Math.max(1, Math.max(h.tilesX(), h.tilesY())));
        this.hilbertPrecN = HilbertCurve.nextPow2(Math.max(1, h.tileSize() / h.precinct()));
    }

    /**
     * Fija la zona visible (en coordenadas de la imagen completa), el maximo
     * nivel de resolucion y el maximo de capas de calidad a entregar, y
     * reconstruye la cola de prioridad con lo que aun falta.
     */
    public synchronized void setViewport(int x, int y, int w, int h0,
                                         int maxLevel, int maxLayers) {
        int ts = h.tileSize();
        int txMin = clamp(x / ts, 0, h.tilesX() - 1);
        int tyMin = clamp(y / ts, 0, h.tilesY() - 1);
        int txMax = clamp((x + w - 1) / ts, 0, h.tilesX() - 1);
        int tyMax = clamp((y + h0 - 1) / ts, 0, h.tilesY() - 1);

        List<Cand> cands = new ArrayList<>();
        for (int ty = tyMin; ty <= tyMax; ty++) {
            for (int tx = txMin; tx <= txMax; tx++) {
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
                        for (int layer = 0; layer < layers; layer++) {
                            long id = packetId(tile, comp, p.level(), p.py(), p.px(), layer);
                            if (sent.contains(id)) {
                                continue;
                            }
                            long key = priorityKey(p.level(), layer, tileH, precH);
                            cands.add(new Cand(tile, comp, p, layer, key));
                        }
                    }
                }
            }
        }
        cands.sort((a, b) -> Long.compareUnsigned(a.key, b.key));
        this.queue = cands;
        this.cursor = 0;
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

    // Prioridad: nivel (6b) | capa (8b) | Hilbert tile (30b) | Hilbert precinct (20b)
    private static long priorityKey(int level, int layer, long tileH, long precH) {
        return ((long) (level & 0x3F) << 58)
                | ((long) (layer & 0xFF) << 50)
                | ((tileH & 0x3FFFFFFF) << 20)
                | (precH & 0xFFFFF);
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

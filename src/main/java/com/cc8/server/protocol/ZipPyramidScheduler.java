package com.cc8.server.protocol;

import com.cc8.server.image.ZipPyramidReader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

/** Schedules PNG pyramid tiles for the three RAPID policies. */
public final class ZipPyramidScheduler implements RapidScheduler {
    private static final int MAX_VISIBLE_PER_LEVEL = 256;
    private static final int MAX_QUEUE = 4096;
    private static final double TOKEN_RATE = 65_536;
    private final ZipPyramidReader reader;
    private final Set<Long> sent = new HashSet<>();
    private List<Candidate> queue = List.of();
    private int cursor, currentPart;
    private byte[] currentPng;
    private int mode, vx, vy, vw, vh, requestedLevel;
    private double dx, dy, confidence, load, tokens = TOKEN_RATE;
    private long tokenNanos = System.nanoTime(), viewportNanos, deadlineMisses;
    private boolean dirty, exhausted;

    private record Candidate(int level, int tx, int ty, int id, int bytes,
                             boolean visible, long hilbert, double utility) { }

    public ZipPyramidScheduler(ZipPyramidReader reader) { this.reader = reader; }

    @Override public synchronized void setMode(int mode) {
        if (mode < 0 || mode > 2) throw new IllegalArgumentException("mode must be 0, 1 or 2");
        if (this.mode != mode) { this.mode = mode; dirty = true; exhausted = false; }
    }

    @Override public synchronized void setLoad(double load) {
        double old = this.load;
        this.load = clamp01(load);
        if ((old >= .7) != (this.load >= .7)
                || (old >= .85) != (this.load >= .85)) {
            dirty = true; exhausted = false;
        }
    }

    @Override public synchronized void setViewport(int x, int y, int w, int h, int level,
            int ignoredLayers, double dx, double dy, double confidence, double load) {
        vx = x; vy = y; vw = w; vh = h;
        requestedLevel = Math.max(0, Math.min(reader.maxLevel(), level));
        this.dx = Math.max(-1, Math.min(1, dx));
        this.dy = Math.max(-1, Math.min(1, dy));
        this.confidence = clamp01(confidence);
        this.load = clamp01(load);
        viewportNanos = System.nanoTime();
        dirty = true; exhausted = false;
        currentPng = null; currentPart = 0;
    }

    public synchronized long deadlineMisses() { return deadlineMisses; }

    @Override public synchronized void forget(int tileId) {
        sent.removeIf(fragment -> (int) (fragment >>> 16) == tileId);
        dirty = true; exhausted = false;
    }

    @Override public synchronized void requeueUnsent(byte[] packet) {
        int id = ZipTilePacket.tileId(packet);
        int part = Wire.getShort(packet, 12);
        sent.remove(fragmentKey(id, part));
        dirty = true; exhausted = false;
    }

    @Override public synchronized boolean hasNext() {
        prepare();
        if (cursor >= queue.size()) return false;
        Candidate c = queue.get(cursor);
        if (c.visible || mode == 0) return true;
        refill();
        return tokens >= Math.min(ZipTilePacket.FRAGMENT,
                c.bytes - currentPart * ZipTilePacket.FRAGMENT);
    }

    @Override public synchronized byte[] next() {
        if (!hasNext()) return null;
        Candidate c = queue.get(cursor);
        try {
            if (currentPng == null) currentPng = reader.tile(c.level, c.tx, c.ty);
            int charge = Math.min(ZipTilePacket.FRAGMENT,
                    currentPng.length - currentPart * ZipTilePacket.FRAGMENT);
            if (!c.visible && mode != 0) {
                refill();
                if (tokens < charge) return null;
                tokens -= charge;
            }
            byte[] packet = ZipTilePacket.encode(c.id, c.level, c.tx, c.ty, currentPart, currentPng);
            sent.add(fragmentKey(c.id, currentPart++));
            if (currentPart * ZipTilePacket.FRAGMENT >= currentPng.length) {
                if (mode == 1 && c.visible) {
                    long elapsed = System.nanoTime() - viewportNanos;
                    if (elapsed > (c.level == 0 ? 250_000_000L : 1_000_000_000L)) deadlineMisses++;
                }
                cursor++; currentPart = 0; currentPng = null;
            }
            return packet;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void prepare() {
        if (dirty) buildQueue();
        while (cursor < queue.size()) {
            Candidate c = queue.get(cursor);
            int parts = (c.bytes + ZipTilePacket.FRAGMENT - 1) / ZipTilePacket.FRAGMENT;
            while (currentPart < parts && sent.contains(fragmentKey(c.id, currentPart))) currentPart++;
            if (currentPart < parts) break;
            cursor++; currentPart = 0; currentPng = null;
        }
        if (cursor >= queue.size() && !exhausted) buildQueue();
    }

    private void buildQueue() {
        dirty = false;
        cursor = 0; currentPart = 0; currentPng = null;
        if (vw <= 0 || vh <= 0) { queue = List.of(); exhausted = true; return; }
        Comparator<Candidate> order = Comparator
                .comparingInt((Candidate c) -> c.visible ? 0 : 1)
                .thenComparingInt(c -> mode == 2 && c.visible && c.level > 0 ? 1 : c.level)
                .thenComparing(mode == 2
                        ? Comparator.comparingDouble(Candidate::utility).reversed()
                        : Comparator.comparingLong(Candidate::hilbert))
                .thenComparingLong(Candidate::hilbert);
        PriorityQueue<Candidate> best = new PriorityQueue<>(MAX_QUEUE, order.reversed());
        for (int level = 0; level <= requestedLevel; level++) {
            // Intermediate pyramid detail is optional under high load; keep
            // the coarse overview and the exact requested resolution.
            if (mode != 0 && load >= .85 && level > 0 && level < requestedLevel) continue;
            long scale = 1L << (reader.maxLevel() - level);
            int width = reader.width(level), height = reader.height(level), size = reader.tileSize();
            long right = Math.min((long) reader.width() - 1, (long) vx + vw - 1);
            long bottom = Math.min((long) reader.height() - 1, (long) vy + vh - 1);
            int x0 = Math.max(0, Math.min(reader.tilesX(level) - 1, (int) (Math.max(0, vx) / scale / size)));
            int y0 = Math.max(0, Math.min(reader.tilesY(level) - 1, (int) (Math.max(0, vy) / scale / size)));
            int x1 = Math.max(0, Math.min(reader.tilesX(level) - 1, (int) (right / scale / size)));
            int y1 = Math.max(0, Math.min(reader.tilesY(level) - 1, (int) (bottom / scale / size)));
            if (right < 0 || bottom < 0 || vx >= reader.width() || vy >= reader.height()) continue;
            if ((long) (x1 - x0 + 1) * (y1 - y0 + 1) > MAX_VISIBLE_PER_LEVEL) {
                int cx = (x0 + x1) / 2, cy = (y0 + y1) / 2;
                x0 = Math.max(0, cx - 7); x1 = Math.min(reader.tilesX(level) - 1, cx + 8);
                y0 = Math.max(0, cy - 7); y1 = Math.min(reader.tilesY(level) - 1, cy + 8);
            }
            int px0 = mode == 0 || load < .7 ? Math.max(0, x0 - 1) : x0;
            int py0 = mode == 0 || load < .7 ? Math.max(0, y0 - 1) : y0;
            int px1 = mode == 0 || load < .7 ? Math.min(reader.tilesX(level) - 1, x1 + 1) : x1;
            int py1 = mode == 0 || load < .7 ? Math.min(reader.tilesY(level) - 1, y1 + 1) : y1;
            int hilbertN = HilbertCurve.nextPow2(Math.max(reader.tilesX(level), reader.tilesY(level)));
            for (int ty = py0; ty <= py1; ty++) for (int tx = px0; tx <= px1; tx++) {
                boolean visible = tx >= x0 && tx <= x1 && ty >= y0 && ty <= y1;
                if (!visible && (level != requestedLevel || (mode == 2
                        && !predictiveStrip(tx, ty, x0, y0, x1, y1)))) continue;
                int id = reader.id(level, tx, ty);
                int bytes = reader.tileBytes(level, tx, ty);
                int parts = (bytes + ZipTilePacket.FRAGMENT - 1) / ZipTilePacket.FRAGMENT;
                boolean needed = false;
                for (int p = 0; p < parts; p++) if (!sent.contains(fragmentKey(id, p))) { needed = true; break; }
                if (!needed) continue;
                long hilbert = HilbertCurve.xy2d(hilbertN, tx, ty);
                double utility = (double) Math.min(size, width - tx * size)
                        * Math.min(size, height - ty * size) / bytes;
                Candidate c = new Candidate(level, tx, ty, id, bytes, visible, hilbert, utility);
                if (best.size() < MAX_QUEUE) best.add(c);
                else if (order.compare(c, best.peek()) < 0) { best.poll(); best.add(c); }
            }
        }
        ArrayList<Candidate> selected = new ArrayList<>(best);
        selected.sort(order);
        queue = selected; exhausted = selected.isEmpty();
    }

    private boolean predictiveStrip(int tx, int ty, int x0, int y0, int x1, int y1) {
        if (confidence <= 0) return false;
        if (Math.abs(dx) >= Math.abs(dy) && dx != 0) {
            if (tx != (dx > 0 ? x1 + 1 : x0 - 1)) return false;
            int keep = Math.max(1, (int) Math.ceil((y1 - y0 + 1) * confidence));
            int start = (y0 + y1 - keep + 1) / 2;
            return ty >= start && ty < start + keep;
        }
        if (dy == 0 || ty != (dy > 0 ? y1 + 1 : y0 - 1)) return false;
        int keep = Math.max(1, (int) Math.ceil((x1 - x0 + 1) * confidence));
        int start = (x0 + x1 - keep + 1) / 2;
        return tx >= start && tx < start + keep;
    }

    private void refill() {
        long now = System.nanoTime();
        tokens = Math.min(TOKEN_RATE, tokens + Math.max(0, now - tokenNanos)
                * TOKEN_RATE / 1_000_000_000.0);
        tokenNanos = now;
    }

    private static double clamp01(double v) {
        return Double.isFinite(v) ? Math.max(0, Math.min(1, v)) : 0;
    }
    private static long fragmentKey(int id, int part) { return ((long) id << 16) | part; }
}

package com.cc8.server.image;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Random access to an overlap-free PNG Deep Zoom pyramid inside one ZIP. */
public final class ZipPyramidReader implements AutoCloseable {
    private static final Pattern TILE = Pattern.compile("(.+_files)/(\\d+)/(\\d+)_(\\d+)\\.png");
    private static final int MAX_TILE_BYTES = 8 * 1024 * 1024;
    private static final int CACHE_BYTES = 64 * 1024 * 1024;
    private static final byte[] PNG = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};

    private final ZipFile zip;
    private final int width, height, tileSize, maxLevel;
    private final int[] widths, heights, tilesX, tilesY, offsets;
    private final Map<Integer, ZipEntry> entries;
    private final LinkedHashMap<Integer, byte[]> cache = new LinkedHashMap<>(128, .75f, true);
    private int cachedBytes;

    public ZipPyramidReader(Path path) throws IOException {
        zip = new ZipFile(path.toFile());
        try {
            List<? extends ZipEntry> all = java.util.Collections.list(zip.entries());
            List<ZipEntry> descriptors = new ArrayList<>();
            for (ZipEntry e : all) if (e.getName().endsWith(".dzi")) descriptors.add(e);
            if (descriptors.size() != 1) throw new IOException("ZIP must contain exactly one .dzi descriptor");
            ZipEntry dzi = descriptors.get(0);
            String stem = dzi.getName().substring(0, dzi.getName().length() - 4);
            String xml;
            try (InputStream in = zip.getInputStream(dzi)) {
                byte[] bytes = in.readNBytes(4097);
                if (bytes.length > 4096) throw new IOException("DZI descriptor too large");
                xml = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            }
            if (!xml.matches("(?s).*\\bFormat=\"png\".*")
                    || !xml.matches("(?s).*\\bOverlap=\"0\".*")) {
                throw new IOException("DZI must use PNG and overlap=0");
            }
            tileSize = attribute(xml, "TileSize");
            width = attribute(xml, "Width");
            height = attribute(xml, "Height");
            if (width <= 0 || height <= 0 || tileSize <= 0 || tileSize > 1024) {
                throw new IOException("Invalid DZI dimensions or tile size");
            }
            int foundMax = -1;
            for (ZipEntry e : all) {
                Matcher m = TILE.matcher(e.getName());
                if (!m.matches() || !m.group(1).equals(stem + "_files")) continue;
                foundMax = Math.max(foundMax, Integer.parseInt(m.group(2)));
            }
            if (foundMax < 0 || foundMax > 30) throw new IOException("Invalid DZI tile levels");
            maxLevel = foundMax;
            widths = new int[maxLevel + 1]; heights = new int[maxLevel + 1];
            tilesX = new int[maxLevel + 1]; tilesY = new int[maxLevel + 1];
            offsets = new int[maxLevel + 1];
            long count = 0;
            for (int level = 0; level <= maxLevel; level++) {
                long scale = 1L << (maxLevel - level);
                widths[level] = (int) ((width + scale - 1) / scale);
                heights[level] = (int) ((height + scale - 1) / scale);
                tilesX[level] = (widths[level] + tileSize - 1) / tileSize;
                tilesY[level] = (heights[level] + tileSize - 1) / tileSize;
                if (count + (long) tilesX[level] * tilesY[level] > Integer.MAX_VALUE) {
                    throw new IOException("Too many DZI tiles for RAPID IDs");
                }
                offsets[level] = (int) count;
                count += (long) tilesX[level] * tilesY[level];
            }
            entries = new HashMap<>((int) Math.min(count * 2, 1_000_000));
            for (ZipEntry e : all) {
                Matcher m = TILE.matcher(e.getName());
                if (!m.matches() || !m.group(1).equals(stem + "_files")) continue;
                int level = Integer.parseInt(m.group(2));
                int tx = Integer.parseInt(m.group(3));
                int ty = Integer.parseInt(m.group(4));
                if (level > maxLevel || tx < 0 || tx >= tilesX[level]
                        || ty < 0 || ty >= tilesY[level] || e.getSize() < 24
                        || e.getSize() > MAX_TILE_BYTES) {
                    throw new IOException("Invalid DZI tile entry: " + e.getName());
                }
                int id = id(level, tx, ty);
                if (entries.put(id, e) != null) throw new IOException("Duplicate DZI tile: " + e.getName());
            }
            if (entries.size() != count) throw new IOException("DZI ZIP is missing tiles: " + entries.size() + "/" + count);
            // Reject a common dzsave mistake: default full Deep Zoom depth renumbers
            // the first level to a tiny pixel, not the requested one-tile overview.
            if (tilesX[0] != 1 || tilesY[0] != 1) throw new IOException("DZI level 0 must be one overview tile");
        } catch (IOException | RuntimeException e) {
            zip.close();
            throw e;
        }
    }

    private static int attribute(String xml, String name) throws IOException {
        Matcher m = Pattern.compile("\\b" + name + "=\"(\\d+)\"").matcher(xml);
        if (!m.find()) throw new IOException("DZI missing " + name);
        try { return Integer.parseInt(m.group(1)); }
        catch (NumberFormatException e) { throw new IOException("Invalid DZI " + name, e); }
    }

    public int width() { return width; }
    public int height() { return height; }
    public int tileSize() { return tileSize; }
    public int maxLevel() { return maxLevel; }
    public int width(int level) { return widths[level]; }
    public int height(int level) { return heights[level]; }
    public int tilesX(int level) { return tilesX[level]; }
    public int tilesY(int level) { return tilesY[level]; }
    public int id(int level, int tx, int ty) { return offsets[level] + ty * tilesX[level] + tx; }
    public int tileBytes(int level, int tx, int ty) {
        return (int) entries.get(id(level, tx, ty)).getSize();
    }

    public synchronized byte[] overviewPng() throws IOException { return tile(0, 0, 0); }

    /** One synchronized load per tile also joins simultaneous client reads. */
    public synchronized byte[] tile(int level, int tx, int ty) throws IOException {
        if (level < 0 || level > maxLevel || tx < 0 || tx >= tilesX[level]
                || ty < 0 || ty >= tilesY[level]) throw new IOException("Tile coordinates out of bounds");
        int id = id(level, tx, ty);
        byte[] bytes = cache.get(id);
        if (bytes != null) return bytes;
        ZipEntry entry = entries.get(id);
        try (InputStream in = zip.getInputStream(entry)) {
            bytes = in.readNBytes(MAX_TILE_BYTES + 1);
        }
        if (bytes.length != entry.getSize() || bytes.length > MAX_TILE_BYTES) {
            throw new IOException("Truncated or oversized DZI tile: " + entry.getName());
        }
        for (int i = 0; i < PNG.length; i++) if (bytes[i] != PNG[i]) {
            throw new IOException("Invalid PNG signature: " + entry.getName());
        }
        if (getInt(bytes, 8) != 13 || bytes[12] != 'I' || bytes[13] != 'H'
                || bytes[14] != 'D' || bytes[15] != 'R'
                || getInt(bytes, 16) != Math.min(tileSize, widths[level] - tx * tileSize)
                || getInt(bytes, 20) != Math.min(tileSize, heights[level] - ty * tileSize)) {
            throw new IOException("PNG tile dimensions mismatch: " + entry.getName());
        }
        cache.put(id, bytes);
        cachedBytes += bytes.length;
        while (cachedBytes > CACHE_BYTES && !cache.isEmpty()) {
            Map.Entry<Integer, byte[]> oldest = cache.entrySet().iterator().next();
            cachedBytes -= oldest.getValue().length;
            cache.remove(oldest.getKey());
        }
        return bytes;
    }

    private static int getInt(byte[] b, int p) {
        return ((b[p] & 255) << 24) | ((b[p + 1] & 255) << 16)
                | ((b[p + 2] & 255) << 8) | (b[p + 3] & 255);
    }

    @Override public void close() throws IOException { zip.close(); }
}

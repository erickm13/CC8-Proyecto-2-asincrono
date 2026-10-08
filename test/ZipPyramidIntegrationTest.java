package com.cc8.server.protocol;

import com.cc8.server.image.ZipPyramidReader;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** End-to-end checks of one libvips ZIP through all three RAPID modes. */
public final class ZipPyramidIntegrationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(45);
    private static final int FRAGMENT = ZipTilePacket.FRAGMENT;

    private record Tile(int level, int tx, int ty, int total, byte[][] parts) {
        Tile(int level, int tx, int ty, int total, int count) {
            this(level, tx, ty, total, new byte[count][]);
        }
        boolean complete() {
            for (byte[] p : parts) if (p == null) return false;
            return true;
        }
        byte[] join() {
            ByteArrayOutputStream out = new ByteArrayOutputStream(total);
            for (byte[] p : parts) out.writeBytes(p);
            byte[] bytes = out.toByteArray();
            check(bytes.length == total, "reassembled PNG length mismatch");
            return bytes;
        }
    }

    private static final class Peer implements WebSocket.Listener, AutoCloseable {
        final LinkedBlockingQueue<byte[]> messages = new LinkedBlockingQueue<>();
        final ByteArrayOutputStream fragments = new ByteArrayOutputStream();
        final WebSocket socket;
        volatile Throwable error;

        Peer(String base) throws Exception {
            socket = HttpClient.newHttpClient().newWebSocketBuilder()
                    .buildAsync(URI.create(base.replaceFirst("^http", "ws") + "/stream"), this)
                    .get(8, TimeUnit.SECONDS);
        }
        @Override public void onOpen(WebSocket ws) { ws.request(1); }
        @Override public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer buffer, boolean last) {
            byte[] part = new byte[buffer.remaining()];
            buffer.get(part);
            synchronized (fragments) {
                fragments.writeBytes(part);
                if (last) {
                    messages.offer(fragments.toByteArray());
                    fragments.reset();
                }
            }
            ws.request(1);
            return null;
        }
        @Override public void onError(WebSocket ws, Throwable cause) {
            error = cause;
            messages.offer(new byte[0]);
        }
        void send(byte[] data) throws Exception {
            socket.sendBinary(ByteBuffer.wrap(data), true).get(8, TimeUnit.SECONDS);
        }
        byte[] take(long millis) throws Exception {
            byte[] data = messages.poll(Math.max(1, millis), TimeUnit.MILLISECONDS);
            check(error == null, "WebSocket failed: " + error);
            return data;
        }
        @Override public void close() throws Exception {
            if (!socket.isOutputClosed())
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "test complete").get(5, TimeUnit.SECONDS);
        }
    }

    public static void main(String[] args) throws Exception {
        check(args.length == 3, "usage: ZipPyramidIntegrationTest http://host:port source.png pyramid.zip");
        String base = args[0];
        BufferedImage source = ImageIO.read(Path.of(args[1]).toFile());
        check(source != null, "source PNG unreadable");
        try (ZipPyramidReader reader = new ZipPyramidReader(Path.of(args[2]))) {
            check(reader.width() == source.getWidth() && reader.height() == source.getHeight(),
                    "DZI dimensions mismatch");
            check(reader.tilesX(0) == 1 && reader.tilesY(0) == 1, "overview is not one tile");
            check(ImageIO.read(new ByteArrayInputStream(reader.overviewPng())).getWidth() == reader.width(0),
                    "overview dimensions mismatch");
            String manifest = HttpClient.newHttpClient().send(HttpRequest.newBuilder()
                    .uri(URI.create(base + "/api/manifest")).build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            check(manifest.contains("\"format\":\"vips-zip\""), "server did not select ZIP format");
            check(manifest.contains("\"levels\":" + reader.maxLevel()), "manifest level mismatch");
            for (int mode = 0; mode < 3; mode++) verifyMode(base, reader, source, mode);
        }
        System.out.println("OK ZIP RAPID: exact full-resolution pixels in modes 0/1/2, fragmentation, batching, retransmission, FORGET");
    }

    private static void verifyMode(String base, ZipPyramidReader reader,
                                   BufferedImage source, int mode) throws Exception {
        try (Peer peer = new Peer(base)) {
            peer.send(new byte[]{Wire.T_HELLO, 2, (byte) mode});
            byte[] viewport = new byte[mode == 2 ? 21 : 18];
            viewport[0] = Wire.T_VIEWPORT;
            Wire.putInt(viewport, 1, 0); Wire.putInt(viewport, 5, 0);
            Wire.putInt(viewport, 9, reader.width()); Wire.putInt(viewport, 13, reader.height());
            viewport[17] = (byte) reader.maxLevel();
            if (mode == 2) { viewport[18] = 127; viewport[20] = (byte) 255; }
            peer.send(viewport);

            Map<Integer, Tile> assembled = new HashMap<>();
            int fullTiles = reader.tilesX(reader.maxLevel()) * reader.tilesY(reader.maxLevel());
            int completeFull = 0;
            int frameCount = 0, batchCount = 0, retxCount = 0;
            boolean delayedAck = false;
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            while (completeFull < fullTiles && System.nanoTime() < deadline) {
                byte[] raw = peer.take(TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
                if (raw == null) break;
                check(raw.length > 0 && raw[0] == Wire.T_DATA, "unexpected ZIP WebSocket frame");
                Wire.Data frame = Wire.decodeData(raw);
                frameCount++;
                if ((frame.flags() & Wire.FLAG_RETX) != 0) retxCount++;
                boolean batched = (frame.flags() & Wire.FLAG_BATCH) != 0;
                check(batched == (mode != 0), "mode DATA batching mismatch");
                if (batched) {
                    batchCount++;
                    check(frame.payload().length <= Wire.MAX_BATCH_PAYLOAD, "batch exceeds 32 KiB");
                }
                List<byte[]> packets = batched ? Wire.decodeBatch(frame.payload()) : List.of(frame.payload());
                check(packets.size() >= 1 && packets.size() <= Wire.MAX_BATCH_PACKETS,
                        "batch packet count out of bounds");
                for (byte[] p : packets) {
                    check(p.length >= ZipTilePacket.HEADER && p.length <= ZipTilePacket.HEADER + FRAGMENT,
                            "fragment exceeds packet bound");
                    check(p[0] == 'Z' && p[1] == 'T' && p[2] == 1, "invalid ZT header");
                    int id = Wire.getInt(p, 3), level = p[7] & 255;
                    int tx = Wire.getShort(p, 8), ty = Wire.getShort(p, 10);
                    int part = Wire.getShort(p, 12), parts = Wire.getShort(p, 14);
                    int total = Wire.getInt(p, 16), len = Wire.getShort(p, 20);
                    check(level <= reader.maxLevel() && tx < reader.tilesX(level)
                            && ty < reader.tilesY(level) && id == reader.id(level, tx, ty),
                            "invalid tile identity");
                    check(parts == (total + FRAGMENT - 1) / FRAGMENT && part < parts
                            && len == Math.min(FRAGMENT, total - part * FRAGMENT)
                            && p.length == ZipTilePacket.HEADER + len, "invalid fragment geometry");
                    Tile tile = assembled.get(id);
                    if (tile == null) assembled.put(id, tile = new Tile(level, tx, ty, total, parts));
                    check(tile.level == level && tile.tx == tx && tile.ty == ty
                            && tile.total == total && tile.parts.length == parts,
                            "inconsistent tile metadata");
                    if (tile.parts[part] == null) {
                        tile.parts[part] = Arrays.copyOfRange(p, ZipTilePacket.HEADER, p.length);
                        if (level == reader.maxLevel() && tile.complete()) completeFull++;
                    }
                }
                // Intentionally drop the first ACK in mode 0 to prove that its DATA is
                // retransmitted unchanged before the viewport can finish.
                if (mode == 0 && !delayedAck) {
                    delayedAck = true;
                    continue;
                }
                peer.send(Wire.encodeAck(frame.seq() + 1, mode == 0 ? 256 : 16,
                        frame.sendTs(), new int[0], new int[0]));
            }
            check(completeFull == fullTiles, "mode " + mode + " missed full-resolution tiles: "
                    + completeFull + "/" + fullTiles);
            check(mode != 0 || retxCount > 0, "deliberate lost ACK did not trigger retransmission");
            check(mode == 0 || batchCount > 0, "batch mode emitted no batch DATA");
            for (Tile tile : assembled.values()) {
                if (tile.level != reader.maxLevel()) continue;
                BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(tile.join()));
                check(decoded != null, "received tile PNG failed to decode");
                int x0 = tile.tx * reader.tileSize(), y0 = tile.ty * reader.tileSize();
                check(decoded.getWidth() == Math.min(reader.tileSize(), reader.width() - x0)
                        && decoded.getHeight() == Math.min(reader.tileSize(), reader.height() - y0),
                        "edge tile dimensions mismatch");
                for (int y = 0; y < decoded.getHeight(); y++) for (int x = 0; x < decoded.getWidth(); x++) {
                    check((decoded.getRGB(x, y) & 0xffffff)
                                    == (source.getRGB(x0 + x, y0 + y) & 0xffffff),
                            "mode " + mode + " pixel mismatch at " + (x0 + x) + "," + (y0 + y));
                }
            }
            int resendId = reader.id(reader.maxLevel(), 0, 0);
            byte[] forget = new byte[7];
            forget[0] = Wire.T_FORGET; Wire.putShort(forget, 1, 1); Wire.putInt(forget, 3, resendId);
            peer.send(forget);
            boolean resent = false;
            long until = System.nanoTime() + Duration.ofSeconds(8).toNanos();
            while (!resent && System.nanoTime() < until) {
                byte[] raw = peer.take(TimeUnit.NANOSECONDS.toMillis(until - System.nanoTime()));
                if (raw == null) break;
                if (raw[0] != Wire.T_DATA) continue;
                Wire.Data frame = Wire.decodeData(raw);
                for (byte[] p : (frame.flags() & Wire.FLAG_BATCH) != 0
                        ? Wire.decodeBatch(frame.payload()) : List.of(frame.payload())) {
                    if (Wire.getInt(p, 3) == resendId) resent = true;
                }
                peer.send(Wire.encodeAck(frame.seq() + 1, mode == 0 ? 256 : 16,
                        frame.sendTs(), new int[0], new int[0]));
            }
            check(resent, "FORGET did not resend tile in mode " + mode);
            System.out.printf("mode=%d exact=%d tiles data=%d frames batch=%d retx=%d forget=true%n",
                    mode, fullTiles, frameCount, batchCount, retxCount);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

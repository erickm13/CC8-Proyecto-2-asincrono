package com.cc8.server.protocol;

import com.cc8.server.image.ZipPyramidReader;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Bounded live smoke test for a ZIP64 pyramid: one edge tile in every mode. */
public final class ZipPyramidLargeSmoke {
    private static final class Peer implements WebSocket.Listener {
        final LinkedBlockingQueue<byte[]> messages = new LinkedBlockingQueue<>();
        final ByteArrayOutputStream fragments = new ByteArrayOutputStream();
        final WebSocket socket;
        volatile Throwable error;

        Peer(String base) throws Exception {
            socket = HttpClient.newHttpClient().newWebSocketBuilder()
                    .buildAsync(URI.create(base.replaceFirst("^http", "ws") + "/stream"), this)
                    .get(10, TimeUnit.SECONDS);
        }
        @Override public void onOpen(WebSocket ws) { ws.request(1); }
        @Override public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer data, boolean last) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            synchronized (fragments) {
                fragments.writeBytes(bytes);
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
        void send(byte[] bytes) throws Exception {
            socket.sendBinary(ByteBuffer.wrap(bytes), true).get(10, TimeUnit.SECONDS);
        }
        void close() throws Exception {
            if (!socket.isOutputClosed())
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException(
                "usage: ZipPyramidLargeSmoke http://localhost:8080 images/large_vips.zip");
        try (ZipPyramidReader reader = new ZipPyramidReader(Path.of(args[1]))) {
            int level = reader.maxLevel();
            int tx = reader.tilesX(level) - 1, ty = reader.tilesY(level) - 1;
            byte[] expected = reader.tile(level, tx, ty);
            check(reader.tile(0, 0, 0).length > 0, "missing overview");
            check(reader.tile(level, 0, 0).length > 0, "missing first full-size tile");
            check(reader.width() > 100_000 && reader.height() > 100_000,
                    "expected the large course image");
            for (int mode = 0; mode < 3; mode++) {
                Peer peer = new Peer(args[0]);
                try {
                    peer.send(new byte[]{Wire.T_HELLO, 2, (byte) mode});
                    int x = tx * reader.tileSize(), y = ty * reader.tileSize();
                    byte[] viewport = new byte[mode == 2 ? 21 : 18];
                    viewport[0] = Wire.T_VIEWPORT;
                    Wire.putInt(viewport, 1, x); Wire.putInt(viewport, 5, y);
                    Wire.putInt(viewport, 9, reader.width() - x);
                    Wire.putInt(viewport, 13, reader.height() - y);
                    viewport[17] = (byte) level;
                    peer.send(viewport);
                    byte[][] parts = new byte[(expected.length + ZipTilePacket.FRAGMENT - 1)
                            / ZipTilePacket.FRAGMENT][];
                    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                    int frames = 0;
                    while (System.nanoTime() < until && Arrays.stream(parts).anyMatch(p -> p == null)) {
                        byte[] raw = peer.messages.poll(1, TimeUnit.SECONDS);
                        if (raw == null) continue;
                        check(peer.error == null, "WebSocket failed: " + peer.error);
                        check(raw.length > 0 && raw[0] == Wire.T_DATA, "bad DATA frame");
                        Wire.Data data = Wire.decodeData(raw);
                        frames++;
                        List<byte[]> packets = (data.flags() & Wire.FLAG_BATCH) != 0
                                ? Wire.decodeBatch(data.payload()) : List.of(data.payload());
                        for (byte[] packet : packets) {
                            if (Wire.getInt(packet, 3) != reader.id(level, tx, ty)) continue;
                            int part = Wire.getShort(packet, 12);
                            parts[part] = Arrays.copyOfRange(packet, ZipTilePacket.HEADER, packet.length);
                        }
                        peer.send(Wire.encodeAck(data.seq() + 1, mode == 0 ? 256 : 16,
                                data.sendTs(), new int[0], new int[0]));
                    }
                    check(Arrays.stream(parts).allMatch(p -> p != null),
                            "mode " + mode + " did not receive edge tile after " + frames + " DATA frames");
                    int offset = 0;
                    for (byte[] part : parts) {
                        check(Arrays.equals(part, Arrays.copyOfRange(expected, offset, offset + part.length)),
                                "mode " + mode + " ZIP64 tile mismatch");
                        offset += part.length;
                    }
                    check(offset == expected.length, "tile length mismatch");
                    System.out.printf("mode=%d ZIP64 edge tile=%d bytes, DATA=%d%n",
                            mode, expected.length, frames);
                } finally {
                    peer.close();
                }
            }
        }
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}

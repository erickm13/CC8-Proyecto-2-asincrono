package com.cc8.server.protocol;

import com.cc8.server.image.ClientReconstructor;
import com.cc8.server.image.H2kFormat;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cliente de prueba de la ruta de PRODUCCION: HTTP para el manifiesto + WebSocket
 * real para el protocolo RAPID, usando {@link ReliableReceiver} (ACK/SACK) y
 * reconstruyendo la imagen para compararla con el original.
 *
 * Uso: RapidClientTest [http://host:puerto] [imagenOriginal.png]
 */
public final class RapidClientTest {

    private static final class Acc {
        int level, py, px, numCoeffs, numPlanes;
        byte[][] layers;
    }

    public static void main(String[] args) throws Exception {
        String httpBase = args.length > 0 ? args[0] : "http://localhost:8080";
        String original = args.length > 1 ? args[1] : "images/sample.png";
        String wsUrl = httpBase.replaceFirst("^http", "ws") + "/stream";

        // 1) Manifiesto por HTTP.
        HttpClient http = HttpClient.newHttpClient();
        String json = http.send(HttpRequest.newBuilder(URI.create(httpBase + "/api/manifest")).build(),
                HttpResponse.BodyHandlers.ofString()).body();
        int width = num(json, "width"), height = num(json, "height");
        int components = num(json, "components"), tileSize = num(json, "tileSize");
        int levels = num(json, "levels"), precinct = num(json, "precinct");
        int tilesX = num(json, "tilesX"), tilesY = num(json, "tilesY");
        System.out.printf("Manifiesto: %dx%d comps=%d tile=%d levels=%d%n",
                width, height, components, tileSize, levels);
        H2kFormat.Header hdr = new H2kFormat.Header(1, 0, components, 8, tileSize,
                levels, precinct, width, height, tilesX, tilesY, 0);

        // 2) Ensamblador de paquetes.
        Map<Integer, Map<Integer, Map<Long, Acc>>> store = new HashMap<>();
        AtomicLong lastRecv = new AtomicLong(System.currentTimeMillis());
        DeliverySink sink = (seq, payload) -> {
            ImagePacket.Packet pk = ImagePacket.decode(payload);
            Acc acc = store.computeIfAbsent(pk.tile(), k -> new HashMap<>())
                    .computeIfAbsent(pk.comp(), k -> new HashMap<>())
                    .computeIfAbsent(key(pk.level(), pk.py(), pk.px()), k -> new Acc());
            acc.level = pk.level(); acc.py = pk.py(); acc.px = pk.px();
            acc.numCoeffs = pk.numCoeffs(); acc.numPlanes = pk.numPlanes();
            if (acc.layers == null) acc.layers = new byte[pk.numPlanes()][];
            acc.layers[pk.layer()] = pk.data();
            lastRecv.set(System.currentTimeMillis());
        };

        // 3) WebSocket real; el ReliableReceiver ACKea de vuelta por el socket.
        AckSender[] ackSenderRef = new AckSender[1];
        ReliableReceiver receiver = new ReliableReceiver(
                bytes -> ackSenderRef[0].send(bytes), sink, 1 << 20);

        WebSocket.Listener listener = new WebSocket.Listener() {
            final ByteArrayOutputStream buf = new ByteArrayOutputStream();
            public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer data, boolean last) {
                byte[] b = new byte[data.remaining()];
                data.get(b);
                buf.writeBytes(b);
                if (last) {
                    byte[] msg = buf.toByteArray();
                    buf.reset();
                    if (Wire.type(msg) == Wire.T_DATA) {
                        receiver.onData(msg);
                    }
                }
                ws.request(1);
                return null;
            }
        };

        WebSocket ws = http.newWebSocketBuilder()
                .buildAsync(URI.create(wsUrl), listener).get(5, TimeUnit.SECONDS);
        ackSenderRef[0] = new AckSender(ws);
        System.out.println("WebSocket RAPID abierto.");

        // 4) Enviar VIEWPORT (imagen completa, maxima resolucion).
        ackSenderRef[0].send(viewport(0, 0, width, height, levels));

        // 5) Esperar quiescencia (sin DATA nuevo por 1.5 s) o timeout.
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
            if (System.currentTimeMillis() - lastRecv.get() > 1500) break;
        }
        System.out.printf("Recibidos: %d entregados, %d duplicados%n",
                receiver.delivered(), receiver.duplicates());

        // 6) Reconstruir y comparar con el original.
        BufferedImage src = ImageIO.read(new File(original));
        long mismatches = 0, compared = 0;
        for (int ty = 0; ty < tilesY; ty++) {
            for (int tx = 0; tx < tilesX; tx++) {
                int tile = ty * tilesX + tx;
                int[][] comp = new int[3][];
                for (int c = 0; c < 3; c++) {
                    comp[c] = ClientReconstructor.component(hdr, precincts(store, tile, c));
                }
                int x0 = tx * tileSize, y0 = ty * tileSize;
                int vw = Math.min(tileSize, width - x0), vh = Math.min(tileSize, height - y0);
                for (int yy = 0; yy < vh; yy++) {
                    for (int xx = 0; xx < vw; xx++) {
                        int p = yy * tileSize + xx;
                        int rgb = (comp[0][p] << 16) | (comp[1][p] << 8) | comp[2][p];
                        compared++;
                        if ((src.getRGB(x0 + xx, y0 + yy) & 0xFFFFFF) != rgb) mismatches++;
                    }
                }
            }
        }
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "fin");
        System.out.printf("Comparados %d pixeles, %d distintos%n", compared, mismatches);
        if (mismatches != 0) {
            System.out.println("FALLO: la reconstruccion no coincide");
            System.exit(1);
        }
        System.out.println("OK  imagen entregada y reconstruida SIN PERDIDA sobre WebSocket real");
        System.out.println("RapidClientTest paso.");
    }

    /** Serializa los envios binarios (el WebSocket del JDK admite uno a la vez). */
    private static final class AckSender {
        private final WebSocket ws;
        private final ArrayDeque<byte[]> q = new ArrayDeque<>();
        private boolean sending = false;
        AckSender(WebSocket ws) { this.ws = ws; }
        synchronized void send(byte[] b) {
            q.add(b);
            if (!sending) { sending = true; drain(); }
        }
        private void drain() {
            byte[] b;
            synchronized (this) {
                b = q.poll();
                if (b == null) { sending = false; return; }
            }
            ws.sendBinary(ByteBuffer.wrap(b), true).thenRun(this::drain);
        }
    }

    private static byte[] viewport(int x, int y, int w, int h, int zoom) {
        byte[] b = new byte[18];
        b[0] = Wire.T_VIEWPORT;
        Wire.putInt(b, 1, x); Wire.putInt(b, 5, y);
        Wire.putInt(b, 9, w); Wire.putInt(b, 13, h);
        b[17] = (byte) zoom;
        return b;
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
            while (received < a.numPlanes && a.layers[received] != null) received++;
            list.add(new ClientReconstructor.Precinct(
                    a.level, a.py, a.px, a.numCoeffs, a.numPlanes, a.layers, received));
        }
        return list;
    }

    private static long key(int level, int py, int px) {
        return ((long) level << 40) | ((long) py << 20) | px;
    }

    private static int num(String json, String key) {
        int i = json.indexOf("\"" + key + "\"");
        int c = json.indexOf(':', i) + 1;
        int e = c;
        while (e < json.length() && "-0123456789".indexOf(json.charAt(e)) >= 0) e++;
        return Integer.parseInt(json.substring(c, e).trim());
    }
}

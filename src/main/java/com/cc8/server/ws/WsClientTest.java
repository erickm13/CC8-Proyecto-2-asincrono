package com.cc8.server.ws;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Cliente de prueba que usa el WebSocket nativo del JDK (java.net.http) para
 * validar el servidor WebSocket propio: handshake + eco de texto y binario.
 * Requiere el servidor corriendo. Uso: WsClientTest [ws://host:puerto/ws]
 */
public final class WsClientTest {

    public static void main(String[] args) throws Exception {
        String url = args.length > 0 ? args[0] : "ws://localhost:8080/ws";
        LinkedBlockingQueue<Object> received = new LinkedBlockingQueue<>();

        WebSocket.Listener listener = new WebSocket.Listener() {
            @Override
            public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                received.add("TEXT:" + data);
                ws.request(1);
                return null;
            }
            @Override
            public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer data, boolean last) {
                byte[] b = new byte[data.remaining()];
                data.get(b);
                received.add(b);
                ws.request(1);
                return null;
            }
        };

        WebSocket ws = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(URI.create(url), listener)
                .get(5, TimeUnit.SECONDS);
        System.out.println("OK  handshake completado, conexion WebSocket abierta");

        // 1) Eco de texto.
        ws.sendText("hola-protocolo", true).get(5, TimeUnit.SECONDS);
        Object t = received.poll(5, TimeUnit.SECONDS);
        if (!("TEXT:hola-protocolo").equals(t)) {
            System.out.println("FALLO eco de texto: " + t);
            System.exit(1);
        }
        System.out.println("OK  eco de texto");

        // 2) Eco binario grande (prueba len extendido de 16 bits).
        byte[] payload = new byte[5000];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i * 31);
        ws.sendBinary(ByteBuffer.wrap(payload), true).get(5, TimeUnit.SECONDS);
        Object b = received.poll(5, TimeUnit.SECONDS);
        if (!(b instanceof byte[] back) || back.length != payload.length) {
            System.out.println("FALLO eco binario (tamano)");
            System.exit(1);
        }
        byte[] back = (byte[]) b;
        for (int i = 0; i < payload.length; i++) {
            if (back[i] != payload[i]) {
                System.out.println("FALLO eco binario en i=" + i);
                System.exit(1);
            }
        }
        System.out.println("OK  eco binario de 5000 bytes");

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "fin").get(5, TimeUnit.SECONDS);
        System.out.println("Todas las pruebas de cliente WebSocket pasaron.");
    }
}

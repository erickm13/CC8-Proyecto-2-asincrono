package com.cc8.server.ws;

import com.cc8.server.http.HttpRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * Handshake de apertura de WebSocket (RFC 6455, seccion 4). El cliente envia
 * una peticion HTTP con "Upgrade: websocket" y una cabecera
 * {@code Sec-WebSocket-Key}; el servidor responde 101 con
 * {@code Sec-WebSocket-Accept = base64(sha1(key + GUID))}.
 *
 * <p>Asi cumplimos el enunciado: la comunicacion inicial es HTTP y a partir de
 * ahi corre nuestro propio protocolo binario sobre el canal WebSocket.
 */
public final class WebSocketHandshake {

    /** GUID magico definido por el RFC 6455. */
    private static final String MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private WebSocketHandshake() {
    }

    /** true si la peticion HTTP es una solicitud de upgrade a WebSocket. */
    public static boolean isUpgrade(HttpRequest req) {
        String upgrade = req.header("upgrade");
        String connection = req.header("connection");
        return upgrade != null && upgrade.toLowerCase().contains("websocket")
                && connection != null && connection.toLowerCase().contains("upgrade")
                && req.header("sec-websocket-key") != null;
    }

    /** Calcula el valor de Sec-WebSocket-Accept para una clave dada. */
    public static String accept(String secWebSocketKey) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest(
                    (secWebSocketKey + MAGIC).getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 no disponible", e);
        }
    }

    /** Construye la respuesta HTTP 101 de cambio de protocolo (bytes ASCII). */
    public static byte[] responseBytes(String secWebSocketKey) {
        String response = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept(secWebSocketKey) + "\r\n"
                + "\r\n";
        return response.getBytes(StandardCharsets.US_ASCII);
    }
}

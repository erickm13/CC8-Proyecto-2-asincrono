package com.cc8.server.ws;

/**
 * Callbacks de una conexion WebSocket. La logica del protocolo de imagen
 * implementara esta interfaz.
 */
public interface WebSocketHandler {

    default void onOpen(WebSocketSession session) {
    }

    /** Mensaje binario del cliente (nuestro protocolo de control va por aqui). */
    void onBinary(WebSocketSession session, byte[] data);

    default void onText(WebSocketSession session, String text) {
    }

    /** Respuesta a un ping enviado por el servidor (util para medir RTT). */
    default void onPong(WebSocketSession session, byte[] data) {
    }

    default void onClose(WebSocketSession session) {
    }
}

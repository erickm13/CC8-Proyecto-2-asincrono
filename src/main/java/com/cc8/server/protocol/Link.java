package com.cc8.server.protocol;

/**
 * Canal por el que viajan los datagramas del protocolo. En produccion es un
 * frame binario de WebSocket; en las pruebas es un enlace simulado con
 * perdida/reordenamiento/retardo. Desacoplar el transporte del canal permite
 * probar los mecanismos (Selective Repeat, SACK, congestion) de forma
 * determinista.
 */
@FunctionalInterface
public interface Link {
    void send(byte[] datagram);

    /** Returns false when the receiver queue is full; existing links accept by default. */
    default boolean trySend(byte[] datagram) {
        send(datagram);
        return true;
    }
}

package com.cc8.server.protocol;

/**
 * Recibe los payloads entregados por el receptor. La entrega es en ORDEN DE
 * LLEGADA (no estricto): cada payload es un paquete de imagen autodescriptivo,
 * por lo que se puede pintar apenas llega, evitando el head-of-line blocking de
 * un flujo estrictamente ordenado. La deduplicacion la garantiza el receptor.
 */
public interface DeliverySink {
    void deliver(int seq, byte[] payload);
}

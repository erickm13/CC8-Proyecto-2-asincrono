package com.cc8.server.http;

import java.nio.channels.AsynchronousSocketChannel;

/**
 * Permite que una peticion HTTP "ascienda" (upgrade) a otro protocolo sobre el
 * mismo socket (p.ej. WebSocket). Se implementa fuera del paquete http para no
 * acoplar el servidor HTTP a un protocolo concreto.
 */
public interface ConnectionUpgrade {

    /** true si esta peticion debe manejarse como upgrade. */
    boolean handles(HttpRequest request);

    /**
     * Toma posesion del canal. A partir de aqui el servidor HTTP deja de
     * gestionar la conexion.
     *
     * @param leftover bytes ya leidos del socket despues de la peticion
     *                 (normalmente vacio; el cliente espera el 101 primero)
     */
    void upgrade(HttpRequest request, AsynchronousSocketChannel channel, byte[] leftover);
}

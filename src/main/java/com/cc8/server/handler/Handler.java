package com.cc8.server.handler;

import com.cc8.server.http.HttpRequest;
import com.cc8.server.http.HttpResponse;

/**
 * Contrato de un manejador de peticiones. Recibe una peticion ya parseada
 * y produce una respuesta.
 */
@FunctionalInterface
public interface Handler {
    HttpResponse handle(HttpRequest request) throws Exception;
}

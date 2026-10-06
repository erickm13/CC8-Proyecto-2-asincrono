package com.cc8.server.handler;

import com.cc8.server.http.HttpRequest;
import com.cc8.server.http.HttpResponse;

import java.util.HashMap;
import java.util.Map;

/**
 * Enrutador simple por (metodo + path exacto). Si ninguna ruta coincide,
 * delega en un manejador de respaldo (fallback), que en este proyecto sirve
 * los archivos estaticos del sitio web.
 *
 * <p>Aqui es donde mas adelante se registraran los endpoints del protocolo
 * de imagen (p.ej. GET /api/tile, GET /api/manifest).
 */
public final class Router {

    private final Map<String, Handler> routes = new HashMap<>();
    private Handler fallback = req -> HttpResponse.notFound();

    public Router route(String method, String path, Handler handler) {
        routes.put(key(method, path), handler);
        return this;
    }

    public Router get(String path, Handler handler) {
        return route("GET", path, handler);
    }

    public Router post(String path, Handler handler) {
        return route("POST", path, handler);
    }

    public Router fallback(Handler handler) {
        this.fallback = handler;
        return this;
    }

    public HttpResponse dispatch(HttpRequest request) throws Exception {
        Handler h = routes.get(key(request.method(), request.path()));
        if (h != null) {
            return h.handle(request);
        }
        return fallback.handle(request);
    }

    private static String key(String method, String path) {
        return method.toUpperCase() + " " + path;
    }
}

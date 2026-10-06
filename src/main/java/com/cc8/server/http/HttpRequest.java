package com.cc8.server.http;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Representa una peticion HTTP/1.1 ya parseada.
 *
 * <p>Solo se soporta lo necesario para el proyecto: linea de peticion,
 * cabeceras y (opcionalmente) cuerpo. Los parametros de query string se
 * extraen para facilitar el manejo del protocolo de imagen.
 */
public final class HttpRequest {

    private final String method;
    private final String rawTarget;      // p.ej. "/tile?img=1&z=2&x=3&y=4"
    private final String path;           // p.ej. "/tile"
    private final String version;
    private final Map<String, String> headers;      // clave en minusculas
    private final Map<String, String> queryParams;
    private final byte[] body;

    public HttpRequest(String method, String rawTarget, String version,
                       Map<String, String> headers, byte[] body) {
        this.method = method;
        this.rawTarget = rawTarget;
        this.version = version;
        this.headers = headers;
        this.body = body == null ? new byte[0] : body;

        int q = rawTarget.indexOf('?');
        this.path = q >= 0 ? rawTarget.substring(0, q) : rawTarget;
        this.queryParams = q >= 0
                ? parseQuery(rawTarget.substring(q + 1))
                : new LinkedHashMap<>();
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> params = new LinkedHashMap<>();
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            if (eq >= 0) {
                params.put(urlDecode(pair.substring(0, eq)),
                        urlDecode(pair.substring(eq + 1)));
            } else {
                params.put(urlDecode(pair), "");
            }
        }
        return params;
    }

    private static String urlDecode(String s) {
        return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    public String method() {
        return method;
    }

    public String rawTarget() {
        return rawTarget;
    }

    public String path() {
        return path;
    }

    public String version() {
        return version;
    }

    public String header(String name) {
        return headers.get(name.toLowerCase());
    }

    public Map<String, String> headers() {
        return headers;
    }

    public String query(String name) {
        return queryParams.get(name);
    }

    public Map<String, String> queryParams() {
        return queryParams;
    }

    public byte[] body() {
        return body;
    }

    /** Indica si el cliente pidio mantener la conexion viva (keep-alive). */
    public boolean keepAlive() {
        String conn = header("connection");
        if (conn == null) {
            // HTTP/1.1 es keep-alive por defecto; HTTP/1.0 no.
            return "HTTP/1.1".equals(version);
        }
        return conn.equalsIgnoreCase("keep-alive");
    }

    @Override
    public String toString() {
        return method + " " + rawTarget + " " + version;
    }
}

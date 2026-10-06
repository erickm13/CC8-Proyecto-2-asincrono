package com.cc8.server.http;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Construye una respuesta HTTP/1.1 lista para escribirse en el canal.
 */
public final class HttpResponse {

    private int status = 200;
    private String reason = "OK";
    private final Map<String, String> headers = new LinkedHashMap<>();
    private byte[] body = new byte[0];
    private boolean keepAlive = true;

    public HttpResponse status(int status, String reason) {
        this.status = status;
        this.reason = reason;
        return this;
    }

    public HttpResponse header(String name, String value) {
        headers.put(name, value);
        return this;
    }

    public HttpResponse keepAlive(boolean keepAlive) {
        this.keepAlive = keepAlive;
        return this;
    }

    public boolean isKeepAlive() {
        return keepAlive;
    }

    public HttpResponse body(byte[] body, String contentType) {
        this.body = body == null ? new byte[0] : body;
        headers.put("Content-Type", contentType);
        return this;
    }

    public HttpResponse text(String text) {
        return body(text.getBytes(StandardCharsets.UTF_8),
                "text/plain; charset=utf-8");
    }

    // ---- Fabricas de conveniencia ----------------------------------------

    public static HttpResponse ok() {
        return new HttpResponse();
    }

    public static HttpResponse notFound() {
        return new HttpResponse().status(404, "Not Found")
                .text("404 - Recurso no encontrado");
    }

    public static HttpResponse badRequest(String msg) {
        return new HttpResponse().status(400, "Bad Request").text(msg);
    }

    public static HttpResponse methodNotAllowed() {
        return new HttpResponse().status(405, "Method Not Allowed")
                .text("405 - Metodo no permitido");
    }

    public static HttpResponse serverError(String msg) {
        return new HttpResponse().status(500, "Internal Server Error")
                .text("500 - " + msg);
    }

    /** Serializa cabecera + cuerpo a un ByteBuffer listo para enviar. */
    public ByteBuffer toByteBuffer() {
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n");

        headers.putIfAbsent("Content-Length", Integer.toString(body.length));
        headers.put("Connection", keepAlive ? "keep-alive" : "close");
        headers.putIfAbsent("Server", "CC8-AsyncServer");

        for (Map.Entry<String, String> e : headers.entrySet()) {
            head.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        }
        head.append("\r\n");

        byte[] headBytes = head.toString().getBytes(StandardCharsets.US_ASCII);
        ByteBuffer buf = ByteBuffer.allocate(headBytes.length + body.length);
        buf.put(headBytes).put(body);
        buf.flip();
        return buf;
    }
}

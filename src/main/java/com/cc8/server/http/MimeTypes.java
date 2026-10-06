package com.cc8.server.http;

import java.util.Map;

/**
 * Mapa minimo de extensiones a tipos MIME para servir archivos estaticos.
 */
public final class MimeTypes {

    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"),
            Map.entry("htm", "text/html; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"),
            Map.entry("js", "application/javascript; charset=utf-8"),
            Map.entry("mjs", "application/javascript; charset=utf-8"),
            Map.entry("json", "application/json; charset=utf-8"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("txt", "text/plain; charset=utf-8"),
            Map.entry("woff", "font/woff"),
            Map.entry("woff2", "font/woff2"),
            Map.entry("bin", "application/octet-stream")
    );

    private MimeTypes() {
    }

    public static String forPath(String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0) {
            return "application/octet-stream";
        }
        String ext = path.substring(dot + 1).toLowerCase();
        return TYPES.getOrDefault(ext, "application/octet-stream");
    }
}

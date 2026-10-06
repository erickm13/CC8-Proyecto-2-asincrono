package com.cc8.server.handler;

import com.cc8.server.http.HttpRequest;
import com.cc8.server.http.HttpResponse;
import com.cc8.server.http.MimeTypes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Sirve archivos estaticos (HTML, JS, CSS, imagenes de UI...) desde un
 * directorio raiz. Todo lo que consume el navegador proviene de este
 * servidor: no se hacen peticiones a servidores externos (requisito 5).
 *
 * <p>Protege contra "path traversal" normalizando la ruta y verificando que
 * el archivo resuelto siga dentro del webroot.
 */
public final class StaticFileHandler implements Handler {

    private final Path webRoot;

    public StaticFileHandler(Path webRoot) {
        this.webRoot = webRoot.toAbsolutePath().normalize();
    }

    @Override
    public HttpResponse handle(HttpRequest request) throws IOException {
        if (!request.method().equalsIgnoreCase("GET")
                && !request.method().equalsIgnoreCase("HEAD")) {
            return HttpResponse.methodNotAllowed();
        }

        String relative = request.path();
        if (relative.equals("/") || relative.isEmpty()) {
            relative = "/index.html";
        }

        // Resuelve y normaliza; luego valida que no escape del webroot.
        Path resolved = webRoot.resolve("." + relative).normalize();
        if (!resolved.startsWith(webRoot)) {
            return HttpResponse.badRequest("Ruta no valida");
        }

        if (!Files.exists(resolved) || Files.isDirectory(resolved)) {
            return HttpResponse.notFound();
        }

        byte[] data = Files.readAllBytes(resolved);
        String mime = MimeTypes.forPath(resolved.getFileName().toString());
        return HttpResponse.ok().body(data, mime);
    }
}

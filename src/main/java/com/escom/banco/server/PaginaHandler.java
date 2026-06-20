package com.escom.banco.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.io.OutputStream;

/** GET / -> sirve dashboard.html (empaquetado como recurso del jar). */
public class PaginaHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange ex) throws IOException {
        if (!"/".equals(ex.getRequestURI().getPath())) {
            ex.sendResponseHeaders(404, -1);
            ex.close();
            return;
        }
        byte[] html;
        try (InputStream is = getClass().getResourceAsStream("/dashboard.html")) {
            if (is == null) { ex.sendResponseHeaders(500, -1); ex.close(); return; }
            html = is.readAllBytes();
        }
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        ex.sendResponseHeaders(200, html.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(html);
        }
    }

    /** Vista previa textual del recurso (lo usan las pruebas). */
    public static String htmlRecurso() throws IOException {
        try (InputStream is = PaginaHandler.class.getResourceAsStream("/dashboard.html")) {
            if (is == null) return null;
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

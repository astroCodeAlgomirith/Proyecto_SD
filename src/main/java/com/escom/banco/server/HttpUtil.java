package com.escom.banco.server;

import com.escom.banco.auth.JWTUtil;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Utilidades compartidas por los handlers: leer body, responder JSON, JWT. */
public final class HttpUtil {

    private HttpUtil() {}

    public static String leerBody(HttpExchange ex) throws IOException {
        try (InputStream is = ex.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public static void enviarJson(HttpExchange ex, int codigo, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        ex.sendResponseHeaders(codigo, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    public static void error(HttpExchange ex, int codigo, String mensaje) throws IOException {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("error", mensaje);
        enviarJson(ex, codigo, com.escom.banco.json.Json.toJson(m));
    }

    /**
     * Devuelve el subject (username) si el header Authorization: Bearer <token>
     * trae un JWT valido; null en cualquier otro caso (-> responder 401).
     */
    public static String usuarioAutenticado(HttpExchange ex) {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) return null;
        try {
            return JWTUtil.verify(auth.substring(7)).getSubject();
        } catch (Exception e) {
            return null;
        }
    }
}

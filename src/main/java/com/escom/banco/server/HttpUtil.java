package com.escom.banco.server;

import com.escom.banco.auth.JWTUtil;
import com.escom.banco.server.http.Solicitud;

import java.util.concurrent.ConcurrentHashMap;

/** Utilidades compartidas por los handlers: autenticacion JWT con cache. */
public final class HttpUtil {

    // Cache de tokens ya verificados (token -> subject). La validacion HMAC es
    // cara y se repite en cada peticion con el mismo token; aqui se hace una vez.
    private static final ConcurrentHashMap<String, String> TOKENS_VALIDOS = new ConcurrentHashMap<>();
    private static final int TOPE_CACHE = 100_000;

    private HttpUtil() {}

    /**
     * Devuelve el subject (username) si el header Authorization: Bearer <token>
     * trae un JWT valido; null en cualquier otro caso (-> responder 401).
     */
    public static String usuarioAutenticado(Solicitud s) {
        // Clave en minuscula: el parser ya normaliza, y asi header() no aloca
        // (toLowerCase sobre texto ya en minusculas devuelve la misma instancia).
        String auth = s.header("authorization");
        if (auth == null) return null;
        // RFC 7235: el scheme es case-insensitive. Localizar el primer espacio,
        // comparar el scheme con "bearer" y tomar el resto (con trim) como token.
        int sp = auth.indexOf(' ');
        if (sp < 0) return null;
        if (!auth.substring(0, sp).equalsIgnoreCase("bearer")) return null;
        String token = auth.substring(sp + 1).trim();
        String cacheado = TOKENS_VALIDOS.get(token);
        if (cacheado != null) return cacheado;
        try {
            String sub = JWTUtil.verify(token).getSubject();
            if (TOKENS_VALIDOS.size() < TOPE_CACHE) TOKENS_VALIDOS.put(token, sub);
            return sub;
        } catch (Exception e) {
            return null;
        }
    }
}

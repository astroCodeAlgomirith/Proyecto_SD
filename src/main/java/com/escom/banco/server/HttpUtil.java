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
        String auth = s.header("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) return null;
        String token = auth.substring(7);
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

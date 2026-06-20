package com.escom.banco.server.http;

import java.nio.charset.StandardCharsets;

/**
 * Respuesta lista para serializar. El body ya esta en bytes UTF-8 y el
 * Content-Length se calcula sobre esos bytes (NO sobre String.length()), porque
 * un Content-Length incorrecto desincroniza el keep-alive y deja al generador
 * esperando bytes que nunca llegan.
 */
public final class Respuesta {

    public final int codigo;          // 200,201,400,401,404,405,409,431,500
    public final String contentType;  // null => no se emite Content-Type (p.ej. sinCuerpo)
    public final byte[] cuerpo;       // nunca null; vacio => Content-Length: 0
    public boolean cerrar;            // true => agregar Connection: close y cerrar tras escribir

    private Respuesta(int codigo, String contentType, byte[] cuerpo) {
        this.codigo = codigo;
        this.contentType = contentType;
        this.cuerpo = (cuerpo == null) ? new byte[0] : cuerpo;
    }

    public static Respuesta json(int codigo, String json) {
        return new Respuesta(codigo, "application/json; charset=UTF-8",
                json.getBytes(StandardCharsets.UTF_8));
    }

    public static Respuesta html(int codigo, byte[] bytes) {
        return new Respuesta(codigo, "text/html; charset=UTF-8", bytes);
    }

    /** Codigos sin cuerpo (405, etc): emite Content-Length: 0 igualmente. */
    public static Respuesta sinCuerpo(int codigo) {
        return new Respuesta(codigo, null, new byte[0]);
    }

    /** {"error":"..."} con escape minimo, misma convencion que el HttpUtil viejo. */
    public static Respuesta error(int codigo, String mensaje) {
        String e = "{\"error\":\"" + mensaje.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
        return json(codigo, e);
    }

    public Respuesta cerrarConexion() { this.cerrar = true; return this; }
}

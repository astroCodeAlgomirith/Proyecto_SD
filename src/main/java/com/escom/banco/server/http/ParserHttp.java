package com.escom.banco.server.http;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Parser HTTP/1.1 incremental sobre los bytes acumulados de una conexion. Es
 * SIN ESTADO: cada intento re-escanea [0,len) del buffer; el llamador acumula
 * mas bytes y reintenta. Devuelve una Solicitud completa (LISTA) indicando
 * cuantos bytes consumir, o INCOMPLETA (faltan datos), o ERROR (400/431).
 *
 * Critico: del request-target se quita query (?) y fragment (#) ANTES de rutear
 * porque AccountHandler hace path.split y parseInt sobre el id.
 */
public final class ParserHttp {

    public enum Estado { LISTA, INCOMPLETA, ERROR }

    public static final class Resultado {
        public Estado estado;
        public Solicitud solicitud;     // si LISTA
        public int bytesConsumidos;     // si LISTA: request-line + headers + body
        public int codigoError;         // si ERROR (400 / 431)
        public boolean enviarContinue;  // si INCOMPLETA y la request trae Expect: 100-continue

        static Resultado incompleta(boolean cont) {
            Resultado r = new Resultado(); r.estado = Estado.INCOMPLETA; r.enviarContinue = cont; return r;
        }
        static Resultado error(int codigo) {
            Resultado r = new Resultado(); r.estado = Estado.ERROR; r.codigoError = codigo; return r;
        }
        static Resultado lista(Solicitud s, int consumidos) {
            Resultado r = new Resultado(); r.estado = Estado.LISTA; r.solicitud = s; r.bytesConsumidos = consumidos; return r;
        }
    }

    private static final int MAX_HEADERS = 16 * 1024;   // request-line + headers
    private static final int MAX_CUERPO = 1024 * 1024;  // body (las requests del banco son <1KB)

    private ParserHttp() {}

    public static Resultado parsear(byte[] buf, int len) {
        int bodyStart = finDeCabeceras(buf, len);
        if (bodyStart < 0) {
            if (len > MAX_HEADERS) return Resultado.error(431);
            return Resultado.incompleta(false);
        }
        int terminadorLen = (buf[bodyStart - 1] == '\n' && buf[bodyStart - 2] == '\r') ? 4 : 2;
        int finCabecerasTexto = bodyStart - terminadorLen;

        String bloque = new String(buf, 0, finCabecerasTexto, StandardCharsets.ISO_8859_1);
        String[] lineas = bloque.split("\n", -1);
        if (lineas.length == 0) return Resultado.error(400);

        // ---- request-line: METODO SP target SP HTTP/x.y ----
        String[] rl = quitarCr(lineas[0]).split(" ");
        if (rl.length < 3) return Resultado.error(400);
        String metodo = rl[0];
        String path = normalizarPath(rl[1]);
        boolean http10 = rl[2].startsWith("HTTP/1.0");

        // ---- headers (clave en minusculas; en duplicados gana el primero) ----
        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lineas.length; i++) {
            String linea = quitarCr(lineas[i]);
            if (linea.isEmpty()) continue;
            int c = linea.indexOf(':');
            if (c <= 0) continue;
            String k = linea.substring(0, c).trim().toLowerCase();
            String v = linea.substring(c + 1).trim();
            headers.putIfAbsent(k, v);
        }

        // Transfer-Encoding chunked en REQUEST: no soportado (ningun cliente lo manda).
        String te = headers.get("transfer-encoding");
        if (te != null && te.toLowerCase().contains("chunked")) return Resultado.error(400);

        long cl = 0;
        String clh = headers.get("content-length");
        if (clh != null) {
            try { cl = Long.parseLong(clh.trim()); }
            catch (NumberFormatException e) { return Resultado.error(400); }
            if (cl < 0 || cl > MAX_CUERPO) return Resultado.error(400);
        }

        boolean expectContinue = "100-continue".equalsIgnoreCase(headers.get("expect"));
        int disponibles = len - bodyStart;
        if (disponibles < cl) return Resultado.incompleta(expectContinue);

        byte[] cuerpo = new byte[(int) cl];
        System.arraycopy(buf, bodyStart, cuerpo, 0, (int) cl);

        String conn = headers.get("connection");
        boolean pideCerrar = (conn != null && conn.toLowerCase().contains("close"))
                || (http10 && (conn == null || !conn.toLowerCase().contains("keep-alive")));

        Solicitud s = new Solicitud(metodo, path, headers, cuerpo, pideCerrar);
        return Resultado.lista(s, bodyStart + (int) cl);
    }

    /** Indice de inicio del body (tras CRLFCRLF o LFLF), o -1 si aun no llego. */
    private static int finDeCabeceras(byte[] buf, int len) {
        for (int i = 0; i + 1 < len; i++) {
            if (buf[i] == '\r' && i + 3 < len
                    && buf[i + 1] == '\n' && buf[i + 2] == '\r' && buf[i + 3] == '\n') {
                return i + 4;
            }
            if (buf[i] == '\n' && buf[i + 1] == '\n') {
                return i + 2;
            }
        }
        return -1;
    }

    private static String quitarCr(String s) {
        return (!s.isEmpty() && s.charAt(s.length() - 1) == '\r') ? s.substring(0, s.length() - 1) : s;
    }

    /** Quita esquema+autoridad (forma absoluta), query (?) y fragment (#). */
    private static String normalizarPath(String target) {
        String t = target;
        int esquema = t.indexOf("://");
        if (esquema >= 0) {
            int barra = t.indexOf('/', esquema + 3);
            t = (barra >= 0) ? t.substring(barra) : "/";
        }
        int q = t.indexOf('?');
        if (q >= 0) t = t.substring(0, q);
        int f = t.indexOf('#');
        if (f >= 0) t = t.substring(0, f);
        return t.isEmpty() ? "/" : t;
    }
}

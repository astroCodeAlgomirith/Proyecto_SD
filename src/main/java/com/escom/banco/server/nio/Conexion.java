package com.escom.banco.server.nio;

import com.escom.banco.server.http.Respuesta;

import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Estado por conexion (attachment de la SelectionKey): buffer de lectura
 * acumulativo + cola de salida (ByteBuffers a escribir). La cola es concurrente
 * porque un worker puede encolar la respuesta desde otro hilo; pero SOLO el
 * reactor escribe al canal y toca interestOps.
 */
final class Conexion {

    private static final int INICIAL = 8 * 1024;
    private static final int MAX = 2 * 1024 * 1024;

    // Fragmentos fijos de la cabecera 200 OK + JSON (el 100% de las respuestas
    // exitosas): pre-codificados una vez para no rearmar/getBytes la cabecera por
    // respuesta. Solo el Content-Length se formatea por respuesta.
    private static final byte[] PRE_200_JSON =
            ("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=UTF-8\r\n"
                    + "Content-Length: ").getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] FIN_CABECERAS = "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
    private static final String CT_JSON = "application/json; charset=UTF-8";

    final SocketChannel canal;
    byte[] buf = new byte[INICIAL];
    int len = 0;

    final Queue<ByteBuffer> salida = new ConcurrentLinkedQueue<>();
    volatile boolean cerrarTrasFlush = false;
    boolean continueEnviado = false;   // 100-continue ya emitido para la request actual (solo hilo reactor)
    volatile boolean esperandoWorker = false; // request despachada al pool: la escribe el worker, la lee el reactor

    Conexion(SocketChannel canal) { this.canal = canal; }

    /** Garantiza espacio para 'extra' bytes mas; devuelve false si excede el tope. */
    boolean asegurar(int extra) {
        if (len + extra <= buf.length) return true;
        int nuevo = buf.length;
        while (nuevo < len + extra) nuevo <<= 1;
        if (nuevo > MAX) return false;
        byte[] mayor = new byte[nuevo];
        System.arraycopy(buf, 0, mayor, 0, len);
        buf = mayor;
        return true;
    }

    /** Descarta los primeros 'consumidos' bytes; el resto es el inicio de la siguiente request. */
    void compactar(int consumidos) {
        int resto = len - consumidos;
        if (resto > 0) System.arraycopy(buf, consumidos, buf, 0, resto);
        len = resto;
    }

    void encolar(ByteBuffer bb) { salida.add(bb); }

    /** Serializa la respuesta a un ByteBuffer (status line + headers + body). */
    static ByteBuffer serializar(Respuesta r, boolean cerrar) {
        // Camino caliente (200 OK + JSON, keep-alive): cabecera con fragmentos
        // pre-codificados; solo se formatea el Content-Length. Byte-identico al
        // camino general. El resto (201, 4xx, 5xx, html, cerrar) usa StringBuilder.
        if (r.codigo == 200 && !cerrar && CT_JSON.equals(r.contentType)) {
            byte[] cl = Integer.toString(r.cuerpo.length).getBytes(StandardCharsets.ISO_8859_1);
            ByteBuffer bb = ByteBuffer.allocate(
                    PRE_200_JSON.length + cl.length + FIN_CABECERAS.length + r.cuerpo.length);
            bb.put(PRE_200_JSON).put(cl).put(FIN_CABECERAS);
            if (r.cuerpo.length > 0) bb.put(r.cuerpo);
            bb.flip();
            return bb;
        }
        StringBuilder sb = new StringBuilder(96);
        sb.append("HTTP/1.1 ").append(r.codigo).append(' ').append(razon(r.codigo)).append("\r\n");
        if (r.contentType != null) sb.append("Content-Type: ").append(r.contentType).append("\r\n");
        sb.append("Content-Length: ").append(r.cuerpo.length).append("\r\n");
        if (cerrar) sb.append("Connection: close\r\n");
        sb.append("\r\n");
        byte[] cab = sb.toString().getBytes(StandardCharsets.ISO_8859_1);
        ByteBuffer bb = ByteBuffer.allocate(cab.length + r.cuerpo.length);
        bb.put(cab);
        if (r.cuerpo.length > 0) bb.put(r.cuerpo);
        bb.flip();
        return bb;
    }

    static ByteBuffer continue100() {
        return ByteBuffer.wrap("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
    }

    private static String razon(int c) {
        switch (c) {
            case 100: return "Continue";
            case 200: return "OK";
            case 201: return "Created";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 409: return "Conflict";
            case 431: return "Request Header Fields Too Large";
            case 500: return "Internal Server Error";
            default:  return "Status";
        }
    }
}

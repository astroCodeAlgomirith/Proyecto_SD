package com.escom.banco.server;

import com.escom.banco.server.http.Manejador;
import com.escom.banco.server.http.Respuesta;
import com.escom.banco.server.http.Solicitud;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** GET / -> sirve dashboard.html (empaquetado como recurso del jar, cacheado en bytes). */
public class PaginaHandler implements Manejador {

    private static final byte[] HTML = cargar();

    @Override
    public Respuesta manejar(Solicitud s) {
        if (!"GET".equalsIgnoreCase(s.metodo())) return Respuesta.sinCuerpo(405);
        if (!"/".equals(s.path())) return Respuesta.sinCuerpo(404);
        if (HTML == null) return Respuesta.sinCuerpo(500);
        return Respuesta.html(200, HTML);
    }

    private static byte[] cargar() {
        try (InputStream is = PaginaHandler.class.getResourceAsStream("/dashboard.html")) {
            return (is == null) ? null : is.readAllBytes();
        } catch (IOException e) {
            return null;
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

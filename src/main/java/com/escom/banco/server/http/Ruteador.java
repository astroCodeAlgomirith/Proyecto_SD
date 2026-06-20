package com.escom.banco.server.http;

import java.util.ArrayList;
import java.util.List;

/**
 * Tabla de rutas por prefijo de path (replica el longest-prefix de
 * HttpServer.createContext). Cada ruta se marca INLINE (se ejecuta en el hilo
 * reactor, para handlers baratos y CPU-only) o WORKER (se descarga al pool, para
 * handlers que bloquean: bcrypt en login/register, fetch a peers en /panel).
 * El metodo HTTP lo valida cada handler (devuelve 405); aqui solo se rutea path.
 */
public final class Ruteador {

    /** Una ruta resuelta: que handler la atiende y si va al pool worker. */
    public static final class Ruta {
        public final Manejador manejador;
        public final boolean worker;
        Ruta(Manejador m, boolean w) { this.manejador = m; this.worker = w; }
    }

    private static final class Entrada {
        final String prefijo;
        final Ruta ruta;
        Entrada(String prefijo, Ruta ruta) { this.prefijo = prefijo; this.ruta = ruta; }
    }

    public static final boolean INLINE = false;
    public static final boolean WORKER = true;

    private final List<Entrada> entradas = new ArrayList<>();

    /** Registra un handler para un prefijo de path. worker=true lo manda al pool. */
    public Ruteador registrar(String prefijo, Manejador m, boolean worker) {
        entradas.add(new Entrada(prefijo, new Ruta(m, worker)));
        return this;
    }

    /** Devuelve la ruta del prefijo mas largo que sea prefijo del path; null si ninguna. */
    public Ruta resolver(String path) {
        Ruta mejor = null;
        int largo = -1;
        for (Entrada e : entradas) {
            if (path.startsWith(e.prefijo) && e.prefijo.length() > largo) {
                mejor = e.ruta;
                largo = e.prefijo.length();
            }
        }
        return mejor;
    }
}

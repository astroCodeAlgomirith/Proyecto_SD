package com.escom.banco.server;

import com.escom.banco.server.http.Ruteador;
import com.escom.banco.server.nio.ServidorNio;

import java.util.List;

/**
 * Arma el servidor NIO del nodo con sus rutas (lo comparten MainServer y los
 * tests). Las 4 rutas del banco se sirven INLINE en el reactor salvo login/
 * register (bcrypt) y /panel (fetch a peers), que van al pool WORKER.
 */
public final class BancoHttp {

    private BancoHttp() {}

    /** Numero de reactores (hilos de I/O). En e2-standard-4 (4 vCPU) => 3. */
    private static int reactores() {
        int cores = Runtime.getRuntime().availableProcessors();
        int defecto = Math.max(2, Math.min(4, cores - 1));
        return enteroEnv("BANCO_REACTORES", defecto);
    }

    /** Hilos del pool worker (login/register/panel; bloqueantes, no CPU). */
    private static int hilosWorker() {
        return enteroEnv("BANCO_WORKERS", 16);
    }

    private static int enteroEnv(String clave, int defecto) {
        String v = System.getenv(clave);
        if (v == null || v.isBlank()) return defecto;
        try { return Integer.parseInt(v.trim()); }
        catch (NumberFormatException e) { return defecto; }
    }

    /** Servidor solo con los endpoints del banco (sin peers); util en pruebas. */
    public static ServidorNio crear(int puerto) {
        return crear(puerto, "este-nodo", List.of());
    }

    /** Crea (sin arrancar) el servidor con los 4 endpoints REST + /stats + panel. */
    public static ServidorNio crear(int puerto, String idLocal, List<String> peers) {
        AuthHandler auth = new AuthHandler();
        Ruteador r = new Ruteador()
                .registrar("/api/register", auth, Ruteador.WORKER)
                .registrar("/api/login", auth, Ruteador.WORKER)
                .registrar("/api/accounts", new AccountHandler(), Ruteador.INLINE)
                .registrar("/api/transactions/transfer", new TransferHandler(), Ruteador.INLINE)
                .registrar("/stats", new StatsHandler(), Ruteador.INLINE)
                .registrar("/panel", new PanelHandler(idLocal, peers), Ruteador.WORKER)
                .registrar("/", new PaginaHandler(), Ruteador.INLINE);
        return new ServidorNio(puerto, r, reactores(), hilosWorker());
    }
}

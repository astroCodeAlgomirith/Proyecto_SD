package com.escom.banco.server;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.Executors;

/** Construye el HttpServer del nodo con sus contextos (lo comparten MainServer y las pruebas). */
public final class BancoHttp {

    private BancoHttp() {}

    /** Tamano del pool de atencion HTTP; configurable por BANCO_HTTP_THREADS. */
    private static int hilosHttp() {
        int defecto = Math.max(32, Runtime.getRuntime().availableProcessors() * 8);
        return enteroEnv("BANCO_HTTP_THREADS", defecto);
    }

    /** Cola de conexiones pendientes del socket; configurable por BANCO_HTTP_BACKLOG. */
    private static int backlog() {
        return enteroEnv("BANCO_HTTP_BACKLOG", 1024);
    }

    private static int enteroEnv(String clave, int defecto) {
        String v = System.getenv(clave);
        if (v == null || v.isBlank()) return defecto;
        try { return Integer.parseInt(v.trim()); }
        catch (NumberFormatException e) { return defecto; }
    }

    /** Servidor solo con los endpoints del banco (sin panel); util en pruebas. */
    public static HttpServer crear(int puerto) throws IOException {
        return crear(puerto, "este-nodo", List.of());
    }

    /** Crea (sin arrancar) el servidor con los 4 endpoints del PDF + /stats + panel. */
    public static HttpServer crear(int puerto, String idLocal, List<String> peers) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(puerto), backlog());
        AuthHandler authHandler = new AuthHandler();
        server.createContext("/api/register", authHandler);
        server.createContext("/api/login", authHandler);
        server.createContext("/api/accounts", new AccountHandler());
        server.createContext("/api/transactions/transfer", new TransferHandler());
        server.createContext("/stats", new StatsHandler());
        server.createContext("/panel", new PanelHandler(idLocal, peers));
        server.createContext("/", new PaginaHandler());
        server.setExecutor(Executors.newFixedThreadPool(hilosHttp()));
        return server;
    }
}
